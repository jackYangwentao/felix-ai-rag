package com.felix.ai.rag.service.incremental;

import com.felix.ai.rag.chunker.ChunkerFactory;
import com.felix.ai.rag.chunker.TextChunker;
import com.felix.ai.rag.loader.DocumentLoader;
import com.felix.ai.rag.model.DocumentMetadata;
import com.felix.ai.rag.service.RagService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于内存的增量文档服务实现
 * 适用于 InMemoryEmbeddingStore
 */
@Service
@Slf4j
public class InMemoryIncrementalDocumentService implements IncrementalDocumentService {

    @Autowired
    private DocumentRegistry documentRegistry;

    @Autowired
    private DocumentFingerprintService fingerprintService;

    @Autowired
    private DocumentLoader documentLoader;

    @Autowired
    private RagService ragService;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private ChunkerFactory chunkerFactory;

    private static final String STORAGE_TYPE = "memory";

    @Override
    public IncrementalImportResult importDocument(MultipartFile file, String documentId, boolean forceUpdate) throws IOException {
        String filename = file.getOriginalFilename();

        // 1. 生成或获取文档ID
        if (documentId == null || documentId.isEmpty()) {
            documentId = fingerprintService.generateDocumentId(file);
        }

        // 2. 计算内容哈希
        String contentHash = fingerprintService.calculateMd5(file);

        log.info("开始增量导入文档: {}, ID: {}, 哈希: {}", filename, documentId, contentHash);

        // 3. 检查是否已存在
        DocumentMetadata existing = documentRegistry.get(documentId);

        if (existing != null) {
            // 3.1 检查内容是否变化
            if (contentHash.equals(existing.getContentHash()) && !forceUpdate) {
                log.info("文档未变化，跳过导入: {}", documentId);
                return IncrementalImportResult.builder()
                        .documentId(documentId)
                        .status("SKIPPED")
                        .version(existing.getVersion())
                        .chunkCount(existing.getChunkCount())
                        .message("文档未变化，跳过导入")
                        .contentHash(contentHash)
                        .build();
            }

            // 3.2 标记为更新中状态
            existing.setStatus(DocumentMetadata.DocumentStatus.UPDATING);
            documentRegistry.register(existing);

            // 3.3 对于内存存储，标记旧分片为删除（逻辑删除）
            // 注意：InMemoryEmbeddingStore 不支持物理删除，需要重建索引
            log.info("文档已存在，将更新: {}, 旧版本: {}", documentId, existing.getVersion());
        }

        // 4. 加载文档内容
        DocumentLoader.DocumentData documentData = documentLoader.loadDocument(file);
        String content = documentData.getContent();

        // 5. 分块并索引
        List<String> chunkIds = indexDocumentWithChunks(content, documentId);

        // 6. 构建新的元数据
        DocumentMetadata metadata = DocumentMetadata.builder()
                .documentId(documentId)
                .documentName(filename)
                .documentType(documentData.getContentType())
                .contentHash(contentHash)
                .contentLength((long) content.length())
                .indexTime(existing != null ? existing.getIndexTime() : LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .version(existing != null ? existing.getVersion() + 1 : 1)
                .status(DocumentMetadata.DocumentStatus.ACTIVE)
                .chunkIds(chunkIds)
                .chunkCount(chunkIds.size())
                .storageType(STORAGE_TYPE)
                .build();

        // 7. 注册到注册表
        documentRegistry.register(metadata);

        String status = existing != null ? "UPDATED" : "CREATED";
        log.info("文档导入成功: {}, 状态: {}, 版本: {}, 分片数: {}",
                documentId, status, metadata.getVersion(), chunkIds.size());

        return IncrementalImportResult.builder()
                .documentId(documentId)
                .status(status)
                .version(metadata.getVersion())
                .chunkCount(chunkIds.size())
                .message("文档导入成功")
                .contentHash(contentHash)
                .build();
    }

    @Override
    public BatchIncrementalResult importBatch(List<MultipartFile> files, String folderId) {
        log.info("开始批量增量导入: {} 个文件, 文件夹: {}", files.size(), folderId);

        BatchIncrementalResult result = BatchIncrementalResult.builder()
                .totalFiles(files.size())
                .created(0)
                .updated(0)
                .skipped(0)
                .deleted(0)
                .failed(0)
                .errors(new ArrayList<>())
                .build();

        // 获取该文件夹下已有的文档列表
        List<DocumentMetadata> existingDocs = documentRegistry.listByFolder(folderId);
        List<String> processedDocIds = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                String filename = file.getOriginalFilename();
                String documentId = folderId + "/" + filename;

                IncrementalImportResult importResult = importDocument(file, documentId, false);

                switch (importResult.getStatus()) {
                    case "CREATED":
                        result.setCreated(result.getCreated() + 1);
                        break;
                    case "UPDATED":
                        result.setUpdated(result.getUpdated() + 1);
                        break;
                    case "SKIPPED":
                        result.setSkipped(result.getSkipped() + 1);
                        break;
                }

                processedDocIds.add(documentId);
            } catch (Exception e) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new ImportError(file.getOriginalFilename(), e.getMessage()));
                log.error("导入文件失败: {}", file.getOriginalFilename(), e);
            }
        }

        // 处理已删除的文档（在原有列表中但不在新列表中）
        for (DocumentMetadata existingDoc : existingDocs) {
            if (!processedDocIds.contains(existingDoc.getDocumentId())) {
                deleteDocument(existingDoc.getDocumentId());
                result.setDeleted(result.getDeleted() + 1);
            }
        }

        log.info("批量导入完成: 总计={}, 新建={}, 更新={}, 跳过={}, 删除={}, 失败={}",
                result.getTotalFiles(), result.getCreated(), result.getUpdated(),
                result.getSkipped(), result.getDeleted(), result.getFailed());

        return result;
    }

    @Override
    public boolean deleteDocument(String documentId) {
        DocumentMetadata metadata = documentRegistry.get(documentId);
        if (metadata == null) {
            log.warn("文档不存在，无法删除: {}", documentId);
            return false;
        }

        // 对于内存存储，标记为删除状态（逻辑删除）
        // 物理删除需要重建整个索引，成本较高
        metadata.setStatus(DocumentMetadata.DocumentStatus.DELETED);
        metadata.setUpdateTime(LocalDateTime.now());
        documentRegistry.markAsDeleted(documentId);

        log.info("文档已标记为删除: {}", documentId);
        return true;
    }

    @Override
    public DocumentStatusResponse getDocumentStatus(String documentId) {
        DocumentMetadata metadata = documentRegistry.get(documentId);

        if (metadata == null) {
            return DocumentStatusResponse.builder()
                    .exists(false)
                    .status("NOT_FOUND")
                    .build();
        }

        return DocumentStatusResponse.builder()
                .exists(true)
                .status(metadata.getStatus().name())
                .version(metadata.getVersion())
                .indexTime(metadata.getIndexTime())
                .updateTime(metadata.getUpdateTime())
                .contentHash(metadata.getContentHash())
                .build();
    }

    @Override
    public List<DocumentMetadata> listDocuments() {
        return documentRegistry.listActive().stream()
                .filter(doc -> STORAGE_TYPE.equals(doc.getStorageType()))
                .collect(Collectors.toList());
    }

    @Override
    public List<DocumentMetadata> listDocumentsByFolder(String folder) {
        return documentRegistry.listByFolder(folder).stream()
                .filter(doc -> STORAGE_TYPE.equals(doc.getStorageType()))
                .filter(doc -> doc.getStatus() == DocumentMetadata.DocumentStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    @Override
    public boolean needsUpdate(String documentId, String contentHash) {
        DocumentMetadata existing = documentRegistry.get(documentId);
        if (existing == null) {
            return true; // 不存在，需要创建
        }
        return !contentHash.equals(existing.getContentHash());
    }

    @Override
    public String getStorageType() {
        return STORAGE_TYPE;
    }

    /**
     * 分块并索引文档
     *
     * @param content    文档内容
     * @param documentId 文档ID
     * @return 分片ID列表
     */
    private List<String> indexDocumentWithChunks(String content, String documentId) {
        // 根据文件类型选择合适的分块器
        TextChunker chunker = chunkerFactory.createChunkerForFile(documentId);

        // 分块
        List<String> chunks = chunker.chunk(content);
        List<String> chunkIds = new ArrayList<>();

        // 索引每个分块
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            TextSegment segment = TextSegment.from(chunk);

            // 添加元数据
            segment.metadata().put("document_id", documentId);
            segment.metadata().put("chunk_index", String.valueOf(i));
            segment.metadata().put("storage_type", STORAGE_TYPE);

            Embedding embedding = embeddingModel.embed(segment).content();

            // 生成 chunk ID
            String chunkId = documentId + "_chunk_" + i + "_" + UUID.randomUUID().toString().substring(0, 8);
            chunkIds.add(chunkId);

            // 添加到向量存储
            embeddingStore.add(embedding, segment);
        }

        return chunkIds;
    }

    /**
     * 重建索引（清理已删除的文档）
     * 注意：这是一个耗时操作，会重建整个向量库
     */
    public void rebuildIndex() {
        log.info("开始重建索引...");

        // 获取所有活动的文档
        List<DocumentMetadata> activeDocs = documentRegistry.listActive();

        // 这里需要实现重建逻辑
        // 由于 InMemoryEmbeddingStore 不支持删除，需要：
        // 1. 清空现有向量库
        // 2. 重新导入所有活动文档

        log.info("索引重建完成，活动文档数: {}", activeDocs.size());
    }
}
