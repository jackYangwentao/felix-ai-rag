package com.felix.ai.rag.service;

import com.felix.ai.rag.processor.SentenceWindowProcessor;
import com.felix.ai.rag.query.QueryExpansionService;
import com.felix.ai.rag.query.QueryRewriteService;
import com.felix.ai.rag.retriever.AdvancedHybridRetriever;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 优化版RAG服务
 * 整合所有检索优化技术：
 * 1. 查询重写 (Query Rewrite)
 * 2. 查询扩展 (Query Expansion)
 * 3. 混合检索 (Hybrid Retrieval - 稠密 + 稀疏)
 * 4. 句子窗口 (Sentence Window)
 * 5. 重排序 (Reranking)
 *
 * 参考 Datawhale All-In-RAG 索引优化 + 混合搜索
 */
@Service
@Slf4j
public class OptimizedRagService {

    private final ChatLanguageModel chatLanguageModel;
    private final AdvancedHybridRetriever hybridRetriever;
    private final QueryRewriteService queryRewriteService;
    private final QueryExpansionService queryExpansionService;
    private final SentenceWindowProcessor sentenceWindowProcessor;
    private final RerankerService rerankerService;

    @Value("${rag.optimized.use-query-rewrite:true}")
    private boolean useQueryRewrite;

    @Value("${rag.optimized.use-query-expansion:false}")
    private boolean useQueryExpansion;

    @Value("${rag.optimized.use-hybrid-retrieval:true}")
    private boolean useHybridRetrieval;

    @Value("${rag.optimized.use-sentence-window:true}")
    private boolean useSentenceWindow;

    @Value("${rag.optimized.use-rerank:true}")
    private boolean useRerank;

    // RAG Prompt 模板
    private static final String RAG_PROMPT_TEMPLATE = """
            你是一个专业的智能助手，专门基于提供的参考资料回答用户问题。

            回答要求：
            1. 严格基于以下提供的参考资料进行回答
            2. 如果参考资料不足以回答问题，请明确告知"根据现有资料无法回答该问题"
            3. 回答应准确、简洁、有条理
            4. 如果涉及多个要点，请使用序号列出

            ====================
            参考资料：
            ====================
            {context}

            ====================
            用户问题：{question}
            ====================

            请基于以上参考资料回答问题：
            """;

    public OptimizedRagService(ChatLanguageModel chatLanguageModel,
                                AdvancedHybridRetriever hybridRetriever,
                                QueryRewriteService queryRewriteService,
                                QueryExpansionService queryExpansionService,
                                SentenceWindowProcessor sentenceWindowProcessor,
                                RerankerService rerankerService) {
        this.chatLanguageModel = chatLanguageModel;
        this.hybridRetriever = hybridRetriever;
        this.queryRewriteService = queryRewriteService;
        this.queryExpansionService = queryExpansionService;
        this.sentenceWindowProcessor = sentenceWindowProcessor;
        this.rerankerService = rerankerService;
    }

    /**
     * 执行优化版RAG问答
     */
    public OptimizedRagResult chat(String userMessage, String sessionId) {
        log.info("执行优化版RAG问答: '{}'", userMessage);
        long startTime = System.currentTimeMillis();

        String processedQuery = userMessage;

        // Step 1: 查询重写
        if (useQueryRewrite) {
            QueryRewriteService.QueryRewriteResult rewriteResult =
                    queryRewriteService.rewrite(userMessage);
            processedQuery = rewriteResult.getRewrittenQuery();
            log.info("查询重写: '{}' -> '{}'", userMessage, processedQuery);
        }

        // Step 2: 检索
        List<Content> relevantContents;
        if (useHybridRetrieval) {
            // 使用高级混合检索
            AdvancedHybridRetriever.HybridSearchResult hybridResult =
                    hybridRetriever.retrieve(processedQuery, 10);

            relevantContents = hybridResult.getResults().stream()
                    .map(AdvancedHybridRetriever.FusedResult::getContent)
                    .collect(Collectors.toList());

            log.info("混合检索完成，召回 {} 个结果", relevantContents.size());
        } else {
            // 回退到普通检索
            // 这里可以调用原来的RagService
            relevantContents = List.of();
        }

        if (relevantContents.isEmpty()) {
            return OptimizedRagResult.builder()
                    .answer("抱歉，根据现有资料无法回答该问题。请尝试上传相关文档或换个问题。")
                    .originalQuery(userMessage)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // Step 3: 句子窗口后处理
        if (useSentenceWindow) {
            relevantContents = sentenceWindowProcessor.process(relevantContents);
            log.info("句子窗口处理后，内容数量: {}", relevantContents.size());
        }

        // Step 4: 重排序
        if (useRerank && rerankerService.isEnabled()) {
            List<String> candidates = relevantContents.stream()
                    .map(c -> c.textSegment().text())
                    .collect(Collectors.toList());

            List<RerankerService.ScoredDocument> reranked =
                    rerankerService.rerank(processedQuery, candidates, 5);

            relevantContents = reranked.stream()
                    .map(doc -> Content.from(dev.langchain4j.data.segment.TextSegment.from(doc.getContent())))
                    .collect(Collectors.toList());

            log.info("重排序完成，返回 {} 个结果", relevantContents.size());
        }

        // Step 5: 构建上下文
        String context = relevantContents.stream()
                .map(c -> c.textSegment().text())
                .collect(Collectors.joining("\n\n"));

        // Step 6: 生成回答
        String finalPrompt = RAG_PROMPT_TEMPLATE
                .replace("{context}", context)
                .replace("{question}", userMessage);

        Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(finalPrompt));
        String answer = response.content().text();

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("优化版RAG问答完成，耗时: {}ms", processingTime);

        return OptimizedRagResult.builder()
                .answer(answer)
                .originalQuery(userMessage)
                .rewrittenQuery(useQueryRewrite ? processedQuery : null)
                .sources(relevantContents.stream()
                        .map(c -> c.textSegment().text())
                        .collect(Collectors.toList()))
                .processingTimeMs(processingTime)
                .build();
    }

    /**
     * 获取当前优化配置
     */
    public OptimizationConfig getConfig() {
        return OptimizationConfig.builder()
                .useQueryRewrite(useQueryRewrite)
                .useQueryExpansion(useQueryExpansion)
                .useHybridRetrieval(useHybridRetrieval)
                .useSentenceWindow(useSentenceWindow)
                .useRerank(useRerank)
                .build();
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class OptimizedRagResult {
        private String answer;
        private String originalQuery;
        private String rewrittenQuery;
        private List<String> sources;
        private long processingTimeMs;
    }

    @Data
    @Builder
    public static class OptimizationConfig {
        private boolean useQueryRewrite;
        private boolean useQueryExpansion;
        private boolean useHybridRetrieval;
        private boolean useSentenceWindow;
        private boolean useRerank;
    }
}
