package com.felix.ai.rag;

import com.felix.ai.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RAG 服务测试类
 */
@SpringBootTest
public class RagServiceTest {

    @Autowired
    private RagService ragService;

    @Test
    void contextLoads() {
        assertNotNull(ragService);
    }

    @Test
    void testIndexAndSearch() {
        // 准备测试文档
        String document = """
                Spring Boot 是一个用于简化 Spring 应用开发的框架。
                它提供了自动配置、嵌入式服务器和开箱即用的功能。
                Spring Boot 使得创建独立的、生产级别的 Spring 应用变得简单。
                """;

        // 索引文档
        ragService.indexDocument(document, "spring-boot-intro");

        // 搜索相关内容
        var results = ragService.searchRelevantContent("什么是Spring Boot");

        System.out.println("检索结果: " + results);
    }
}
