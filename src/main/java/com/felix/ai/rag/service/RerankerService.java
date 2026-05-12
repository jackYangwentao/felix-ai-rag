package com.felix.ai.rag.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 重排序服务
 * 使用交叉编码器模型对检索结果进行重排序，提高相关性
 *
 * 参考 Datawhale All-In-RAG 向量数据库章节的重排序概念
 */
@Service
@Slf4j
public class RerankerService {

    private final ChatLanguageModel chatLanguageModel;

    @Value("${rag.reranker.enabled:true}")
    private boolean rerankerEnabled;

    @Value("${rag.reranker.top-k:5}")
    private int rerankTopK;

    // 重排序提示词模板
    private static final String RERANK_PROMPT_TEMPLATE = """
            请评估以下文档片段与用户问题的相关性，并给出0-10的分数。
            10分表示非常相关，0分表示完全不相关。

            用户问题：{question}

            文档片段：{document}

            请只返回一个0-10之间的数字分数，不需要解释。
            分数：""";

    public RerankerService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 对检索结果进行重排序
     *
     * @param query 用户查询
     * @param candidates 候选文档列表
     * @param topK 返回前K个结果
     * @return 重排序后的结果
     */
    public List<ScoredDocument> rerank(String query, List<String> candidates, int topK) {
        if (!rerankerEnabled) {
            log.debug("重排序功能已禁用，返回原始结果");
            return candidates.stream()
                    .map(doc -> ScoredDocument.builder().content(doc).score(1.0).build())
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        log.info("开始重排序，查询: '{}', 候选数: {}, 返回Top-{}", query, candidates.size(), topK);

        // 对每个候选文档进行评分
        List<ScoredDocument> scoredDocs = candidates.parallelStream()
                .map(doc -> scoreDocument(query, doc))
                .filter(Objects::nonNull)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        // 返回前K个结果
        List<ScoredDocument> topResults = scoredDocs.stream()
                .limit(topK)
                .collect(Collectors.toList());

        log.info("重排序完成，返回 {} 个结果，最高分: {}, 最低分: {}",
                topResults.size(),
                topResults.isEmpty() ? 0 : topResults.get(0).getScore(),
                topResults.isEmpty() ? 0 : topResults.get(topResults.size() - 1).getScore());

        return topResults;
    }

    /**
     * 对检索结果进行重排序（使用默认topK）
     */
    public List<ScoredDocument> rerank(String query, List<String> candidates) {
        return rerank(query, candidates, rerankTopK);
    }

    /**
     * 评分单个文档
     */
    private ScoredDocument scoreDocument(String query, String document) {
        try {
            String prompt = RERANK_PROMPT_TEMPLATE
                    .replace("{question}", query)
                    .replace("{document}", document.length() > 500 ? document.substring(0, 500) + "..." : document);

            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String output = response.content().text().trim();
            double score = extractScore(output);

            return ScoredDocument.builder()
                    .content(document)
                    .score(score)
                    .build();

        } catch (Exception e) {
            log.warn("文档评分失败，使用默认分数", e);
            return ScoredDocument.builder()
                    .content(document)
                    .score(5.0) // 默认中等分数
                    .build();
        }
    }

    /**
     * 从模型输出中提取分数
     */
    private double extractScore(String output) {
        // 尝试匹配数字（包括小数）
        Pattern pattern = Pattern.compile("(\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(output);

        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group(1));
                // 归一化到0-10范围
                return Math.max(0.0, Math.min(10.0, score));
            } catch (NumberFormatException e) {
                log.warn("无法解析分数: {}", output);
            }
        }

        // 如果无法提取数字，根据关键词判断
        String lower = output.toLowerCase();
        if (lower.contains("high") || lower.contains("relevant") || lower.contains("相关")) {
            return 8.0;
        } else if (lower.contains("medium") || lower.contains("一般")) {
            return 5.0;
        } else if (lower.contains("low") || lower.contains("irrelevant") || lower.contains("不相关")) {
            return 2.0;
        }

        return 5.0; // 默认分数
    }

    /**
     * 检查是否启用重排序
     */
    public boolean isEnabled() {
        return rerankerEnabled;
    }

    /**
     * 带分数的文档
     */
    @Data
    @Builder
    public static class ScoredDocument {
        private String content;
        private double score;
    }
}
