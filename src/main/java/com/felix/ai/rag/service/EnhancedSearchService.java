package com.felix.ai.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * 增强搜索服务
 * 支持元数据过滤和重排序的高级搜索功能
 *
 * 参考 Datawhale All-In-RAG 向量数据库章节
 */
@Service
@Slf4j
public class EnhancedSearchService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final RerankerService rerankerService;

    @Value("${rag.max-results:5}")
    private int defaultMaxResults;

    @Value("${rag.min-score:0.7}")
    private double defaultMinScore;

    @Value("${rag.search.rerank:false}")
    private boolean enableRerank;

    public EnhancedSearchService(EmbeddingStore<TextSegment> embeddingStore,
                                  EmbeddingModel embeddingModel,
                                  RerankerService rerankerService) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.rerankerService = rerankerService;
    }

    /**
     * 增强搜索 - 支持重排序
     *
     * @param query 查询文本
     * @param maxResults 最大结果数
     * @param minScore 最小相似度分数
     * @param useRerank 是否使用重排序
     * @return 搜索结果
     */
    public SearchResult search(String query, Integer maxResults, Double minScore, Boolean useRerank) {
        int topK = maxResults != null ? maxResults : defaultMaxResults;
        double scoreThreshold = minScore != null ? minScore : defaultMinScore;
        boolean shouldRerank = useRerank != null ? useRerank : enableRerank;

        log.info("执行增强搜索: query='{}', topK={}, minScore={}, rerank={}",
                query, topK, scoreThreshold, shouldRerank);

        // 1. 向量检索（第一阶段 - 召回）
        long startTime = System.currentTimeMillis();
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                queryEmbedding,
                shouldRerank ? topK * 2 : topK, // 如果重排序，多召回一些候选
                scoreThreshold
        );

        log.debug("向量检索完成，召回 {} 个候选，耗时 {}ms",
                matches.size(), System.currentTimeMillis() - startTime);

        if (matches.isEmpty()) {
            return SearchResult.builder()
                    .query(query)
                    .results(new ArrayList<>())
                    .totalResults(0)
                    .build();
        }

        // 2. 提取文档内容
        List<String> candidates = matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());

        // 3. 重排序（第二阶段 - 精排）
        List<SearchResultItem> finalResults;
        if (shouldRerank && rerankerService.isEnabled()) {
            long rerankStart = System.currentTimeMillis();
            List<RerankerService.ScoredDocument> reranked = rerankerService.rerank(query, candidates, topK);

            finalResults = reranked.stream()
                    .map(doc -> SearchResultItem.builder()
                            .content(doc.getContent())
                            .rerankScore(doc.getScore())
                            .build())
                    .collect(Collectors.toList());

            log.debug("重排序完成，耗时 {}ms", System.currentTimeMillis() - rerankStart);
        } else {
            finalResults = candidates.stream()
                    .limit(topK)
                    .map(content -> SearchResultItem.builder()
                            .content(content)
                            .build())
                    .collect(Collectors.toList());
        }

        log.info("搜索完成，返回 {} 个结果，总耗时 {}ms",
                finalResults.size(), System.currentTimeMillis() - startTime);

        return SearchResult.builder()
                .query(query)
                .results(finalResults)
                .totalResults(finalResults.size())
                .usedRerank(shouldRerank)
                .build();
    }

    /**
     * 简化版搜索
     */
    public SearchResult search(String query) {
        return search(query, null, null, null);
    }

    /**
     * 多查询搜索 - 使用多个相关查询进行检索，合并结果
     * 用于提高召回率（参考 Milvus 混合检索最佳实践）
     *
     * @param queries 多个查询
     * @param maxResults 最大结果数
     * @return 合并后的搜索结果
     */
    public SearchResult multiQuerySearch(List<String> queries, int maxResults) {
        log.info("执行多查询搜索，查询数: {}", queries.size());

        Set<String> uniqueResults = new LinkedHashSet<>();

        for (String query : queries) {
            SearchResult result = search(query, maxResults * 2, null, false);
            for (SearchResultItem item : result.getResults()) {
                uniqueResults.add(item.getContent());
            }
        }

        // 去重后重排序
        List<String> candidates = new ArrayList<>(uniqueResults);
        List<RerankerService.ScoredDocument> reranked = rerankerService.rerank(
                queries.get(0), candidates, maxResults);

        List<SearchResultItem> finalResults = reranked.stream()
                .map(doc -> SearchResultItem.builder()
                        .content(doc.getContent())
                        .rerankScore(doc.getScore())
                        .build())
                .collect(Collectors.toList());

        return SearchResult.builder()
                .query(String.join(" | ", queries))
                .results(finalResults)
                .totalResults(finalResults.size())
                .usedRerank(true)
                .build();
    }

    /**
     * 批量搜索 - 同时处理多个查询（参考 Milvus 批量查询优化）
     *
     * @param queries 查询列表
     * @param maxResults 每个查询的最大结果数
     * @return 批量搜索结果
     */
    public Map<String, SearchResult> batchSearch(List<String> queries, int maxResults) {
        log.info("执行批量搜索，查询数: {}", queries.size());
        long startTime = System.currentTimeMillis();

        Map<String, SearchResult> results = new HashMap<>();

        // 并行处理多个查询
        queries.parallelStream().forEach(query -> {
            SearchResult result = search(query, maxResults, null, false);
            results.put(query, result);
        });

        log.info("批量搜索完成，{} 个查询，耗时 {}ms",
                queries.size(), System.currentTimeMillis() - startTime);

        return results;
    }

    /**
     * 多样性搜索 - 确保结果来自不同文档（参考 Milvus 分组检索）
     *
     * @param query 查询
     * @param maxResults 最大结果数
     * @param diversityFactor 多样性因子（0-1，越大多样性越高）
     * @return 多样化的搜索结果
     */
    public SearchResult diverseSearch(String query, int maxResults, double diversityFactor) {
        log.info("执行多样性搜索: '{}', maxResults={}, diversity={}", query, maxResults, diversityFactor);

        // 1. 获取更多候选
        int candidateCount = (int) (maxResults * (1 + diversityFactor));
        SearchResult initialResult = search(query, candidateCount, null, false);

        if (initialResult.getResults().size() <= maxResults) {
            return initialResult;
        }

        // 2. 使用 MMR (Maximal Marginal Relevance) 算法选择多样化结果
        List<SearchResultItem> diverseResults = selectDiverseResults(
                initialResult.getResults(), maxResults, diversityFactor);

        return SearchResult.builder()
                .query(query)
                .results(diverseResults)
                .totalResults(diverseResults.size())
                .usedRerank(false)
                .build();
    }

    /**
     * MMR 算法选择多样化结果
     */
    private List<SearchResultItem> selectDiverseResults(
            List<SearchResultItem> candidates, int maxResults, double diversityFactor) {
        List<SearchResultItem> selected = new ArrayList<>();
        Set<Integer> selectedIndices = new HashSet<>();

        while (selected.size() < maxResults && selectedIndices.size() < candidates.size()) {
            double maxScore = -1;
            int bestIndex = -1;

            for (int i = 0; i < candidates.size(); i++) {
                if (selectedIndices.contains(i)) continue;

                SearchResultItem candidate = candidates.get(i);

                // 计算相关性得分
                double relevanceScore = candidate.getRerankScore() != null
                        ? candidate.getRerankScore()
                        : 0.5;

                // 计算多样性得分（与已选结果的平均相似度）
                double diversityScore = calculateDiversityScore(candidate, selected);

                // MMR 分数 = λ * 相关性 - (1-λ) * 相似度
                double mmrScore = diversityFactor * relevanceScore
                        - (1 - diversityFactor) * diversityScore;

                if (mmrScore > maxScore) {
                    maxScore = mmrScore;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0) {
                selected.add(candidates.get(bestIndex));
                selectedIndices.add(bestIndex);
            } else {
                break;
            }
        }

        return selected;
    }

    /**
     * 计算与已选结果的平均相似度（用于 MMR）
     */
    private double calculateDiversityScore(SearchResultItem candidate, List<SearchResultItem> selected) {
        if (selected.isEmpty()) {
            return 0;
        }

        String candidateText = candidate.getContent();
        double totalSimilarity = 0;

        for (SearchResultItem item : selected) {
            totalSimilarity += calculateTextSimilarity(candidateText, item.getContent());
        }

        return totalSimilarity / selected.size();
    }

    /**
     * 简单的文本相似度计算（基于词重叠）
     */
    private double calculateTextSimilarity(String text1, String text2) {
        Set<String> words1 = new HashSet<>(Arrays.asList(text1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.toLowerCase().split("\\s+")));

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    /**
     * 搜索结果
     */
    @Data
    @Builder
    public static class SearchResult {
        private String query;
        private List<SearchResultItem> results;
        private int totalResults;
        private boolean usedRerank;
    }

    /**
     * 搜索结果项
     */
    @Data
    @Builder
    public static class SearchResultItem {
        private String content;
        private Double vectorScore;
        private Double rerankScore;
    }
}
