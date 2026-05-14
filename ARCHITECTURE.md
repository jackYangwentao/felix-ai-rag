# 生产级混合检索架构设计

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              用户请求层                                   │
│                    (REST API / WebSocket / gRPC)                        │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  ↓
┌─────────────────────────────────────────────────────────────────────────┐
│                           应用服务层 (Spring Boot)                        │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐      │
│  │  ProductionHybrid │  │ DocumentIndexing │  │  RAG Controller  │      │
│  │    Retriever      │  │     Service      │  │                  │      │
│  └────────┬─────────┘  └────────┬─────────┘  └──────────────────┘      │
└───────────┼─────────────────────┼──────────────────────────────────────┘
            ↓                     ↓
┌───────────┴─────────────────────┴──────────────────────────────────────┐
│                            存储抽象层                                     │
│  ┌────────────────────┐            ┌────────────────────┐              │
│  │   VectorStore      │◄──────────►│   KeywordStore     │              │
│  │    (Interface)     │            │    (Interface)     │              │
│  └─────────┬──────────┘            └─────────┬──────────┘              │
└────────────┼────────────────────────────────┼──────────────────────────┘
             ↓                                ↓
┌────────────┴────────────────────────────────┴──────────────────────────┐
│                            数据存储层                                     │
│                                                                         │
│  ┌──────────────────────┐      ┌──────────────────────┐                │
│  │       Milvus         │      │   Elasticsearch      │                │
│  │   (向量数据库)         │      │   (搜索引擎)          │                │
│  │                      │      │                      │                │
│  │  • HNSW/IVF 索引      │      │  • BM25 相关性打分     │                │
│  │  • 余弦相似度搜索      │      │  • IK 中文分词         │                │
│  │  • 十亿级向量支持      │      │  • 高亮片段提取        │                │
│  │  • 分区与副本         │      │  • 丰富的过滤条件       │                │
│  └──────────────────────┘      └──────────────────────┘                │
│                                                                         │
│  ┌──────────────────────┐                                              │
│  │     PostgreSQL       │                                              │
│  │   (元数据存储)         │                                              │
│  │                      │                                              │
│  │  • 文档元数据          │                                              │
│  │  • 文档-片段映射        │                                              │
│  │  • 索引状态追踪        │                                              │
│  └──────────────────────┘                                              │
└─────────────────────────────────────────────────────────────────────────┘
```

## 核心组件说明

### 1. 存储接口层

| 接口 | 职责 | 实现类 |
|------|------|--------|
| `VectorStore` | 向量存储抽象 | `MilvusVectorStore` |
| `KeywordStore` | 关键词存储抽象 | `ElasticsearchKeywordStore` |

### 2. 检索层

| 组件 | 说明 |
|------|------|
| `ProductionHybridRetriever` | 生产级混合检索器，支持并行双路检索 + RRF/加权融合 |
| `DocumentIndexingService` | 文档索引服务，实现分块→向量化→双写流水线 |

### 3. 融合策略

#### RRF (Reciprocal Rank Fusion)
```
score = Σ(1 / (rank + k))
```
- **优点**: 无需归一化，对异常值鲁棒
- **适用**: 通用搜索场景，无需调参

#### Weighted Sum (加权线性组合)
```
score = α * norm(vector_score) + (1-α) * norm(keyword_score)
```
- **优点**: 可精细控制权重
- **适用**: 需要调整语义vs关键词比重的场景

## 执行流程

### 检索流程

```
用户查询
    ↓
并行双路检索 ──────┬──────────────┐
                  ↓              ↓
        向量检索(Milvus)    关键词检索(ES)
                  ↓              ↓
        EmbeddingMatch      SearchResult
                  └──────┬─────┘
                         ↓
                  结果融合(RRF/Weighted)
                         ↓
                  排序 + 去重
                         ↓
                  返回 HybridSearchResult
```

### 索引流程

```
文档上传
    ↓
文档分块 (DocumentSplitter)
    ↓
批量向量化 (EmbeddingModel)
    ↓
