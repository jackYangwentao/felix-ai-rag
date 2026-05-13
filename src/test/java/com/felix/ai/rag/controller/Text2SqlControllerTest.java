package com.felix.ai.rag.controller;

import org.junit.jupiter.api.BeforeEach;
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
 * Text2SqlController 测试类
 * 测试Text2SQL相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class Text2SqlControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/text2sql";
    }

    @BeforeEach
    void setUp() {
        // 清理知识库并添加测试数据
        restTemplate.delete(getBaseUrl() + "/knowledge");
        addTestKnowledge();
    }

    @Test
    void testQuery() {
        Map<String, String> request = Map.of("question", "年龄大于30的用户有哪些");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/query",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("success"));
        assertTrue(response.getBody().containsKey("generatedSql"));
    }

    @Test
    void testGenerateSql() {
        Map<String, String> request = Map.of("question", "查询所有用户");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/generate",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("generatedSql"));
    }

    @Test
    void testAddDdl() {
        Map<String, String> request = Map.of(
                "tableName", "test_table",
                "ddl", "CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(100))",
                "description", "测试表"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/knowledge/ddl",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
    }

    @Test
    void testAddFieldDescription() {
        Map<String, Object> request = Map.of(
                "tableName", "users",
                "fieldName", "age",
                "fieldType", "INT",
                "description", "用户年龄",
                "synonyms", List.of("年龄", "岁数")
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/knowledge/field",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testAddQueryExample() {
        Map<String, String> request = Map.of(
                "question", "查询所有用户",
                "sql", "SELECT * FROM users LIMIT 100",
                "description", "查询用户表示例"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/knowledge/example",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testAddBusinessTerm() {
        Map<String, String> request = Map.of(
                "term", "花费",
                "mapping", "cost",
                "explanation", "花费对应cost字段"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/knowledge/term",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetKnowledgeStats() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                getBaseUrl() + "/knowledge/stats",
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("statistics"));
    }

    @Test
    void testClearKnowledge() {
        ResponseEntity<Map> response = restTemplate.exchange(
                getBaseUrl() + "/knowledge",
                org.springframework.http.HttpMethod.DELETE,
                null,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
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

    @Test
    void testComplexQuery() {
        // 测试复杂查询
        Map<String, String> request = Map.of(
                "question",
                "查询技术部年龄大于28岁的用户姓名和邮箱"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                getBaseUrl() + "/query",
                request,
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        Boolean success = (Boolean) response.getBody().get("success");
        assertNotNull(success);
    }

    private void addTestKnowledge() {
        // 添加DDL
        Map<String, String> ddlRequest = Map.of(
                "tableName", "users",
                "ddl", "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100), email VARCHAR(100), age INT, city VARCHAR(50), department VARCHAR(50))",
                "description", "用户表"
        );
        restTemplate.postForEntity(getBaseUrl() + "/knowledge/ddl", ddlRequest, Map.class);

        // 添加示例
        Map<String, String> exampleRequest = Map.of(
                "question", "查询所有用户",
                "sql", "SELECT * FROM users LIMIT 100",
                "description", "查询示例"
        );
        restTemplate.postForEntity(getBaseUrl() + "/knowledge/example", exampleRequest, Map.class);
    }
}
