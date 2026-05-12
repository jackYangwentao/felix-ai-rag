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

    @Value("${rag.max-results:5}")
    private int maxResults;

    @Value("${rag.min-score:0.7}")
    private double minScore;

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
