package com.felix.ai.rag.controller;

import com.felix.ai.rag.dto.ChatRequest;
import com.felix.ai.rag.dto.ChatResponse;
import com.felix.ai.rag.dto.DocumentUploadRequest;
import com.felix.ai.rag.filter.MetadataFilter;
import com.felix.ai.rag.loader.DocumentLoader;
import com.felix.ai.rag.model.DocumentMetadata;
import com.felix.ai.rag.service.EnhancedSearchService;
import com.felix.ai.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final EnhancedSearchService enhancedSearchService;

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
            @RequestParam(value = "description", required = false) String description) {
        log.info("收到文件上传请求: {}", file.getOriginalFilename());

        try {
            // 使用增强的文档加载器解析文件
            DocumentLoader.DocumentData documentData = documentLoader.loadDocument(file);

            String sourceName = file.getOriginalFilename();

            // 构建文档元数据（从文件名自动提取）
            DocumentMetadata metadata = DocumentMetadata.extractFromFilename(sourceName);
            if (description != null && !description.isEmpty()) {
                metadata.setContentType(description);
            }
            // 从DocumentLoader解析的元数据合并
            if (documentData.getMetadata() != null) {
                metadata.setDocumentType(documentData.getMetadata().getOrDefault("detected-type", "unknown"));
            }

            // 索引文档内容到向量存储（带元数据）
            ragService.indexDocument(documentData.getContent(), sourceName, metadata);

            // 构建成功响应信息
            StringBuilder response = new StringBuilder();
            response.append("文件 \"").append(sourceName).append("\" 上传并索引成功\n");
            response.append("- 字符数: ").append(documentData.getContent().length()).append("\n");
            response.append("- 文件类型: ").append(documentData.getContentType()).append("\n");

            // 添加元数据信息
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
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 内容检索接口（基础版）
     * 只检索相关文档，不生成回答
     */
    @GetMapping("/search")
    public ResponseEntity<List<String>> search(
            @RequestParam("query") String query) {
        log.info("收到检索请求: {}", query);

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
}
