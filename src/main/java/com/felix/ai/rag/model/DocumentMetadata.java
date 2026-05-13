package com.felix.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档元数据
 * 存储文档的结构化信息，支持元数据过滤检索
 *
 * 参考 Datawhale All-In-RAG 结构化索引设计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {

    // ==================== 基础信息 ====================
    private String documentId;           // 文档唯一ID
    private String documentName;         // 文档名称
    private String documentType;         // 文档类型 (pdf, txt, md, docx等)
    private String contentType;          // 内容类型 (article, report, code, faq等)

    // ==================== 来源信息 ====================
    private String source;               // 来源路径/URL
    private String author;               // 作者
    private LocalDateTime createTime;    // 创建时间
    private LocalDateTime indexTime;     // 索引时间

    // ==================== 结构信息 ====================
    private String title;                // 文档标题
    private String chapter;              // 章节标题
    private String section;              // 小节标题
    private Integer pageNumber;          // 页码
    private Integer totalPages;          // 总页数

    // ==================== 业务标签 ====================
    private String category;             // 业务分类
    private String tags;                 // 标签（逗号分隔）
    private String year;                 // 年份
    private String quarter;              // 季度
    private String department;           // 部门
    private String project;              // 项目

    // ==================== 自定义扩展 ====================
    @Builder.Default
    private Map<String, String> customFields = new HashMap<>();

    /**
     * 转换为 LangChain4j Metadata 格式
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();

        putIfNotNull(map, "document_id", documentId);
        putIfNotNull(map, "document_name", documentName);
        putIfNotNull(map, "document_type", documentType);
        putIfNotNull(map, "content_type", contentType);
        putIfNotNull(map, "source", source);
        putIfNotNull(map, "author", author);
        putIfNotNull(map, "create_time", createTime != null ? createTime.toString() : null);
        putIfNotNull(map, "index_time", indexTime != null ? indexTime.toString() : null);
        putIfNotNull(map, "title", title);
        putIfNotNull(map, "chapter", chapter);
        putIfNotNull(map, "section", section);
        putIfNotNull(map, "page_number", pageNumber != null ? pageNumber.toString() : null);
        putIfNotNull(map, "total_pages", totalPages != null ? totalPages.toString() : null);
        putIfNotNull(map, "category", category);
        putIfNotNull(map, "tags", tags);
        putIfNotNull(map, "year", year);
        putIfNotNull(map, "quarter", quarter);
        putIfNotNull(map, "department", department);
        putIfNotNull(map, "project", project);

        // 添加自定义字段
        if (customFields != null) {
            map.putAll(customFields);
        }

        return map;
    }

    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    /**
     * 从文件名自动提取元数据
     */
    public static DocumentMetadata extractFromFilename(String filename) {
        DocumentMetadataBuilder builder = DocumentMetadata.builder();

        // 基础信息
        builder.documentName(filename);
        builder.documentType(extractFileExtension(filename));
        builder.indexTime(LocalDateTime.now());

        // 尝试从文件名提取年份
        String year = extractYearFromFilename(filename);
        if (year != null) {
            builder.year(year);
        }

        // 尝试从文件名提取季度
        String quarter = extractQuarterFromFilename(filename);
        if (quarter != null) {
            builder.quarter(quarter);
        }

        return builder.build();
    }

    /**
     * 提取文件扩展名
     */
    private static String extractFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 从文件名提取年份 (如: report_2023_Q1.pdf -> 2023)
     */
    private static String extractYearFromFilename(String filename) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(20\\d{2})");
        java.util.regex.Matcher matcher = pattern.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从文件名提取季度 (如: report_2023_Q1.pdf -> Q1)
     */
    private static String extractQuarterFromFilename(String filename) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(Q[1-4])");
        java.util.regex.Matcher matcher = pattern.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
