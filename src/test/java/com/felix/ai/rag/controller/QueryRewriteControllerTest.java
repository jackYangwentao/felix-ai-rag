package com.felix.ai.rag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRewriteController 测试类
 * 测试查询重写相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class QueryRewriteControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/query-rewrite";
    }

    @Test
    void testStructuredAnalysis() {
        Map<String, String> request = Map.of("query", "时间最短的视频");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/structured",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("isStructured"));
    }

    @Test
    void testMultiQueryDecomposition() {
        Map<String, String> request = Map.of(
                "query",
                "在《流浪地球》中，刘慈欣对人工智能和未来社会结构有何看法？"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/multi-query",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("subQueries"));

        @SuppressWarnings("unchecked")
        List<String> subQueries = (List<String>) response.getBody().get("subQueries");
        assertNotNull(subQueries);
        assertTrue(subQueries.size() > 1);
    }

    @Test
    void testStepBack() {
        Map<String, String> request = Map.of(
                "query",
                "在一个密闭容器中，加热气体后压力会如何变化？"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/step-back",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("stepBackQuestion"));
    }

    @Test
    void testHyde() {
        Map<String, String> request = Map.of("query", "什么是RAG技术？");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/hyde",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("hypotheticalDocument"));
    }

    @Test
    void testComprehensiveRewrite() {
        Map<String, String> request = Map.of("query", "2023年播放量最高的技术视频");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/comprehensive",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetTechniques() {
        ResponseEntity<List> response = restTemplate.getForEntity(
                getBaseUrl() + "/techniques",
                List.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().size() >= 4);
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
    void testEmptyQuery() {
        Map<String, String> request = Map.of("query", "");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/structured",
                request,
                Map.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);
    }
}
