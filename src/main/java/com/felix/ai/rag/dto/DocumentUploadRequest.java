package com.felix.ai.rag.dto;

import lombok.Data;

import java.util.Map;

/**
 * 文档上传请求 DTO
 * 支持元数据过滤检索
 */
@Data
public class DocumentUploadRequest {

    /**
     * 文档内容（文本）
     */
    private String content;

    /**
     * 文档名称
     */
    private String documentName;

    /**
     * 文档类型（如：pdf, txt, docx）
     */
    private String documentType;

    /**
     * 文档元数据描述
     */
    private String description;

    // ==================== 元数据字段 ====================

    /**
     * 作者
     */
    private String author;

    /**
     * 业务分类
     */
    private String category;

    /**
     * 年份
     */
    private String year;

    /**
     * 季度 (Q1, Q2, Q3, Q4)
     */
    private String quarter;

    /**
     * 部门
     */
    private String department;

    /**
     * 项目
     */
    private String project;

    /**
     * 标签（逗号分隔）
     */
    private String tags;

    /**
     * 自定义元数据字段
     */
    private Map<String, String> customMetadata;
}
