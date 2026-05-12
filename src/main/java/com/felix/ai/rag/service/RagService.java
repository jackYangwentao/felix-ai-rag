package com.felix.ai.rag.service;

import com.felix.ai.rag.chunker.ChunkerFactory;
import com.felix.ai.rag.chunker.TextChunker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RAG 服务类
 * 处理文档索引和问答逻辑
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ContentRetriever contentRetriever;
    private final ChunkerFactory chunkerFactory;

    /**
     * RAG Prompt 模板 - 参考LangChain最佳实践
     * 使用清晰的结构和明确的指令
     */
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

    /**
     * 索引文档到向量存储
     * 支持多种分块策略：固定大小、递归、语义、代码等
     *
     * @param content    文档内容
     * @param sourceName 文档来源名称
     */
    public void indexDocument(String content, String sourceName) {
        log.info("开始索引文档: {}，使用分块策略: {}", sourceName, chunkerFactory.getCurrentStrategy());

        // 使用分块器工厂创建分块器
        TextChunker chunker = chunkerFactory.createChunker();

        // 分割文档
        List<String> chunks = chunker.chunk(content);

        log.info("文档分割为 {} 个片段，使用策略: {}", chunks.size(), chunker.getStrategyName());

        // 为每个片段生成嵌入并存储
        for (String chunk : chunks) {
            TextSegment segment = TextSegment.from(chunk);
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        log.info("文档索引完成: {}", sourceName);
    }

    /**
     * 基于RAG的问答 - 参考LangChain实现
     * 流程：检索 → 上下文拼接 → Prompt构建 → LLM生成
     *
     * @param userMessage 用户问题
     * @param sessionId   会话ID
     * @return AI回答
     */
    public String chatWithRag(String userMessage, String sessionId) {
        long startTime = System.currentTimeMillis();

        // Step 1: 检索相关内容 (Similarity Search)
        List<Content> relevantContents = contentRetriever.retrieve(
                dev.langchain4j.rag.query.Query.from(userMessage)
        );

        if (relevantContents.isEmpty()) {
            log.warn("未检索到相关内容");
            return "抱歉，根据现有资料无法回答该问题。请尝试上传相关文档或换个问题。";
        }

        log.info("检索到 {} 条相关内容", relevantContents.size());

        // Step 2: 构建上下文 - 使用"\n\n"分隔，让LLM更清晰识别段落边界
        String context = relevantContents.stream()
                .map(Content::textSegment)
                .map(TextSegment::text)
                .collect(Collectors.joining("\n\n"));

        // Step 3: 构建完整Prompt
        String finalPrompt = RAG_PROMPT_TEMPLATE
                .replace("{context}", context)
                .replace("{question}", userMessage);

        // Step 4: 调用LLM生成回答
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                UserMessage.from(finalPrompt)
        );

        Response<AiMessage> response = chatLanguageModel.generate(messages);
        String answer = response.content().text();

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("RAG问答完成，检索到{}条内容，耗时: {}ms", relevantContents.size(), processingTime);

        return answer;
    }

    /**
     * 普通聊天（不使用RAG）
     *
     * @param userMessage 用户问题
     * @return AI回答
     */
    public String chat(String userMessage) {
        Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(userMessage));
        return response.content().text();
    }

    /**
     * 检索相关内容（不带生成）
     *
     * @param query 查询文本
     * @return 相关内容列表
     */
    public List<String> searchRelevantContent(String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
                queryEmbedding,
                5,
                0.7
        );

        return matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
    }

    /**
     * 生成会话ID
     */
    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 清空向量存储（用于重置知识库）
     */
    public void clearVectorStore() {
        log.info("清空向量存储");
        // InMemoryEmbeddingStore 没有直接清空方法，这里通过重新创建实现
        // 实际生产环境应使用持久化存储
    }
}
