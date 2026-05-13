package com.felix.ai.rag.query;

import com.felix.ai.rag.filter.MetadataFilter;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-Query检索器
 * 参考 Datawhale All-In-RAG 查询构建章节
 *
 * 核心功能：将自然语言查询自动解析为
 * 1. 语义查询字符串（用于向量检索）
 * 2. 元数据过滤条件（用于结构化过滤）
 *
 * 示例：
 * 输入: "2023年发布的关于机器学习的论文"
 * 输出: 查询="机器学习 论文" + 过滤器={year: "2023", type: "论文"}
 */
@Component
@Slf4j
public class SelfQueryRetriever {

    private final ChatLanguageModel chatLanguageModel;

    // 支持的元数据字段定义
    private final List<MetadataField> metadataFields;

    public SelfQueryRetriever(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
        this.metadataFields = initializeDefaultFields();
    }

    public SelfQueryRetriever(ChatLanguageModel chatLanguageModel,
                               List<MetadataField> customFields) {
        this.chatLanguageModel = chatLanguageModel;
        this.metadataFields = customFields != null ? customFields : initializeDefaultFields();
    }

    /**
     * 解析自然语言查询
     */
    public SelfQueryResult parse(String naturalLanguageQuery) {
        log.info("Self-Query解析: '{}'", naturalLanguageQuery);
        long startTime = System.currentTimeMillis();

        try {
            // 使用LLM解析查询
            String parseResult = llmParse(naturalLanguageQuery);

            // 解析LLM输出
            SelfQueryResult result = extractQueryAndFilters(parseResult);
            result.setOriginalQuery(naturalLanguageQuery);

            log.info("Self-Query解析完成，语义查询: '{}', 过滤器数量: {}, 耗时 {}ms",
                    result.getSemanticQuery(),
                    result.getFilter() != null ? result.getFilter().getConditions().size() : 0,
                    System.currentTimeMillis() - startTime);

            return result;

        } catch (Exception e) {
            log.error("Self-Query解析失败，返回原始查询", e);
            return SelfQueryResult.builder()
                    .originalQuery(naturalLanguageQuery)
                    .semanticQuery(naturalLanguageQuery)
                    .filter(new MetadataFilter())
                    .build();
        }
    }

    /**
     * 使用LLM解析查询
     */
    private String llmParse(String query) {
        String prompt = buildParsePrompt(query);

        Response<dev.langchain4j.data.message.AiMessage> response =
                chatLanguageModel.generate(UserMessage.from(prompt));

        return response.content().text().trim();
    }

    /**
     * 构建解析提示词
     */
    private String buildParsePrompt(String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个查询解析专家。请将用户的自然语言查询解析为两部分：\n\n");
        sb.append("1. 语义查询（用于语义检索）\n");
        sb.append("2. 元数据过滤条件（用于结构化过滤）\n\n");

        sb.append("可用的元数据字段：\n");
        for (MetadataField field : metadataFields) {
            sb.append(String.format("- %s: %s (类型: %s, 示例值: %s)\n",
                    field.getName(),
                    field.getDescription(),
                    field.getType(),
                    field.getExampleValues()));
        }

        sb.append("\n输出格式（严格遵循）：\n");
        sb.append("语义查询: <提取的核心查询内容>\n");
        sb.append("过滤条件:\n");
        sb.append("- 字段名1: 操作符=值\n");
        sb.append("- 字段名2: 操作符=值\n");
        sb.append("\n支持的操作符: equals, contains, gt(大于), lt(小于), gte, lte\n");
        sb.append("如果没有过滤条件，写无\n\n");

        sb.append("示例:\n");
        sb.append("用户查询: 2023年张三写的关于人工智能的论文\n");
        sb.append("语义查询: 人工智能 论文\n");
        sb.append("过滤条件:\n");
        sb.append("- year: equals=2023\n");
        sb.append("- author: equals=张三\n\n");

        sb.append("用户查询: ").append(query).append("\n");
        sb.append("解析结果:\n");

        return sb.toString();
    }

