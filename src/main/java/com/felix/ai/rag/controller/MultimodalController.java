package com.felix.ai.rag.controller;

import com.felix.ai.rag.service.MultimodalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/**
 * 多模态控制器
 * 提供图像处理和问答API接口
 */
@RestController
@RequestMapping("/api/v1/rag/multimodal")
@RequiredArgsConstructor
@Slf4j
public class MultimodalController {

    private final MultimodalService multimodalService;

    /**
     * 图像描述接口
     * 上传图像并获取AI生成的描述
     */
    @PostMapping("/describe")
    public ResponseEntity<?> describeImage(
            @RequestParam("image") MultipartFile imageFile,
            @RequestParam(value = "prompt", required = false) String prompt) {
        log.info("收到图像描述请求: {}", imageFile.getOriginalFilename());

        try {
            MultimodalService.ImageProcessingResult result =
                    multimodalService.processImage(imageFile, prompt);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", result.getFilename(),
                    "description", result.getDescription(),
                    "metadata", result.getMetadata()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("图像描述失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 上传图像并索引到知识库
     */
    @PostMapping("/images")
    public ResponseEntity<?> uploadImage(
            @RequestParam("image") MultipartFile imageFile,
            @RequestParam(value = "prompt", required = false) String prompt) {
        log.info("收到图像上传请求: {}", imageFile.getOriginalFilename());

        try {
            String result = multimodalService.uploadAndIndexImage(imageFile, prompt);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "图像上传并索引成功",
                    "indexedContent", result
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("图像上传失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 基于图像的问答
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chatWithImage(
            @RequestParam("image") MultipartFile imageFile,
            @RequestParam(value = "question", required = false) String question) {
        log.info("收到图像问答请求: {}, 问题: {}",
                imageFile.getOriginalFilename(), question);

        try {
            String answer = multimodalService.chatWithImage(imageFile, question);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("图像问答失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 获取支持的图像类型
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Set<String>> getSupportedTypes() {
        return ResponseEntity.ok(multimodalService.getSupportedImageTypes());
    }

    /**
     * 检查文件是否为图像
     */
    @PostMapping("/check-image")
    public ResponseEntity<?> checkIsImage(@RequestParam("file") MultipartFile file) {
        boolean isImage = multimodalService.isImageFile(file);
        return ResponseEntity.ok(Map.of(
                "isImage", isImage,
                "contentType", file.getContentType()
        ));
    }
}
