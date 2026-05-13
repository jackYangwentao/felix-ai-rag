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
     * 多样性搜索 - 使用 MMR 重排序
     * 确保结果既相关又多样
     *
     * @param query 查询
     * @param maxResults 最大结果数
     * @param diversityFactor 多样性因子（0-1，越大多样性越高）
     * @return 多样化的搜索结果
     */
    public SearchResult diverseSearch(String query, int maxResults, double diversityFactor) {
        log.info("执行多样性搜索: '{}', maxResults={}, diversity={}", query, maxResults, diversityFactor);

        // 1. 获取更多候选
        int candidateCount = (int) (maxResults * 2);
        SearchResult initialResult = search(query, candidateCount, null, false);

        if (initialResult.getResults().size() <= maxResults) {
            return initialResult;
        }

        // 2. 使用 RerankerService 的 MMR 算法
        List<String> candidates = initialResult.getResults().stream()
                .map(SearchResultItem::getContent)
                .collect(Collectors.toList());

        List<RerankerService.ScoredDocument> mmrResults = rerankerService.rerankWithMMR(
                query, candidates, maxResults, 1 - diversityFactor);

        List<SearchResultItem> diverseResults = mmrResults.stream()
                .map(doc -> SearchResultItem.builder()
                        .content(doc.getContent())
                        .rerankScore(doc.getScore())
                        .build())
                .collect(Collectors.toList());

        return SearchResult.builder()
                .query(query)
                .results(diverseResults)
                .totalResults(diverseResults.size())
                .usedRerank(true)
                .build();
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
