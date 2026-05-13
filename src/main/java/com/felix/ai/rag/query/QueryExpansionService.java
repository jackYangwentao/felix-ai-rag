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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询扩展服务
 * 预检索优化技术：使用LLM生成多个查询变体，提高召回率
 *
 * 参考 Datawhale All-In-RAG 查询优化技术
 */
@Service
@Slf4j
public class QueryExpansionService {

    private final ChatLanguageModel chatLanguageModel;

    @Value("${rag.query-expansion.enabled:true}")
    private boolean enabled;

    @Value("${rag.query-expansion.variations:3}")
    private int variationCount;

    @Value("${rag.query-expansion.hyde.enabled:false}")
    private boolean hydeEnabled;

    // 查询扩展提示词模板
    private static final String EXPANSION_PROMPT_TEMPLATE = """
            你是一个搜索优化专家。请基于用户的问题，生成 {count} 个不同的搜索查询变体。
            这些变体应该从不同角度表达相同的问题，以提高搜索结果的相关性。

            用户问题：{query}

            要求：
            1. 每个查询变体应该是完整的问句或搜索词
            2. 变体之间应该有明显的差异（如：使用同义词、改变句式、补充细节等）
            3. 不要生成与原问题完全相同的内容
            4. 每个变体单独一行，不要有编号

            生成的查询变体：""";

    // HyDE提示词模板
    private static final String HYDE_PROMPT_TEMPLATE = """
            请根据以下用户问题，编写一段可能包含答案的文档片段。
            这段文档应该是从某个知识库中检索到的理想内容。

            用户问题：{query}

            请直接输出文档片段内容，不需要解释：""";

    public QueryExpansionService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 扩展查询 - 生成多个查询变体
     *
     * @param originalQuery 原始查询
     * @return 扩展后的查询列表（包含原始查询）
     */
    public List<String> expandQuery(String originalQuery) {
        if (!enabled) {
            log.debug("查询扩展已禁用，返回原始查询");
            return List.of(originalQuery);
        }

        log.info("扩展查询: '{}'", originalQuery);
        long startTime = System.currentTimeMillis();

        try {
            String prompt = EXPANSION_PROMPT_TEMPLATE
                    .replace("{query}", originalQuery)
                    .replace("{count}", String.valueOf(variationCount));

            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String generated = response.content().text().trim();

            // 解析生成的查询变体
            List<String> variations = parseVariations(generated);

            // 添加原始查询到列表开头
            List<String> expandedQueries = new ArrayList<>();
            expandedQueries.add(originalQuery);

            // 过滤掉与原始查询过于相似的变体
            for (String variation : variations) {
                if (!isSimilar(variation, originalQuery) && !expandedQueries.contains(variation)) {
                    expandedQueries.add(variation);
                }
                if (expandedQueries.size() >= variationCount + 1) {
                    break;
                }
            }

            log.info("查询扩展完成，生成 {} 个变体，耗时 {}ms",
                    expandedQueries.size() - 1, System.currentTimeMillis() - startTime);

            return expandedQueries;

        } catch (Exception e) {
            log.error("查询扩展失败，返回原始查询", e);
            return List.of(originalQuery);
        }
    }

    /**
     * HyDE (Hypothetical Document Embeddings)
     * 生成假设文档，然后用假设文档进行检索
     *
     * @param query 用户查询
     * @return 生成的假设文档内容
     */
    public String generateHypotheticalDocument(String query) {
        if (!hydeEnabled) {
            return null;
        }

        log.info("生成HyDE假设文档: '{}'", query);
        long startTime = System.currentTimeMillis();

        try {
            String prompt = HYDE_PROMPT_TEMPLATE.replace("{query}", query);

            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String hypotheticalDoc = response.content().text().trim();

            log.info("HyDE文档生成完成，长度 {}，耗时 {}ms",
                    hypotheticalDoc.length(), System.currentTimeMillis() - startTime);

            return hypotheticalDoc;

        } catch (Exception e) {
            log.error("HyDE文档生成失败", e);
            return null;
        }
    }

    /**
     * 改写查询 - 优化查询表述
     *
     * @param query 原始查询
     * @return 改写后的查询
     */
    public String rewriteQuery(String query) {
        log.debug("改写查询: '{}'", query);

        String prompt = """
                请优化以下搜索查询，使其更清晰、更具体、更适合语义搜索。
                保持原意不变，但改进表述方式。

                原始查询：{query}

                优化后的查询：""".replace("{query}", query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String rewritten = response.content().text().trim();

            // 如果改写结果太长，返回原始查询
            if (rewritten.length() > query.length() * 2) {
                return query;
            }

            return rewritten;

        } catch (Exception e) {
            log.warn("查询改写失败，返回原始查询", e);
            return query;
        }
    }

    /**
     * 解析生成的查询变体
     */
    private List<String> parseVariations(String generated) {
        List<String> variations = new ArrayList<>();

        // 按行分割
        String[] lines = generated.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            // 移除行首的数字编号（如 "1.", "1)" 等）
            trimmed = trimmed.replaceAll("^\\d+[.\\)\\-\\s]+", "").trim();

            if (!trimmed.isEmpty() && trimmed.length() > 5) {
                variations.add(trimmed);
            }
        }

        return variations;
    }

    /**
     * 检查两个查询是否相似（简单的词重叠度计算）
     */
    private boolean isSimilar(String q1, String q2) {
        String[] words1 = q1.toLowerCase().split("\\s+");
        String[] words2 = q2.toLowerCase().split("\\s+");

        java.util.Set<String> set1 = new java.util.HashSet<>(Arrays.asList(words1));
        java.util.Set<String> set2 = new java.util.HashSet<>(Arrays.asList(words2));

        // 计算Jaccard相似度
        java.util.Set<String> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);

        java.util.Set<String> union = new java.util.HashSet<>(set1);
        union.addAll(set2);

        double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

        return jaccard > 0.8;  // 相似度超过80%认为是相似的
    }

    /**
     * 批量扩展多个查询
     */
    public List<ExpandedQuery> expandQueries(List<String> queries) {
        return queries.stream()
                .map(q -> ExpandedQuery.builder()
                        .originalQuery(q)
                        .expandedQueries(expandQuery(q))
                        .build())
                .collect(Collectors.toList());
    }

    // ==================== 内部类 ====================

    @Data
    @Builder
    public static class ExpandedQuery {
        private String originalQuery;
        private List<String> expandedQueries;
        private String hypotheticalDocument;
    }
}
