package com.felix.ai.rag.rag;

import com.felix.ai.rag.query.AdvancedQueryRewriteService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 校正检索服务 (Corrective RAG / C-RAG)
 * 参考 Datawhale All-In-RAG 高级检索技术章节
 *
 * 核心流程："检索-评估-行动"自我反思机制
 *
 * 1. 检索：从知识库获取文档
 * 2. 评估：判断文档相关性（CORRECT/INCORRECT/AMBIGUOUS）
 * 3. 行动：
 *    - CORRECT → 知识精炼（分解为知识片段，过滤重组）
 *    - INCORRECT → 知识搜索（查询重写 + Web搜索）
 *    - AMBIGUOUS → 知识搜索（直接使用原始查询进行Web搜索）
 */
@Service
@Slf4j
public class CorrectiveRagService {

    private final ChatLanguageModel chatLanguageModel;
    private final AdvancedQueryRewriteService queryRewriteService;

    @Value("${rag.crag.max-iterations:3}")
    private int maxIterations;

    @Value("${rag.crag.web-search-enabled:false}")
    private boolean webSearchEnabled;

    public CorrectiveRagService(ChatLanguageModel chatLanguageModel,
                                 AdvancedQueryRewriteService queryRewriteService) {
        this.chatLanguageModel = chatLanguageModel;
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * 执行校正检索
     */
    public CragResult retrieve(String query, List<Content> retrievedDocuments) {
        log.info("C-RAG校正检索: '{}'", query);
        long startTime = System.currentTimeMillis();

        CragResult.CragResultBuilder resultBuilder = CragResult.builder()
                .originalQuery(query);

        // 第1步：评估检索结果
        RetrievalAssessment assessment = assessRetrieval(query, retrievedDocuments);
        resultBuilder.assessment(assessment);

        log.info("C-RAG评估结果: {}", assessment.getGrade());

        // 第2步：根据评估结果采取行动
        List<Content> finalContents = new ArrayList<>();
        String actionTaken;

        switch (assessment.getGrade()) {
            case CORRECT -> {
                // 知识精炼
                actionTaken = "知识精炼";
                finalContents = knowledgeRefinement(query, retrievedDocuments);
            }
            case INCORRECT -> {
                // 知识搜索（查询重写 + Web搜索）
                actionTaken = "知识搜索（查询重写）";
                finalContents = knowledgeSearchWithRewrite(query);
            }
            case AMBIGUOUS -> {
                // 知识搜索（直接使用原始查询）
                actionTaken = "知识搜索（原始查询）";
                finalContents = knowledgeSearch(query);
            }
            default -> {
                actionTaken = "默认处理";
                finalContents = retrievedDocuments;
            }
        }

        resultBuilder
                .actionTaken(actionTaken)
                .finalContents(finalContents)
                .processingTimeMs(System.currentTimeMillis() - startTime);

        return resultBuilder.build();
    }

    /**
     * 评估检索结果
     * 返回：CORRECT(相关)/INCORRECT(不相关)/AMBIGUOUS(模糊)
     */
    private RetrievalAssessment assessRetrieval(String query, List<Content> documents) {
        if (documents == null || documents.isEmpty()) {
            return RetrievalAssessment.builder()
                    .grade(Grade.INCORRECT)
                    .reason("未检索到任何文档")
                    .build();
        }

        // 构建评估提示
        StringBuilder docBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(documents.size(), 3); i++) {
            docBuilder.append("文档").append(i + 1).append(": ")
                    .append(documents.get(i).textSegment().text().substring(
                            0, Math.min(300, documents.get(i).textSegment().text().length())))
                    .append("\n\n");
        }

        String prompt = """
                请评估以下检索到的文档对于回答用户查询的相关性。

                用户查询: %s

                检索到的文档:
                %s

                请从以下三个等级中选择：
                - CORRECT: 文档内容相关且足以回答查询
                - INCORRECT: 文档内容完全不相关或无法回答查询
                - AMBIGUOUS: 文档内容部分相关，但信息不完整或存在歧义

                只回答等级（CORRECT/INCORRECT/AMBIGUOUS），然后简要说明原因。
                格式：等级|原因

                评估结果:""".formatted(query, docBuilder.toString());

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String result = response.content().text().trim();
            String[] parts = result.split("\\|", 2);

            Grade grade = Grade.AMBIGUOUS;
            String reason = "";

            if (parts.length > 0) {
                String gradeStr = parts[0].trim().toUpperCase();
                try {
                    grade = Grade.valueOf(gradeStr);
                } catch (IllegalArgumentException e) {
                    // 尝试从文本中推断
                    if (gradeStr.contains("CORRECT") && !gradeStr.contains("INCORRECT")) {
                        grade = Grade.CORRECT;
                    } else if (gradeStr.contains("INCORRECT") || gradeStr.contains("不相关")) {
                        grade = Grade.INCORRECT;
                    }
                }
            }

            if (parts.length > 1) {
                reason = parts[1].trim();
            }

            return RetrievalAssessment.builder()
                    .grade(grade)
                    .reason(reason)
                    .build();

        } catch (Exception e) {
            log.error("评估检索结果失败", e);
            return RetrievalAssessment.builder()
                    .grade(Grade.AMBIGUOUS)
                    .reason("评估过程出错: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 知识精炼 - 分解为知识片段，过滤重组
     */
    private List<Content> knowledgeRefinement(String query, List<Content> documents) {
        log.info("C-RAG: 执行知识精炼");

        List<Content> refinedContents = new ArrayList<>();

        for (Content doc : documents) {
            String text = doc.textSegment().text();

            // 提取关键知识片段
            String refined = extractKnowledgeSnippets(query, text);

            if (!refined.isEmpty()) {
                refinedContents.add(Content.from(
                        dev.langchain4j.data.segment.TextSegment.from(refined)));
            }
        }

        return refinedContents.isEmpty() ? documents : refinedContents;
    }

    /**
     * 提取知识片段
     */
    private String extractKnowledgeSnippets(String query, String document) {
        String prompt = """
                请将以下文档分解为知识片段，每个片段应是一个独立的事实或信息点。
                只保留与查询相关的知识片段，去除无关内容。
                每个片段单独一行，以"- "开头。

                查询: %s

                文档: %s

                知识片段:""".formatted(query, truncate(document, 1500));

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            return response.content().text().trim();

        } catch (Exception e) {
            log.warn("知识片段提取失败", e);
            return document;
        }
    }

    /**
     * 知识搜索（带查询重写）
     */
    private List<Content> knowledgeSearchWithRewrite(String query) {
        log.info("C-RAG: 执行知识搜索（查询重写）");

        if (!webSearchEnabled) {
            log.warn("Web搜索未启用，返回空结果");
            return List.of();
        }

        // 使用查询重写服务优化查询
        AdvancedQueryRewriteService.ComprehensiveRewriteResult rewriteResult =
                queryRewriteService.comprehensiveRewrite(query);

        String searchQuery = rewriteResult.getMainQuery();

        // 这里应该调用Web搜索API
        // 简化实现：返回空列表
        log.info("C-RAG: 使用重写后的查询进行搜索: '{}'", searchQuery);

        return performWebSearch(searchQuery);
    }

    /**
     * 知识搜索（原始查询）
     */
    private List<Content> knowledgeSearch(String query) {
        log.info("C-RAG: 执行知识搜索（原始查询）");

        if (!webSearchEnabled) {
            log.warn("Web搜索未启用，返回空结果");
            return List.of();
        }

        return performWebSearch(query);
    }

    /**
     * 执行Web搜索（占位实现）
     */
    private List<Content> performWebSearch(String query) {
        // TODO: 集成实际的Web搜索API（如SerpAPI、Bing Search等）
        log.info("C-RAG: 执行Web搜索: '{}'", query);

        // 返回空列表作为占位
        return List.of();
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class CragResult {
        private String originalQuery;
        private RetrievalAssessment assessment;
        private String actionTaken;
        private List<Content> finalContents;
        private long processingTimeMs;
    }

    @Data
    @Builder
    public static class RetrievalAssessment {
        private Grade grade;
        private String reason;
    }

    public enum Grade {
        CORRECT,      // 相关且足以回答
        INCORRECT,    // 不相关或无法回答
        AMBIGUOUS     // 部分相关，信息不完整
    }
}
