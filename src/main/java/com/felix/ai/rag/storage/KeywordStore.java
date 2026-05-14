package com.felix.ai.rag.storage;

import java.util.List;
import java.util.Map;

/**
 * 关键词存储接口
 * 抽象不同搜索引擎的实现（Elasticsearch、OpenSearch等）
 */
public interface KeywordStore {

    /**
     * 索引文档
     *
     * @param id       文档ID
     * @param content  文档内容
     * @param metadata 元数据
     */
    void index(String id, String content, Map<String, Object> metadata);

    /**
     * 批量索引
     */
    void indexBatch(List<KeywordDocument> documents);

    /**
     * 关键词搜索（BM25）
     *
     * @param query    查询词
     * @param topK     返回结果数
     * @param minScore 最小分数
     * @return 搜索结果列表
     */
    List<SearchResult> search(String query, int topK, double minScore);

    /**
     * 带过滤条件的关键词搜索
     *
     * @param query    查询词
     * @param topK     返回结果数
     * @param minScore 最小分数
     * @param filter   过滤条件
     * @return 搜索结果列表
     */
    List<SearchResult> searchWithFilter(
            String query, int topK, double minScore, Map<String, Object> filter);

    /**
     * 高亮搜索（返回高亮片段）
     */
    List<SearchResult> searchWithHighlight(String query, int topK, int fragmentSize);

    /**
     * 删除文档
     */
    void delete(String id);

    /**
     * 批量删除
     */
    void deleteBatch(List<String> ids);

    /**
     * 检查索引是否存在
     */
    boolean indexExists();

    /**
     * 创建索引（带映射配置）
     */
    void createIndex();

    /**
     * 删除索引
     */
    void deleteIndex();

    /**
     * 检查存储是否健康
     */
    boolean isHealthy();

    /**
     * 获取存储统计信息
     */
    Map<String, Object> getStats();

    /**
     * 关键词文档封装类
     */
    record KeywordDocument(
            String id,
            String content,
            Map<String, Object> metadata
    ) {}

    /**
     * 搜索结果封装类
     */
    record SearchResult(
            String id,
            String content,
            double score,
            Map<String, Object> metadata,
            List<String> highlights,
            List<String> matchedTerms
    ) {}
}
