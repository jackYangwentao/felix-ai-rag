package com.felix.ai.rag.controller;

import com.felix.ai.rag.dto.ChatRequest;
import com.felix.ai.rag.dto.ChatResponse;
import com.felix.ai.rag.service.OptimizedRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 优化版RAG控制器
 * 整合所有检索优化技术的API
 */
@RestController
@RequestMapping("/api/v1/rag/optimized")
@RequiredArgsConstructor
@Slf4j
public class OptimizedRagController {

    private final OptimizedRagService optimizedRagService;

    /**
     * 优化版RAG问答
     * 整合：查询重写 + 混合检索 + 句子窗口 + 重排序
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> optimizedChat(@RequestBody ChatRequest request) {
        log.info("收到优化版RAG聊天请求");

        long startTime = System.currentTimeMillis();
        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : java.util.UUID.randomUUID().toString();

        OptimizedRagService.OptimizedRagResult result =
                optimizedRagService.chat(request.getMessage(), sessionId);

        ChatResponse response = ChatResponse.builder()
                .answer(result.getAnswer())
                .sessionId(sessionId)
                .sources(result.getSources())
                .processingTimeMs(result.getProcessingTimeMs())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 获取当前优化配置
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        OptimizedRagService.OptimizationConfig config = optimizedRagService.getConfig();

        Map<String, Object> response = new HashMap<>();
        response.put("useQueryRewrite", config.isUseQueryRewrite());
        response.put("useQueryExpansion", config.isUseQueryExpansion());
        response.put("useHybridRetrieval", config.isUseHybridRetrieval());
        response.put("useSentenceWindow", config.isUseSentenceWindow());
        response.put("useRerank", config.isUseRerank());

        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("useQueryRewrite", "查询重写 - 使用LLM优化查询表述");
        descriptions.put("useQueryExpansion", "查询扩展 - 生成多个查询变体提高召回");
        descriptions.put("useHybridRetrieval", "混合检索 - 稠密向量 + 稀疏关键词检索");
        descriptions.put("useSentenceWindow", "句子窗口 - 小块检索，大块生成");
        descriptions.put("useRerank", "重排序 - 使用LLM对结果重排序");

        response.put("descriptions", descriptions);

        return ResponseEntity.ok(response);
    }

    /**
     * 优化技术说明
     */
    @GetMapping("/docs")
    public ResponseEntity<Map<String, Object>> getOptimizationDocs() {
        Map<String, Object> response = new HashMap<>();

        Map<String, String> optimizations = new HashMap<>();
        optimizations.put("查询重写", "分析查询意图，将口语化查询改写为更清晰的检索式");
        optimizations.put("混合检索", "结合稠密向量检索（语义理解）和稀疏关键词检索（精确匹配）");
        optimizations.put("RRF融合", "使用Reciprocal Rank Fusion融合多路召回结果，无需归一化");
        optimizations.put("句子窗口", "索引时切分为句子，检索后扩展为完整窗口，平衡精度与上下文");
        optimizations.put("重排序", "使用LLM评估结果相关性，提升排序质量");
        optimizations.put("查询扩展", "生成多个查询变体，使用HyDE生成假设文档");

        response.put("optimizations", optimizations);

        Map<String, String> scenarios = new HashMap<>();
        scenarios.put("精确术语搜索", "建议：提高关键词检索权重，使用WEIGHTED_SUM策略");
        scenarios.put("语义理解搜索", "建议：提高向量检索权重，启用查询重写");
        scenarios.put("长文档问答", "建议：启用句子窗口，提高上下文连贯性");
        scenarios.put("多主题文档", "建议：启用多样性重排序（MMR），避免结果单一");

        response.put("scenarios", scenarios);

        return ResponseEntity.ok(response);
    }
}
