package com.felix.ai.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    @Value("${rag.chunk-size:500}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:50}")
    private int chunkOverlap;

    private static final String SYSTEM_PROMPT = """
            你是一个智能助手，专门回答用户问题。
            请基于以下检索到的相关信息来回答问题。
            如果相关信息不足，请明确告知用户。
            回答要简洁、准确、有帮助。

            相关信息：
            {context}
            """;

    /**
     * 索引文档到向量存储
     *
     * @param content    文档内容
     * @param sourceName 文档来源名称
     */
    public void indexDocument(String content, String sourceName) {
        log.info("开始索引文档: {}", sourceName);

        // 创建文档
        Document document = Document.from(content);

        // 分割文档
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);

        log.info("文档分割为 {} 个片段", segments.size());

        // 为每个片段生成嵌入并存储
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        log.info("文档索引完成: {}", sourceName);
    }

    /**
     * 基于RAG的问答
     *
     * @param userMessage 用户问题
     * @param sessionId   会话ID
     * @return AI回答
     */
    public String chatWithRag(String userMessage, String sessionId) {
        long startTime = System.currentTimeMillis();

        // 检索相关内容
        List<Content> relevantContents = contentRetriever.retrieve(
                dev.langchain4j.rag.query.Query.from(userMessage)
        );

        log.info("检索到 {} 条相关内容", relevantContents.size());

        // 构建上下文
        String context = relevantContents.stream()
                .map(Content::textSegment)
                .map(TextSegment::text)
                .collect(Collectors.joining("\n---\n"));

        // 构建系统提示词
        String systemPrompt = SYSTEM_PROMPT.replace("{context}", context);

        // 调用模型
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        );

        Response<AiMessage> response = chatLanguageModel.generate(messages);
        String answer = response.content().text();

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("RAG问答完成，耗时: {}ms", processingTime);

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
}
