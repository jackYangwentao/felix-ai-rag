package com.felix.ai.rag.controller;

import com.felix.ai.rag.dto.ChatRequest;
import com.felix.ai.rag.dto.ChatResponse;
import com.felix.ai.rag.dto.DocumentUploadRequest;
import com.felix.ai.rag.filter.MetadataFilter;
import com.felix.ai.rag.loader.DocumentLoader;
import com.felix.ai.rag.loader.DocumentProcessor;
import com.felix.ai.rag.model.DocumentMetadata;
import com.felix.ai.rag.service.EnhancedSearchService;
import com.felix.ai.rag.service.MultimodalService;
import com.felix.ai.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 控制器
 * 提供文档上传和问答API接口
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Slf4j
public class RagController {

    private final RagService ragService;
    private final DocumentLoader documentLoader;
    private final DocumentProcessor documentProcessor;
    private final EnhancedSearchService enhancedSearchService;
    private final MultimodalService multimodalService;

    /**
     * RAG 问答接口
     * 基于检索到的文档内容回答问题
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("收到聊天请求，使用RAG: {}", request.isUseRag());

        long startTime = System.currentTimeMillis();

        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : ragService.generateSessionId();

        String answer;
        List<String> sources = null;

        if (request.isUseRag()) {
            // 完整的 RAG 问答流程
            answer = ragService.chatWithRag(request.getMessage(), sessionId);
            sources = ragService.searchRelevantContent(request.getMessage());
        } else {
            answer = ragService.chat(request.getMessage());
        }

        long processingTime = System.currentTimeMillis() - startTime;

        ChatResponse response = ChatResponse.builder()
                .answer(answer)
                .sessionId(sessionId)
                .sources(sources)
                .processingTimeMs(processingTime)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 普通聊天接口（不使用RAG）
     */
    @PostMapping("/chat/direct")
    public ResponseEntity<ChatResponse> chatDirect(@RequestBody ChatRequest request) {
        log.info("收到直接聊天请求");

        long startTime = System.currentTimeMillis();
        String answer = ragService.chat(request.getMessage());

        ChatResponse response = ChatResponse.builder()
                .answer(answer)
                .sessionId(ragService.generateSessionId())
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 文档上传接口（文本形式）
     * 将文档内容索引到向量存储，支持元数据
     */
    @PostMapping("/documents")
    public ResponseEntity<String> uploadDocument(@RequestBody DocumentUploadRequest request) {
        log.info("收到文档上传请求: {}", request.getDocumentName());

        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("文档内容不能为空");
        }

        String sourceName = request.getDocumentName() != null
                ? request.getDocumentName()
                : "unnamed-document";

        // 构建文档元数据
        DocumentMetadata metadata = DocumentMetadata.builder()
                .documentName(request.getDocumentName())
                .documentType(request.getDocumentType())
                .contentType(request.getDescription())
                .author(request.getAuthor())
                .category(request.getCategory())
                .year(request.getYear())
                .quarter(request.getQuarter())
                .department(request.getDepartment())
                .project(request.getProject())
                .tags(request.getTags())
                .customFields(request.getCustomMetadata())
                .build();

        ragService.indexDocument(request.getContent(), sourceName, metadata);

        return ResponseEntity.ok("文档 \"" + sourceName + "\" 索引成功，包含元数据");
    }

