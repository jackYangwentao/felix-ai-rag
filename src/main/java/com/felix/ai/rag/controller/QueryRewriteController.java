package com.felix.ai.rag.controller;

import com.felix.ai.rag.query.AdvancedQueryRewriteService;
import com.felix.ai.rag.query.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 查询重写控制器
 * 提供查询重写相关的API接口
 *
 * 参考 Datawhale All-In-RAG 查询重写章节
 */
@RestController
@RequestMapping("/api/v1/rag/query-rewrite")
@RequiredArgsConstructor
@Slf4j
public class QueryRewriteController {

    private final QueryRewriteService queryRewriteService;
    private final AdvancedQueryRewriteService advancedQueryRewriteService;

    /**
     * 基础查询重写
     * 意图识别 + 查询优化
     */
    @PostMapping("/basic")
    public ResponseEntity<Map<String, Object>> basicRewrite(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("基础查询重写: '{}'", query);
        long startTime = System.currentTimeMillis();

        QueryRewriteService.QueryRewriteResult result = queryRewriteService.rewrite(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("rewrittenQuery", result.getRewrittenQuery());
        response.put("intent", result.getIntent() != null ? result.getIntent().getType().name() : "UNKNOWN");
        response.put("confidence", result.getIntent() != null ? result.getIntent().getConfidence() : 0);
        response.put("keywords", result.getKeywords());
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 结构化查询分析
     * 检测排序/比较类查询，生成结构化指令
     *
     * 示例：
     * 输入: "时间最短的视频"
     * 输出: {"isStructured": true, "sortBy": "length", "order": "asc"}
     */
    @PostMapping("/structured")
    public ResponseEntity<Map<String, Object>> analyzeStructuredQuery(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("结构化查询分析: '{}'", query);
        long startTime = System.currentTimeMillis();

        AdvancedQueryRewriteService.StructuredInstruction instruction =
                advancedQueryRewriteService.analyzeStructuredQuery(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", query);
        response.put("isStructured", instruction.isStructured());

        if (instruction.isStructured()) {
            response.put("sortBy", instruction.getSortBy());
            response.put("order", instruction.getOrder());
            response.put("limit", instruction.getLimit());
            response.put("description", String.format("按%s %s排序，取前%d个",
                    instruction.getSortBy(),
                    instruction.getOrder().equals("asc") ? "升序" : "降序",
                    instruction.getLimit()));
        }

        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 多查询分解
     * 将复杂查询拆分为多个子查询
     *
     * 示例：
     * 输入: "在《流浪地球》中，刘慈欣对人工智能和未来社会结构有何看法？"
     * 输出: ["《流浪地球》中描述的人工智能技术有哪些？", "《流浪地球》中描绘的未来社会结构是怎样的？", ...]
     */
    @PostMapping("/multi-query")
    public ResponseEntity<Map<String, Object>> decomposeQuery(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("多查询分解: '{}'", query);
        long startTime = System.currentTimeMillis();

        AdvancedQueryRewriteService.MultiQueryResult result =
                advancedQueryRewriteService.decomposeQuery(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("subQueries", result.getSubQueries());
        response.put("subQueryCount", result.getSubQueries().size());
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 退步提示（Step-Back Prompting）
     * 生成更抽象的通用原理问题
     *
     * 示例：
     * 输入: "在一个密闭容器中，加热气体后压力会如何变化？"
     * 输出: "气体状态变化遵循什么物理定律？"
     */
    @PostMapping("/step-back")
    public ResponseEntity<Map<String, Object>> stepBackPrompt(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("退步提示: '{}'", query);
        long startTime = System.currentTimeMillis();

        AdvancedQueryRewriteService.StepBackResult result =
                advancedQueryRewriteService.stepBackPrompt(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("stepBackQuestion", result.getStepBackQuestion());
        response.put("hasStepBack", result.getStepBackQuestion() != null);
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        if (result.getStepBackQuestion() != null) {
            response.put("strategy", "先检索通用原理，再结合原问题推理");
        } else {
            response.put("strategy", "问题不需要退步处理，直接检索");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * HyDE（Hypothetical Document Embeddings）
     * 生成假设性答案文档用于检索
     */
    @PostMapping("/hyde")
    public ResponseEntity<Map<String, Object>> generateHyde(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("HyDE生成: '{}'", query);
        long startTime = System.currentTimeMillis();

        AdvancedQueryRewriteService.HydeResult result =
                advancedQueryRewriteService.generateHypotheticalDocument(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("hasHypotheticalDoc", result.getHypotheticalDocument() != null);
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        if (result.getHypotheticalDocument() != null) {
            response.put("hypotheticalDocument", result.getHypotheticalDocument());
            response.put("documentLength", result.getHypotheticalDocument().length());
            response.put("strategy", "使用假设文档的向量进行语义检索");
        } else {
            response.put("strategy", "HyDE未启用或生成失败，使用原查询");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 综合查询重写
     * 应用所有适用的重写技术
     */
    @PostMapping("/comprehensive")
    public ResponseEntity<Map<String, Object>> comprehensiveRewrite(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("综合查询重写: '{}'", query);

        AdvancedQueryRewriteService.ComprehensiveRewriteResult result =
                advancedQueryRewriteService.comprehensiveRewrite(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("mainQuery", result.getMainQuery());

        // 结构化指令
        if (result.getStructuredInstruction() != null && result.getStructuredInstruction().isStructured()) {
            Map<String, Object> structured = new HashMap<>();
            structured.put("isStructured", true);
            structured.put("sortBy", result.getStructuredInstruction().getSortBy());
            structured.put("order", result.getStructuredInstruction().getOrder());
            response.put("structuredInstruction", structured);
        }

        // 子查询
        if (result.getMultiQuery() != null) {
            response.put("subQueries", result.getMultiQuery().getSubQueries());
        }

        // 退步问题
        if (result.getStepBack() != null && result.getStepBack().getStepBackQuestion() != null) {
            response.put("stepBackQuestion", result.getStepBack().getStepBackQuestion());
        }

        // HyDE
        if (result.getHyde() != null && result.getHyde().getHypotheticalDocument() != null) {
            Map<String, Object> hyde = new HashMap<>();
            hyde.put("documentLength", result.getHyde().getHypotheticalDocument().length());
            hyde.put("preview", result.getHyde().getHypotheticalDocument().substring(
                    0, Math.min(100, result.getHyde().getHypotheticalDocument().length())) + "...");
            response.put("hyde", hyde);
        }

        // 所有用于检索的查询
        response.put("allRetrievalQueries", result.getAllQueriesForRetrieval());

        // 策略总结
        List<String> strategies = new java.util.ArrayList<>();
        if (result.requiresStructuredProcessing()) strategies.add("结构化处理");
        if (result.getMultiQuery() != null && result.getMultiQuery().getSubQueries().size() > 1) strategies.add("多查询分解");
        if (result.getStepBack() != null && result.getStepBack().getStepBackQuestion() != null) strategies.add("退步提示");
        if (result.usedHyde()) strategies.add("HyDE");
        response.put("appliedStrategies", strategies);

        response.put("processingTimeMs", result.getProcessingTimeMs());

        return ResponseEntity.ok(response);
    }

    /**
     * 获取查询重写技术说明
     */
    @GetMapping("/docs")
    public ResponseEntity<Map<String, Object>> getDocumentation() {
        Map<String, Object> response = new HashMap<>();

        Map<String, Map<String, String>> techniques = new HashMap<>();

        techniques.put("basic", Map.of(
                "name", "基础查询重写",
                "description", "意图识别 + 查询优化",
                "适用场景", "通用场景，改善查询表述",
                "特点", "快速、轻量"
        ));

        techniques.put("structured", Map.of(
                "name", "结构化查询分析",
                "description", "检测排序/比较类查询，生成结构化指令",
                "适用场景", "最值查询（时间最短、播放量最高等）",
                "特点", "支持排序、过滤操作"
        ));

        techniques.put("multiQuery", Map.of(
                "name", "多查询分解",
                "description", "将复杂查询拆分为多个子查询",
                "适用场景", "多主题、复杂条件的问题",
                "特点", "提高召回率，覆盖全面"
        ));

        techniques.put("stepBack", Map.of(
                "name", "退步提示",
                "description", "先抽象原理，再具体推理",
                "适用场景", "需要复杂推理的科学/数学问题",
                "特点", "提高推理准确性"
        ));

        techniques.put("hyde", Map.of(
                "name", "HyDE",
                "description", "生成假设性答案文档用于检索",
                "适用场景", "查询短、文档长，语义鸿沟大",
                "特点", "提升语义匹配质量，增加LLM调用"
        ));

        response.put("techniques", techniques);

        Map<String, String> recommendations = new HashMap<>();
        recommendations.put("精确术语搜索", "基础重写 + 关键词提取");
        recommendations.put("排序/比较查询", "结构化查询分析");
        recommendations.put("复杂多主题问题", "多查询分解");
        recommendations.put("科学推理解题", "退步提示");
        recommendations.put("短查询长文档", "HyDE");
        recommendations.put("通用场景", "综合查询重写（所有技术）");

        response.put("recommendations", recommendations);

        return ResponseEntity.ok(response);
    }

    /**
     * 查询重写示例
     */
    @GetMapping("/examples")
    public ResponseEntity<Map<String, Object>> getExamples() {
        Map<String, Object> response = new HashMap<>();

        Map<String, Map<String, String>> examples = new HashMap<>();

        examples.put("structured", Map.of(
                "input", "时间最短的视频",
                "output", "{\"isStructured\": true, \"sortBy\": \"length\", \"order\": \"asc\"}",
                "technique", "结构化查询分析"
        ));

        examples.put("multiQuery", Map.of(
                "input", "在《流浪地球》中，刘慈欣对人工智能和未来社会结构有何看法？",
                "output", "[\"《流浪地球》中描述的人工智能技术有哪些？\", \"《流浪地球》中描绘的未来社会结构是怎样的？\", \"刘慈欣关于人工智能的观点是什么？\"]",
                "technique", "多查询分解"
        ));

        examples.put("stepBack", Map.of(
                "input", "在一个密闭容器中，加热气体后压力会如何变化？",
                "output", "气体状态变化遵循什么物理定律？（理想气体定律 PV=nRT）",
                "technique", "退步提示"
        ));

        examples.put("hyde", Map.of(
                "input", "什么是RAG？",
                "output", "生成一段假设性的RAG详细介绍文档，用于向量检索",
                "technique", "HyDE"
        ));

        response.put("examples", examples);

        return ResponseEntity.ok(response);
    }
}
