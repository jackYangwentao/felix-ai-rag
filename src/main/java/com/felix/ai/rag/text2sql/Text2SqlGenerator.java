package com.felix.ai.rag.text2sql;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Text2SQL 生成器
 * 参考 Datawhale All-In-RAG Text2SQL章节
 *
 * 核心功能：
 * 1. 根据知识库上下文生成SQL
 * 2. 错误修复机制
 * 3. SQL验证和格式化
 */
@Component
@Slf4j
public class Text2SqlGenerator {

    private final ChatLanguageModel chatLanguageModel;

    @Value("${rag.text2sql.temperature:0.0}")
    private double temperature;

    public Text2SqlGenerator(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    /**
     * 生成SQL
     */
    public SqlGenerationResult generateSql(String userQuestion, String context) {
        log.info("生成SQL: '{}'", userQuestion);
        long startTime = System.currentTimeMillis();

        String prompt = buildGenerationPrompt(userQuestion, context);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String sql = extractSql(response.content().text());

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("SQL生成完成，耗时 {}ms", processingTime);

            return SqlGenerationResult.builder()
                    .success(true)
                    .sql(sql)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (Exception e) {
            log.error("SQL生成失败", e);
            return SqlGenerationResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 修复SQL错误
     */
    public SqlGenerationResult fixSql(String originalSql, String errorMessage,
                                       String context, String userQuestion) {
        log.info("修复SQL错误: {}，错误: {}", originalSql, errorMessage);
        long startTime = System.currentTimeMillis();

        String prompt = buildFixPrompt(originalSql, errorMessage, context, userQuestion);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));

            String fixedSql = extractSql(response.content().text());

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("SQL修复完成，耗时 {}ms", processingTime);

            return SqlGenerationResult.builder()
                    .success(true)
                    .sql(fixedSql)
                    .fixed(true)
                    .originalSql(originalSql)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (Exception e) {
            log.error("SQL修复失败", e);
            return SqlGenerationResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .originalSql(originalSql)
                    .build();
        }
    }

    /**
     * 解释SQL（用于可解释性）
     */
    public String explainSql(String sql, String context) {
        String prompt = """
                请用通俗易懂的语言解释以下SQL查询的作用。
                说明这个查询在做什么，涉及哪些表，以及查询条件是什么。

                SQL语句：
                %s

                数据库信息：
                %s

                请用中文解释：""".formatted(sql, context);

        try {
            Response<dev.langchain4j.data.message.AiMessage> response =
                    chatLanguageModel.generate(UserMessage.from(prompt));
            return response.content().text().trim();
        } catch (Exception e) {
            log.warn("SQL解释失败", e);
            return "无法解释该SQL语句";
        }
    }

    /**
     * 构建生成提示词
     */
    private String buildGenerationPrompt(String userQuestion, String context) {
        return """
                你是一个SQL专家。请根据以下数据库信息，将用户问题转换为SQL查询语句。

                %s

                用户问题：%s

                要求：
                1. 只返回SQL语句，不要包含任何解释或markdown标记
                2. 确保SQL语法正确，使用标准SQL
                3. 使用上下文中提供的表名和字段名
                4. 如果需要JOIN，请根据表结构进行合理关联
                5. 对于聚合查询，确保GROUP BY包含所有非聚合字段
                6. 日期字段使用标准日期函数处理
                7. 字符串比较注意大小写敏感性

                SQL语句：""".formatted(context, userQuestion);
    }

    /**
     * 构建修复提示词
     */
    private String buildFixPrompt(String originalSql, String errorMessage,
                                   String context, String userQuestion) {
        return """
                请修复以下SQL语句的错误。

                数据库信息：
                %s

                用户问题：%s

                原始SQL：
                %s

                错误信息：
                %s

                修复要求：
                1. 分析错误原因并修复
                2. 只返回修复后的SQL语句，不要解释
                3. 确保修复后的SQL符合数据库结构
                4. 如果涉及不存在的表或字段，请根据上下文选择正确的名称

                修复后的SQL语句：""".formatted(context, userQuestion, originalSql, errorMessage);
    }

    /**
     * 提取SQL语句
     */
    private String extractSql(String text) {
        text = text.trim();

        // 移除markdown代码块标记
        if (text.startsWith("```sql")) {
            text = text.substring(6);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }

        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }

        // 移除可能的"SQL:"前缀
        text = text.replaceAll("^(?i)sql\\s*[:：]\\s*", "");

        return text.trim();
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class SqlGenerationResult {
        private boolean success;
        private String sql;
        private String errorMessage;
        private boolean fixed;
        private String originalSql;
        private long processingTimeMs;
    }
}
