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
 * HybridSearchController 测试类
 * 测试混合检索相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class HybridSearchControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/hybrid";
    }

    @Test
    void testHybridSearch() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/search?query=机器学习&maxResults=5",
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testHybridSearchWithFilters() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/search?query=Spring Boot&maxResults=5&minScore=0.5",
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testAdvancedHybridSearch() {
        Map<String, Object> request = Map.of(
                "query", "人工智能",
                "maxResults", 5,
                "vectorWeight", 0.7,
                "keywordWeight", 0.3
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/search/advanced",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testEmptyQuery() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/search?query=&maxResults=5",
                Map.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);
    }
}
