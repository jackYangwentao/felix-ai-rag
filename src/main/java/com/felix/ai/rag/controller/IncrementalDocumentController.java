package com.felix.ai.rag.controller;

import com.felix.ai.rag.model.DocumentMetadata;
import com.felix.ai.rag.service.incremental.IncrementalDocumentService;
import com.felix.ai.rag.service.incremental.IncrementalDocumentService.BatchIncrementalResult;
import com.felix.ai.rag.service.incremental.IncrementalDocumentService.DocumentStatusResponse;
import com.felix.ai.rag.service.incremental.IncrementalDocumentService.IncrementalImportResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 增量文档导入控制器
 * 提供文档增量导入、更新、删除、查询等接口
 */
@RestController
@RequestMapping("/api/v1/rag/documents")
@RequiredArgsConstructor
@Slf4j
public class IncrementalDocumentController {

    @Autowired
    private IncrementalDocumentService incrementalDocumentService;

    /**
     * 增量导入单个文档
     *
     * @param file        上传的文件
     * @param documentId  文档ID（可选，默认使用文件名）
     * @param forceUpdate 是否强制更新（可选，默认false）
     * @return 导入结果
     */
    @PostMapping("/incremental")
    public ResponseEntity<IncrementalImportResult> uploadDocumentIncremental(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentId", required = false) String documentId,
            @RequestParam(value = "forceUpdate", required = false, defaultValue = "false") boolean forceUpdate) {

        log.info("收到增量导入请求: {}, documentId: {}, forceUpdate: {}",
                file.getOriginalFilename(), documentId, forceUpdate);

        try {
            IncrementalImportResult result = incrementalDocumentService.importDocument(file, documentId, forceUpdate);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("文档导入失败", e);
            return ResponseEntity.badRequest().body(IncrementalImportResult.builder()
                    .documentId(documentId)
                    .status("FAILED")
                    .message("文件读取失败: " + e.getMessage())
                    .build());
        } catch (Exception e) {
            log.error("文档导入失败", e);
            return ResponseEntity.internalServerError().body(IncrementalImportResult.builder()
                    .documentId(documentId)
                    .status("FAILED")
                    .message("导入失败: " + e.getMessage())
                    .build());
        }
    }

    /**
     * 批量增量导入文档
     *
     * @param files    文件列表
     * @param folderId 文件夹ID（用于组织文档）
     * @return 批量导入结果
     */
    @PostMapping("/incremental/batch")
    public ResponseEntity<BatchIncrementalResult> uploadBatchIncremental(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "folderId", required = false, defaultValue = "default") String folderId) {

        log.info("收到批量增量导入请求: {} 个文件, folderId: {}", files.size(), folderId);

        if (files.isEmpty()) {
            return ResponseEntity.badRequest().body(BatchIncrementalResult.builder()
                    .totalFiles(0)
                    .failed(0)
                    .errors(List.of(new IncrementalDocumentService.ImportError("", "文件列表为空")))
                    .build());
        }

        BatchIncrementalResult result = incrementalDocumentService.importBatch(files, folderId);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取已导入文档列表
     *
     * @param folder 文件夹路径（可选，用于过滤）
     * @return 文档列表
     */
    @GetMapping("/registry")
    public ResponseEntity<List<DocumentMetadata>> listDocuments(
            @RequestParam(value = "folder", required = false) String folder) {

        log.info("查询文档列表, folder: {}", folder);

        List<DocumentMetadata> documents;
        if (folder != null && !folder.isEmpty()) {
            documents = incrementalDocumentService.listDocumentsByFolder(folder);
        } else {
            documents = incrementalDocumentService.listDocuments();
        }

        return ResponseEntity.ok(documents);
    }

    /**
     * 删除指定文档
     *
     * @param documentId 文档ID
     * @return 删除结果
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<String> deleteDocument(@PathVariable String documentId) {
        log.info("删除文档: {}", documentId);

        boolean success = incrementalDocumentService.deleteDocument(documentId);

        if (success) {
            return ResponseEntity.ok("文档删除成功: " + documentId);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 检查文档状态
     *
     * @param documentId  文档ID
     * @param contentHash 内容哈希（可选，用于检测是否需要更新）
     * @return 文档状态
     */
    @GetMapping("/{documentId}/status")
    public ResponseEntity<DocumentStatusResponse> checkDocumentStatus(
            @PathVariable String documentId,
            @RequestParam(value = "hash", required = false) String contentHash) {

        log.info("检查文档状态: {}, hash: {}", documentId, contentHash);

        DocumentStatusResponse response = incrementalDocumentService.getDocumentStatus(documentId);

        // 如果提供了哈希，检测是否需要更新
        if (contentHash != null && !contentHash.isEmpty() && response.isExists()) {
            boolean needsUpdate = incrementalDocumentService.needsUpdate(documentId, contentHash);
            // 重新构建响应
            response = DocumentStatusResponse.builder()
                    .exists(response.isExists())
                    .status(response.getStatus())
                    .version(response.getVersion())
                    .indexTime(response.getIndexTime())
                    .updateTime(response.getUpdateTime())
                    .contentHash(response.getContentHash())
                    .changed(needsUpdate)
                    .build();
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取存储类型
     *
     * @return 当前使用的存储类型
     */
    @GetMapping("/storage-type")
    public ResponseEntity<String> getStorageType() {
        String storageType = incrementalDocumentService.getStorageType();
        return ResponseEntity.ok(storageType);
    }

    /**
     * 检查文档是否需要更新
     *
     * @param documentId  文档ID
     * @param contentHash 内容哈希
     * @return 是否需要更新
     */
    @GetMapping("/{documentId}/needs-update")
    public ResponseEntity<Boolean> needsUpdate(
            @PathVariable String documentId,
            @RequestParam("hash") String contentHash) {

        boolean needsUpdate = incrementalDocumentService.needsUpdate(documentId, contentHash);
        return ResponseEntity.ok(needsUpdate);
    }
}
