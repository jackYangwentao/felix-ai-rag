package com.felix.ai.rag.retriever;

import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 集成检索器 (Ensemble Retriever)
 * 参考 Datawhale All-In-RAG 高级检索技术
 *
 * 核心功能：
 * - 组合多个不同的检索器
 * - 使用RRF (Reciprocal Rank Fusion) 融合多路召回结果
 * - 提高检索的召回率和准确性
 *
 * 支持的检索器类型：
 * - 稠密向量检索器（语义理解）
 * - 稀疏关键词检索器（精确匹配）
 * - 图检索器（关系推理）
 * - 自定义检索器
 */
@Component
@Slf4j
public class EnsembleRetriever {

    private final List<RetrieverConfig> retrievers;
    private final double rrfK;

    public EnsembleRetriever() {
        this.retrievers = new ArrayList<>();
        this.rrfK = 60.0;
    }

    public EnsembleRetriever(double rrfK) {
        this.retrievers = new ArrayList<>();
        this.rrfK = rrfK;
    }

    /**
     * 添加检索器
     */
    public void addRetriever(String name, RetrieverFunction retriever, double weight) {
        retrievers.add(RetrieverConfig.builder()
                .name(name)
                .retriever(retriever)
                .weight(weight)
                .build());
        log.info("添加检索器: {} (权重: {})", name, weight);
    }

    /**
     * 集成检索 - 使用RRF融合多个检索器的结果
     */
    public EnsembleResult retrieve(String query, int maxResults) {
        log.info("集成检索: 查询='{}', 检索器数量={}", query, retrievers.size());
        long startTime = System.currentTimeMillis();

        // 1. 从所有检索器获取结果
        Map<String, List<RetrievalResult>> allResults = new HashMap<>();

        for (RetrieverConfig config : retrievers) {
            try {
                List<RetrievalResult> results = config.getRetriever().retrieve(query, maxResults * 2);
                allResults.put(config.getName(), results);
                log.debug("检索器 '{}' 返回 {} 个结果", config.getName(), results.size());
            } catch (Exception e) {
                log.error("检索器 '{}' 执行失败", config.getName(), e);
            }
        }

        // 2. 使用RRF融合结果
        List<FusedResult> fusedResults = reciprocalRankFusion(allResults, maxResults);

        // 3. 转换为最终内容
        List<Content> finalContents = fusedResults.stream()
                .map(r -> Content.from(dev.langchain4j.data.segment.TextSegment.from(r.getContent())))
                .collect(Collectors.toList());

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("集成检索完成: 返回 {} 个结果, 耗时 {}ms", finalContents.size(), processingTime);

        return EnsembleResult.builder()
                .query(query)
                .results(finalContents)
                .fusedResults(fusedResults)
                .sourceResults(allResults)
                .processingTimeMs(processingTime)
                .build();
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合排序
     * 公式: RRF_score(d) = Σ(1 / (rank_i(d) + k))
     */
    private List<FusedResult> reciprocalRankFusion(
            Map<String, List<RetrievalResult>> allResults,
            int maxResults) {

        Map<String, MutableFusedResult> resultMap = new HashMap<>();

        // 处理每个检索器的结果
        for (Map.Entry<String, List<RetrievalResult>> entry : allResults.entrySet()) {
            String retrieverName = entry.getKey();
            List<RetrievalResult> results = entry.getValue();

            for (int i = 0; i < results.size(); i++) {
                RetrievalResult result = results.get(i);
                String content = result.getContent();
                int rank = i + 1;

                // 计算RRF分数
                double rrfScore = 1.0 / (rrfK + rank);

                MutableFusedResult mutable = resultMap.computeIfAbsent(content, k -> new MutableFusedResult());
                mutable.content = content;
                mutable.rrfScore += rrfScore;
                mutable.sourceRanks += retrieverName + "=" + rank + "; ";
            }
        }

        // 排序并返回
        return resultMap.values().stream()
                .map(m -> FusedResult.builder()
                        .content(m.content)
                        .rrfScore(m.rrfScore)
                        .sourceRanks(m.sourceRanks)
                        .build())
                .sorted(Comparator.comparingDouble(FusedResult::getRrfScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    // ==================== 函数式接口 ====================

    @FunctionalInterface
    public interface RetrieverFunction {
        List<RetrievalResult> retrieve(String query, int maxResults);
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class RetrieverConfig {
        private String name;
        private RetrieverFunction retriever;
        private double weight;
    }

    @Data
    @Builder
    public static class RetrievalResult {
        private String content;
        private double score;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    public static class FusedResult {
        private String content;
        private double rrfScore;
        private String sourceRanks;
    }

    @Data
    @Builder
    public static class EnsembleResult {
        private String query;
        private List<Content> results;
        private List<FusedResult> fusedResults;
        private Map<String, List<RetrievalResult>> sourceResults;
        private long processingTimeMs;
    }

    // 用于构建的辅助类
    private static class MutableFusedResult {
        String content;
        double rrfScore = 0.0;
        String sourceRanks = "";
    }
}
