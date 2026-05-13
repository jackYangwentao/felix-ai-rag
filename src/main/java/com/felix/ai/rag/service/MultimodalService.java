package com.felix.ai.rag.service;

import com.felix.ai.rag.loader.DocumentLoader;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * 多模态服务
 * 支持图像理解和处理
 *
 * 参考 Datawhale All-In-RAG 多模态嵌入实现
 * 使用 Ollama 视觉模型（如 llava）进行图像理解
 */
@Service
@Slf4j
public class MultimodalService {

    @Value("${langchain4j.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${rag.multimodal.vision-model:llava}")
    private String visionModelName;

    @Value("${rag.multimodal.enabled:true}")
    private boolean multimodalEnabled;

    private final RagService ragService;
    private final DocumentLoader documentLoader;

    // 支持的图像格式
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    );

    public MultimodalService(RagService ragService, DocumentLoader documentLoader) {
        this.ragService = ragService;
        this.documentLoader = documentLoader;
    }

    /**
     * 处理图像文件并生成描述
     *
     * @param imageFile 图像文件
     * @param prompt 提示词（可选，用于指导模型如何描述图像）
     * @return 图像描述文本
     */
    public ImageProcessingResult processImage(MultipartFile imageFile, String prompt) {
        if (!multimodalEnabled) {
            throw new IllegalStateException("多模态功能未启用");
        }

        // 验证文件类型
        if (!isImageFile(imageFile)) {
            throw new IllegalArgumentException("不支持的文件类型: " + imageFile.getContentType());
        }

        log.info("处理图像: {}, 大小: {} bytes", imageFile.getOriginalFilename(), imageFile.getSize());

        try {
            // 读取图像数据
            byte[] imageBytes = imageFile.getBytes();
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // 获取实际的MIME类型
            String mimeType = imageFile.getContentType();
            if (mimeType == null || mimeType.isEmpty()) {
                mimeType = "image/jpeg"; // 默认回退
            }

            // 使用视觉模型生成描述
            String description = generateImageDescription(base64Image, mimeType, prompt);

            // 获取图像元数据
            ImageMetadata metadata = extractImageMetadata(imageBytes, imageFile.getOriginalFilename());

            return ImageProcessingResult.builder()
                    .filename(imageFile.getOriginalFilename())
                    .description(description)
                    .metadata(metadata)
                    .success(true)
                    .build();

        } catch (IOException e) {
            log.error("图像处理失败", e);
            throw new RuntimeException("图像处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传图像并索引到知识库
     *
     * @param imageFile 图像文件
     * @param descriptionPrompt 描述提示词
     * @return 处理结果
     */
    public String uploadAndIndexImage(MultipartFile imageFile, String descriptionPrompt) {
        ImageProcessingResult result = processImage(imageFile, descriptionPrompt);

        if (!result.isSuccess()) {
            throw new RuntimeException("图像处理失败");
        }

        // 构建索引内容
        StringBuilder content = new StringBuilder();
        content.append("【图像内容】\n");
        content.append("文件名: ").append(result.getFilename()).append("\n");
        content.append("描述: ").append(result.getDescription()).append("\n");

        // 添加元数据
        ImageMetadata metadata = result.getMetadata();
        if (metadata != null) {
            content.append("尺寸: ").append(metadata.getWidth()).append("x").append(metadata.getHeight()).append("\n");
            content.append("格式: ").append(metadata.getFormat()).append("\n");
        }

        // 索引到知识库
        String sourceName = "image:" + result.getFilename();
        ragService.indexDocument(content.toString(), sourceName);

        log.info("图像已索引到知识库: {}", sourceName);

        return content.toString();
    }

    /**
     * 基于图像进行问答
     *
     * @param imageFile 图像文件
     * @param question 用户问题
     * @return 回答
     */
    public String chatWithImage(MultipartFile imageFile, String question) {
        if (!multimodalEnabled) {
            throw new IllegalStateException("多模态功能未启用");
        }

        try {
            byte[] imageBytes = imageFile.getBytes();
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // 创建视觉模型
            ChatLanguageModel visionModel = OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(visionModelName)
                    .timeout(Duration.ofSeconds(120))
                    .build();

            // 构建用户消息（包含图像和文本）
            Image image = Image.builder()
                    .base64Data(base64Image)
                    .mimeType(imageFile.getContentType())
                    .build();

            UserMessage userMessage = UserMessage.from(
                    TextContent.from(question != null ? question : "请描述这张图片的内容"),
                    ImageContent.from(image)
            );

            // 调用视觉模型
            Response<AiMessage> response = visionModel.generate(userMessage);
            return response.content().text();

        } catch (IOException e) {
            log.error("图像问答失败", e);
            throw new RuntimeException("图像问答失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成图像描述
     */
    private String generateImageDescription(String base64Image, String mimeType, String prompt) {
        try {
            ChatLanguageModel visionModel = OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(visionModelName)
                    .timeout(Duration.ofSeconds(120))
                    .build();

            String userPrompt = prompt != null && !prompt.isEmpty()
                    ? prompt
                    : "请详细描述这张图片的内容，包括：1. 图片中的主要对象和场景 2. 文字内容（如果有） 3. 整体氛围和风格";

            Image image = Image.builder()
                    .base64Data(base64Image)
                    .mimeType(mimeType)
                    .build();

            UserMessage userMessage = UserMessage.from(
                    TextContent.from(userPrompt),
                    ImageContent.from(image)
            );

            Response<AiMessage> response = visionModel.generate(userMessage);
            return response.content().text();

        } catch (Exception e) {
            log.error("生成图像描述失败", e);
            return "无法生成图像描述: " + e.getMessage();
        }
    }

    /**
     * 提取图像元数据
     */
    private ImageMetadata extractImageMetadata(byte[] imageBytes, String filename) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
            BufferedImage bufferedImage = ImageIO.read(bais);

            if (bufferedImage == null) {
                return null;
            }

            String format = filename != null
                    ? filename.substring(filename.lastIndexOf('.') + 1).toUpperCase()
                    : "UNKNOWN";

            return ImageMetadata.builder()
                    .width(bufferedImage.getWidth())
                    .height(bufferedImage.getHeight())
                    .format(format)
                    .build();

        } catch (Exception e) {
            log.warn("提取图像元数据失败", e);
            return null;
        }
    }

    /**
     * 检查是否为图像文件
     */
    public boolean isImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        String contentType = file.getContentType();
        return contentType != null && SUPPORTED_IMAGE_TYPES.contains(contentType.toLowerCase());
    }

    /**
     * 获取支持的图像类型
     */
    public Set<String> getSupportedImageTypes() {
        return SUPPORTED_IMAGE_TYPES;
    }

    /**
     * 图像处理结果
     */
    @lombok.Data
    @lombok.Builder
    public static class ImageProcessingResult {
        private String filename;
        private String description;
        private ImageMetadata metadata;
        private boolean success;
        private String errorMessage;
    }

    /**
     * 图像元数据
     */
    @lombok.Data
    @lombok.Builder
    public static class ImageMetadata {
        private int width;
        private int height;
        private String format;
    }
}
