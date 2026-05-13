package com.felix.ai.rag.text2sql;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Text2SQL 知识库
 * 参考 Datawhale All-In-RAG Text2SQL章节
 *
 * 知识库组成：
 * 1. DDL（数据定义语言）：表结构定义
 * 2. 表和字段的详细描述：自然语言解释业务含义
 * 3. 同义词和业务术语：如"花费"→cost字段
 * 4. 复杂查询示例：含JOIN、GROUP BY、子查询的Q&A对
 */
@Component
@Slf4j
public class Text2SqlKnowledgeBase {

    private final EmbeddingModel embeddingModel;

    // 知识库存储
    private final List<KnowledgeItem> knowledgeItems;

    public Text2SqlKnowledgeBase(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.knowledgeItems = new ArrayList<>();
    }

    /**
     * 添加DDL知识（表结构定义）
     */
    public void addDdl(String tableName, String ddl, String description) {
        String content = String.format("表名: %s\nDDL: %s\n描述: %s",
                tableName, ddl, description);

        addKnowledgeItem(content, KnowledgeType.DDL, tableName);
        log.info("添加DDL知识: {}", tableName);
    }

    /**
     * 添加字段描述知识
     */
    public void addFieldDescription(String tableName, String fieldName,
                                     String fieldType, String description,
                                     List<String> synonyms) {
        StringBuilder content = new StringBuilder();
        content.append(String.format("表: %s, 字段: %s, 类型: %s\n", tableName, fieldName, fieldType));
        content.append(String.format("描述: %s\n", description));

        if (synonyms != null && !synonyms.isEmpty()) {
            content.append(String.format("同义词: %s", String.join(", ", synonyms)));
        }

        addKnowledgeItem(content.toString(), KnowledgeType.DESCRIPTION,
                tableName + "." + fieldName);
        log.debug("添加字段描述: {}.{}", tableName, fieldName);
    }

    /**
     * 添加查询示例知识
     */
    public void addQueryExample(String question, String sql, String description) {
        String content = String.format("问题: %s\nSQL: %s\n说明: %s",
                question, sql, description);

        addKnowledgeItem(content, KnowledgeType.QSQL, "example");
        log.debug("添加查询示例: {}", question.substring(0, Math.min(30, question.length())));
    }

    /**
     * 添加业务术语映射
     */
    public void addBusinessTerm(String term, String mapping, String explanation) {
        String content = String.format("业务术语: %s\n映射: %s\n解释: %s",
                term, mapping, explanation);

        addKnowledgeItem(content, KnowledgeType.TERM, term);
        log.debug("添加业务术语: {}", term);
    }

    /**
     * 搜索知识库
     */
    public List<KnowledgeSearchResult> search(String query, int topK) {
        log.info("知识库搜索: '{}'", query);

        // 计算查询的嵌入向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        float[] queryVector = queryEmbedding.vector();

        // 计算相似度并排序
        return knowledgeItems.stream()
                .map(item -> {
                    double similarity = cosineSimilarity(queryVector, item.getEmbedding());
                    return KnowledgeSearchResult.builder()
                            .item(item)
                            .similarity(similarity)
                            .build();
                })
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::getSimilarity).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 构建上下文（分层组织）
     * 顺序：DDL结构 → 字段描述 → 查询示例
     */
    public String buildContext(List<KnowledgeSearchResult> searchResults) {
        StringBuilder context = new StringBuilder();

        // 1. DDL信息（最优先）
        List<String> ddlContents = searchResults.stream()
                .filter(r -> r.getItem().getType() == KnowledgeType.DDL)
                .map(r -> r.getItem().getContent())
                .collect(Collectors.toList());

        if (!ddlContents.isEmpty()) {
            context.append("=== 数据库表结构 ===\n");
            ddlContents.forEach(c -> context.append(c).append("\n\n"));
        }

        // 2. 字段描述信息
        List<String> descContents = searchResults.stream()
                .filter(r -> r.getItem().getType() == KnowledgeType.DESCRIPTION)
                .map(r -> r.getItem().getContent())
                .collect(Collectors.toList());

        if (!descContents.isEmpty()) {
            context.append("=== 字段说明 ===\n");
            descContents.forEach(c -> context.append(c).append("\n\n"));
        }

        // 3. 业务术语
        List<String> termContents = searchResults.stream()
                .filter(r -> r.getItem().getType() == KnowledgeType.TERM)
                .map(r -> r.getItem().getContent())
                .collect(Collectors.toList());

        if (!termContents.isEmpty()) {
            context.append("=== 业务术语 ===\n");
            termContents.forEach(c -> context.append(c).append("\n\n"));
        }

        // 4. 查询示例
        List<String> exampleContents = searchResults.stream()
                .filter(r -> r.getItem().getType() == KnowledgeType.QSQL)
                .map(r -> r.getItem().getContent())
                .collect(Collectors.toList());

        if (!exampleContents.isEmpty()) {
            context.append("=== 查询示例 ===\n");
            exampleContents.forEach(c -> context.append(c).append("\n\n"));
        }

        return context.toString().trim();
    }

    /**
     * 添加知识项
     */
    private void addKnowledgeItem(String content, KnowledgeType type, String source) {
        // 计算嵌入向量
        Embedding embedding = embeddingModel.embed(content).content();

        KnowledgeItem item = KnowledgeItem.builder()
                .id(UUID.randomUUID().toString())
                .content(content)
                .type(type)
                .source(source)
                .embedding(embedding.vector())
                .build();

        knowledgeItems.add(item);
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 获取知识库统计
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        for (KnowledgeType type : KnowledgeType.values()) {
            long count = knowledgeItems.stream()
                    .filter(i -> i.getType() == type)
                    .count();
            stats.put(type.name(), (int) count);
        }
        stats.put("TOTAL", knowledgeItems.size());
        return stats;
    }

    /**
     * 清空知识库
     */
    public void clear() {
        knowledgeItems.clear();
        log.info("知识库已清空");
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class KnowledgeItem {
        private String id;
        private String content;
        private KnowledgeType type;
        private String source;
        private float[] embedding;
    }

    @Data
    @Builder
    public static class KnowledgeSearchResult {
        private KnowledgeItem item;
        private double similarity;
    }

    public enum KnowledgeType {
        DDL,          // 表结构定义
        DESCRIPTION,  // 字段描述
        QSQL,         // 查询示例
        TERM          // 业务术语
    }
}
