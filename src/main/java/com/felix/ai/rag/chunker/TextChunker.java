package com.felix.ai.rag.chunker;

import java.util.List;

/**
 * 文本分块器接口
 * 参考 Datawhale All-In-RAG 分块策略实现
 */
public interface TextChunker {

    /**
     * 将文本分块
     *
     * @param text 输入文本
     * @return 分块后的文本列表
     */
    List<String> chunk(String text);

    /**
     * 获取分块策略名称
     *
     * @return 策略名称
     */
    String getStrategyName();
}
