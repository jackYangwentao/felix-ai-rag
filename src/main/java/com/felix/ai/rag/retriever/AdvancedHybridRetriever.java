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
 * 高级混合检索器
 * 参考 Datawhale All-In-RAG 混合搜索章节
 *
 * 核心特性：
 * 1. 支持 RRF (Reciprocal Rank Fusion) 融合排序
 * 2. 支持加权线性组合 (Weighted Linear Combination)
 * 3. 完善的 BM25 稀疏检索
 * 4. 结果可解释性分析
 * 5. 中文分词支持
 */
@Component
@Slf4j
public class AdvancedHybridRetriever {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${rag.advanced-hybrid.strategy:RRF}")
    private FusionStrategy fusionStrategy;

    @Value("${rag.advanced-hybrid.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${rag.advanced-hybrid.keyword-weight:0.3}")
    private double keywordWeight;

    @Value("${rag.advanced-hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${rag.advanced-hybrid.max-results:5}")
    private int maxResults;

    @Value("${rag.advanced-hybrid.min-score:0.5}")
    private double minScore;

    @Value("${rag.advanced-hybrid.bm25.k1:1.5}")
    private double bm25K1;

    @Value("${rag.advanced-hybrid.bm25.b:0.75}")
    private double bm25B;

    // 关键词索引
    private final Map<String, List<DocumentFrequency>> keywordIndex = new HashMap<>();
    private final Map<String, TextSegment> documentStore = new HashMap<>();
    private final Map<String, Integer> documentLengths = new HashMap<>();
    private int totalDocuments = 0;
    private double avgDocLength = 0;

    public enum FusionStrategy {
        RRF,           // Reciprocal Rank Fusion
        WEIGHTED_SUM   // 加权线性组合
    }

    public AdvancedHybridRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                    EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 执行高级混合检索
     */
    public HybridSearchResult retrieve(String query) {
        return retrieve(query, maxResults);
    }

    /**
     * 执行高级混合检索
     */
    public HybridSearchResult retrieve(String query, int maxResults) {
        log.info("执行高级混合检索: '{}', 策略: {}, maxResults={}",
                query, fusionStrategy, maxResults);
        long startTime = System.currentTimeMillis();

        // 1. 向量检索（稠密检索）
        List<ChannelResult> vectorResults = denseRetrieval(query, maxResults * 2);
        log.debug("稠密检索完成，召回 {} 个结果", vectorResults.size());

        // 2. 关键词检索（稀疏检索/BM25）
        List<ChannelResult> keywordResults = sparseRetrieval(query, maxResults * 2);
        log.debug("稀疏检索完成，召回 {} 个结果", keywordResults.size());

        // 3. 融合排序
        List<FusedResult> fusedResults;
        if (fusionStrategy == FusionStrategy.RRF) {
            fusedResults = reciprocalRankFusion(vectorResults, keywordResults, maxResults);
        } else {
            fusedResults = weightedLinearCombination(vectorResults, keywordResults, maxResults);
        }

        // 4. 构建可解释性分析
        Explanation explanation = buildExplanation(query, vectorResults, keywordResults, fusedResults);

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("高级混合检索完成，返回 {} 个结果，总耗时 {}ms", fusedResults.size(), processingTime);

        return HybridSearchResult.builder()
                .query(query)
                .results(fusedResults)
                .explanation(explanation)
                .processingTimeMs(processingTime)
                .build();
    }

    /**
     * 稠密检索（向量语义检索）
     */
    private List<ChannelResult> denseRetrieval(String query, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                queryEmbedding, topK, minScore);

