package com.felix.ai.rag.storage.milvus;

import com.felix.ai.rag.storage.VectorStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.FieldDataWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现
 * 支持大规模、高性能的向量相似度搜索
 *
 * 特性：
 * - 支持 IVF_FLAT、HNSW 等索引类型
 * - 支持多分区、多副本
 * - 支持动态字段和元数据过滤
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.storage.vector", name = "type", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    @Value("${rag.storage.milvus.host:localhost}")
    private String host;

    @Value("${rag.storage.milvus.port:19530}")
    private int port;

    @Value("${rag.storage.milvus.collection:rag_documents}")
    private String collectionName;

    @Value("${rag.embedding-dimension:768}")
    private int dimension;

    @Value("${rag.storage.milvus.index-type:HNSW}")
    private String indexType;

    @Value("${rag.storage.milvus.metric-type:COSINE}")
    private String metricType;

    private MilvusServiceClient milvusClient;

    // 字段名常量
    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "embedding";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_METADATA = "metadata";

    @PostConstruct
    public void init() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();

        milvusClient = new MilvusServiceClient(connectParam);

        // 检查并创建集合
        if (!hasCollection()) {
            createCollection();
        }

        // 加载集合到内存
        milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        log.info("Milvus vector store initialized. Collection: {}", collectionName);
    }

    @PreDestroy
    public void close() {
        if (milvusClient != null) {
            milvusClient.close();
            log.info("Milvus client closed");
        }
    }

    @Override
    public void upsert(String id, TextSegment segment, Embedding embedding, Map<String, Object> metadata) {
        upsertBatch(Collections.singletonList(
                new VectorDocument(id, segment, embedding, metadata)));
    }

    @Override
    public void upsertBatch(List<VectorDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> metadataJson = new ArrayList<>();

        for (VectorDocument doc : documents) {
            ids.add(doc.id());
            vectors.add(toFloatList(doc.embedding().vector()));
            contents.add(doc.segment().text());
            metadataJson.add(metadataToJson(doc.metadata()));
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_ID, ids));
        fields.add(new InsertParam.Field(FIELD_VECTOR, vectors));
        fields.add(new InsertParam.Field(FIELD_CONTENT, contents));
        fields.add(new InsertParam.Field(FIELD_METADATA, metadataJson));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.insert(insertParam);

        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to insert vectors: " + response.getMessage());
        }

        log.debug("Inserted {} documents into Milvus", documents.size());
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore) {
        return searchWithFilter(queryEmbedding, topK, minScore, null);
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> searchWithFilter(
            Embedding queryEmbedding, int topK, double minScore, String filter) {

        List<List<Float>> searchVectors = Collections.singletonList(
                toFloatList(queryEmbedding.vector()));

        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectors(searchVectors)
                .withVectorFieldName(FIELD_VECTOR)
                .withTopK(topK)
                .withMetricType(MetricType.valueOf(metricType))
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .withOutFields(List.of(FIELD_ID, FIELD_CONTENT, FIELD_METADATA));

        // 添加过滤条件
        if (filter != null && !filter.isEmpty()) {
            searchBuilder.withExpr(filter);
        }

        // 添加距离阈值（将相似度分数转换为距离）
        // COSINE 相似度 = 1 - 距离，所以距离 < 1 - minScore
        if (minScore > 0) {
            String distanceFilter = FIELD_VECTOR + " < " + (1 - minScore);
            if (filter != null && !filter.isEmpty()) {
                searchBuilder.withExpr("(" + filter + ") && (" + distanceFilter + ")");
            } else {
                searchBuilder.withExpr(distanceFilter);
            }
        }

        R<SearchResults> response = milvusClient.search(searchBuilder.build());

        if (response.getStatus() != R.Status.Success.getCode()) {
            log.error("Search failed: {}", response.getMessage());
            return Collections.emptyList();
        }

        return parseSearchResults(response.getData());
    }

    @Override
    public void delete(String id) {
        deleteBatch(Collections.singletonList(id));
    }

    @Override
    public void deleteBatch(List<String> ids) {
        String expr = FIELD_ID + " in [" + ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]";

        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build();

        R<MutationResult> response = milvusClient.delete(deleteParam);

        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to delete documents: " + response.getMessage());
        }

        log.debug("Deleted {} documents from Milvus", ids.size());
    }

    @Override
    public boolean isHealthy() {
        try {
            R<Boolean> response = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            return response.getData() != null && response.getData();
        } catch (Exception e) {
            log.error("Health check failed", e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("type", "Milvus");
        stats.put("host", host);
        stats.put("port", port);
        stats.put("collection", collectionName);
        stats.put("dimension", dimension);
        stats.put("indexType", indexType);
        stats.put("metricType", metricType);
        stats.put("healthy", isHealthy());
        return stats;
    }

    // ==================== Private Methods ====================

    private boolean hasCollection() {
        R<Boolean> response = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());
        return response.getData() != null && response.getData();
    }

    private void createCollection() {
        // 定义字段
        FieldType idField = FieldType.newBuilder()
                .withName(FIELD_ID)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName(FIELD_VECTOR)
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName(FIELD_CONTENT)
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName(FIELD_METADATA)
                .withDataType(DataType.VarChar)
                .withMaxLength(4096)
                .build();

        // 创建集合
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldTypes(List.of(idField, vectorField, contentField, metadataField))
                .build();

        R<RpcStatus> response = milvusClient.createCollection(createParam);

        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to create collection: " + response.getMessage());
        }

        // 创建索引
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(FIELD_VECTOR)
                .withIndexType(IndexType.valueOf(indexType))
                .withMetricType(MetricType.valueOf(metricType))
                .withExtraParam(getIndexParams())
                .build();

        R<RpcStatus> indexResponse = milvusClient.createIndex(indexParam);

        if (indexResponse.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to create index: " + indexResponse.getMessage());
        }

        log.info("Created Milvus collection: {} with {} index", collectionName, indexType);
    }

    private String getIndexParams() {
        // 根据索引类型返回参数
        return switch (indexType) {
            case "HNSW" -> "{\"M\":16,\"efConstruction\":64}";
            case "IVF_FLAT" -> "{\"nlist\":128}";
            default -> "{}";
        };
    }

    private List<EmbeddingMatch<TextSegment>> parseSearchResults(SearchResults results) {
        SearchResultsWrapper wrapper = new SearchResultsWrapper(results.getResults());
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();

        // 获取搜索结果的数量
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
        if (idScores == null || idScores.isEmpty()) {
            return matches;
        }

        for (int i = 0; i < idScores.size(); i++) {
            try {
                // 获取字段数据
                FieldDataWrapper idField = wrapper.getFieldWrapper(FIELD_ID);
                FieldDataWrapper contentField = wrapper.getFieldWrapper(FIELD_CONTENT);

                String id = idField != null ? idField.get(i, FIELD_ID).toString() : "";
                String content = contentField != null ? contentField.get(i, FIELD_CONTENT).toString() : "";

                // 获取距离分数
                SearchResultsWrapper.IDScore idScore = idScores.get(i);
                double distance = idScore.getScore();

                // COSINE 距离转相似度
                double score = 1 - distance;

                TextSegment segment = TextSegment.from(content);
                // EmbeddingMatch(score, embeddingId, embedding, embedded)
                matches.add(new EmbeddingMatch<>(score, id, null, segment));
            } catch (Exception e) {
                log.error("Error parsing search result at index {}", i, e);
            }
        }

        return matches;
    }

    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    private String metadataToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        // 简化的 JSON 转换
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
