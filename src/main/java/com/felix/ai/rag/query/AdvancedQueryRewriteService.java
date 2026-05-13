package com.felix.ai.rag.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 高级查询重写服务
 * 参考 Datawhale All-In-RAG 查询重写章节
 *
 * 实现4种查询重写技术：
 * 1. 提示工程（Prompt Engineering）- 处理排序/比较查询
 * 2. 多查询分解（Multi-Query）- 将复杂问题拆分为子问题
 * 3. 退步提示（Step-Back Prompting）- 先抽象原理再具体推理
 * 4. HyDE（Hypothetical Document Embeddings）- 假设文档嵌入
 */
@Service
@Slf4j
public class AdvancedQueryRewriteService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    @Value("${rag.query-rewrite.multi-query.enabled:true}")
    private boolean multiQueryEnabled;

    @Value("${rag.query-rewrite.step-back.enabled:true}")
    private boolean stepBackEnabled;

    @Value("${rag.query-rewrite.hyde.enabled:false}")
    private boolean hydeEnabled;

    @Value("${rag.query-rewrite.multi-query.variations:3}")
    private int multiQueryVariations;

    public AdvancedQueryRewriteService(ChatLanguageModel chatLanguageModel,
                                        EmbeddingModel embeddingModel) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 1. 提示工程（Prompt Engineering）====================

    /**
     * 分析查询是否为排序/比较类查询，生成结构化指令
     * 适用于：最值查询（时间最短、播放量最高等）
     */
    public StructuredInstruction analyzeStructuredQuery(String query) {
        log.info("分析结构化查询: '{}'", query);

        String prompt = """
                你是一个智能查询分析助手。请分析用户的查询，识别是否为排序或比较类查询。

                如果是排序/比较查询，请输出JSON格式的指令：
                {
                  "isStructured": true,
                  "sortBy": "字段名",
                  "order": "asc或desc",
                  "limit": 数字
                }

                支持的排序字段：
                - "date" - 日期/时间
                - "view_count" - 浏览量/播放量
                - "length" - 长度/时长
                - "score" - 评分/分数
                - "price" - 价格

                如果不是排序/比较查询，输出：
                {"isStructured": false}

                示例：
                - "时间最短的视频" → {"isStructured": true, "sortBy": "length", "order": "asc"}
                - "播放量最高的文章" → {"isStructured": true, "sortBy": "view_count", "order": "desc"}
                - "2023年最新的报告" → {"isStructured": true, "sortBy": "date", "order": "desc"}
                - "什么是RAG" → {"isStructured": false}

                用户查询: %s

                JSON输出:""".formatted(query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String jsonText = response.content().text().trim();
            // 提取JSON部分
            jsonText = extractJson(jsonText);

            JsonNode jsonNode = objectMapper.readTree(jsonText);

            boolean isStructured = jsonNode.has("isStructured") &&
                    jsonNode.get("isStructured").asBoolean();

            if (isStructured) {
                return StructuredInstruction.builder()
                        .isStructured(true)
                        .sortBy(jsonNode.has("sortBy") ? jsonNode.get("sortBy").asText() : null)
                        .order(jsonNode.has("order") ? jsonNode.get("order").asText() : "desc")
                        .limit(jsonNode.has("limit") ? jsonNode.get("limit").asInt() : 1)
                        .build();
            } else {
                return StructuredInstruction.builder()
                        .isStructured(false)
                        .build();
            }

        } catch (Exception e) {
            log.warn("结构化查询分析失败", e);
            return StructuredInstruction.builder()
                    .isStructured(false)
                    .build();
        }
    }

    // ==================== 2. 多查询分解（Multi-Query）====================

    /**
     * 将复杂查询分解为多个子查询
     * 适用于：多主题、复杂条件的问题
     */
    public MultiQueryResult decomposeQuery(String query) {
        if (!multiQueryEnabled) {
            return MultiQueryResult.builder()
                    .originalQuery(query)
                    .subQueries(List.of(query))
                    .build();
        }

        log.info("多查询分解: '{}'", query);
        long startTime = System.currentTimeMillis();

        String prompt = """
                你是一个查询分解专家。请将用户的复杂查询分解为2-4个更简单的子查询，
                每个子查询从不同的角度或子主题来探索原问题。

                要求：
                1. 每个子查询应该独立、具体、可检索
                2. 子查询之间应该有互补性，覆盖原问题的不同方面
                3. 不要分解过于简单的问题
                4. 每个子查询单独一行，以"- "开头

                示例：
                原问题: "在《流浪地球》中，刘慈欣对人工智能和未来社会结构有何看法？"
                子查询:
                - 《流浪地球》中描述的人工智能技术有哪些？
                - 《流浪地球》中描绘的未来社会结构是怎样的？
                - 刘慈欣关于人工智能的观点是什么？

                原问题: %s
                子查询:""".formatted(query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String result = response.content().text().trim();
            List<String> subQueries = new ArrayList<>();

            for (String line : result.split("\\n")) {
                line = line.trim();
                if (line.startsWith("-") || line.startsWith("•")) {
                    String subQuery = line.substring(1).trim();
                    if (!subQuery.isEmpty() && !subQuery.equals(query)) {
                        subQueries.add(subQuery);
                    }
                }
            }

            // 如果没有分解成功，返回原查询
            if (subQueries.isEmpty()) {
                subQueries.add(query);
            }

            log.info("多查询分解完成，生成 {} 个子查询，耗时 {}ms",
                    subQueries.size(), System.currentTimeMillis() - startTime);

            return MultiQueryResult.builder()
                    .originalQuery(query)
                    .subQueries(subQueries)
                    .build();

        } catch (Exception e) {
            log.error("多查询分解失败", e);
            return MultiQueryResult.builder()
                    .originalQuery(query)
                    .subQueries(List.of(query))
                    .build();
        }
    }

    // ==================== 3. 退步提示（Step-Back Prompting）====================

    /**
     * 退步提示：先抽象原理，再具体推理
     * 适用于：需要复杂推理的科学/数学问题
     */
    public StepBackResult stepBackPrompt(String query) {
        if (!stepBackEnabled) {
            return StepBackResult.builder()
                    .originalQuery(query)
                    .stepBackQuestion(null)
                    .build();
        }

        log.info("退步提示处理: '{}'", query);
        long startTime = System.currentTimeMillis();

        // 第一步：生成退步问题（更抽象的通用原理问题）
        String stepBackPrompt = """
                面对以下具体问题，请"退一步"思考，提出一个更通用、更抽象的原理性问题。
                这个问题应该涉及该问题背后的通用概念、原理或理论。

                示例1：
                具体问题: "在一个密闭容器中，加热气体后压力会如何变化？"
                退步问题: "气体状态变化遵循什么物理定律？"

                示例2：
                具体问题: "为什么Transformer架构比RNN更适合长文本？"
                退步问题: "神经网络处理序列数据时面临什么根本挑战？"

                具体问题: %s
                退步问题:""".formatted(query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> stepBackResponse =
                    chatLanguageModel.generate(UserMessage.from(stepBackPrompt));

            String stepBackQuestion = stepBackResponse.content().text().trim();

            // 如果退步问题与原问题过于相似，则跳过
            if (stepBackQuestion.equals(query) ||
                stepBackQuestion.contains(query.substring(0, Math.min(10, query.length())))) {
                return StepBackResult.builder()
                        .originalQuery(query)
                        .stepBackQuestion(null)
                        .build();
            }

            log.info("退步提示完成，退步问题: '{}'，耗时 {}ms",
                    stepBackQuestion, System.currentTimeMillis() - startTime);

            return StepBackResult.builder()
                    .originalQuery(query)
                    .stepBackQuestion(stepBackQuestion)
                    .build();

        } catch (Exception e) {
            log.warn("退步提示失败", e);
            return StepBackResult.builder()
                    .originalQuery(query)
                    .stepBackQuestion(null)
                    .build();
        }
    }

    // ==================== 4. HyDE（Hypothetical Document Embeddings）====================

    /**
     * HyDE：生成假设性答案文档，用于向量检索
     * 适用于：查询短、文档长，语义鸿沟大的场景
     */
    public HydeResult generateHypotheticalDocument(String query) {
        if (!hydeEnabled) {
            return HydeResult.builder()
                    .originalQuery(query)
                    .hypotheticalDocument(null)
                    .build();
        }

        log.info("HyDE生成假设文档: '{}'", query);
        long startTime = System.currentTimeMillis();

        String prompt = """
                请根据以下查询，生成一段假设性的理想答案文档。
                这段文档应该详细、全面地回答查询，即使你不确定具体内容，也请基于常识生成合理的答案。

                要求：
                1. 文档应该是一段连贯的文本，100-300字
                2. 包含查询涉及的关键概念和信息
                3. 语言风格应该与真实文档相似
                4. 不需要完全准确，重点是语义相关性

                查询: %s

                假设性答案文档:""".formatted(query);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String hypotheticalDoc = response.content().text().trim();

            // 生成假设文档的嵌入向量
            Embedding embedding = embeddingModel.embed(hypotheticalDoc).content();

            log.info("HyDE生成完成，文档长度: {}，耗时 {}ms",
                    hypotheticalDoc.length(), System.currentTimeMillis() - startTime);

            return HydeResult.builder()
                    .originalQuery(query)
                    .hypotheticalDocument(hypotheticalDoc)
                    .hypotheticalEmbedding(embedding)
                    .build();

        } catch (Exception e) {
            log.error("HyDE生成失败", e);
            return HydeResult.builder()
                    .originalQuery(query)
                    .hypotheticalDocument(null)
                    .build();
        }
    }

    // ==================== 综合查询重写 ====================

    /**
     * 综合查询重写 - 应用所有适用的技术
     */
    public ComprehensiveRewriteResult comprehensiveRewrite(String query) {
        log.info("执行综合查询重写: '{}'", query);
        long startTime = System.currentTimeMillis();

        ComprehensiveRewriteResult.ComprehensiveRewriteResultBuilder resultBuilder =
                ComprehensiveRewriteResult.builder()
                        .originalQuery(query);

        // 1. 检查是否为结构化查询（排序/比较）
        StructuredInstruction structured = analyzeStructuredQuery(query);
        resultBuilder.structuredInstruction(structured);

        // 2. 多查询分解
        MultiQueryResult multiQuery = decomposeQuery(query);
        resultBuilder.multiQuery(multiQuery);

        // 3. 退步提示
        StepBackResult stepBack = stepBackPrompt(query);
        resultBuilder.stepBack(stepBack);

        // 4. HyDE（如果启用）
        if (hydeEnabled) {
            HydeResult hyde = generateHypotheticalDocument(query);
            resultBuilder.hyde(hyde);
        }

        // 确定主查询（用于检索）
        String mainQuery = query;
        if (stepBack.getStepBackQuestion() != null) {
            // 如果有退步问题，使用退步问题作为主要检索查询
            mainQuery = stepBack.getStepBackQuestion();
        }
        resultBuilder.mainQuery(mainQuery);

        resultBuilder.processingTimeMs(System.currentTimeMillis() - startTime);

        log.info("综合查询重写完成，主查询: '{}'，耗时 {}ms",
                mainQuery, System.currentTimeMillis() - startTime);

        return resultBuilder.build();
    }

    // ==================== 工具方法 ====================

    private String extractJson(String text) {
        // 提取JSON内容（处理可能的markdown代码块）
        text = text.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class StructuredInstruction {
        private boolean isStructured;
        private String sortBy;      // 排序字段
        private String order;       // asc 或 desc
        private int limit;          // 返回数量限制
    }

    @Data
    @Builder
    public static class MultiQueryResult {
        private String originalQuery;
        private List<String> subQueries;
    }

    @Data
    @Builder
    public static class StepBackResult {
        private String originalQuery;
        private String stepBackQuestion;  // 退步后的抽象问题
    }

    @Data
    @Builder
    public static class HydeResult {
        private String originalQuery;
        private String hypotheticalDocument;
        private Embedding hypotheticalEmbedding;
    }

    @Data
    @Builder
    public static class ComprehensiveRewriteResult {
        private String originalQuery;
        private String mainQuery;                    // 用于检索的主查询
        private StructuredInstruction structuredInstruction;
        private MultiQueryResult multiQuery;
        private StepBackResult stepBack;
        private HydeResult hyde;
        private long processingTimeMs;

        /**
         * 获取所有需要检索的查询（原查询 + 子查询）
         */
        public List<String> getAllQueriesForRetrieval() {
            List<String> queries = new ArrayList<>();

            // 主查询
            if (mainQuery != null && !mainQuery.isEmpty()) {
                queries.add(mainQuery);
            }

            // 子查询
            if (multiQuery != null && multiQuery.getSubQueries() != null) {
                for (String subQuery : multiQuery.getSubQueries()) {
                    if (!queries.contains(subQuery)) {
                        queries.add(subQuery);
                    }
                }
            }

            // 退步问题
            if (stepBack != null && stepBack.getStepBackQuestion() != null &&
                !queries.contains(stepBack.getStepBackQuestion())) {
                queries.add(stepBack.getStepBackQuestion());
            }

            return queries;
        }

        /**
         * 是否需要结构化处理（排序/过滤）
         */
        public boolean requiresStructuredProcessing() {
            return structuredInstruction != null && structuredInstruction.isStructured;
        }

        /**
         * 是否使用了HyDE
         */
        public boolean usedHyde() {
            return hyde != null && hyde.getHypotheticalDocument() != null;
        }
    }
}