        List<ChannelResult> results = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            results.add(ChannelResult.builder()
                    .id("dense_" + i)
                    .content(Content.from(match.embedded()))
                    .text(match.embedded().text())
                    .score(match.score())
                    .rank(i + 1)
                    .channel("dense")
                    .build());
        }

        return results;
    }

    /**
     * 稀疏检索（BM25关键词检索）
     */
    private List<ChannelResult> sparseRetrieval(String query, int topK) {
        if (keywordIndex.isEmpty()) {
            log.warn("关键词索引为空，跳过稀疏检索");
            return new ArrayList<>();
        }

        // 中文分词
        List<String> queryTerms = chineseTokenize(query);
        log.debug("查询分词结果: {}", queryTerms);

        // 计算每个文档的BM25分数
        Map<String, Double> docScores = new HashMap<>();
        Map<String, Set<String>> matchedTerms = new HashMap<>();

        for (String term : queryTerms) {
            List<DocumentFrequency> postings = keywordIndex.get(term.toLowerCase());
            if (postings == null) continue;

            // IDF计算（BM25公式）
            double idf = Math.log(1 + (totalDocuments - postings.size() + 0.5)
                    / (postings.size() + 0.5));

            for (DocumentFrequency df : postings) {
                String docId = df.getDocumentId();
                int termFreq = df.getFrequency();

                int docLength = documentLengths.getOrDefault(docId, 0);

                // 完整的BM25公式
                double tf = (termFreq * (bm25K1 + 1))
                        / (termFreq + bm25K1 * (1 - bm25B + bm25B * docLength / avgDocLength));

                double score = idf * tf;
                docScores.merge(docId, score, Double::sum);
                matchedTerms.computeIfAbsent(docId, k -> new HashSet<>()).add(term);
            }
        }

        // 排序并返回结果
        List<Map.Entry<String, Double>> sortedEntries = docScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        List<ChannelResult> results = new ArrayList<>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            Map.Entry<String, Double> entry = sortedEntries.get(i);
            String docId = entry.getKey();
            TextSegment segment = documentStore.get(docId);

            results.add(ChannelResult.builder()
                    .id(docId)
                    .content(Content.from(segment))
                    .text(segment.text())
                    .score(entry.getValue())
                    .rank(i + 1)
                    .channel("sparse")
                    .matchedTerms(new ArrayList<>(matchedTerms.getOrDefault(docId, Set.of())))
                    .build());
        }

        return results;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合排序
     * 公式: RRF_score(d) = Σ(1 / (rank_i(d) + k))
     */
    private List<FusedResult> reciprocalRankFusion(
            List<ChannelResult> denseResults,
            List<ChannelResult> sparseResults,
            int maxResults) {

        Map<String, MutableFusedResult> resultMap = new HashMap<>();

        // 处理稠密检索结果
        for (ChannelResult result : denseResults) {
            String text = result.getText();
            double rrfScore = vectorWeight * (1.0 / (rrfK + result.getRank()));

            MutableFusedResult mutable = resultMap.computeIfAbsent(text, k -> new MutableFusedResult());
            mutable.text = text;
            mutable.content = result.getContent();
            mutable.denseRank = result.getRank();
            mutable.denseScore = result.getScore();
            mutable.rrfScore = (mutable.rrfScore == null ? 0 : mutable.rrfScore) + rrfScore;
        }

        // 处理稀疏检索结果
        for (ChannelResult result : sparseResults) {
            String text = result.getText();
            double rrfScore = keywordWeight * (1.0 / (rrfK + result.getRank()));

            MutableFusedResult mutable = resultMap.computeIfAbsent(text, k -> new MutableFusedResult());
            mutable.text = text;
            if (mutable.content == null) {
                mutable.content = result.getContent();
            }
            mutable.sparseRank = result.getRank();
            mutable.sparseScore = result.getScore();
            mutable.matchedTerms = result.getMatchedTerms();
            mutable.rrfScore = (mutable.rrfScore == null ? 0 : mutable.rrfScore) + rrfScore;
        }

        // 转换为FusedResult列表
        List<FusedResult> results = resultMap.values().stream()
                .map(m -> {
                    int dRank = m.denseRank != null ? m.denseRank : 999;
                    int sRank = m.sparseRank != null ? m.sparseRank : 999;
                    double finalScore = (vectorWeight / (rrfK + dRank)) + (keywordWeight / (rrfK + sRank));

                    return FusedResult.builder()
                            .text(m.text)
                            .content(m.content)
                            .denseRank(dRank)
                            .denseScore(m.denseScore)
                            .sparseRank(sRank)
                            .sparseScore(m.sparseScore)
                            .matchedTerms(m.matchedTerms)
                            .finalScore(finalScore)
                            .build();
                })
                .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());

        // 设置最终排名
        for (int i = 0; i < results.size(); i++) {
            FusedResult r = results.get(i);
            results.set(i, FusedResult.builder()
                    .text(r.getText())
                    .content(r.getContent())
                    .denseRank(r.getDenseRank())
                    .denseScore(r.getDenseScore())
                    .sparseRank(r.getSparseRank())
                    .sparseScore(r.getSparseScore())
                    .matchedTerms(r.getMatchedTerms())
                    .finalScore(r.getFinalScore())
                    .finalRank(i + 1)
                    .build());
        }

        return results;
    }

    /**
     * 加权线性组合融合
     * 公式: Hybrid_score = α * Dense_score + (1-α) * Sparse_score
     */
    private List<FusedResult> weightedLinearCombination(
            List<ChannelResult> denseResults,
            List<ChannelResult> sparseResults,
            int maxResults) {

        // 归一化分数到 [0, 1] 区间
        Map<String, Double> denseNorm = normalizeScores(denseResults);
        Map<String, Double> sparseNorm = normalizeScores(sparseResults);

        // 构建融合结果
        Set<String> allDocs = new HashSet<>();
        allDocs.addAll(denseNorm.keySet());
        allDocs.addAll(sparseNorm.keySet());

        List<FusedResult> results = new ArrayList<>();

        for (String text : allDocs) {
            double denseScore = denseNorm.getOrDefault(text, 0.0);
            double sparseScore = sparseNorm.getOrDefault(text, 0.0);

            // 加权组合
            double finalScore = vectorWeight * denseScore + keywordWeight * sparseScore;

            // 获取原始结果
            ChannelResult denseResult = denseResults.stream()
                    .filter(r -> r.getText().equals(text))
                    .findFirst()
                    .orElse(null);
            ChannelResult sparseResult = sparseResults.stream()
                    .filter(r -> r.getText().equals(text))
                    .findFirst()
                    .orElse(null);

            Content content = denseResult != null ? denseResult.getContent()
                    : (sparseResult != null ? sparseResult.getContent() : null);

            Integer denseRank = denseResult != null ? denseResult.getRank() : 999;
            Double denseOrigScore = denseResult != null ? denseResult.getScore() : null;
            Integer sparseRank = sparseResult != null ? sparseResult.getRank() : 999;
            Double sparseOrigScore = sparseResult != null ? sparseResult.getScore() : null;
            List<String> matchedTerms = sparseResult != null ? sparseResult.getMatchedTerms() : null;

            results.add(FusedResult.builder()
                    .text(text)
                    .content(content)
                    .finalScore(finalScore)
                    .denseRank(denseRank)
                    .denseScore(denseOrigScore)
                    .sparseRank(sparseRank)
                    .sparseScore(sparseOrigScore)
                    .matchedTerms(matchedTerms)
                    .build());
        }

        // 排序并设置排名
        results.sort(Comparator.comparingDouble(FusedResult::getFinalScore).reversed());

        List<FusedResult> finalResults = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, results.size()); i++) {
            FusedResult r = results.get(i);
            finalResults.add(FusedResult.builder()
                    .text(r.getText())
                    .content(r.getContent())
                    .finalScore(r.getFinalScore())
                    .finalRank(i + 1)
                    .denseRank(r.getDenseRank())
                    .denseScore(r.getDenseScore())
                    .sparseRank(r.getSparseRank())
                    .sparseScore(r.getSparseScore())
                    .matchedTerms(r.getMatchedTerms())
                    .build());
        }

        return finalResults;
    }

    /**
     * Min-Max 归一化分数到 [0, 1]
     */
    private Map<String, Double> normalizeScores(List<ChannelResult> results) {
        if (results.isEmpty()) return Map.of();

        double maxScore = results.stream()
                .mapToDouble(ChannelResult::getScore)
                .max()
                .orElse(1.0);
        double minScore = results.stream()
                .mapToDouble(ChannelResult::getScore)
                .min()
                .orElse(0.0);

        double range = maxScore - minScore;
        if (range == 0) range = 1;

        final double finalRange = range;
        final double finalMin = minScore;

        return results.stream()
                .collect(Collectors.toMap(
                        ChannelResult::getText,
                        r -> (r.getScore() - finalMin) / finalRange
                ));
    }

    /**
     * 中文分词（简单实现，支持中英文）
     */
    private List<String> chineseTokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> tokens = new ArrayList<>();

        // 1. 按空格分割（处理英文单词）
        String[] parts = text.split("\\s+");

        for (String part : parts) {
            if (part.matches("[a-zA-Z]+")) {
                // 纯英文单词
                if (part.length() > 1) {
                    tokens.add(part.toLowerCase());
                }
            } else {
                // 中文或混合内容，按字符分割并提取词语
                tokens.addAll(extractChineseTerms(part));
            }
        }

        return tokens.stream()
                .filter(s -> s.length() >= 2)  // 过滤单字符
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 提取中文词语（简单实现）
     */
    private List<String> extractChineseTerms(String text) {
        List<String> terms = new ArrayList<>();

        // 移除标点符号
        String cleaned = text.replaceAll("[^\\w\\s\\u4e00-\\u9fa5]", " ");

        // 添加2-4字的滑动窗口
        for (int len = 2; len <= 4 && len <= cleaned.length(); len++) {
            for (int i = 0; i <= cleaned.length() - len; i++) {
                String term = cleaned.substring(i, i + len).trim();
                if (term.length() == len && term.matches(".*[\\u4e00-\\u9fa5].*")) {
                    terms.add(term.toLowerCase());
                }
            }
        }

        return terms;
    }

    /**
     * 构建可解释性分析
     */
    private Explanation buildExplanation(String query,
                                          List<ChannelResult> denseResults,
                                          List<ChannelResult> sparseResults,
                                          List<FusedResult> fusedResults) {
        return Explanation.builder()
                .query(query)
                .fusionStrategy(fusionStrategy.name())
                .vectorWeight(vectorWeight)
                .keywordWeight(keywordWeight)
                .rrfK(rrfK)
                .denseRetrievalCount(denseResults.size())
                .sparseRetrievalCount(sparseResults.size())
                .finalResultCount(fusedResults.size())
                .analysis(generateAnalysis(fusedResults))
                .build();
    }

    private String generateAnalysis(List<FusedResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("混合检索分析:\n");

        int denseOnly = 0, sparseOnly = 0, both = 0;
        for (FusedResult r : results) {
            if (r.getDenseRank() != null && r.getDenseRank() < 999) {
                if (r.getSparseRank() != null && r.getSparseRank() < 999) {
                    both++;
                } else {
                    denseOnly++;
                }
            } else if (r.getSparseRank() != null && r.getSparseRank() < 999) {
                sparseOnly++;
            }
        }

        sb.append(String.format("- 仅稠密检索: %d, 仅稀疏检索: %d, 两者共同: %d%n",
                denseOnly, sparseOnly, both));
        sb.append(String.format("- 融合策略: %s, 向量权重: %.2f, 关键词权重: %.2f%n",
                fusionStrategy, vectorWeight, keywordWeight));

        return sb.toString();
    }

    /**
     * 添加文档到索引
     */
    public void indexDocument(String docId, TextSegment segment) {
        documentStore.put(docId, segment);
        int length = segment.text().length();
        documentLengths.put(docId, length);

        // 更新平均长度
        totalDocuments++;
        avgDocLength = documentLengths.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0);

        // 分词并建立倒排索引
        List<String> terms = chineseTokenize(segment.text());
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
     * 获取检索器统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDocuments", totalDocuments);
        stats.put("avgDocLength", avgDocLength);
        stats.put("keywordIndexSize", keywordIndex.size());
        stats.put("fusionStrategy", fusionStrategy.name());
        stats.put("vectorWeight", vectorWeight);
        stats.put("keywordWeight", keywordWeight);
        stats.put("rrfK", rrfK);
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
        private String text;
        private Content content;
        private Double finalScore;
        private Integer finalRank;
        private Integer denseRank;
        private Double denseScore;
        private Integer sparseRank;
        private Double sparseScore;
        private List<String> matchedTerms;
    }

    @Data
    @Builder
    public static class ChannelResult {
        private String id;
        private String text;
        private Content content;
        private Double score;
        private Integer rank;
        private String channel;
        private List<String> matchedTerms;
    }

    @Data
    @Builder
    public static class Explanation {
        private String query;
        private String fusionStrategy;
        private double vectorWeight;
        private double keywordWeight;
        private int rrfK;
        private int denseRetrievalCount;
        private int sparseRetrievalCount;
        private int finalResultCount;
        private String analysis;
    }

    @Data
    @AllArgsConstructor
    private static class DocumentFrequency {
        private String documentId;
        private int frequency;
    }

    /**
     * 可变的融合结果（用于构建过程中）
     */
    private static class MutableFusedResult {
        String text;
        Content content;
        Integer denseRank;
        Double denseScore;
        Integer sparseRank;
        Double sparseScore;
        List<String> matchedTerms;
        Double rrfScore;
    }
}
