package com.felix.ai.rag.storage;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;
import java.util.Map;

/**
 * 向量存储接口
 * 抽象不同向量数据库的实现（Milvus、Qdrant、Pinecone等）
 */
public interface VectorStore {

    /**
     * 存储文档片段及其向量
     *
     * @param id        文档ID
     * @param segment   文本片段
     * @param embedding 向量
     * @param metadata  元数据
     */
    void upsert(String id, TextSegment segment, Embedding embedding, Map<String, Object> metadata);

    /**
     * 批量存储
     */
    void upsertBatch(List<VectorDocument> documents);

    /**
     * 相似度搜索
     *
     * @param queryEmbedding 查询向量
     * @param topK           返回结果数
     * @param minScore       最小相似度分数
     * @return 匹配结果列表
     */
    List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore);

    /**
     * 带过滤条件的相似度搜索
     *
     * @param queryEmbedding 查询向量
     * @param topK           返回结果数
     * @param minScore       最小相似度分数
     * @param filter         过滤条件 (如: "category = 'tech' AND status = 'active'")
     * @return 匹配结果列表
     */
    List<EmbeddingMatch<TextSegment>> searchWithFilter(
            Embedding queryEmbedding, int topK, double minScore, String filter);

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 批量删除
     */
    void deleteBatch(List<String> ids);

    /**
     * 检查存储是否健康
     */
    boolean isHealthy();

    /**
     * 获取存储统计信息
     */
    Map<String, Object> getStats();

    /**
     * 向量文档封装类
     */
    record VectorDocument(
            String id,
            TextSegment segment,
            Embedding embedding,
            Map<String, Object> metadata
    ) {}
}
