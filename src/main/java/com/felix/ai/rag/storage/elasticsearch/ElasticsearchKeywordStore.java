package com.felix.ai.rag.storage.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.felix.ai.rag.storage.KeywordStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Elasticsearch 关键词存储实现
 * 支持高性能的 BM25 关键词搜索和高亮显示
 *
 * 特性：
 * - 原生 BM25 相关性打分
 * - 中文分词支持 (IK Analyzer)
 * - 高亮片段提取
 * - 丰富的过滤条件支持
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.storage.keyword", name = "type", havingValue = "elasticsearch")
public class ElasticsearchKeywordStore implements KeywordStore {

    @Value("${rag.storage.elasticsearch.host:localhost}")
    private String host;

    @Value("${rag.storage.elasticsearch.port:9200}")
    private int port;

    @Value("${rag.storage.elasticsearch.username:}")
    private String username;

    @Value("${rag.storage.elasticsearch.password:}")
    private String password;

    @Value("${rag.storage.elasticsearch.index:rag_documents}")
    private String indexName;

    @Value("${rag.storage.elasticsearch.shards:1}")
    private int numberOfShards;

    @Value("${rag.storage.elasticsearch.replicas:0}")
    private int numberOfReplicas;

    private ElasticsearchClient esClient;

    // 字段名常量
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_TIMESTAMP = "timestamp";

