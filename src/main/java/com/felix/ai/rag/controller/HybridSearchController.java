package com.felix.ai.rag.controller;

import com.felix.ai.rag.retriever.AdvancedHybridRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合搜索控制器
 * 提供高级混合检索API
 *
 * 参考 Datawhale All-In-RAG 混合搜索章节
 */
@RestController
@RequestMapping("/api/v1/rag/hybrid")
@RequiredArgsConstructor
@Slf4j
public class HybridSearchController {

    private final AdvancedHybridRetriever advancedHybridRetriever;

    /**
     * 执行高级混合检索
     * 结合稠密向量检索和稀疏关键词检索
     *
     * @param query 查询文本
     * @param maxResults 最大结果数
     * @param strategy 融合策略 (RRF 或 WEIGHTED_SUM)
     * @return 混合检索结果
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> hybridSearch(
            @RequestParam("query") String query,
            @RequestParam(value = "maxResults", required = false, defaultValue = "5") int maxResults,
            @RequestParam(value = "strategy", required = false) String strategy) {

        log.info("收到高级混合检索请求: '{}', strategy={}", query, strategy);

        long startTime = System.currentTimeMillis();

        // 执行混合检索
        AdvancedHybridRetriever.HybridSearchResult result =
                advancedHybridRetriever.retrieve(query, maxResults);

        // 构建响应
        Map<String, Object> response = new HashMap<>();
        response.put("query", result.getQuery());
        response.put("processingTimeMs", result.getProcessingTimeMs());

        // 结果列表
        List<Map<String, Object>> results = result.getResults().stream()
                .map(r -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("rank", r.getFinalRank());
                    item.put("text", r.getText());
                    item.put("finalScore", r.getFinalScore());
                    item.put("denseRank", r.getDenseRank());
                    item.put("denseScore", r.getDenseScore());
                    item.put("sparseRank", r.getSparseRank());
                    item.put("sparseScore", r.getSparseScore());
                    item.put("matchedTerms", r.getMatchedTerms());
                    return item;
                })
                .collect(Collectors.toList());

        response.put("results", results);

        // 可解释性分析
        AdvancedHybridRetriever.Explanation explanation = result.getExplanation();
        Map<String, Object> explanationMap = new HashMap<>();
        explanationMap.put("fusionStrategy", explanation.getFusionStrategy());
        explanationMap.put("vectorWeight", explanation.getVectorWeight());
        explanationMap.put("keywordWeight", explanation.getKeywordWeight());
        explanationMap.put("denseRetrievalCount", explanation.getDenseRetrievalCount());
        explanationMap.put("sparseRetrievalCount", explanation.getSparseRetrievalCount());
        explanationMap.put("analysis", explanation.getAnalysis());

        response.put("explanation", explanationMap);

        log.info("混合检索完成，返回 {} 个结果，总耗时 {}ms",
                results.size(), System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 对比检索 - 同时展示不同检索方式的结果
     * 便于理解稠密检索 vs 稀疏检索的差异
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareRetrieval(
            @RequestParam("query") String query,
            @RequestParam(value = "maxResults", required = false, defaultValue = "5") int maxResults) {

        log.info("收到检索对比请求: '{}'", query);

        // 执行混合检索
        AdvancedHybridRetriever.HybridSearchResult hybridResult =
                advancedHybridRetriever.retrieve(query, maxResults);

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);

        // 只提取稠密检索结果（rank < 999 表示有结果）
        List<Map<String, Object>> denseOnly = hybridResult.getResults().stream()
                .filter(r -> r.getDenseRank() != null && r.getDenseRank() < 999)
                .filter(r -> r.getSparseRank() == null || r.getSparseRank() == 999)
                .map(r -> createResultMap(r, "dense"))
                .collect(Collectors.toList());

        // 只提取稀疏检索结果
        List<Map<String, Object>> sparseOnly = hybridResult.getResults().stream()
                .filter(r -> r.getSparseRank() != null && r.getSparseRank() < 999)
                .filter(r -> r.getDenseRank() == null || r.getDenseRank() == 999)
                .map(r -> createResultMap(r, "sparse"))
                .collect(Collectors.toList());

        // 两者都有的结果
        List<Map<String, Object>> both = hybridResult.getResults().stream()
                .filter(r -> r.getDenseRank() != null && r.getDenseRank() < 999)
                .filter(r -> r.getSparseRank() != null && r.getSparseRank() < 999)
                .map(r -> createResultMap(r, "both"))
                .collect(Collectors.toList());

        response.put("denseOnly", denseOnly);
        response.put("sparseOnly", sparseOnly);
        response.put("bothChannels", both);

        // 分析差异
        String analysis = String.format(
                "查询 '%s' 的检索分析：\n" +
                "- 仅语义检索命中: %d 条\n" +
                "- 仅关键词检索命中: %d 条\n" +
                "- 两者共同命中: %d 条\n" +
                "混合检索优势：语义检索能捕捉同义词和语义相似性，关键词检索对精确匹配和专业术语更有效。",
                query, denseOnly.size(), sparseOnly.size(), both.size()
        );
        response.put("analysis", analysis);

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> createResultMap(AdvancedHybridRetriever.FusedResult r, String source) {
        Map<String, Object> map = new HashMap<>();
        map.put("text", r.getText().substring(0, Math.min(100, r.getText().length())) + "...");
        map.put("finalScore", r.getFinalScore());
        map.put("denseRank", r.getDenseRank());
        map.put("sparseRank", r.getSparseRank());
        map.put("matchedTerms", r.getMatchedTerms());
        map.put("source", source);
        return map;
    }

    /**
     * 获取检索器统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = advancedHybridRetriever.getStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * 检索策略说明
     */
    @GetMapping("/strategies")
    public ResponseEntity<Map<String, Object>> getStrategies() {
        Map<String, Object> response = new HashMap<>();

        Map<String, String> strategies = new HashMap<>();
        strategies.put("RRF", "Reciprocal Rank Fusion - 基于排名的融合，对异常值鲁棒，无需归一化");
        strategies.put("WEIGHTED_SUM", "Weighted Linear Combination - 加权线性组合，可精细控制语义vs关键词权重");

        response.put("availableStrategies", strategies);

        Map<String, String> recommendations = new HashMap<>();
        recommendations.put("电商搜索", "WEIGHTED_SUM with keyword-weight=0.7 - 侧重关键词匹配，确保型号精确");
        recommendations.put("智能问答", "WEIGHTED_SUM with vector-weight=0.7 - 侧重语义理解，捕捉用户意图");
        recommendations.put("通用搜索", "RRF - 平衡两者，无需调参");

        response.put("recommendations", recommendations);

        return ResponseEntity.ok(response);
    }
}
