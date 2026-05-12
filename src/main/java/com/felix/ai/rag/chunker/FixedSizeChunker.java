package com.felix.ai.rag.chunker;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定大小分块器
 * 参考 LangChain CharacterTextSplitter 实现
 *
 * 特点：
 * - 按目标字符数切分
 * - 优先保持段落完整性（以 separator 分隔）
 * - 通过重叠机制保持上下文连续性
 */
@Slf4j
public class FixedSizeChunker implements TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;
    private final String separator;

    public FixedSizeChunker(int chunkSize, int chunkOverlap, String separator) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separator = separator != null ? separator : "\n\n";

        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be less than chunkSize");
        }
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        log.debug("使用固定大小分块，文本长度: {}, chunkSize: {}, chunkOverlap: {}",
                text.length(), chunkSize, chunkOverlap);

        // 1. 先按分隔符分割（默认段落）
        String[] splits = text.split(separator);

        List<String> chunks = new ArrayList<>();
        List<String> currentChunk = new ArrayList<>();
        int currentLength = 0;

        for (String split : splits) {
            String trimmed = split.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int splitLength = trimmed.length();

            // 如果当前段落本身就超过 chunkSize，直接作为一个块
            if (splitLength > chunkSize) {
                // 先保存之前的累积内容
                if (!currentChunk.isEmpty()) {
                    chunks.add(String.join(separator, currentChunk));
                    currentChunk.clear();
                    currentLength = 0;
                }
                // 将超长段落分割成多个块
                chunks.addAll(splitLongText(trimmed));
                continue;
            }

            // 如果加入当前段落会超过限制，先保存当前块
            if (currentLength + splitLength + separator.length() > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(String.join(separator, currentChunk));

                // 保留重叠部分
                currentChunk = getOverlapChunks(currentChunk);
                currentLength = currentChunk.stream().mapToInt(String::length).sum()
                        + (currentChunk.size() - 1) * separator.length();
            }

            // 添加当前段落到当前块
            currentChunk.add(trimmed);
            currentLength += splitLength + (currentChunk.size() > 1 ? separator.length() : 0);
        }

        // 保存最后一个块
        if (!currentChunk.isEmpty()) {
            chunks.add(String.join(separator, currentChunk));
        }

        log.debug("固定大小分块完成，生成 {} 个块", chunks.size());
        return chunks;
    }

    /**
     * 分割超长文本
     */
    private List<String> splitLongText(String text) {
        List<String> result = new ArrayList<>();

        for (int i = 0; i < text.length(); i += chunkSize - chunkOverlap) {
            int end = Math.min(i + chunkSize, text.length());
            result.add(text.substring(i, end));

            if (end == text.length()) {
                break;
            }
        }

        if (result.size() > 1) {
            log.warn("文本片段长度({})超过 chunkSize({})，已强制分割为 {} 块",
                    text.length(), chunkSize, result.size());
        }

        return result;
    }

    /**
     * 获取重叠的块内容
     */
    private List<String> getOverlapChunks(List<String> chunks) {
        List<String> overlapChunks = new ArrayList<>();
        int overlapLength = 0;

        // 从后往前添加，直到满足重叠长度
        for (int i = chunks.size() - 1; i >= 0; i--) {
            String chunk = chunks.get(i);
            overlapChunks.add(0, chunk);
            overlapLength += chunk.length() + separator.length();

            if (overlapLength >= chunkOverlap) {
                break;
            }
        }

        return overlapChunks;
    }

    @Override
    public String getStrategyName() {
        return "fixed-size";
    }
}
