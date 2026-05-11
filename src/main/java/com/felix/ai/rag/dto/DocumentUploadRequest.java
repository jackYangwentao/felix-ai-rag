package com.felix.ai.rag.dto;

import lombok.Data;

/**
 * 文档上传请求 DTO
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
}
