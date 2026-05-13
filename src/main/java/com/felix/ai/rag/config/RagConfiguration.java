package com.felix.ai.rag.config;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RAG 配置类
 * 配置 LangChain4J 所需的模型和存储
 * 支持多种向量数据库：memory, redis, chroma, qdrant, pgvector
 */
@Configuration
public class RagConfiguration {

    @Value("${langchain4j.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${langchain4j.ollama.chat-model.model-name:llama3.2}")
    private String chatModelName;

    @Value("${langchain4j.ollama.embedding-model.model-name:nomic-embed-text}")
    private String embeddingModelName;

    /**
     * 作用：控制每次检索时返回的最大文档片段数量。
     * 含义：当用户提出问题后，系统会在向量数据库中搜索相关内容，但只返回相似度排名前 5 的文档片段给大模型（LLM）作为参考上下文。
     * 调优建议：
     * 值越大，提供给 LLM 的背景信息越丰富，但也会增加 Token 消耗和响应延迟，甚至可能引入噪声（不太相关的内容）。
     * 值越小，响应越快、成本越低，但可能遗漏关键信息。
     */
    @Value("${rag.max-results:5}")
    private int maxResults;

    /**
     * 作用：设置检索结果的最低相似度阈值（过滤低质量结果）。
     * 含义：向量检索返回的文档片段相似度分数范围通常是 0~1（1 表示完全匹配）。设为 0.7 意味着只有相似度 ≥ 0.7 的文档片段才会被采纳，低于此阈值的结果会被丢弃，即使它在 Top N 中。
     * 调优建议：
     * 值越高（如 0.8~0.9），检索结果越精准，但可能导致"召回不足"（没有文档满足阈值，LLM 没有上下文可用）。
     * 值越低（如 0.3~0.5），召回率越高，但可能引入不相关内容，导致 LLM 产生幻觉。
     */
    @Value("${rag.min-score:0.7}")
    private double minScore;

     /**
      * 作用：定义 Embedding（文本向量化）的向量维度。
      * 含义：文本在被送入向量数据库之前，会被 Embedding 模型转换为一个 768 维的浮点数向量。这个维度必须与所使用的 Embedding 模型输出维度一致。
      * 调优建议：
      * 这个值不是随意调整的，必须与你选择的 Embedding 模型匹配。常见的维度有：
      * 768：如 BERT 系列模型、bge-large-zh 等
      * 1024：如 bge-large-zh-v1.5
      * 1536：如 OpenAI 的 text-embedding-ada-002
      * 如果维度设置与模型实际输出不一致，会导致向量存储或检索失败。
      */
    @Value("${rag.embedding-dimension:768}")
    private int embeddingDimension;

    /**
     * 配置聊天模型 (本地 Ollama 模型)
     */
    @Bean
    ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(chatModelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 配置嵌入模型 (用于向量化文档)
     */
    @Bean
    EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    // ==================== 内存向量存储 (默认) ====================

    /**
     * 配置内存向量存储 (默认方案，应用重启数据丢失)
     */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "memory", matchIfMissing = true)
    EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    // ==================== Redis 向量存储 ====================

    @Value("${rag.vector-store.redis.host:localhost}")
    private String redisHost;

    @Value("${rag.vector-store.redis.port:6379}")
    private int redisPort;

    @Value("${rag.vector-store.redis.index-name:rag-index}")
    private String redisIndexName;

    /**
     * 配置 Redis 向量存储
     */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "redis")
    EmbeddingStore<TextSegment> redisEmbeddingStore() {
        return RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .dimension(embeddingDimension)
                .indexName(redisIndexName)
                .build();
    }

    // ==================== Chroma 向量存储 ====================

    @Value("${rag.vector-store.chroma.base-url:http://localhost:8000}")
    private String chromaBaseUrl;

    @Value("${rag.vector-store.chroma.collection-name:rag-collection}")
    private String chromaCollectionName;

    /**
     * 配置 Chroma 向量存储
     */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "chroma")
    EmbeddingStore<TextSegment> chromaEmbeddingStore() {
        return ChromaEmbeddingStore.builder()
                .baseUrl(chromaBaseUrl)
                .collectionName(chromaCollectionName)
                .build();
    }

    // ==================== Qdrant 向量存储 ====================

    @Value("${rag.vector-store.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${rag.vector-store.qdrant.port:6334}")
    private int qdrantPort;

    @Value("${rag.vector-store.qdrant.collection-name:rag-collection}")
    private String qdrantCollectionName;

    /**
     * 配置 Qdrant 向量存储
     */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "qdrant")
    EmbeddingStore<TextSegment> qdrantEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantPort)
                .collectionName(qdrantCollectionName)
                .build();
    }

    // ==================== PGVector 向量存储 ====================

    @Value("${rag.vector-store.pgvector.host:localhost}")
    private String pgHost;

    @Value("${rag.vector-store.pgvector.port:5432}")
    private int pgPort;

    @Value("${rag.vector-store.pgvector.database:postgres}")
    private String pgDatabase;

    @Value("${rag.vector-store.pgvector.user:postgres}")
    private String pgUser;

    @Value("${rag.vector-store.pgvector.password:postgres}")
    private String pgPassword;

    @Value("${rag.vector-store.pgvector.table:embedding_store}")
    private String pgTable;

    /**
     * 配置 PGVector 向量存储
     */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "pgvector")
    EmbeddingStore<TextSegment> pgVectorEmbeddingStore() {
        return PgVectorEmbeddingStore.builder()
                .host(pgHost)
                .port(pgPort)
                .database(pgDatabase)
                .user(pgUser)
                .password(pgPassword)
                .table(pgTable)
                .dimension(embeddingDimension)
                .build();
    }

    // ==================== 内容检索器 ====================

    /**
     * 配置内容检索器
     */
    @Bean
    ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }
}
