package com.felix.ai.rag.controller;

import com.felix.ai.rag.dto.ChatRequest;
import com.felix.ai.rag.dto.ChatResponse;
import com.felix.ai.rag.dto.DocumentUploadRequest;
import com.felix.ai.rag.loader.DocumentLoader;
import com.felix.ai.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

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
     * 将文档内容索引到向量存储
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

        ragService.indexDocument(request.getContent(), sourceName);

        return ResponseEntity.ok("文档 \"" + sourceName + "\" 索引成功");
    }

    /**
     * 文档上传接口（文件形式）
     * 支持 txt, md, json, csv 等文本文件
     */
    @PostMapping("/documents/file")
    public ResponseEntity<String> uploadDocumentFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {
        log.info("收到文件上传请求: {}", file.getOriginalFilename());

        try {
            String content = documentLoader.loadFromMultipartFile(file);
            String sourceName = file.getOriginalFilename();

            ragService.indexDocument(content, sourceName);

            return ResponseEntity.ok("文件 \"" + sourceName + "\" 上传并索引成功，共 " + content.length() + " 字符");
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return ResponseEntity.badRequest().body("文件读取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 内容检索接口
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
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("RAG服务运行正常");
    }
}
