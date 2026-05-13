package com.felix.ai.rag.controller;

import com.felix.ai.rag.dto.ChatRequest;
import com.felix.ai.rag.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OptimizedRagController 测试类
 * 测试优化版RAG相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OptimizedRagControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/optimized";
    }

    @Test
    void testOptimizedChat() {
        ChatRequest request = new ChatRequest();
        request.setMessage("什么是RAG技术？");
        request.setUseRag(true);

        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/chat",
                request,
                ChatResponse.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getAnswer());
    }

    @Test
    void testOptimizedChatWithoutRag() {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");
        request.setUseRag(false);

        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/chat",
                request,
                ChatResponse.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetOptimizationConfig() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/config",
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testEmptyMessage() {
        ChatRequest request = new ChatRequest();
        request.setMessage("");
        request.setUseRag(true);

        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/chat",
                request,
                ChatResponse.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);
    }
}
