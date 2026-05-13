package com.felix.ai.rag.controller;

import com.felix.ai.rag.dto.ChatRequest;
import com.felix.ai.rag.dto.ChatResponse;
import com.felix.ai.rag.dto.DocumentUploadRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagController 测试类
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class RagControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag";
    }

    @Test
    void testHealthCheck() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testChatWithRag() {
        // 先上传测试文档
        uploadTestDocument();

        ChatRequest request = new ChatRequest();
        request.setMessage("Spring Boot 有什么特点？");
        request.setUseRag(true);

        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/chat",
                request,
                ChatResponse.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getAnswer());
        assertNotNull(response.getBody().getSessionId());
    }

    @Test
    void testChatDirect() {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好，请介绍一下自己");
        request.setUseRag(false);

        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/chat/direct",
                request,
                ChatResponse.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getAnswer());
    }

    @Test
    void testUploadDocument() {
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setContent("这是一个测试文档内容。Spring Boot 是一个简化 Spring 应用开发的框架。");
        request.setDocumentName("test-document.txt");
        request.setDocumentType("text");
        request.setDescription("测试文档");

        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/documents",
                request,
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("成功") || response.getBody().contains("indexed"));
    }

    @Test
    void testUploadDocumentWithMetadata() {
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setContent("技术部2023年Q1的产品报告内容。");
        request.setDocumentName("product-report-q1.txt");
        request.setDocumentType("report");
        request.setDescription("Q1产品报告");
        request.setCustomMetadata(Map.of(
                "year", "2023",
                "quarter", "Q1",
                "department", "技术部"
        ));

        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/documents",
                request,
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testSearch() {
        // 先上传测试文档
        uploadTestDocument();

        ResponseEntity<List> response = restTemplate.getForEntity(
                getBaseUrl() + "/search?query=Spring Boot&maxResults=5",
                List.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testUploadDocumentFile() throws Exception {
        // 创建临时测试文件
        Path tempFile = Files.createTempFile("test-", ".txt");
        Files.writeString(tempFile, "这是一个测试文件内容。Java 是一种广泛使用的编程语言。");

        FileSystemResource fileResource = new FileSystemResource(tempFile.toFile());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/documents/file",
                HttpMethod.POST,
                requestEntity,
                String.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);

        // 清理临时文件
        Files.deleteIfExists(tempFile);
    }

    @Test
    void testUploadEmptyDocument() {
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setContent("");
        request.setDocumentName("empty.txt");

        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/documents",
                request,
                String.class
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private void uploadTestDocument() {
        DocumentUploadRequest request = new DocumentUploadRequest();
        request.setContent("Spring Boot 是一个用于简化 Spring 应用开发的框架。" +
                "它提供了自动配置、嵌入式服务器和开箱即用的功能。");
        request.setDocumentName("spring-boot-test.txt");
        request.setDocumentType("text");

        restTemplate.postForEntity(getBaseUrl() + "/documents", request, String.class);
    }
}
