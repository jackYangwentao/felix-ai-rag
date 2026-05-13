package com.felix.ai.rag.chunker;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 句子窗口分块器
 * 参考 LlamaIndex SentenceWindowNodeParser 实现
 *
 * 核心思想：为检索精确性而索引小块（句子），为上下文丰富性而检索大块（窗口）
 *
 * 实现机制：
 * - 索引阶段：文档分割为单个句子，每个句子作为独立片段
 * - 元数据存储：存储前后N个句子作为上下文窗口（window_size）
 * - 检索阶段：在单一句子片段上执行相似度搜索（高精度）
 * - 后处理阶段：用完整窗口文本替换单一句子（丰富上下文）
 */
@Slf4j
public class SentenceWindowChunker implements TextChunker {

    private final int windowSize;

    // 句子分隔符正则（支持中英文）
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
            "[^。！？.!?\n]+[。！？.!?]?",
            Pattern.MULTILINE
    );

    public SentenceWindowChunker(int windowSize) {
        this.windowSize = windowSize;
        log.info("创建句子窗口分块器，窗口大小: {}", windowSize);
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 分割为句子
        List<Sentence> sentences = splitIntoSentences(text);
        log.debug("文本分割为 {} 个句子", sentences.size());

        // 2. 为每个句子构建窗口
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            SentenceWindow window = buildWindow(sentences, i);
            // 返回中心句子（用于嵌入），但元数据中包含完整窗口
            String chunk = serializeWindow(window);
            chunks.add(chunk);
        }

        log.info("句子窗口分块完成: {} 个句子，窗口大小: {}", chunks.size(), windowSize);
        return chunks;
    }

    /**
     * 将文本分割为句子列表
     */
    private List<Sentence> splitIntoSentences(String text) {
        List<Sentence> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(text);

        int position = 0;
        while (matcher.find()) {
            String content = matcher.group().trim();
            if (!content.isEmpty()) {
                sentences.add(Sentence.builder()
                        .content(content)
                        .startPosition(position)
                        .build());
                position++;
            }
        }

        // 如果没有匹配到句子（比如没有标点），按行分割
        if (sentences.isEmpty()) {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    sentences.add(Sentence.builder()
                            .content(line)
                            .startPosition(i)
                            .build());
                }
            }
        }

        return sentences;
    }

    /**
     * 为指定索引的句子构建上下文窗口
     */
    private SentenceWindow buildWindow(List<Sentence> sentences, int centerIndex) {
        Sentence centerSentence = sentences.get(centerIndex);

        // 计算窗口范围
        int windowStart = Math.max(0, centerIndex - windowSize);
        int windowEnd = Math.min(sentences.size() - 1, centerIndex + windowSize);

        // 收集窗口内句子
        List<String> beforeSentences = new ArrayList<>();
        List<String> afterSentences = new ArrayList<>();

        for (int i = windowStart; i <= windowEnd; i++) {
            if (i < centerIndex) {
                beforeSentences.add(sentences.get(i).getContent());
            } else if (i > centerIndex) {
                afterSentences.add(sentences.get(i).getContent());
            }
        }

        return SentenceWindow.builder()
                .centerSentence(centerSentence.getContent())
                .beforeSentences(beforeSentences)
                .afterSentences(afterSentences)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .centerIndex(centerIndex)
                .build();
    }

    /**
     * 序列化窗口为存储格式
     * 格式: [WINDOW_START]...前后句子...[CENTER]中心句子[CENTER_END]...[WINDOW_END]
     * 便于后续提取完整窗口内容
     */
    private String serializeWindow(SentenceWindow window) {
        StringBuilder sb = new StringBuilder();

        // 添加前后句子
        if (!window.getBeforeSentences().isEmpty()) {
            sb.append("[BEFORE]");
            sb.append(String.join("", window.getBeforeSentences()));
            sb.append("[BEFORE_END]");
        }

        // 添加中心句子（用于嵌入的关键内容）
        sb.append("[CENTER]");
        sb.append(window.getCenterSentence());
        sb.append("[CENTER_END]");

        // 添加后续句子
        if (!window.getAfterSentences().isEmpty()) {
            sb.append("[AFTER]");
            sb.append(String.join("", window.getAfterSentences()));
            sb.append("[AFTER_END]");
        }

        return sb.toString();
    }

    /**
     * 从序列化的窗口中提取完整窗口文本（用于检索后替换）
     */
    public static String extractFullWindow(String serializedChunk) {
        if (serializedChunk == null || serializedChunk.isEmpty()) {
            return "";
        }

        StringBuilder fullWindow = new StringBuilder();

        // 提取前面的句子
        String before = extractTagContent(serializedChunk, "BEFORE");
        if (!before.isEmpty()) {
            fullWindow.append(before);
        }

        // 提取中心句子
        String center = extractTagContent(serializedChunk, "CENTER");
        if (!center.isEmpty()) {
            fullWindow.append(center);
        }

        // 提取后面的句子
        String after = extractTagContent(serializedChunk, "AFTER");
        if (!after.isEmpty()) {
            fullWindow.append(after);
        }

        return fullWindow.toString().trim();
    }

    /**
     * 从序列化的窗口中提取中心句子（用于嵌入）
     */
    public static String extractCenterSentence(String serializedChunk) {
        return extractTagContent(serializedChunk, "CENTER");
    }

    /**
     * 提取标签内容
     */
    private static String extractTagContent(String text, String tagName) {
        String startTag = "[" + tagName + "]";
        String endTag = "[" + tagName + "_END]";

        int start = text.indexOf(startTag);
        int end = text.indexOf(endTag);

        if (start != -1 && end != -1 && end > start) {
            return text.substring(start + startTag.length(), end);
        }

        return "";
    }

    @Override
    public String getStrategyName() {
        return "sentence-window";
    }

    /**
     * 句子对象
     */
    @Data
    @Builder
    private static class Sentence {
        private String content;
        private int startPosition;
    }

    /**
     * 句子窗口对象
     */
    @Data
    @Builder
    private static class SentenceWindow {
        private String centerSentence;           // 中心句子
        private List<String> beforeSentences;    // 前面的句子
        private List<String> afterSentences;     // 后面的句子
        private int windowStart;                 // 窗口起始索引
        private int windowEnd;                   // 窗口结束索引
        private int centerIndex;                 // 中心句子索引
    }
}