    /**
     * 文档上传接口（文件形式）
     * 支持多种文档格式：txt, md, pdf, doc, docx, xls, xlsx 等
     *
     * 参考 Datawhale All-In-RAG 数据加载实现
     */
    @PostMapping("/documents/file")
    public ResponseEntity<String> uploadDocumentFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "extractImages", required = false, defaultValue = "false") boolean extractImages) {
        log.info("收到文件上传请求: {}, 提取图片: {}", file.getOriginalFilename(), extractImages);

        try {
            // 使用统一的文档处理器解析文件
            DocumentProcessor.ProcessingOptions options = DocumentProcessor.ProcessingOptions.builder()
                    .extractImages(extractImages)
                    .build();
            DocumentProcessor.ProcessedDocument processedDoc = documentProcessor.process(file, options);

            String sourceName = file.getOriginalFilename();

            // 构建文档元数据
            DocumentMetadata metadata = DocumentMetadata.extractFromFilename(sourceName);
            if (description != null && !description.isEmpty()) {
                metadata.setContentType(description);
            }
            metadata.setDocumentType(processedDoc.getContentType());

            // 索引文档内容（包含所有文本块）
            String fullText = processedDoc.getFullText();
            ragService.indexDocument(fullText, sourceName, metadata);

            // 如果有图片且需要提取，使用多模态服务处理图片
            if (processedDoc.isHasImages() && extractImages) {
                log.info("PDF 包含 {} 张图片，开始处理...", processedDoc.getImageCount());

                int imageIndex = 1;
                for (DocumentProcessor.ContentBlock imageBlock : processedDoc.getImageBlocks()) {
                    try {
                        // 1. 使用视觉模型生成图片描述
                        String imageDescription = multimodalService.describeImage(
                                imageBlock.getImageData(),
                                "请详细描述这张图片的内容，包括图表数据、文字信息、视觉元素等"
                        );

                        // 2. 构建图片文档元数据
                        DocumentMetadata imageMetadata = DocumentMetadata.extractFromFilename(
                                sourceName + "_image_" + imageIndex
                        );
                        imageMetadata.setContentType("pdf-extracted-image");
                        imageMetadata.setDocumentType("image/png");
                        if (imageBlock.getMetadata() != null) {
                            String pageNum = imageBlock.getMetadata().getOrDefault("pageNumber", "未知");
                            imageMetadata.setTitle(String.format(
                                    "PDF第%s页提取的图片", pageNum
                            ));
                        }

                        // 3. 将图片描述索引到向量库（作为文本知识）
                        ragService.indexDocument(
                                imageDescription,
                                sourceName + "_image_" + imageIndex,
                                imageMetadata
                        );

                        log.info("PDF图片 {} 已处理并索引: {}", imageIndex,
                                imageDescription.substring(0, Math.min(50, imageDescription.length())) + "...");

                        imageIndex++;
                    } catch (Exception e) {
                        log.error("处理PDF图片 {} 失败: {}", imageIndex, e.getMessage());
                    }
                }

                log.info("PDF 图片处理完成，共索引 {} 张图片", imageIndex - 1);
            }

            // 构建成功响应信息
            StringBuilder response = new StringBuilder();
            response.append("文件 \"").append(sourceName).append("\" 上传并索引成功\n");
            response.append("- 字符数: ").append(fullText.length()).append("\n");
            response.append("- 文件类型: ").append(processedDoc.getContentType()).append("\n");
            if (processedDoc.isHasImages()) {
                response.append("- 图片数量: ").append(processedDoc.getImageCount()).append("\n");
            }

            // 添加元数据信息
            if (processedDoc.getMetadata() != null && !processedDoc.getMetadata().isEmpty()) {
                response.append("- 元数据: ");
                processedDoc.getMetadata().forEach((key, value) -> {
                    response.append(key).append("=").append(value).append(" ");
                });
            }

            return ResponseEntity.ok(response.toString().trim());
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return ResponseEntity.badRequest().body("文件读取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 内容检索接口（基础版）
     * 只检索相关文档，不生成回答
     */
    @GetMapping("/search")
    public ResponseEntity<List<String>> search(@RequestParam("query") String query) {
        log.info("收到检索请求: {}", query);

        List<String> results = ragService.searchRelevantContent(query);
        return ResponseEntity.ok(results);
    }

    /**
     * 内容检索接口（POST版本）
     * 支持中文查询，避免URL编码问题
     */
    @PostMapping("/search")
    public ResponseEntity<List<String>> searchPost(@RequestParam("query") String query) {
        log.info("收到检索请求(POST): {}", query);

        List<String> results = ragService.searchRelevantContent(query);
        return ResponseEntity.ok(results);
    }

    /**
     * 增强搜索接口
     * 支持重排序的高级检索
     *
     * 参考 Datawhale All-In-RAG 向量数据库章节的重排序概念
     */
    @GetMapping("/search/enhanced")
    public ResponseEntity<EnhancedSearchService.SearchResult> searchEnhanced(
            @RequestParam("query") String query,
            @RequestParam(value = "maxResults", required = false) Integer maxResults,
            @RequestParam(value = "minScore", required = false) Double minScore,
            @RequestParam(value = "rerank", required = false) Boolean useRerank) {
        log.info("收到增强检索请求: {}, maxResults={}, rerank={}", query, maxResults, useRerank);

        EnhancedSearchService.SearchResult result = enhancedSearchService.search(
                query, maxResults, minScore, useRerank);

        return ResponseEntity.ok(result);
    }

    /**
     * 多查询搜索接口
     * 使用多个相关查询进行检索，合并并去重结果
     */
    @PostMapping("/search/multi-query")
    public ResponseEntity<EnhancedSearchService.SearchResult> searchMultiQuery(
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) request.get("queries");
        Integer maxResults = (Integer) request.get("maxResults");

        if (queries == null || queries.isEmpty()) {
            return ResponseEntity.badRequest().body(null);
        }

        log.info("收到多查询检索请求，查询数: {}", queries.size());

        EnhancedSearchService.SearchResult result = enhancedSearchService.multiQuerySearch(
                queries, maxResults != null ? maxResults : 5);

        return ResponseEntity.ok(result);
    }

    /**
     * 批量搜索接口
     * 同时处理多个查询（参考 Milvus 批量查询优化）
     */
    @PostMapping("/search/batch")
    public ResponseEntity<Map<String, EnhancedSearchService.SearchResult>> searchBatch(
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) request.get("queries");
        Integer maxResults = (Integer) request.get("maxResults");

        if (queries == null || queries.isEmpty()) {
            return ResponseEntity.badRequest().body(null);
        }

        log.info("收到批量检索请求，查询数: {}", queries.size());

        Map<String, EnhancedSearchService.SearchResult> results =
                enhancedSearchService.batchSearch(queries, maxResults != null ? maxResults : 5);

        return ResponseEntity.ok(results);
    }

    /**
     * 多样性搜索接口
     * 使用 MMR 算法确保结果多样性（参考 Milvus 分组检索）
     */
    @GetMapping("/search/diverse")
    public ResponseEntity<EnhancedSearchService.SearchResult> searchDiverse(
            @RequestParam("query") String query,
            @RequestParam(value = "maxResults", required = false) Integer maxResults,
            @RequestParam(value = "diversity", required = false) Double diversityFactor) {

        int topK = maxResults != null ? maxResults : 5;
        double diversity = diversityFactor != null ? diversityFactor : 0.5;

        log.info("收到多样性检索请求: {}, maxResults={}, diversity={}", query, topK, diversity);

        EnhancedSearchService.SearchResult result =
                enhancedSearchService.diverseSearch(query, topK, diversity);

        return ResponseEntity.ok(result);
    }

    /**
     * 元数据过滤搜索接口
     * 先过滤后搜索，提高效率和准确性
     *
     * 参考 Datawhale All-In-RAG 元数据过滤 + 向量搜索
     */
    @GetMapping("/search/filtered")
    public ResponseEntity<List<String>> searchWithFilter(
            @RequestParam("query") String query,
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "year", required = false) String year,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "maxResults", required = false, defaultValue = "5") int maxResults,
            @RequestParam(value = "minScore", required = false, defaultValue = "0.7") double minScore) {

        log.info("收到元数据过滤检索请求: {}, documentType={}, year={}, category={}",
                query, documentType, year, category);

        // 构建元数据过滤器
        MetadataFilter filter = new MetadataFilter();
        filter.setOperator(MetadataFilter.LogicalOperator.AND);

        java.util.List<MetadataFilter.FilterCondition> conditions = new java.util.ArrayList<>();

        if (documentType != null && !documentType.isEmpty()) {
            conditions.add(MetadataFilter.eq("document_type", documentType));
        }
        if (year != null && !year.isEmpty()) {
            conditions.add(MetadataFilter.eq("year", year));
        }
        if (category != null && !category.isEmpty()) {
            conditions.add(MetadataFilter.eq("category", category));
        }
        if (author != null && !author.isEmpty()) {
            conditions.add(MetadataFilter.eq("author", author));
        }

        filter.setConditions(conditions);

        // 执行过滤搜索
        List<String> results = ragService.searchRelevantContent(
                query, filter, maxResults, minScore);

        return ResponseEntity.ok(results);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("RAG服务运行正常");
    }

    // ==================== 基于 DocumentLoader 的普通文档处理接口 ====================

    /**
     * 简单文件上传（使用 DocumentLoader，不提取图片）
     * 保持对普通文档处理的兼容性和简单性
     */
    @PostMapping("/documents/simple")
    public ResponseEntity<String> uploadDocumentSimple(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {
        log.info("收到简单文件上传请求: {}", file.getOriginalFilename());

        try {
            // 使用传统的 DocumentLoader 解析文件
            DocumentLoader.DocumentData documentData = documentLoader.loadDocument(file);

            String sourceName = file.getOriginalFilename();

            // 构建文档元数据
            DocumentMetadata metadata = DocumentMetadata.extractFromFilename(sourceName);
            if (description != null && !description.isEmpty()) {
                metadata.setContentType(description);
            }
            if (documentData.getMetadata() != null) {
                metadata.setDocumentType(documentData.getMetadata().getOrDefault("detected-type", "unknown"));
            }

            // 索引文档内容
            ragService.indexDocument(documentData.getContent(), sourceName, metadata);

            // 构建响应
            StringBuilder response = new StringBuilder();
            response.append("文件 \"").append(sourceName).append("\" 上传并索引成功\n");
            response.append("- 字符数: ").append(documentData.getContent().length()).append("\n");
            response.append("- 文件类型: ").append(documentData.getContentType()).append("\n");

            if (documentData.getMetadata() != null && !documentData.getMetadata().isEmpty()) {
                response.append("- 元数据: ");
                documentData.getMetadata().forEach((key, value) -> {
                    if (!"detected-type".equals(key)) {
                        response.append(key).append("=").append(value).append(" ");
                    }
                });
            }

            return ResponseEntity.ok(response.toString().trim());
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return ResponseEntity.badRequest().body("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 批量上传文件（使用 DocumentLoader）
     */
    @PostMapping("/documents/batch")
    public ResponseEntity<Map<String, Object>> uploadDocumentsBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "description", required = false) String description) {
        log.info("收到批量文件上传请求，文件数: {}", files.size());

        List<String> successFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                DocumentLoader.DocumentData documentData = documentLoader.loadDocument(file);
                String sourceName = file.getOriginalFilename();

                DocumentMetadata metadata = DocumentMetadata.extractFromFilename(sourceName);
                if (description != null && !description.isEmpty()) {
                    metadata.setContentType(description);
                }

                ragService.indexDocument(documentData.getContent(), sourceName, metadata);
                successFiles.add(sourceName);
            } catch (Exception e) {
                log.error("文件上传失败: {}", file.getOriginalFilename(), e);
                failedFiles.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", successFiles);
        response.put("failed", failedFiles);
        response.put("successCount", successFiles.size());
        response.put("failedCount", failedFiles.size());

        return ResponseEntity.ok(response);
    }

    /**
     * 预览文件内容（使用 DocumentLoader）
     * 不上传到知识库，仅预览解析后的内容
     */
    @PostMapping("/documents/preview")
    public ResponseEntity<Map<String, Object>> previewDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "maxLength", required = false, defaultValue = "1000") int maxLength) {
        log.info("收到文件预览请求: {}", file.getOriginalFilename());

        try {
            DocumentLoader.DocumentData documentData = documentLoader.loadDocument(file);

            String content = documentData.getContent();
            String preview = content.length() > maxLength
                    ? content.substring(0, maxLength) + "..."
                    : content;

            Map<String, Object> response = new HashMap<>();
            response.put("filename", file.getOriginalFilename());
            response.put("contentType", documentData.getContentType());
            response.put("fileSize", documentData.getFileSize());
            response.put("totalLength", content.length());
            response.put("preview", preview);
            response.put("metadata", documentData.getMetadata());

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("文件预览失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 检查文件是否可解析
     */
    @PostMapping("/documents/check")
    public ResponseEntity<Map<String, Object>> checkDocument(
            @RequestParam("file") MultipartFile file) {
        log.info("收到文件检查请求: {}", file.getOriginalFilename());

        try {
            DocumentLoader.DocumentData documentData = documentLoader.loadDocument(file);

            Map<String, Object> response = new HashMap<>();
            response.put("filename", file.getOriginalFilename());
            response.put("parseable", true);
            response.put("contentType", documentData.getContentType());
            response.put("contentLength", documentData.getContent().length());
            response.put("metadata", documentData.getMetadata());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("filename", file.getOriginalFilename());
            response.put("parseable", false);
            response.put("error", e.getMessage());

            return ResponseEntity.ok(response);
        }
    }
}