    /**
     * 从LLM输出中提取查询和过滤器
     */
    private SelfQueryResult extractQueryAndFilters(String llmOutput) {
        String semanticQuery = extractSemanticQuery(llmOutput);
        MetadataFilter filter = extractFilters(llmOutput);

        return SelfQueryResult.builder()
                .semanticQuery(semanticQuery)
                .filter(filter)
                .build();
    }

    /**
     * 提取语义查询
     */
    private String extractSemanticQuery(String text) {
        Pattern pattern = Pattern.compile("语义查询[:：]\\s*(.+?)(?:\\n|过滤条件|$)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String query = matcher.group(1).trim();
            // 移除可能的"无"或"none"
            if (query.equalsIgnoreCase("无") || query.equalsIgnoreCase("none")) {
                return "";
            }
            return query;
        }

        return "";
    }

    /**
     * 提取过滤条件
     */
    private MetadataFilter extractFilters(String text) {
        MetadataFilter filter = new MetadataFilter();
        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();

        // 查找过滤条件部分
        Pattern sectionPattern = Pattern.compile("过滤条件[:：](.+?)(?:\\n\\n|$)", Pattern.DOTALL);
        Matcher sectionMatcher = sectionPattern.matcher(text);

        String filterSection = "";
        if (sectionMatcher.find()) {
            filterSection = sectionMatcher.group(1).trim();
        }

        if (filterSection.isEmpty() ||
            filterSection.equalsIgnoreCase("无") ||
            filterSection.equalsIgnoreCase("none")) {
            return filter;
        }

        // 解析每个过滤条件
        Pattern conditionPattern = Pattern.compile("-?\\s*(\\w+)[:：]\\s*(\\w+)=(.+?)(?:\\n|$)");
        Matcher conditionMatcher = conditionPattern.matcher(filterSection);

        while (conditionMatcher.find()) {
            String fieldName = conditionMatcher.group(1).trim();
            String operatorStr = conditionMatcher.group(2).trim().toLowerCase();
            String value = conditionMatcher.group(3).trim();

            MetadataFilter.Operator operator = parseOperator(operatorStr);

            if (operator != null && !value.isEmpty()) {
                conditions.add(MetadataFilter.FilterCondition.builder()
                        .key(fieldName)
                        .operator(operator)
                        .value(value)
                        .build());
            }
        }

        filter.setConditions(conditions);
        filter.setOperator(MetadataFilter.LogicalOperator.AND);

        return filter;
    }

    private MetadataFilter.Operator parseOperator(String op) {
        return switch (op) {
            case "equals", "eq", "=" -> MetadataFilter.Operator.EQUALS;
            case "contains" -> MetadataFilter.Operator.CONTAINS;
            case "gt", ">" -> MetadataFilter.Operator.GREATER_THAN;
            case "lt", "<" -> MetadataFilter.Operator.LESS_THAN;
            default -> MetadataFilter.Operator.EQUALS;
        };
    }

    /**
     * 初始化默认元数据字段
     */
    private List<MetadataField> initializeDefaultFields() {
        return List.of(
                MetadataField.builder()
                        .name("year")
                        .description("文档发布年份")
                        .type("string")
                        .exampleValues("2023, 2022, 2021")
                        .build(),
                MetadataField.builder()
                        .name("quarter")
                        .description("季度")
                        .type("string")
                        .exampleValues("Q1, Q2, Q3, Q4")
                        .build(),
                MetadataField.builder()
                        .name("author")
                        .description("文档作者")
                        .type("string")
                        .exampleValues("张三, 李四")
                        .build(),
                MetadataField.builder()
                        .name("category")
                        .description("业务分类")
                        .type("string")
                        .exampleValues("技术, 产品, 运营")
                        .build(),
                MetadataField.builder()
                        .name("documentType")
                        .description("文档类型")
                        .type("string")
                        .exampleValues("论文, 报告, 手册")
                        .build(),
                MetadataField.builder()
                        .name("department")
                        .description("部门")
                        .type("string")
                        .exampleValues("研发部, 市场部")
                        .build()
        );
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class SelfQueryResult {
        private String originalQuery;
        private String semanticQuery;
        private MetadataFilter filter;
    }

    @Data
    @Builder
    public static class MetadataField {
        private String name;
        private String description;
        private String type;
        private String exampleValues;
    }
}
