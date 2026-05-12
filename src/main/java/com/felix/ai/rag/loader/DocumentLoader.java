package com.felix.ai.rag.loader;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档加载器
 * 支持多种文档格式的加载和解析
 *
 * 参考 Datawhale All-In-RAG 数据加载实现
 * 支持格式：
 * - 文本文件：txt, md, markdown, json, xml, html, csv, yaml 等
 * - 办公文档：pdf, doc, docx, xls, xlsx, ppt, pptx
 * - 其他格式：rtf, odt, ods, odp 等
 */
@Component
@Slf4j
public class DocumentLoader {

    private final Tika tika;
    private final Parser parser;

    public DocumentLoader() {
        this.tika = new Tika();
        this.parser = new AutoDetectParser();
    }

    /**
     * 文档数据类
     * 包含内容和元数据
     */
    @Data
    @Builder
    public static class DocumentData {
        private String content;
        private String filename;
        private String contentType;
        private long fileSize;
        private Map<String, String> metadata;
        private Instant loadTime;
    }

    /**
     * 从 MultipartFile 加载文档
     * 自动检测文件类型并解析
     *
     * @param file 上传的文件
     * @return 文档数据（包含内容和元数据）
     * @throws IOException 读取失败时抛出
     */
    public DocumentData loadDocument(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        log.info("正在加载文档: {}, 大小: {} bytes", filename, file.getSize());

        try (InputStream inputStream = file.getInputStream()) {
            // 使用 Tika 检测 MIME 类型
            String contentType = tika.detect(inputStream);
            log.debug("检测到文件类型: {} -> {}", filename, contentType);

            // 重置流位置（Tika 检测后需要重新读取）
            inputStream.reset();

            // 根据文件类型选择合适的解析方式
            DocumentData documentData;
            if (isTextFile(contentType, filename)) {
                documentData = parseTextFile(inputStream, filename, contentType, file.getSize());
            } else {
                documentData = parseBinaryFile(inputStream, filename, contentType, file.getSize());
            }

            log.info("文档加载成功: {}, 字符数: {}, 类型: {}",
                    filename, documentData.getContent().length(), contentType);

            return documentData;
        }
    }

    /**
     * 从文本内容创建文档
     *
     * @param content    文本内容
     * @param sourceName 来源名称
     * @return 文档数据
     */
    public DocumentData loadFromText(String content, String sourceName) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        log.info("加载文本文档: {}, 字符数: {}", sourceName, content.length());

        return DocumentData.builder()
                .content(content)
                .filename(sourceName)
                .contentType("text/plain")
                .fileSize(content.getBytes(StandardCharsets.UTF_8).length)
                .metadata(new HashMap<>())
                .loadTime(Instant.now())
                .build();
    }

    /**
     * 解析文本文件
     */
    private DocumentData parseTextFile(InputStream inputStream, String filename,
                                       String contentType, long fileSize) throws IOException {
        String content = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("detected-type", contentType);

        return DocumentData.builder()
                .content(content)
                .filename(filename)
                .contentType(contentType)
                .fileSize(fileSize)
                .metadata(metadata)
                .loadTime(Instant.now())
                .build();
    }

    /**
     * 解析二进制文件（PDF、Word、Excel 等）
     * 使用 Apache Tika 提取内容
     */
    private DocumentData parseBinaryFile(InputStream inputStream, String filename,
                                         String contentType, long fileSize) throws IOException {
        Metadata tikaMetadata = new Metadata();
        tikaMetadata.set("resourceName", filename);
        tikaMetadata.set(Metadata.CONTENT_TYPE, contentType);

        // 设置内容提取的最大字符数（避免内存溢出）
        BodyContentHandler handler = new BodyContentHandler(10 * 1024 * 1024); // 10MB 上限

        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);

        try {
            parser.parse(inputStream, handler, tikaMetadata, context);

            String content = handler.toString();

            // 提取元数据
            Map<String, String> metadata = extractMetadata(tikaMetadata);
            metadata.put("detected-type", contentType);

            return DocumentData.builder()
                    .content(content)
                    .filename(filename)
                    .contentType(contentType)
                    .fileSize(fileSize)
                    .metadata(metadata)
                    .loadTime(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("解析文件失败: {}", filename, e);
            throw new IOException("无法解析文件: " + filename + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 从 Tika Metadata 中提取常用元数据
     */
    private Map<String, String> extractMetadata(Metadata tikaMetadata) {
        Map<String, String> metadata = new HashMap<>();

        // 文档标题
        addMetadataIfPresent(metadata, tikaMetadata, "title", "title");

        // 作者
        addMetadataIfPresent(metadata, tikaMetadata, "Author", "author");
        addMetadataIfPresent(metadata, tikaMetadata, "creator", "creator");

        // 创建/修改时间
        addMetadataIfPresent(metadata, tikaMetadata, "Creation-Date", "creation-date");
        addMetadataIfPresent(metadata, tikaMetadata, "Last-Modified", "last-modified");

        // 页数（PDF、Word）
        addMetadataIfPresent(metadata, tikaMetadata, "xmpTPg:NPages", "page-count");

        // 字数
        addMetadataIfPresent(metadata, tikaMetadata, "Word-Count", "word-count");

        // 应用程序
        addMetadataIfPresent(metadata, tikaMetadata, "Application-Name", "application");

        return metadata;
    }

    private void addMetadataIfPresent(Map<String, String> metadata, Metadata tikaMetadata,
                                      String tikaKey, String ourKey) {
        String value = tikaMetadata.get(tikaKey);
        if (value != null && !value.isEmpty()) {
            metadata.put(ourKey, value);
        }
    }

    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String contentType, String filename) {
        if (contentType != null) {
            return contentType.startsWith("text/") ||
                   contentType.equals("application/json") ||
                   contentType.equals("application/xml") ||
                   contentType.equals("application/javascript");
        }

        // 根据扩展名判断
        String lowerName = filename != null ? filename.toLowerCase() : "";
        return lowerName.endsWith(".txt") ||
               lowerName.endsWith(".md") ||
               lowerName.endsWith(".markdown") ||
               lowerName.endsWith(".json") ||
               lowerName.endsWith(".xml") ||
               lowerName.endsWith(".csv") ||
               lowerName.endsWith(".yaml") ||
               lowerName.endsWith(".yml") ||
               lowerName.endsWith(".html") ||
               lowerName.endsWith(".htm") ||
               lowerName.endsWith(".log") ||
               lowerName.endsWith(".properties") ||
               lowerName.endsWith(".sql");
    }

    /**
     * 检查文件类型是否支持
     */
    public boolean isSupportedFile(String filename) {
        if (filename == null) {
            return false;
        }
        // Tika 支持绝大多数文档格式，这里默认支持所有类型
        return true;
    }

    /**
     * 获取支持的文件类型说明
     */
    public String getSupportedFormats() {
        return "文本文件: .txt, .md, .json, .xml, .html, .csv, .yaml\n" +
               "办公文档: .pdf, .doc, .docx, .xls, .xlsx, .ppt, .pptx\n" +
               "其他格式: .rtf, .odt, .ods, .odp";
    }
}
