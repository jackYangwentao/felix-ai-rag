package com.felix.ai.rag.dto;

import lombok.Data;

/**
 * 聊天请求 DTO
 */
@Data
public class ChatRequest {

    /**
     * 用户输入的消息
     */
    private String message;

    /**
     * 会话ID（可选，用于保持上下文）
     */
    private String sessionId;

    /**
     * 是否使用RAG检索
     */
    private boolean useRag = true;
}