    @PostConstruct
    public void init() {
        try {
            RestClient restClient = createRestClient();
            ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            esClient = new ElasticsearchClient(transport);

            // 检查并创建索引
            if (!indexExists()) {
                createIndex();
            }

            log.info("Elasticsearch keyword store initialized. Index: {}", indexName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Elasticsearch client", e);
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (esClient != null) {
                esClient._transport().close();
                log.info("Elasticsearch client closed");
            }
        } catch (IOException e) {
            log.error("Error closing Elasticsearch client", e);
        }
    }

    private RestClient createRestClient() {
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(host, port));

        // 配置认证
        if (username != null && !username.isEmpty()) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));

            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        return builder.build();
    }

    @Override
    public void index(String id, String content, Map<String, Object> metadata) {
        indexBatch(Collections.singletonList(new KeywordDocument(id, content, metadata)));
    }

    @Override
    public void indexBatch(List<KeywordDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (KeywordDocument doc : documents) {
            Map<String, Object> source = new HashMap<>();
            source.put(FIELD_CONTENT, doc.content());
            source.put(FIELD_METADATA, doc.metadata() != null ? doc.metadata() : new HashMap<>());
            source.put(FIELD_TIMESTAMP, System.currentTimeMillis());

            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(indexName)
                            .id(doc.id())
                            .document(source)
                    )
            );
        }

        try {
            BulkResponse response = esClient.bulk(bulkBuilder.build());

            if (response.errors()) {
                List<String> errors = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.id() + ": " + item.error().reason())
                        .collect(Collectors.toList());
                throw new RuntimeException("Bulk index errors: " + errors);
            }

            log.debug("Indexed {} documents into Elasticsearch", documents.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to bulk index documents", e);
        }
    }

    @Override
    public List<SearchResult> search(String query, int topK, double minScore) {
        return searchWithFilter(query, topK, minScore, null);
    }

    @Override
    public List<SearchResult> searchWithFilter(
            String query, int topK, double minScore, Map<String, Object> filter) {

        try {
            // 构建基础查询
            var queryBuilder = co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                    .multiMatch(mm -> mm
                            .query(query)
                            .fields(FIELD_CONTENT + "^2", FIELD_METADATA + "*")
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                    )
            );

            // 构建搜索请求
            SearchRequest searchRequest = SearchRequest.of(s -> {
                s.index(indexName)
                        .query(queryBuilder)
                        .size(topK)
                        .minScore(minScore)
                        .source(src -> src
                                .filter(f -> f.includes(FIELD_CONTENT, FIELD_METADATA))
                        );

                // 添加过滤条件
                if (filter != null && !filter.isEmpty()) {
                    List<co.elastic.clients.elasticsearch._types.query_dsl.Query> filterQueries =
                            new ArrayList<>();

                    for (Map.Entry<String, Object> entry : filter.entrySet()) {
                        filterQueries.add(
                                co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                                        .term(t -> t
                                                .field(FIELD_METADATA + "." + entry.getKey())
                                                .value(FieldValue.of(entry.getValue().toString()))
                                        )
                                )
                        );
                    }

                    s.postFilter(f -> f.bool(b -> b.filter(filterQueries)));
                }

                return s;
            });

            SearchResponse<Map> response = esClient.search(searchRequest, Map.class);

            return parseSearchResults(response, false);

        } catch (IOException e) {
            log.error("Search failed", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<SearchResult> searchWithHighlight(String query, int topK, int fragmentSize) {
        try {
            // 构建高亮配置
            Highlight highlight = Highlight.of(h -> h
                    .fields(FIELD_CONTENT, HighlightField.of(hf -> hf
                            .fragmentSize(fragmentSize)
                            .numberOfFragments(3)
                            .preTags("<em>")
                            .postTags("</em>")
                    ))
            );

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(indexName)
                    .query(q -> q
                            .multiMatch(mm -> mm
                                    .query(query)
                                    .fields(FIELD_CONTENT)
                            )
                    )
                    .size(topK)
                    .highlight(highlight)
            );

            SearchResponse<Map> response = esClient.search(searchRequest, Map.class);

            return parseSearchResults(response, true);

        } catch (IOException e) {
            log.error("Highlight search failed", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void delete(String id) {
        deleteBatch(Collections.singletonList(id));
    }

    @Override
    public void deleteBatch(List<String> ids) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(indexName)
                    .query(q -> q
                            .ids(i -> i.values(ids))
                    )
            );

            esClient.deleteByQuery(request);
            log.debug("Deleted {} documents from Elasticsearch", ids.size());

        } catch (IOException e) {
            throw new RuntimeException("Failed to delete documents", e);
        }
    }

    @Override
    public boolean indexExists() {
        try {
            ExistsRequest request = ExistsRequest.of(e -> e.index(indexName));
            return esClient.indices().exists(request).value();
        } catch (IOException e) {
            log.error("Failed to check index existence", e);
            return false;
        }
    }

    @Override
    public void createIndex() {
        try {
            CreateIndexRequest request = CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .settings(s -> s
                            .numberOfShards(String.valueOf(numberOfShards))
                            .numberOfReplicas(String.valueOf(numberOfReplicas))
                            .analysis(a -> a
                                    .analyzer("ik_max_word_analyzer", ik -> ik
                                            .custom(ca -> ca
                                                    .tokenizer("ik_max_word")
                                            )
                                    )
                            )
                    )
                    .mappings(m -> m
                            .properties(FIELD_CONTENT, p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")
                                            .searchAnalyzer("ik_smart")
                                    )
                            )
                            .properties(FIELD_METADATA, p -> p
                                    .object(o -> o)
                            )
                            .properties(FIELD_TIMESTAMP, p -> p
                                    .date(d -> d)
                            )
                    )
            );

            esClient.indices().create(request);
            log.info("Created Elasticsearch index: {}", indexName);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create index", e);
        }
    }

    @Override
    public void deleteIndex() {
        try {
            DeleteIndexRequest request = DeleteIndexRequest.of(d -> d.index(indexName));
            esClient.indices().delete(request);
            log.info("Deleted Elasticsearch index: {}", indexName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete index", e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            return esClient.ping().value();
        } catch (IOException e) {
            log.error("Health check failed", e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("type", "Elasticsearch");
        stats.put("host", host);
        stats.put("port", port);
        stats.put("index", indexName);
        stats.put("shards", numberOfShards);
        stats.put("replicas", numberOfReplicas);
        stats.put("healthy", isHealthy());
        return stats;
    }

    // ==================== Private Methods ====================

    private List<SearchResult> parseSearchResults(SearchResponse<Map> response, boolean withHighlight) {
        List<SearchResult> results = new ArrayList<>();

        response.hits().hits().forEach(hit -> {
            String id = hit.id();
            double score = hit.score() != null ? hit.score() : 0.0;

            Map<String, Object> source = hit.source();
            String content = source != null ?
                    (String) source.getOrDefault(FIELD_CONTENT, "") : "";

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = source != null ?
                    (Map<String, Object>) source.getOrDefault(FIELD_METADATA, new HashMap<>()) :
                    new HashMap<>();

            // 提取高亮片段
            List<String> highlights = new ArrayList<>();
            if (withHighlight && hit.highlight() != null) {
                List<String> contentHighlights = hit.highlight().get(FIELD_CONTENT);
                if (contentHighlights != null) {
                    highlights.addAll(contentHighlights);
                }
            }

            // 提取匹配词（从元数据或高亮中推断）
            List<String> matchedTerms = extractMatchedTerms(highlights);

            results.add(new SearchResult(
                    id, content, score, metadata, highlights, matchedTerms));
        });

        return results;
    }

    private List<String> extractMatchedTerms(List<String> highlights) {
        List<String> terms = new ArrayList<>();
        for (String highlight : highlights) {
            // 从 <em>tag</em> 中提取匹配词
            int start = 0;
            while ((start = highlight.indexOf("<em>", start)) != -1) {
                int end = highlight.indexOf("</em>", start);
                if (end != -1) {
                    String term = highlight.substring(start + 4, end);
                    if (!terms.contains(term)) {
                        terms.add(term);
                    }
                    start = end + 5;
                } else {
                    break;
                }
            }
        }
        return terms;
    }
}
