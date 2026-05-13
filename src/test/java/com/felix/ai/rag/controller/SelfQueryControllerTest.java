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
 * SelfQueryController 测试类
 * 测试Self-Query相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SelfQueryControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/self-query";
    }

    @Test
    void testParseQuery() {
        Map<String, String> request = Map.of("query", "2023年张三写的关于机器学习的论文");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/parse",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("semanticQuery"));
        assertTrue(response.getBody().containsKey("filter"));
    }

    @Test
    void testSearchWithSelfQuery() {
        Map<String, Object> request = Map.of(
                "query", "技术部2023年Q1的产品报告",
                "maxResults", 5
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/search",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testSimpleQuery() {
        Map<String, String> request = Map.of("query", "关于人工智能的文档");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/parse",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testEmptyQuery() {
        Map<String, String> request = Map.of("query", "");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/parse",
                request,
                Map.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);
    }
}
