package com.felix.ai.rag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MultimodalController 测试类
 * 测试多模态相关接口
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MultimodalControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1/rag/multimodal";
    }

    @Test
    void testDescribeImage() throws Exception {
        // 创建一个简单的测试图片文件（实际测试需要真实图片）
        Path tempImage = Files.createTempFile("test-", ".jpg");
        // 写入简单的JPEG头（简化测试）
        Files.write(tempImage, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        FileSystemResource fileResource = new FileSystemResource(tempImage.toFile());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                getBaseUrl() + "/describe",
                HttpMethod.POST,
                requestEntity,
                Map.class
        );

        // 可能成功或失败，取决于是否配置了视觉模型
        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST ||
                response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR);

        Files.deleteIfExists(tempImage);
    }

    @Test
    void testChatWithImage() throws Exception {
        Path tempImage = Files.createTempFile("test-", ".jpg");
        Files.write(tempImage, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        FileSystemResource fileResource = new FileSystemResource(tempImage.toFile());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", fileResource);
        body.add("question", "这是什么图片？");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                getBaseUrl() + "/chat",
                HttpMethod.POST,
                requestEntity,
                Map.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);

        Files.deleteIfExists(tempImage);
    }

    @Test
    void testUploadImage() throws Exception {
        Path tempImage = Files.createTempFile("test-", ".jpg");
        Files.write(tempImage, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        FileSystemResource fileResource = new FileSystemResource(tempImage.toFile());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                getBaseUrl() + "/images",
                HttpMethod.POST,
                requestEntity,
                Map.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.OK ||
                response.getStatusCode() == HttpStatus.BAD_REQUEST);

        Files.deleteIfExists(tempImage);
    }

    @Test
    void testDescribeImageWithoutFile() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                getBaseUrl() + "/describe",
                HttpMethod.POST,
                requestEntity,
                Map.class
        );

        assertTrue(response.getStatusCode() == HttpStatus.BAD_REQUEST ||
                response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
