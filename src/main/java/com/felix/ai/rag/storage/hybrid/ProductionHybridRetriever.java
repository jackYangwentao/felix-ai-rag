package com.felix.ai.rag.storage.hybrid;

import com.felix.ai.rag.storage.KeywordStore;
import com.felix.ai.rag.storage.VectorStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 生产级混合检索器
 * 结合 Milvus 向量检索 + Elasticsearch 关键词检索
 *
 * 核心特性：
 * 1. 并行双路检索（向量和关键词同时查询）
 * 2. RRF 和 Weighted Sum 两种融合策略
 * 3. 自动去重和结果合并
 * 4. 支持元数据过滤
 * 5. 可解释性分析
 * 6. 性能监控和统计
 */
@Slf4j
@Component
public class ProductionHybridRetriever {

    private final VectorStore vectorStore;
    private final KeywordStore keywordStore;
    private final EmbeddingModel embeddingModel;
    private final ExecutorService executorService;

    @Value("${rag.hybrid-retriever.strategy:RRF}")
    private FusionStrategy fusionStrategy;

    @Value("${rag.hybrid-retriever.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${rag.hybrid-retriever.keyword-weight:0.3}")
    private double keywordWeight;

    @Value("${rag.hybrid-retriever.rrf-k:60}")
    private int rrfK;

    @Value("${rag.hybrid-retriever.max-results:10}")
    private int defaultMaxResults;

    @Value("${rag.hybrid-retriever.min-score:0.5}")
    private double minScore;

    @Value("${rag.hybrid-retriever.enable-parallel:true}")
    private boolean enableParallel;

    @Value("${rag.hybrid-retriever.thread-pool-size:10}")
    private int threadPoolSize;

    public enum FusionStrategy {
        RRF,           // Reciprocal Rank Fusion
        WEIGHTED_SUM   // 加权线性组合
    }

    public ProductionHybridRetriever(VectorStore vectorStore,
                                      KeywordStore keywordStore,
                                      EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.keywordStore = keywordStore;
        this.embeddingModel = embeddingModel;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
        log.info("ProductionHybridRetriever initialized with strategy: {}", fusionStrategy);
    }

    /**
     * 执行混合检索
     */
    public HybridSearchResult retrieve(String query) {
        return retrieve(query, defaultMaxResults, null);
    }

    /**
     * 执行混合检索（指定返回数量）
     */
    public HybridSearchResult retrieve(String query, int maxResults) {
        return retrieve(query, maxResults, null);
    }

