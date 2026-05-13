package com.felix.ai.rag.retriever;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器
 * 结合 BM25 关键词检索和向量语义检索，使用 RRF 融合排序
 *
 * 参考 Datawhale All-In-RAG 混合检索最佳实践
 */
@Component
@Slf4j
public class HybridRetriever {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${rag.hybrid-retriever.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${rag.hybrid-retriever.keyword-weight:0.3}")
    private double keywordWeight;

    @Value("${rag.hybrid-retriever.rrf-k:60}")
    private int rrfK;  // RRF 公式中的常数k

    @Value("${rag.hybrid-retriever.max-results:5}")
    private int maxResults;

    @Value("${rag.hybrid-retriever.min-score:0.5}")
    private double minScore;

    // 内存中的关键词索引（简化实现）
    private final Map<String, List<DocumentFrequency>> keywordIndex = new HashMap<>();
    private final Map<String, TextSegment> documentStore = new HashMap<>();
    private int totalDocuments = 0;

    public HybridRetriever(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 执行混合检索
     *
     * @param query 查询文本
     * @return 融合排序后的结果
     */
    public List<ScoredContent> retrieve(String query) {
        return retrieve(query, maxResults);
    }

    /**
     * 执行混合检索
     *
     * @param query      查询文本
     * @param maxResults 最大结果数
     * @return 融合排序后的结果
     */
    public List<ScoredContent> retrieve(String query, int maxResults) {
        log.info("执行混合检索: '{}', maxResults={}", query, maxResults);
        long startTime = System.currentTimeMillis();

        // 1. 向量检索
        List<ScoredContent> vectorResults = vectorSearch(query, maxResults * 2);
        log.debug("向量检索完成，召回 {} 个结果", vectorResults.size());

        // 2. 关键词检索（BM25）
        List<ScoredContent> keywordResults = keywordSearch(query, maxResults * 2);
        log.debug("关键词检索完成，召回 {} 个结果", keywordResults.size());

        // 3. RRF 融合排序
        List<ScoredContent> fusedResults = reciprocalRankFusion(vectorResults, keywordResults, maxResults);
        log.debug("RRF融合完成，返回 {} 个结果", fusedResults.size());

        log.info("混合检索完成，总耗时 {}ms", System.currentTimeMillis() - startTime);
        return fusedResults;
    }

    /**
     * 向量检索
     */
    private List<ScoredContent> vectorSearch(String query, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                queryEmbedding, topK, minScore);

        List<ScoredContent> results = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            results.add(ScoredContent.builder()
                    .content(Content.from(match.embedded()))
                    .text(match.embedded().text())
                    .vectorScore(match.score())
                    .rank(i + 1)
                    .source("vector")
                    .build());
        }

        return results;
    }

