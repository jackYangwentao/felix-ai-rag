package com.felix.ai.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * AI 回答内容
     */
    private String answer;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 检索到的相关文档片段
     */
    private List<String> sources;

    /**
     * 处理时间（毫秒）
     */
    private long processingTimeMs;
}