    /**
     * 执行混合检索（带过滤条件）
     */
    public HybridSearchResult retrieve(String query, int maxResults, Map<String, Object> filter) {
        long startTime = System.currentTimeMillis();
        log.info("Starting hybrid search for query: '{}', strategy: {}, maxResults: {}",
                query, fusionStrategy, maxResults);

        try {
            // 执行双路检索
            DualChannelResults dualResults;
            if (enableParallel) {
                dualResults = retrieveParallel(query, maxResults * 2, filter);
            } else {
                dualResults = retrieveSequential(query, maxResults * 2, filter);
            }

            // 融合排序
            List<FusedResult> fusedResults;
            if (fusionStrategy == FusionStrategy.RRF) {
                fusedResults = reciprocalRankFusion(
                        dualResults.vectorResults, dualResults.keywordResults, maxResults);
            } else {
                fusedResults = weightedLinearCombination(
                        dualResults.vectorResults, dualResults.keywordResults, maxResults);
            }

            long processingTime = System.currentTimeMillis() - startTime;

            // 构建可解释性分析
            Explanation explanation = buildExplanation(query, dualResults, fusedResults, processingTime);

            log.info("Hybrid search completed. Retrieved {} vector + {} keyword = {} fused results in {}ms",
                    dualResults.vectorResults.size(), dualResults.keywordResults.size(),
                    fusedResults.size(), processingTime);

            return HybridSearchResult.builder()
                    .query(query)
                    .results(fusedResults)
                    .explanation(explanation)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (Exception e) {
            log.error("Hybrid search failed", e);
            throw new RuntimeException("Hybrid search failed", e);
        }
    }

    /**
     * 并行双路检索
     */
    private DualChannelResults retrieveParallel(String query, int topK, Map<String, Object> filter) {
        CompletableFuture<List<VectorResult>> vectorFuture = CompletableFuture.supplyAsync(() ->
                retrieveFromVector(query, topK, filter), executorService);

        CompletableFuture<List<KeywordResult>> keywordFuture = CompletableFuture.supplyAsync(() ->
                retrieveFromKeyword(query, topK, filter), executorService);

        CompletableFuture.allOf(vectorFuture, keywordFuture).join();

        return new DualChannelResults(
                vectorFuture.join(),
                keywordFuture.join()
        );
    }

    /**
     * 串行双路检索
     */
    private DualChannelResults retrieveSequential(String query, int topK, Map<String, Object> filter) {
        List<VectorResult> vectorResults = retrieveFromVector(query, topK, filter);
        List<KeywordResult> keywordResults = retrieveFromKeyword(query, topK, filter);
        return new DualChannelResults(vectorResults, keywordResults);
    }

    /**
     * 向量检索
     */
    private List<VectorResult> retrieveFromVector(String query, int topK, Map<String, Object> filter) {
        long start = System.currentTimeMillis();
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            List<EmbeddingMatch<TextSegment>> matches;
            if (filter != null && !filter.isEmpty()) {
                String filterExpr = buildFilterExpression(filter);
                matches = vectorStore.searchWithFilter(queryEmbedding, topK, minScore, filterExpr);
            } else {
                matches = vectorStore.search(queryEmbedding, topK, minScore);
            }

            List<VectorResult> results = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                results.add(VectorResult.builder()
                        .id(match.embeddingId())
                        .content(match.embedded().text())
                        .score(match.score())
                        .rank(i + 1)
                        .metadata(match.embedded().metadata())
                        .build());
            }

            log.debug("Vector retrieval completed in {}ms, found {} results",
                    System.currentTimeMillis() - start, results.size());
            return results;

        } catch (Exception e) {
            log.error("Vector retrieval failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * 关键词检索
     */
    private List<KeywordResult> retrieveFromKeyword(String query, int topK, Map<String, Object> filter) {
        long start = System.currentTimeMillis();
        try {
            List<KeywordStore.SearchResult> results;
            if (filter != null && !filter.isEmpty()) {
                results = keywordStore.searchWithFilter(query, topK, minScore, filter);
            } else {
                results = keywordStore.search(query, topK, minScore);
            }

            List<KeywordResult> keywordResults = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                KeywordStore.SearchResult result = results.get(i);
                keywordResults.add(KeywordResult.builder()
                        .id(result.id())
                        .content(result.content())
                        .score(result.score())
                        .rank(i + 1)
                        .metadata(result.metadata())
                        .matchedTerms(result.matchedTerms())
                        .highlights(result.highlights())
                        .build());
            }

            log.debug("Keyword retrieval completed in {}ms, found {} results",
                    System.currentTimeMillis() - start, keywordResults.size());
            return keywordResults;

        } catch (Exception e) {
            log.error("Keyword retrieval failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * RRF 融合排序
     * 公式: score = Σ(1 / (rank + k))
     */
    private List<FusedResult> reciprocalRankFusion(
            List<VectorResult> vectorResults,
            List<KeywordResult> keywordResults,
            int maxResults) {

        Map<String, FusedResultBuilder> resultMap = new HashMap<>();

        // 处理向量检索结果
        for (VectorResult result : vectorResults) {
            String key = result.getContent();
            double rrfScore = vectorWeight * (1.0 / (rrfK + result.getRank()));

            resultMap.computeIfAbsent(key, FusedResultBuilder::new)
                    .withVectorResult(result)
                    .addRrfScore(rrfScore);
        }

        // 处理关键词检索结果
        for (KeywordResult result : keywordResults) {
            String key = result.getContent();
            double rrfScore = keywordWeight * (1.0 / (rrfK + result.getRank()));

            resultMap.computeIfAbsent(key, FusedResultBuilder::new)
                    .withKeywordResult(result)
                    .addRrfScore(rrfScore);
        }

        // 构建最终排序结果
        return resultMap.values().stream()
                .map(FusedResultBuilder::build)
                .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 加权线性组合融合
     */
    private List<FusedResult> weightedLinearCombination(
            List<VectorResult> vectorResults,
            List<KeywordResult> keywordResults,
            int maxResults) {

        Map<String, Double> vectorNorm = normalizeVectorScores(vectorResults);
        Map<String, Double> keywordNorm = normalizeKeywordScores(keywordResults);

        Set<String> allContent = new HashSet<>();
        allContent.addAll(vectorNorm.keySet());
        allContent.addAll(keywordNorm.keySet());

        List<FusedResult> results = new ArrayList<>();

        for (String content : allContent) {
            double vectorScore = vectorNorm.getOrDefault(content, 0.0);
            double keywordScore = keywordNorm.getOrDefault(content, 0.0);
            double finalScore = vectorWeight * vectorScore + keywordWeight * keywordScore;

            VectorResult vResult = vectorResults.stream()
                    .filter(r -> r.getContent().equals(content))
                    .findFirst()
                    .orElse(null);

            KeywordResult kResult = keywordResults.stream()
                    .filter(r -> r.getContent().equals(content))
                    .findFirst()
                    .orElse(null);

            FusedResult.FusedResultBuilder builder = FusedResult.builder()
                    .content(content)
                    .finalScore(finalScore)
                    .vectorScore(vResult != null ? vResult.getScore() : null)
                    .vectorRank(vResult != null ? vResult.getRank() : null)
                    .keywordScore(kResult != null ? kResult.getScore() : null)
                    .keywordRank(kResult != null ? kResult.getRank() : null)
                    .matchedTerms(kResult != null ? kResult.getMatchedTerms() : null);

            if (vResult != null && vResult.getMetadata() != null) {
                Map<String, Object> metaMap = new HashMap<>();
                vResult.getMetadata().asMap().forEach((k, v) -> metaMap.put(k, v));
                builder.metadata(metaMap);
            } else if (kResult != null && kResult.getMetadata() != null) {
                builder.metadata(kResult.getMetadata());
            }

            results.add(builder.build());
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 构建过滤表达式
     */
    private String buildFilterExpression(Map<String, Object> filter) {
        return filter.entrySet().stream()
                .map(e -> e.getKey() + " == " + formatFilterValue(e.getValue()))
                .collect(Collectors.joining(" && "));
    }

    private String formatFilterValue(Object value) {
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return value.toString();
    }

    /**
     * 分数归一化
     */
    private Map<String, Double> normalizeVectorScores(List<VectorResult> results) {
        return normalizeScores(results.stream()
                .collect(Collectors.toMap(VectorResult::getContent, VectorResult::getScore)));
    }

    private Map<String, Double> normalizeKeywordScores(List<KeywordResult> results) {
        return normalizeScores(results.stream()
                .collect(Collectors.toMap(KeywordResult::getContent, KeywordResult::getScore)));
    }

    private Map<String, Double> normalizeScores(Map<String, Double> scores) {
        if (scores.isEmpty()) {
            return Collections.emptyMap();
        }

        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double min = scores.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double range = max - min;

        if (range == 0) {
            return scores.keySet().stream()
                    .collect(Collectors.toMap(k -> k, k -> 1.0));
        }

        return scores.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> (e.getValue() - min) / range
                ));
    }

    /**
     * 构建可解释性分析
     */
    private Explanation buildExplanation(String query,
                                          DualChannelResults dualResults,
                                          List<FusedResult> fusedResults,
                                          long processingTime) {
        int vectorOnly = 0, keywordOnly = 0, both = 0;

        for (FusedResult r : fusedResults) {
            if (r.getVectorRank() != null && r.getKeywordRank() != null) {
                both++;
            } else if (r.getVectorRank() != null) {
                vectorOnly++;
            } else if (r.getKeywordRank() != null) {
                keywordOnly++;
            }
        }

        String analysis = String.format(
                "查询 '%s' 混合检索分析:\n" +
                        "- 向量检索召回: %d 条, 关键词检索召回: %d 条\n" +
                        "- 仅向量命中: %d, 仅关键词命中: %d, 两者共同: %d\n" +
                        "- 融合策略: %s, 向量权重: %.2f, 关键词权重: %.2f\n" +
                        "- 总耗时: %dms",
                query, dualResults.vectorResults.size(), dualResults.keywordResults.size(),
                vectorOnly, keywordOnly, both,
                fusionStrategy, vectorWeight, keywordWeight,
                processingTime
        );

        return Explanation.builder()
                .query(query)
                .fusionStrategy(fusionStrategy.name())
                .vectorWeight(vectorWeight)
                .keywordWeight(keywordWeight)
                .vectorRetrievalCount(dualResults.vectorResults.size())
                .keywordRetrievalCount(dualResults.keywordResults.size())
                .finalResultCount(fusedResults.size())
                .processingTimeMs(processingTime)
                .analysis(analysis)
                .build();
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("fusionStrategy", fusionStrategy.name());
        stats.put("vectorWeight", vectorWeight);
        stats.put("keywordWeight", keywordWeight);
        stats.put("rrfK", rrfK);
        stats.put("enableParallel", enableParallel);
        stats.put("vectorStoreHealthy", vectorStore.isHealthy());
        stats.put("keywordStoreHealthy", keywordStore.isHealthy());
        stats.putAll(vectorStore.getStats());
        stats.putAll(keywordStore.getStats());
        return stats;
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class HybridSearchResult {
        private String query;
        private List<FusedResult> results;
        private Explanation explanation;
        private long processingTimeMs;
    }

    @Data
    @Builder
    public static class FusedResult {
        private String id;
        private String content;
        private Double finalScore;
        private Integer finalRank;
        private Double vectorScore;
        private Integer vectorRank;
        private Double keywordScore;
        private Integer keywordRank;
        private List<String> matchedTerms;
        private List<String> highlights;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    public static class VectorResult {
        private String id;
        private String content;
        private Double score;
        private Integer rank;
        private Metadata metadata;
    }

    @Data
    @Builder
    public static class KeywordResult {
        private String id;
        private String content;
        private Double score;
        private Integer rank;
        private Map<String, Object> metadata;
        private List<String> matchedTerms;
        private List<String> highlights;
    }

    @Data
    @Builder
    public static class Explanation {
        private String query;
        private String fusionStrategy;
        private double vectorWeight;
        private double keywordWeight;
        private int vectorRetrievalCount;
        private int keywordRetrievalCount;
        private int finalResultCount;
        private long processingTimeMs;
        private String analysis;
    }

    // ==================== Internal Classes ====================

    @AllArgsConstructor
    private static class DualChannelResults {
        final List<VectorResult> vectorResults;
        final List<KeywordResult> keywordResults;
    }

    private static class FusedResultBuilder {
        private final String content;
        private VectorResult vectorResult;
        private KeywordResult keywordResult;
        private double rrfScore = 0.0;

        FusedResultBuilder(String content) {
            this.content = content;
        }

        FusedResultBuilder withVectorResult(VectorResult result) {
            this.vectorResult = result;
            return this;
        }

        FusedResultBuilder withKeywordResult(KeywordResult result) {
            this.keywordResult = result;
            return this;
        }

        FusedResultBuilder addRrfScore(double score) {
            this.rrfScore += score;
            return this;
        }

        FusedResult build() {
            // 构建元数据
            Map<String, Object> metaMap = new HashMap<>();
            if (vectorResult != null && vectorResult.getMetadata() != null) {
                vectorResult.getMetadata().asMap().forEach((k, v) -> metaMap.put(k, v));
            } else if (keywordResult != null && keywordResult.getMetadata() != null) {
                metaMap.putAll(keywordResult.getMetadata());
            }

            return FusedResult.builder()
                    .id(vectorResult != null ? vectorResult.getId() :
                            (keywordResult != null ? keywordResult.getId() : null))
                    .content(content)
                    .finalScore(rrfScore)
                    .finalRank(0) // Will be set later
                    .vectorScore(vectorResult != null ? vectorResult.getScore() : null)
                    .vectorRank(vectorResult != null ? vectorResult.getRank() : null)
                    .keywordScore(keywordResult != null ? keywordResult.getScore() : null)
                    .keywordRank(keywordResult != null ? keywordResult.getRank() : null)
                    .matchedTerms(keywordResult != null ? keywordResult.getMatchedTerms() : null)
                    .highlights(keywordResult != null ? keywordResult.getHighlights() : null)
                    .metadata(metaMap.isEmpty() ? null : metaMap)
                    .build();
        }
    }
}
