package com.felix.ai.rag.retriever;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文压缩检索器
 * 参考 Datawhale All-In-RAG 高级检索技术章节
 *
 * 核心功能：
 * 1. 内容提取：从文档中只抽出与查询相关的句子或段落
 * 2. 文档过滤：丢弃经判断后不相关的整个文档
 *
 * 解决"初步检索到的文档块包含大量无关噪音文本"的问题
 */
@Component
@Slf4j
public class ContextualCompressionRetriever {

    private final ChatLanguageModel chatLanguageModel;

    public ContextualCompressionRetriever(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 压缩内容 - 提取与查询相关的部分
     */
    public CompressionResult compress(String query, List<Content> contents) {
        log.info("上下文压缩: 查询='{}', 文档数={}", query, contents.size());
        long startTime = System.currentTimeMillis();

        List<CompressedContent> compressedContents = new ArrayList<>();
        int filteredCount = 0;

        for (Content content : contents) {
            String text = content.textSegment().text();

            // 1. 首先判断文档是否相关
            boolean isRelevant = checkRelevance(query, text);

            if (!isRelevant) {
                filteredCount++;
                log.debug("文档被过滤: 与查询不相关");
                continue;
            }

            // 2. 提取相关内容
            String extractedContent = extractRelevantContent(query, text);

            if (extractedContent != null && !extractedContent.isEmpty()) {
                compressedContents.add(CompressedContent.builder()
                        .originalContent(text)
                        .compressedContent(extractedContent)
                        .compressionRatio((double) extractedContent.length() / text.length())
                        .build());
            }
        }

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("上下文压缩完成: 原始{}个文档, 保留{}个, 过滤{}个, 耗时{}ms",
                contents.size(), compressedContents.size(), filteredCount, processingTime);

        return CompressionResult.builder()
                .originalCount(contents.size())
                .compressedCount(compressedContents.size())
                .filteredCount(filteredCount)
                .compressedContents(compressedContents)
                .processingTimeMs(processingTime)
                .build();
    }

    /**
     * 检查文档是否与查询相关
     */
    private boolean checkRelevance(String query, String document) {
        String prompt = """
                请判断以下文档是否与用户查询相关。
                只回答"相关"或"不相关"，不要解释。

                查询: %s

                文档: %s

                是否相关:""".formatted(query, truncate(document, 1000));

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String result = response.content().text().trim().toLowerCase();
            return result.contains("相关") && !result.contains("不相关");

        } catch (Exception e) {
            log.warn("相关性检查失败，默认保留文档", e);
            return true;
        }
    }

    /**
     * 提取与查询相关的内容
     */
    private String extractRelevantContent(String query, String document) {
        String prompt = """
                请从以下文档中提取与用户查询最相关的句子或段落。
                只提取直接相关的内容，去除无关信息。
                如果文档中有多个相关部分，请保留所有相关部分，用换行分隔。
                不要添加总结或解释，只返回提取的内容。

                查询: %s

                文档: %s

                相关内容:""".formatted(query, truncate(document, 1500));

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String extracted = response.content().text().trim();

            // 清理提取的内容
            extracted = cleanExtractedContent(extracted);

            // 如果提取的内容太短，返回原文
            if (extracted.length() < document.length() * 0.1) {
                return document;
            }

            return extracted;

        } catch (Exception e) {
            log.warn("内容提取失败，返回原文", e);
            return document;
        }
    }

    /**
     * 批量压缩 - 使用EmbeddingsFilter快速过滤
     * 成本更低，适合大规模文档
     */
    public CompressionResult compressWithEmbeddingFilter(
            String query,
            List<Content> contents,
            double similarityThreshold) {

        log.info("基于嵌入的上下文压缩: 查询='{}', 阈值={}", query, similarityThreshold);

        // 这里简化实现，实际应该使用嵌入模型计算相似度
        // 暂时使用LLM方式进行过滤
        return compress(query, contents);
    }

    /**
     * 清理提取的内容
     */
    private String cleanExtractedContent(String content) {
        // 移除常见的LLM输出前缀
        content = content.replaceAll("^(相关内容[:：]?\\s*)", "");
        content = content.replaceAll("^(提取的内容[:：]?\\s*)", "");
        content = content.replaceAll("^(以下是[:：]?\\s*)", "");

        // 移除markdown代码块标记
        content = content.replaceAll("```\\w*\\n?", "");

        return content.trim();
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class CompressionResult {
        private int originalCount;
        private int compressedCount;
        private int filteredCount;
        private List<CompressedContent> compressedContents;
        private long processingTimeMs;

        /**
         * 获取压缩后的文本内容列表
         */
        public List<String> getCompressedTexts() {
            return compressedContents.stream()
                    .map(CompressedContent::getCompressedContent)
                    .toList();
        }

        /**
         * 平均压缩率
         */
        public double getAverageCompressionRatio() {
            if (compressedContents.isEmpty()) {
                return 0.0;
            }
            return compressedContents.stream()
                    .mapToDouble(CompressedContent::getCompressionRatio)
                    .average()
                    .orElse(0.0);
        }
    }

    @Data
    @Builder
    public static class CompressedContent {
        private String originalContent;
        private String compressedContent;
        private double compressionRatio;
    }
}