并行双写 ───────┬──────────────┐
               ↓              ↓
       Milvus.upsert()   ES.index()
               └──────┬─────┘
                      ↓
               返回 segment IDs
```

## 配置说明

### 1. 存储类型切换

```yaml
rag:
  storage:
    vector:
      type: milvus  # 可选: memory, milvus, qdrant, pgvector
    keyword:
      type: elasticsearch  # 可选: memory, elasticsearch
```

### 2. 混合检索参数

```yaml
rag:
  hybrid-retriever:
    strategy: RRF           # RRF | WEIGHTED_SUM
    vector-weight: 0.7      # 向量检索权重
    keyword-weight: 0.3     # 关键词检索权重
    rrf-k: 60               # RRF平滑参数
    enable-parallel: true   # 并行双路检索
```

### 3. Milvus 索引选择

| 索引类型 | 适用场景 | 特点 |
|---------|---------|------|
| HNSW | 高性能搜索 | 图算法，查询快，内存占用高 |
| IVF_FLAT | 平衡场景 | 倒排+暴力搜索，内存友好 |
| IVF_SQ8 | 大规模数据 | 量化压缩，存储效率高 |

## 部署指南

### 1. 启动基础设施

```bash
# 创建数据目录
mkdir -p volumes/{etcd,minio,milvus,elasticsearch,postgres,prometheus,grafana}

# 启动所有服务
docker-compose -f docker-compose-production.yml up -d

# 查看服务状态
docker-compose -f docker-compose-production.yml ps
```

### 2. 验证服务

```bash
# Milvus 健康检查
curl http://localhost:9091/healthz

# Elasticsearch 健康检查
curl http://localhost:9200/_cluster/health

# PostgreSQL 连接
psql -h localhost -U rag_user -d rag_metadata
```

### 3. 启动应用

```bash
# 使用生产配置
mvn spring-boot:run -Dspring-boot.run.profiles=production

# 或打包后运行
mvn clean package
java -jar target/felix-ai-rag-1.0.0.jar --spring.profiles.active=production
```

## API 示例

### 混合检索

```bash
curl "http://localhost:8080/api/v1/rag/hybrid/search?query=什么是机器学习&maxResults=5"
```

响应示例：
```json
{
  "query": "什么是机器学习",
  "processingTimeMs": 45,
  "results": [
    {
      "rank": 1,
      "text": "机器学习是人工智能的一个分支...",
      "finalScore": 0.89,
      "denseRank": 1,
      "denseScore": 0.92,
      "sparseRank": 2,
      "sparseScore": 0.85,
      "matchedTerms": ["机器学习", "人工智能"]
    }
  ],
  "explanation": {
    "fusionStrategy": "RRF",
    "vectorWeight": 0.7,
    "keywordWeight": 0.3,
    "denseRetrievalCount": 10,
    "sparseRetrievalCount": 10
  }
}
```

### 文档索引

```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "机器学习是AI的核心技术...",
    "documentName": "ml-intro.txt",
    "metadata": {"category": "tech", "author": "felix"}
  }'
```

## 性能优化建议

### 1. Milvus 优化

```yaml
# 选择合适的索引类型
rag:
  storage:
    milvus:
      index-type: HNSW        # 查询性能优先
      # 或
      index-type: IVF_FLAT    # 内存受限场景
```

### 2. Elasticsearch 优化

```yaml
# 根据数据量调整分片
rag:
  storage:
    elasticsearch:
      shards: 3    # 每 20-50GB 数据一个分片
      replicas: 1  # 生产环境至少1个副本
```

### 3. 应用层优化

```yaml
# 启用并行检索
rag:
  hybrid-retriever:
    enable-parallel: true
    thread-pool-size: 10
```

## 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|---------|
| `hybrid_search_latency` | 混合检索延迟 | > 200ms |
| `vector_store_health` | 向量存储健康 | = 0 |
| `keyword_store_health` | 关键词存储健康 | = 0 |
| `indexing_rate` | 索引速率 | < 10 docs/s |

访问监控面板：
- Grafana: http://localhost:3000
- Kibana: http://localhost:5601
- Prometheus: http://localhost:9090
