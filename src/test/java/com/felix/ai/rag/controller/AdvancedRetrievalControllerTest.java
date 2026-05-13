package com.felix.ai.rag.controller;

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
 * AdvancedRetrievalController 测试类
 * 测试高级检索相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AdvancedRetrievalControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/advanced-retrieval";
    }

    @Test
    void testContextualCompression() {
        Map<String, Object> request = Map.of(
                "query", "机器学习",
                "maxResults", 5
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/compress",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCorrectiveRag() {
        Map<String, Object> request = Map.of(
                "query", "量子计算最新进展",
                "maxResults", 5
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/crag",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetDocumentation() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/docs",
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetExamples() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/examples",
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
