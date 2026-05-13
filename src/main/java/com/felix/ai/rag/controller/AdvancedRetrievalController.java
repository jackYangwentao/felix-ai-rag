package com.felix.ai.rag.controller;

import com.felix.ai.rag.rag.CorrectiveRagService;
import com.felix.ai.rag.retriever.ContextualCompressionRetriever;
import com.felix.ai.rag.service.RagService;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 高级检索控制器
 * 提供上下文压缩、C-RAG等高级检索技术的API接口
 *
 * 参考 Datawhale All-In-RAG 高级检索技术章节
 */
@RestController
@RequestMapping("/api/v1/rag/advanced-retrieval")
@RequiredArgsConstructor
@Slf4j
public class AdvancedRetrievalController {

    private final ContextualCompressionRetriever compressionRetriever;
    private final CorrectiveRagService correctiveRagService;
    private final RagService ragService;

    /**
     * 上下文压缩检索
     * 从文档中提取与查询最相关的内容，去除噪音
     */
    @PostMapping("/compress")
    public ResponseEntity<Map<String, Object>> contextualCompression(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer maxResults = (Integer) request.getOrDefault("maxResults", 5);

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("上下文压缩检索: '{}'", query);
        long startTime = System.currentTimeMillis();

        // 1. 先进行基础检索
        List<Content> initialResults = retrieveContents(query, maxResults * 2);

        // 2. 应用上下文压缩
        ContextualCompressionRetriever.CompressionResult compressionResult =
                compressionRetriever.compress(query, initialResults);

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("originalCount", compressionResult.getOriginalCount());
        response.put("compressedCount", compressionResult.getCompressedCount());
        response.put("filteredCount", compressionResult.getFilteredCount());
        response.put("averageCompressionRatio", String.format("%.2f%%",
                compressionResult.getAverageCompressionRatio() * 100));
        response.put("compressedContents", compressionResult.getCompressedContents().stream()
                .map(c -> Map.of(
                        "compressed", c.getCompressedContent(),
                        "compressionRatio", String.format("%.2f%%", c.getCompressionRatio() * 100)
                ))
                .collect(Collectors.toList()));
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 校正检索 (C-RAG)
     * 自我反思机制：检索-评估-行动
     */
    @PostMapping("/crag")
    public ResponseEntity<Map<String, Object>> correctiveRag(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer maxResults = (Integer) request.getOrDefault("maxResults", 5);

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("C-RAG校正检索: '{}'", query);
        long startTime = System.currentTimeMillis();

        // 1. 初始检索
        List<Content> initialResults = retrieveContents(query, maxResults);

        // 2. 应用C-RAG
        CorrectiveRagService.CragResult cragResult =
                correctiveRagService.retrieve(query, initialResults);

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("assessment", Map.of(
                "grade", cragResult.getAssessment().getGrade(),
                "reason", cragResult.getAssessment().getReason()
        ));
        response.put("actionTaken", cragResult.getActionTaken());
        response.put("finalContentCount", cragResult.getFinalContents().size());
        response.put("finalContents", cragResult.getFinalContents().stream()
                .map(c -> c.textSegment().text().substring(
                        0, Math.min(200, c.textSegment().text().length())) + "...")
                .collect(Collectors.toList()));
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取高级检索技术说明
     */
    @GetMapping("/docs")
    public ResponseEntity<Map<String, Object>> getDocumentation() {
        Map<String, Object> response = new HashMap<>();

        Map<String, Map<String, String>> techniques = new HashMap<>();

        techniques.put("contextualCompression", Map.of(
                "name", "上下文压缩",
                "description", "从文档中提取与查询最相关的部分，去除无关噪音",
                "核心功能", "内容提取 + 文档过滤",
                "适用场景", "检索结果包含大量无关内容",
                "优势", "减少上下文噪音，提高LLM处理效率"
        ));

        techniques.put("correctiveRag", Map.of(
                "name", "校正检索 (C-RAG)",
                "description", "自我反思机制：检索-评估-行动",
                "核心流程", "检索 → 评估(CORRECT/INCORRECT/AMBIGUOUS) → 行动",
                "适用场景", "需要高可靠性答案的场景",
                "优势", "自动检测并修正检索质量问题"
        ));

        techniques.put("parentDocument", Map.of(
                "name", "父文档检索",
                "description", "小块检索匹配，大块提供上下文",
                "核心思想", "细粒度匹配 + 粗粒度上下文",
                "适用场景", "需要精确匹配但又要保持上下文连贯性",
                "优势", "平衡检索精度和上下文丰富性"
        ));

        techniques.put("ensemble", Map.of(
                "name", "集成检索器",
                "description", "组合多个检索器，RRF融合结果",
                "核心机制", "多路召回 + RRF融合排序",
                "适用场景", "需要高召回率的场景",
                "优势", "综合利用多种检索策略的优势"
        ));

        response.put("techniques", techniques);

        Map<String, String> workflow = new HashMap<>();
        workflow.put("上下文压缩", "初始检索 → 相关性判断 → 内容提取 → 压缩结果");
        workflow.put("C-RAG", "初始检索 → 质量评估 → [知识精炼|知识搜索] → 最终结果");
        workflow.put("父文档检索", "小块索引 → 小块检索 → 获取父文档 → 返回完整上下文");
        workflow.put("集成检索", "多检索器并行 → RRF融合 → 排序返回");

        response.put("workflows", workflow);

        return ResponseEntity.ok(response);
    }

    /**
     * 高级检索示例
     */
    @GetMapping("/examples")
    public ResponseEntity<Map<String, Object>> getExamples() {
        Map<String, Object> response = new HashMap<>();

        Map<String, Map<String, String>> examples = new HashMap<>();

        examples.put("contextualCompression", Map.of(
                "scenario", "检索结果包含大量无关内容",
                "before", "文档包含10页内容，其中只有1段相关",
                "after", "自动提取相关段落，去除9页无关内容",
                "benefit", "减少token消耗，提高LLM回答质量"
        ));

        examples.put("correctiveRag", Map.of(
                "scenario", "查询量子计算的最新进展",
                "assessment", "AMBIGUOUS - 检索到的文档信息不完整",
                "action", "触发知识搜索，获取最新信息",
                "benefit", "避免基于过时或不完整信息回答"
        ));

        examples.put("parentDocument", Map.of(
                "scenario", "查询具体法律条款",
                "retrieval", "精确定位到具体条款句子",
                "context", "返回包含该条款的完整法律条文",
                "benefit", "既精确又保持法律条文的完整性"
        ));

        response.put("examples", examples);

        return ResponseEntity.ok(response);
    }

    // ==================== 辅助方法 ====================

    private List<Content> retrieveContents(String query, int maxResults) {
        // 这里简化实现，实际应该调用RagService的检索方法
        // 暂时返回空列表
        return List.of();
    }
}
