# Felix AI RAG

基于 LangChain4J 的 Spring Boot RAG (Retrieval-Augmented Generation) 应用，深度参考 [Datawhale All-In-RAG](https://github.com/datawhalechina/all-in-rag) 教程实现，整合了索引优化、查询构建、混合搜索等高级RAG技术。

## 功能特性

### 核心RAG能力
- 基于 LangChain4J 框架的 Java 实现
- 支持本地大模型（通过 Ollama）
- 完整的 RAG 流程：文档加载 → 分块 → 向量化 → 索引 → 检索 → 生成

### 文档处理
- **多格式文档加载**：txt, md, pdf, doc, docx, xls, xlsx, ppt, pptx 等
- **自动元数据提取**：标题、作者、页数、创建时间等
- **多种分块策略**：递归、固定大小、语义、代码、句子窗口

### 索引优化（参考 Datawhale All-In-RAG）
- **句子窗口检索**：小块索引保证精度，窗口扩展保证上下文
- **元数据过滤**：支持按年份、部门、分类等结构化过滤
- **混合检索**：稠密向量检索 + 稀疏关键词检索（BM25）
- **多路召回**：RRF融合排序、加权线性组合

### 查询优化
- **Self-Query**：自然语言自动解析为语义查询+元数据过滤
- **查询重写**：意图识别驱动的查询优化
- **查询扩展**：多查询变体、HyDE假设文档

### 检索优化
- **重排序（Reranking）**：两阶段检索提高相关性
- **MMR多样性搜索**：确保结果多样性
- **批量搜索**：并行处理多个查询

### 多模态支持
- **图像理解**：使用视觉模型生成描述
- **图像问答**：基于图像内容问答
- **图像索引**：图像描述加入知识库

## 技术栈

- Spring Boot 3.2+
- LangChain4J 0.31.0
- Ollama（本地模型服务）
- Java 17+
- Maven
- 多种向量数据库（内存/Redis/Chroma/Qdrant/PGVector）

## RAG 架构流程

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  原始文档    │ → │  文本分块    │ → │  嵌入向量化  │ → │  向量索引    │
│ (多种格式)   │    │(多种策略)   │    │(Ollama嵌入) │    │(向量数据库)  │
└─────────────┘    └─────────────┘    └─────────────┘    └──────┬──────┘
                                                                  │
┌─────────────────────────────────────────────────────────────────┘
│
│  用户查询 → [查询重写] → [Self-Query解析] → [查询扩展]
│                                           ↓
│                              ┌──────────────────────┐
│                              │   混合检索 (Hybrid)   │
│                              │  ┌────────┐ ┌──────┐ │
│                              │  │稠密检索│ │稀疏检索│ │
│                              │  │(向量) │ │(BM25)│ │
│                              │  └───┬────┘ └──┬───┘ │
│                              │      └────┬────┘     │
│                              │      [RRF融合排序]    │
│                              └───────────┼──────────┘
│                                          ↓
│  最终答案 ← LLM生成 ← 提示工程 ← [句子窗口] ← [重排序] ← 检索结果
│
```

## 快速开始

### 1. 环境准备

```bash
# 安装 Ollama
brew install ollama

# 拉取模型
ollama pull llama3.1        # 聊天模型
ollama pull nomic-embed-text # 嵌入模型

# 启动服务
ollama serve
```

### 2. 构建运行

```bash
# 构建
export JAVA_HOME=/Users/yangwentao01/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home
mvn clean install

# 运行
mvn spring-boot:run
```

### 3. 验证服务

```bash
curl http://localhost:8080/api/v1/rag/health
```

## API 使用指南

### 基础接口

#### RAG 问答
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Spring Boot 有什么特点？",
    "useRag": true
  }'
```

#### 上传文档
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/path/to/document.pdf"
```

### 高级检索接口

#### 1. 混合检索（推荐）
结合稠密向量检索和稀疏关键词检索，RRF融合排序

```bash
# 基础混合检索
curl "http://localhost:8080/api/v1/rag/hybrid/search?query=机器学习&maxResults=5"

# 返回结果包含：
# - 语义检索排名和分数
# - 关键词检索排名和分数
# - RRF融合后的最终排名
# - 匹配的关键词
# - 可解释性分析
```

#### 2. Self-Query检索
自然语言自动解析为语义查询+元数据过滤

```bash
# 解析查询
curl -X POST http://localhost:8080/api/v1/rag/self-query/parse \
  -H "Content-Type: application/json" \
  -d '{"query": "2023年张三写的关于机器学习的论文"}'

# 返回:
# {
#   "semanticQuery": "机器学习 论文",
#   "filters": [
#     {"field": "year", "operator": "EQUALS", "value": "2023"},
#     {"field": "author", "operator": "EQUALS", "value": "张三"}
#   ]
# }

# 执行Self-Query搜索
curl -X POST http://localhost:8080/api/v1/rag/self-query/search \
  -H "Content-Type: application/json" \
  -d '{"query": "技术部2023年Q1的产品报告"}'
```

#### 3. 元数据过滤搜索
```bash
curl "http://localhost:8080/api/v1/rag/search/filtered?query=AI&year=2023&category=技术"
```

#### 4. 多样性搜索（MMR）
```bash
curl "http://localhost:8080/api/v1/rag/search/diverse?query=Spring Boot&maxResults=5&diversity=0.5"
```

#### 5. 优化版RAG（整合所有优化技术）
```bash
curl -X POST http://localhost:8080/api/v1/rag/optimized/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是RAG？", "useRag": true}'
```

### 检索对比分析
```bash
# 对比稠密检索 vs 稀疏检索的差异
curl "http://localhost:8080/api/v1/rag/hybrid/compare?query=AI技术&maxResults=5"
```

## 高级配置

### 1. 分块策略配置

```yaml
rag:
  chunk:
    strategy: sentence-window   # 句子窗口策略
    sentence-window:
      size: 3                   # 前后各3个句子

  sentence-window:
    enabled: true               # 检索后扩展为完整窗口
```

### 2. 混合检索配置

```yaml
rag:
  advanced-hybrid:
    strategy: RRF               # RRF 或 WEIGHTED_SUM
    vector-weight: 0.5          # 稠密检索权重
    keyword-weight: 0.5         # 稀疏检索权重
    rrf-k: 60                   # RRF平滑参数
    
    bm25:
      k1: 1.5                   # 词频饱和参数
      b: 0.75                   # 长度归一化参数
```

### 3. 查询优化配置

```yaml
rag:
  query-rewrite:
    enabled: true               # 启用查询重写
    intent-analysis: true       # 启用意图分析
  
  query-expansion:
    enabled: false              # 启用查询扩展
    variations: 3               # 查询变体数量
  
  self-query:
    enabled: true               # 启用Self-Query
```

### 4. 向量数据库配置

```yaml
rag:
  vector-store:
    type: redis                 # memory | redis | chroma | qdrant | pgvector
    redis:
      host: localhost
      port: 6379
```

## 优化技术详解

### 1. 句子窗口检索（Sentence Window Retrieval）

**原理**：为检索精确性而索引小块（句子），为上下文丰富性而检索大块（窗口）

**流程**：
1. 索引阶段：文档分割为句子，每个句子独立索引
2. 检索阶段：在单一句子上执行相似度搜索（高精度）
3. 后处理：用完整窗口文本替换单一句子（丰富上下文）

**配置**：
```yaml
rag:
  chunk:
    strategy: sentence-window
    sentence-window:
      size: 3                   # 窗口大小：前后各N个句子
```

### 2. 混合检索（Hybrid Search）

**原理**：结合稠密向量检索（语义理解）和稀疏关键词检索（精确匹配）

**融合策略**：
- **RRF** (Reciprocal Rank Fusion): 基于排名的融合，无需归一化
- **Weighted Sum**: 加权线性组合，可精细控制权重

**BM25公式**：
```
Score(Q, D) = Σ IDF(q_i) · [f(q_i,D)·(k1+1)] / [f(q_i,D) + k1·(1-b+b·|D|/avgdl)]
```

**配置**：
```yaml
rag:
  advanced-hybrid:
    strategy: RRF
    vector-weight: 0.5
    keyword-weight: 0.5
```

### 3. Self-Query检索

**原理**：使用LLM将自然语言查询解析为语义查询+元数据过滤条件

**示例**：
```
输入: "2023年张三写的关于机器学习的论文"
输出: 
  - 语义查询: "机器学习 论文"
  - 过滤条件: year=2023, author=张三
```

**优势**：
- 用户无需学习过滤语法
- 先过滤后检索，提高效率和准确性
- 结合语义理解和结构化查询

### 4. MMR多样性搜索

**原理**：Maximal Marginal Relevance，在相关性和多样性之间取得平衡

**公式**：
```
MMR Score = λ · Relevance - (1-λ) · MaxSimilarity
```

**应用场景**：
- 需要不同角度的答案
- 避免结果过于相似
- 探索性搜索

## 项目结构

```
felix-ai-rag/
├── src/main/java/com/felix/ai/rag/
│   ├── config/
│   │   └── RagConfiguration.java           # RAG配置
│   ├── controller/
│   │   ├── RagController.java              # 基础API
│   │   ├── HybridSearchController.java     # 混合检索API
│   │   ├── SelfQueryController.java        # Self-Query API
│   │   ├── OptimizedRagController.java     # 优化版RAG API
│   │   └── MultimodalController.java       # 多模态API
│   ├── service/
│   │   ├── RagService.java                 # 基础RAG服务
│   │   ├── OptimizedRagService.java        # 优化版RAG服务
│   │   ├── RerankerService.java            # 重排序服务
│   │   └── EnhancedSearchService.java      # 增强搜索服务
│   ├── retriever/
│   │   ├── HybridRetriever.java            # 基础混合检索器
│   │   └── AdvancedHybridRetriever.java    # 高级混合检索器
│   ├── query/
│   │   ├── SelfQueryRetriever.java         # Self-Query解析
│   │   ├── QueryRewriteService.java        # 查询重写
│   │   ├── QueryExpansionService.java      # 查询扩展
│   │   └── StructuredQueryBuilder.java     # 结构化查询构建
│   ├── processor/
│   │   └── SentenceWindowProcessor.java    # 句子窗口后处理
│   ├── chunker/
│   │   ├── SentenceWindowChunker.java      # 句子窗口分块
│   │   ├── RecursiveChunker.java           # 递归分块
│   │   ├── SemanticChunker.java            # 语义分块
│   │   └── ...
│   └── filter/
│       └── MetadataFilter.java             # 元数据过滤器
└── src/main/resources/
    └── application.yml                     # 配置文件
```

## 使用场景推荐

| 场景 | 推荐配置 |
|------|----------|
| **通用知识问答** | 句子窗口 + 混合检索(RRF) + Self-Query |
| **精确术语搜索** | 提高关键词权重(0.7) + 元数据过滤 |
| **长文档问答** | 启用句子窗口 + 增大chunk-size |
| **多主题探索** | MMR多样性搜索(diversity=0.5) |
| **结构化数据** | Self-Query + 元数据过滤 |

## 性能优化建议

### 1. 向量数据库选择
| 类型 | 适用场景 |
|------|----------|
| memory | 快速测试、开发调试 |
| redis | 生产环境、高性能需求 |
| qdrant | 高性能检索、过滤查询 |
| pgvector | 已有PostgreSQL环境 |

### 2. 索引优化
- 使用HNSW索引提高检索速度
- 合理设置向量维度（nomic-embed-text=768）
- 大批量导入时先禁用实时索引

### 3. 查询优化
- 启用查询重写改善检索质量
- 使用批量搜索提高吞吐量
- 合理设置max-results和min-score

## 参考资源

- [Datawhale All-In-RAG 教程](https://github.com/datawhalechina/all-in-rag)
- [LangChain4J 官方文档](https://docs.langchain4j.dev/)
- [Ollama 官方文档](https://ollama.com/)

## License

MIT
