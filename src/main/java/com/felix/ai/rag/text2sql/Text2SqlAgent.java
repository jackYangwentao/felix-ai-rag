package com.felix.ai.rag.text2sql;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Text2SQL Agent
 * 参考 Datawhale All-In-RAG Text2SQL章节
 *
 * 核心功能：
 * 1. 协调知识库检索、SQL生成、SQL执行的完整流程
 * 2. 错误修复和重试机制
 * 3. 安全执行策略（LIMIT限制、只读查询等）
 */
@Component
@Slf4j
public class Text2SqlAgent {

    private final Text2SqlKnowledgeBase knowledgeBase;
    private final Text2SqlGenerator sqlGenerator;
    private final DataSource dataSource;

    @Value("${rag.text2sql.max-retry-count:3}")
    private int maxRetryCount;

    @Value("${rag.text2sql.top-k-retrieval:5}")
    private int topKRetrieval;

    @Value("${rag.text2sql.max-result-rows:100}")
    private int maxResultRows;

    @Value("${rag.text2sql.read-only:true}")
    private boolean readOnly;

    public Text2SqlAgent(Text2SqlKnowledgeBase knowledgeBase,
                         Text2SqlGenerator sqlGenerator,
                         DataSource dataSource) {
        this.knowledgeBase = knowledgeBase;
        this.sqlGenerator = sqlGenerator;
        this.dataSource = dataSource;
    }

    /**
     * 执行自然语言查询
     */
    public QueryResult query(String userQuestion) {
        log.info("Text2SQL查询: '{}'", userQuestion);
        long startTime = System.currentTimeMillis();

        // Step 1: 知识库检索
        List<Text2SqlKnowledgeBase.KnowledgeSearchResult> knowledgeResults =
                knowledgeBase.search(userQuestion, topKRetrieval);

        String context = knowledgeBase.buildContext(knowledgeResults);
        log.debug("构建的上下文:\n{}", context);

        // Step 2: 生成SQL
        Text2SqlGenerator.SqlGenerationResult generationResult =
                sqlGenerator.generateSql(userQuestion, context);

        if (!generationResult.isSuccess()) {
            return QueryResult.builder()
                    .success(false)
                    .errorMessage("SQL生成失败: " + generationResult.getErrorMessage())
                    .userQuestion(userQuestion)
                    .build();
        }

        String sql = generationResult.getSql();
        log.info("生成的SQL: {}", sql);

        // Step 3: 执行SQL（带重试机制）
        return executeWithRetry(sql, userQuestion, context, 0);
    }

    /**
     * 执行SQL（带重试机制）
     */
    private QueryResult executeWithRetry(String sql, String userQuestion,
                                          String context, int retryCount) {
        SqlExecutionResult executionResult = executeSql(sql);

        if (executionResult.isSuccess()) {
            // 执行成功
            String explanation = sqlGenerator.explainSql(sql, context);

            return QueryResult.builder()
                    .success(true)
                    .userQuestion(userQuestion)
                    .generatedSql(sql)
                    .explanation(explanation)
                    .columns(executionResult.getColumns())
                    .rows(executionResult.getRows())
                    .rowCount(executionResult.getRowCount())
                    .retryCount(retryCount)
                    .build();
        }

        // 执行失败，尝试修复
        if (retryCount < maxRetryCount) {
            log.info("SQL执行失败，尝试修复 (重试 {}/{}): {}",
                    retryCount + 1, maxRetryCount, executionResult.getErrorMessage());

            // 修复SQL
            Text2SqlGenerator.SqlGenerationResult fixResult =
                    sqlGenerator.fixSql(sql, executionResult.getErrorMessage(),
                            context, userQuestion);

            if (fixResult.isSuccess()) {
                String fixedSql = fixResult.getSql();
                log.info("修复后的SQL: {}", fixedSql);

                // 递归重试
                return executeWithRetry(fixedSql, userQuestion, context, retryCount + 1);
            }
        }

        // 重试次数用尽，返回错误
        return QueryResult.builder()
                .success(false)
                .userQuestion(userQuestion)
                .generatedSql(sql)
                .errorMessage("SQL执行失败（已重试" + retryCount + "次）: " +
                        executionResult.getErrorMessage())
                .retryCount(retryCount)
                .build();
    }

    /**
     * 执行SQL语句
     */
    private SqlExecutionResult executeSql(String sql) {
        log.debug("执行SQL: {}", sql);

        // 安全检查
        if (readOnly && !isReadOnlyQuery(sql)) {
            return SqlExecutionResult.builder()
                    .success(false)
                    .errorMessage("只读模式下不允许执行非查询语句")
                    .build();
        }

        // 自动添加LIMIT
        sql = addLimitIfNeeded(sql);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 设置超时
            stmt.setQueryTimeout(30);

            boolean hasResultSet = stmt.execute(sql);

            if (hasResultSet) {
                // 处理查询结果
                try (ResultSet rs = stmt.getResultSet()) {
                    return processResultSet(rs);
                }
            } else {
                // 处理更新计数
                int updateCount = stmt.getUpdateCount();
                return SqlExecutionResult.builder()
                        .success(true)
                        .rowCount(updateCount)
                        .columns(List.of("Affected Rows"))
                        .rows(List.of(List.of(String.valueOf(updateCount))))
                        .build();
            }

        } catch (SQLException e) {
            log.error("SQL执行错误: {}", e.getMessage());
            return SqlExecutionResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 处理结果集
     */
    private SqlExecutionResult processResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        // 获取列名
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }

        // 获取数据行
        List<List<String>> rows = new ArrayList<>();
        int rowCount = 0;

        while (rs.next() && rowCount < maxResultRows) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                String value = rs.getString(i);
                row.add(value != null ? value : "NULL");
            }
            rows.add(row);
            rowCount++;
        }

        return SqlExecutionResult.builder()
                .success(true)
                .columns(columns)
                .rows(rows)
                .rowCount(rowCount)
                .build();
    }

    /**
     * 检查是否为只读查询
     */
    private boolean isReadOnlyQuery(String sql) {
        String upperSql = sql.trim().toUpperCase();
        return upperSql.startsWith("SELECT") ||
               upperSql.startsWith("SHOW") ||
               upperSql.startsWith("DESCRIBE") ||
               upperSql.startsWith("EXPLAIN");
    }

    /**
     * 自动添加LIMIT
     */
    private String addLimitIfNeeded(String sql) {
        String upperSql = sql.trim().toUpperCase();

        // 只对SELECT语句添加LIMIT
        if (!upperSql.startsWith("SELECT")) {
            return sql;
        }

        // 检查是否已有LIMIT
        if (upperSql.contains("LIMIT")) {
            return sql;
        }

        // 添加LIMIT
        return sql.trim().replaceAll(";?\\s*$", "") +
               " LIMIT " + maxResultRows;
    }

    /**
     * 验证SQL语法（简单验证）
     */
    public boolean validateSql(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // 使用EXPLAIN验证语法
            stmt.execute("EXPLAIN " + sql);
            return true;
        } catch (SQLException e) {
            log.warn("SQL验证失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class QueryResult {
        private boolean success;
        private String userQuestion;
        private String generatedSql;
        private String explanation;
        private List<String> columns;
        private List<List<String>> rows;
        private int rowCount;
        private String errorMessage;
        private int retryCount;
    }

    @Data
    @Builder
    public static class SqlExecutionResult {
        private boolean success;
        private List<String> columns;
        private List<List<String>> rows;
        private int rowCount;
        private String errorMessage;
    }
}
