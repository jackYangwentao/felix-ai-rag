package com.felix.ai.rag.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 文档加载器
 * 支持从文件加载文本内容
 */
@Component
@Slf4j
public class DocumentLoader {

    /**
     * 支持的文件类型
     */
    private static final String[] SUPPORTED_EXTENSIONS = {
            "txt", "md", "markdown", "json", "xml", "html", "htm",
            "csv", "tsv", "log", "yaml", "yml", "properties", "sql"
    };

    /**
     * 从MultipartFile加载文档内容
     *
     * @param file 上传的文件
     * @return 文档内容
     * @throws IOException 读取失败时抛出
     */
    public String loadFromMultipartFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        log.info("正在加载文档: {}", filename);

        // 检查文件类型
        if (!isSupportedFile(filename)) {
            throw new IllegalArgumentException("不支持的文件类型: " + filename);
        }

        // 读取文件内容
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String content = reader.lines().collect(Collectors.joining("\n"));
            log.info("文档加载成功: {}, 字符数: {}", filename, content.length());
            return content;
        }
    }

    /**
     * 从文本内容创建文档（用于直接粘贴的文本）
     *
     * @param content 文本内容
     * @param sourceName 来源名称
     * @return 文档内容
     */
    public String loadFromText(String content, String sourceName) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        log.info("加载文本文档: {}, 字符数: {}", sourceName, content.length());
        return content;
    }

    /**
     * 检查文件类型是否支持
     */
    private boolean isSupportedFile(String filename) {
        if (filename == null) return false;
        String lowerName = filename.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lowerName.endsWith("." + ext)) {
                return true;
            }
        }
        return true; // 默认允许未知类型
    }

    /**
     * 获取支持的文件类型列表
     */
    public String getSupportedExtensions() {
        return String.join(", ", SUPPORTED_EXTENSIONS);
    }
}
