package com.felix.ai.rag.query;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询重写服务
 * 参考 Datawhale All-In-RAG 查询处理最佳实践
 *
 * 核心功能：
 * 1. 查询理解与分析
 * 2. 查询意图识别
 * 3. 查询规范化
 * 4. 多语言查询处理
 */
@Service
@Slf4j
public class QueryRewriteService {

    private final ChatLanguageModel chatLanguageModel;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean enabled;

    @Value("${rag.query-rewrite.intent-analysis:true}")
    private boolean intentAnalysisEnabled;

    public QueryRewriteService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 重写查询 - 使其更适合检索
     */
    public QueryRewriteResult rewrite(String query) {
        if (!enabled) {
            return QueryRewriteResult.builder()
                    .originalQuery(query)
                    .rewrittenQuery(query)
                    .build();
        }

        log.info("重写查询: '{}'", query);
        long startTime = System.currentTimeMillis();

        try {
            // 1. 分析查询意图
            QueryIntent intent = analyzeIntent(query);

            // 2. 根据意图重写查询
            String rewritten = generateRewrittenQuery(query, intent);

            // 3. 提取关键词（用于稀疏检索）
            List<String> keywords = extractKeywords(query);

            log.info("查询重写完成，耗时 {}ms", System.currentTimeMillis() - startTime);

            return QueryRewriteResult.builder()
                    .originalQuery(query)
                    .rewrittenQuery(rewritten)
                    .intent(intent)
                    .keywords(keywords)
                    .build();

        } catch (Exception e) {
            log.error("查询重写失败，返回原始查询", e);
            return QueryRewriteResult.builder()
                    .originalQuery(query)
                    .rewrittenQuery(query)
                    .build();
        }
    }

    /**
     * 分析查询意图
     */
    private QueryIntent analyzeIntent(String query) {
        if (!intentAnalysisEnabled) {
            return QueryIntent.builder()
                    .type(QueryIntent.IntentType.INFORMATIONAL)
                    .confidence(0.5)
                    .build();
        }

        String prompt = """
                分析以下用户查询的意图，从以下类型中选择最合适的：
                - FACTUAL: 事实性查询（需要具体答案）
                - DEFINITION: 定义查询（询问概念含义）
                - HOW_TO: 操作/方法查询（询问如何做某事）
                - COMPARISON: 比较查询（比较多个事物）
                - LIST: 列表查询（要求列举）
                - INFORMATIONAL: 信息性查询（一般性了解）

                只返回意图类型，不需要解释。

                查询：%s
                意图类型：""".formatted(query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String intentText = response.content().text().trim().toUpperCase();
            QueryIntent.IntentType type = parseIntentType(intentText);

            return QueryIntent.builder()
                    .type(type)
                    .confidence(0.8)
                    .build();

        } catch (Exception e) {
            log.warn("意图分析失败", e);
            return QueryIntent.builder()
                    .type(QueryIntent.IntentType.INFORMATIONAL)
                    .confidence(0.5)
                    .build();
        }
    }

    /**
     * 根据意图重写查询
     */
    private String generateRewrittenQuery(String query, QueryIntent intent) {
        String prompt = switch (intent.getType()) {
            case FACTUAL -> """
                    将以下事实性查询改写成更清晰的检索式，保留关键实体和概念。
                    原始查询：%s
                    优化后的查询：""".formatted(query);

            case DEFINITION -> """
                    将以下定义查询改写成更明确的检索式，强调需要定义的核心概念。
                    原始查询：%s
                    优化后的查询：""".formatted(query);

            case HOW_TO -> """
                    将以下操作查询改写成更具体的检索式，明确目标动作和对象。
                    原始查询：%s
                    优化后的查询：""".formatted(query);

            case COMPARISON -> """
                    将以下比较查询改写成更清晰的检索式，明确比较的双方和维度。
                    原始查询：%s
                    优化后的查询：""".formatted(query);

            default -> """
                    优化以下查询，使其更适合语义检索，保持原意但改进表述。
                    原始查询：%s
                    优化后的查询：""".formatted(query);
        };

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String rewritten = response.content().text().trim();

            // 验证重写结果
            if (rewritten.length() > query.length() * 3 || rewritten.length() < 3) {
                return query;
            }

            return rewritten;

        } catch (Exception e) {
            log.warn("查询重写失败", e);
            return query;
        }
    }

    /**
     * 提取关键词
     */
    private List<String> extractKeywords(String query) {
        String prompt = """
                从以下查询中提取3-5个最重要的关键词，用于关键词检索。
                每个关键词单独一行，不要编号。

                查询：%s
                关键词：""".formatted(query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String keywordsText = response.content().text().trim();
            List<String> keywords = new ArrayList<>();

            for (String line : keywordsText.split("\\n")) {
                String keyword = line.trim();
                if (!keyword.isEmpty() && keyword.length() <= 20) {
                    keywords.add(keyword);
                }
            }

            return keywords;

        } catch (Exception e) {
            log.warn("关键词提取失败", e);
            return List.of();
        }
    }

    private QueryIntent.IntentType parseIntentType(String text) {
        try {
            return QueryIntent.IntentType.valueOf(text.replaceAll("[^A-Z_]", ""));
        } catch (IllegalArgumentException e) {
            return QueryIntent.IntentType.INFORMATIONAL;
        }
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class QueryRewriteResult {
        private String originalQuery;
        private String rewrittenQuery;
        private QueryIntent intent;
        private List<String> keywords;
    }

    @Data
    @Builder
    public static class QueryIntent {
        private IntentType type;
        private double confidence;

        public enum IntentType {
            FACTUAL,        // 事实性查询
            DEFINITION,     // 定义查询
            HOW_TO,         // 操作/方法查询
            COMPARISON,     // 比较查询
            LIST,           // 列表查询
            INFORMATIONAL   // 信息性查询
        }
    }
}
