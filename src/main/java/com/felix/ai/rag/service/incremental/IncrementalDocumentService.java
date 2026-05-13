package com.felix.ai.rag.service.incremental;

import com.felix.ai.rag.model.DocumentMetadata;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 增量文档服务接口
 * 定义增量导入的核心操作
 */
public interface IncrementalDocumentService {

    /**
     * 增量导入单个文档
     *
     * @param file       上传的文件
     * @param documentId 文档ID（可为空，自动生成）
     * @param forceUpdate 是否强制更新
     * @return 导入结果
     */
    IncrementalImportResult importDocument(MultipartFile file, String documentId, boolean forceUpdate) throws IOException;

    /**
     * 批量增量导入文档
     *
     * @param files    文件列表
     * @param folderId 文件夹ID
     * @return 批量导入结果
     */
    BatchIncrementalResult importBatch(List<MultipartFile> files, String folderId);

    /**
     * 删除文档
     *
     * @param documentId 文档ID
     * @return 是否删除成功
     */
    boolean deleteDocument(String documentId);

    /**
     * 获取文档状态
     *
     * @param documentId 文档ID
     * @return 文档状态
     */
    DocumentStatusResponse getDocumentStatus(String documentId);

    /**
     * 获取所有文档
     *
     * @return 文档列表
     */
    List<DocumentMetadata> listDocuments();

    /**
     * 根据文件夹获取文档
     *
     * @param folder 文件夹路径
     * @return 文档列表
     */
    List<DocumentMetadata> listDocumentsByFolder(String folder);

    /**
     * 检查文档是否需要更新
     *
     * @param documentId  文档ID
     * @param contentHash 内容哈希
     * @return 是否需要更新
     */
    boolean needsUpdate(String documentId, String contentHash);

    /**
     * 获取存储类型
     *
     * @return 存储类型标识
     */
    String getStorageType();

    /**
     * 增量导入结果
     */
    class IncrementalImportResult {
        private String documentId;
        private String status;  // CREATED, UPDATED, SKIPPED, FAILED
        private Integer version;
        private Integer chunkCount;
        private String message;
        private String contentHash;

        public static IncrementalImportResultBuilder builder() {
            return new IncrementalImportResultBuilder();
        }

        public static class IncrementalImportResultBuilder {
            private IncrementalImportResult result = new IncrementalImportResult();

            public IncrementalImportResultBuilder documentId(String documentId) {
                result.documentId = documentId;
                return this;
            }

            public IncrementalImportResultBuilder status(String status) {
                result.status = status;
                return this;
            }

            public IncrementalImportResultBuilder version(Integer version) {
                result.version = version;
                return this;
            }

            public IncrementalImportResultBuilder chunkCount(Integer chunkCount) {
                result.chunkCount = chunkCount;
                return this;
            }

            public IncrementalImportResultBuilder message(String message) {
                result.message = message;
                return this;
            }

            public IncrementalImportResultBuilder contentHash(String contentHash) {
                result.contentHash = contentHash;
                return this;
            }

            public IncrementalImportResult build() {
                return result;
            }
        }

        // Getters
        public String getDocumentId() { return documentId; }
        public String getStatus() { return status; }
        public Integer getVersion() { return version; }
        public Integer getChunkCount() { return chunkCount; }
        public String getMessage() { return message; }
        public String getContentHash() { return contentHash; }
    }

    /**
     * 批量导入结果
     */
    class BatchIncrementalResult {
        private int totalFiles;
        private int created;
        private int updated;
        private int skipped;
        private int deleted;
        private int failed;
        private List<ImportError> errors;

        public static BatchIncrementalResultBuilder builder() {
            return new BatchIncrementalResultBuilder();
        }

        public static class BatchIncrementalResultBuilder {
            private BatchIncrementalResult result = new BatchIncrementalResult();

            public BatchIncrementalResultBuilder totalFiles(int totalFiles) {
                result.totalFiles = totalFiles;
                return this;
            }

            public BatchIncrementalResultBuilder created(int created) {
                result.created = created;
                return this;
            }

            public BatchIncrementalResultBuilder updated(int updated) {
                result.updated = updated;
                return this;
            }

            public BatchIncrementalResultBuilder skipped(int skipped) {
                result.skipped = skipped;
                return this;
            }

            public BatchIncrementalResultBuilder deleted(int deleted) {
                result.deleted = deleted;
                return this;
            }

            public BatchIncrementalResultBuilder failed(int failed) {
                result.failed = failed;
                return this;
            }

            public BatchIncrementalResultBuilder errors(List<ImportError> errors) {
                result.errors = errors;
                return this;
            }

            public BatchIncrementalResult build() {
                return result;
            }
        }

        // Getters
        public int getTotalFiles() { return totalFiles; }
        public int getCreated() { return created; }
        public int getUpdated() { return updated; }
        public int getSkipped() { return skipped; }
        public int getDeleted() { return deleted; }
        public int getFailed() { return failed; }
        public List<ImportError> getErrors() { return errors; }

        // Setters for internal use
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
        public void setCreated(int created) { this.created = created; }
        public void setUpdated(int updated) { this.updated = updated; }
        public void setSkipped(int skipped) { this.skipped = skipped; }
        public void setDeleted(int deleted) { this.deleted = deleted; }
        public void setFailed(int failed) { this.failed = failed; }
        public void setErrors(List<ImportError> errors) { this.errors = errors; }
    }

    /**
     * 导入错误
     */
    class ImportError {
        private String filename;
        private String error;

        public ImportError(String filename, String error) {
            this.filename = filename;
            this.error = error;
        }

        public String getFilename() { return filename; }
        public String getError() { return error; }
    }

    /**
     * 文档状态响应
     */
    class DocumentStatusResponse {
        private boolean exists;
        private String status;
        private Integer version;
        private java.time.LocalDateTime indexTime;
        private java.time.LocalDateTime updateTime;
        private boolean changed;
        private String contentHash;

        public static DocumentStatusResponseBuilder builder() {
            return new DocumentStatusResponseBuilder();
        }

        public static class DocumentStatusResponseBuilder {
            private DocumentStatusResponse response = new DocumentStatusResponse();

            public DocumentStatusResponseBuilder exists(boolean exists) {
                response.exists = exists;
                return this;
            }

            public DocumentStatusResponseBuilder status(String status) {
                response.status = status;
                return this;
            }

            public DocumentStatusResponseBuilder version(Integer version) {
                response.version = version;
                return this;
            }

            public DocumentStatusResponseBuilder indexTime(java.time.LocalDateTime indexTime) {
                response.indexTime = indexTime;
                return this;
            }

            public DocumentStatusResponseBuilder updateTime(java.time.LocalDateTime updateTime) {
                response.updateTime = updateTime;
                return this;
            }

            public DocumentStatusResponseBuilder changed(boolean changed) {
                response.changed = changed;
                return this;
            }

            public DocumentStatusResponseBuilder contentHash(String contentHash) {
                response.contentHash = contentHash;
                return this;
            }

            public DocumentStatusResponse build() {
                return response;
            }
        }

        // Getters
        public boolean isExists() { return exists; }
        public String getStatus() { return status; }
        public Integer getVersion() { return version; }
        public java.time.LocalDateTime getIndexTime() { return indexTime; }
        public java.time.LocalDateTime getUpdateTime() { return updateTime; }
        public boolean isChanged() { return changed; }
        public String getContentHash() { return contentHash; }
    }
}