    /**
     * 关键词检索（简化版BM25）
     */
    private List<ScoredContent> keywordSearch(String query, int topK) {
        if (keywordIndex.isEmpty()) {
            log.warn("关键词索引为空，跳过关键词检索");
            return new ArrayList<>();
        }

        // 分词
        List<String> queryTerms = tokenize(query);

        // 计算每个文档的BM25分数
        Map<String, Double> docScores = new HashMap<>();

        for (String term : queryTerms) {
            List<DocumentFrequency> postings = keywordIndex.get(term.toLowerCase());
            if (postings == null) continue;

            // IDF计算
            double idf = Math.log(1 + (totalDocuments - postings.size() + 0.5)
                    / (postings.size() + 0.5));

            for (DocumentFrequency df : postings) {
                String docId = df.getDocumentId();
                int termFreq = df.getFrequency();

                // 简化版BM25计算
                double k1 = 1.5;  // 饱和参数
                double b = 0.75;  // 长度归一化参数

                TextSegment segment = documentStore.get(docId);
                if (segment == null) continue;

                int docLength = segment.text().length();
                double avgDocLength = calculateAvgDocLength();

                double tf = (termFreq * (k1 + 1))
                        / (termFreq + k1 * (1 - b + b * docLength / avgDocLength));

                double score = idf * tf;
                docScores.merge(docId, score, Double::sum);
            }
        }

        // 排序并返回结果
        List<Map.Entry<String, Double>> sortedEntries = docScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        List<ScoredContent> results = new ArrayList<>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            Map.Entry<String, Double> entry = sortedEntries.get(i);
            String docId = entry.getKey();
            TextSegment segment = documentStore.get(docId);
            results.add(ScoredContent.builder()
                    .content(Content.from(segment))
                    .text(segment.text())
                    .keywordScore(entry.getValue())
                    .rank(i + 1)
                    .source("keyword")
                    .build());
        }
        return results;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合排序
     * 公式: score = Σ(1 / (k + rank))
     */
    private List<ScoredContent> reciprocalRankFusion(
            List<ScoredContent> vectorResults,
            List<ScoredContent> keywordResults,
            int maxResults) {

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, ScoredContent> contentMap = new HashMap<>();

        // 处理向量检索结果
        for (ScoredContent content : vectorResults) {
            String text = content.getText();
            double rrfScore = vectorWeight * (1.0 / (rrfK + content.getRank()));
            rrfScores.merge(text, rrfScore, Double::sum);
            contentMap.put(text, content);
        }

        // 处理关键词检索结果
        for (ScoredContent content : keywordResults) {
            String text = content.getText();
            double rrfScore = keywordWeight * (1.0 / (rrfK + content.getRank()));
            rrfScores.merge(text, rrfScore, Double::sum);
            contentMap.put(text, content);
        }

        // 排序并返回
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(entry -> {
                    ScoredContent original = contentMap.get(entry.getKey());
                    return ScoredContent.builder()
                            .content(original.getContent())
                            .text(original.getText())
                            .vectorScore(original.getVectorScore())
                            .keywordScore(original.getKeywordScore())
                            .rrfScore(entry.getValue())
                            .source("hybrid")
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 添加文档到关键词索引
     */
    public void indexDocument(String docId, TextSegment segment) {
        documentStore.put(docId, segment);
        totalDocuments++;

        // 分词并建立倒排索引
        List<String> terms = tokenize(segment.text());
        Map<String, Integer> termFreq = new HashMap<>();

        for (String term : terms) {
            termFreq.merge(term.toLowerCase(), 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            int freq = entry.getValue();

            keywordIndex.computeIfAbsent(term, k -> new ArrayList<>())
                    .add(new DocumentFrequency(docId, freq));
        }
    }

    /**
     * 简单分词（按空格和标点符号）
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        // 移除标点，分割成词
        String cleaned = text.replaceAll("[^\\w\\s]", " ").toLowerCase();
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(s -> s.length() > 1)  // 过滤单字符
                .collect(Collectors.toList());
    }

    /**
     * 计算平均文档长度
     */
    private double calculateAvgDocLength() {
        if (documentStore.isEmpty()) return 0;
        return documentStore.values().stream()
                .mapToInt(s -> s.text().length())
                .average()
                .orElse(0);
    }

    /**
     * 转换为 LangChain4j ContentRetriever
     */
    public ContentRetriever toContentRetriever() {
        return new ContentRetriever() {
            @Override
            public List<Content> retrieve(Query query) {
                return HybridRetriever.this.retrieve(query.text()).stream()
                        .map(ScoredContent::getContent)
                        .collect(Collectors.toList());
            }
        };
    }

    // ==================== 内部类 ====================

    @Data
    @Builder
    public static class ScoredContent {
        private Content content;
        private String text;
        private Double vectorScore;
        private Double keywordScore;
        private Double rrfScore;
        private int rank;
        private String source;
    }

    @Data
    @AllArgsConstructor
    private static class DocumentFrequency {
        private String documentId;
        private int frequency;
    }
}
