# Felix AI RAG

基于 LangChain4J 的 Spring Boot RAG (Retrieval-Augmented Generation) 应用，参考 [Datawhale All-In-RAG](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter1/03_get_start_rag.md) 教程实现。

## 功能特性

- 基于 LangChain4J 框架的 Java 实现
- 支持本地大模型（通过 Ollama）
- 完整的 RAG 流程：文档加载 → 分块 → 向量化 → 索引 → 检索 → 生成
- **多格式文档加载**（参考 Datawhale All-In-RAG 实现）
  - 文本文件：txt, md, json, xml, html, csv, yaml 等
  - 办公文档：pdf, doc, docx, xls, xlsx, ppt, pptx
  - 自动元数据提取（标题、作者、页数等）
- **多种分块策略**（参考 Datawhale All-In-RAG 实现）
  - 递归分块（默认）：分层分隔符优先级处理
  - 固定大小分块：按目标字符数切分
  - 语义分块：基于嵌入相似度检测断点
  - 代码分块：针对编程语言优化
- **多模态支持**（参考 Datawhale All-In-RAG 实现）
  - 图像理解：使用视觉模型（llava）生成图像描述
  - 图像问答：基于图像内容进行问答
  - 图像索引：将图像描述索引到知识库
- **高级检索功能**（参考 Datawhale All-In-RAG 向量数据库章节）
  - 重排序（Reranking）：两阶段检索，提高结果相关性
  - 多查询搜索：使用多个查询提高召回率
  - 混合检索支持（可扩展）
- 参考 LangChain 最佳实践的 Prompt 模板设计
- 支持文本上传和文件上传两种方式
- RESTful API 接口

## 技术栈

- Spring Boot 3.2+
- LangChain4J 0.31.0
- Ollama（本地模型服务）
- Java 17+
- Maven
- 多种向量数据库（内存/Redis/Chroma/Qdrant/PGVector）
- Apache Tika（文档解析）
- 重排序（Reranking）支持

## RAG 架构流程

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  原始文档    │ → │  文本分块    │ → │  嵌入向量化  │ → │  向量索引    │
│ (txt/md等)  │    │(Recursive   │    │(Ollama嵌入) │    │(向量数据库)  │
│             │    │ Splitter)   │    │             │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └──────┬──────┘
                                                                  │
                              用户问题 ──────────────────────────┤
                              (Query)                            │
                                                                  ↓
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   最终答案   │ ← │  LLM生成    │ ← │  提示工程    │ ← │  语义检索    │
│  (Answer)   │    │(Ollama本地) │    │(Context+    │    │(Top-3相似)  │
│             │    │             │    │  Question)  │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

## 环境准备

### 1. 安装 Ollama

```bash
# macOS
brew install ollama

# 或从官网下载：https://ollama.com/download
```

### 2. 拉取模型

```bash
# 拉取聊天模型
ollama pull llama3.1

# 拉取嵌入模型
ollama pull nomic-embed-text
```

### 3. 启动 Ollama 服务

```bash
ollama serve
```

## 快速开始

### 1. 构建项目

```bash
cd felix-ai-rag
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-16.0.2.jdk/Contents/Home
mvn clean install
```

### 2. 运行应用

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-16.0.2.jdk/Contents/Home
mvn spring-boot:run
```

或

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-16.0.2.jdk/Contents/Home
java -jar target/felix-ai-rag-1.0.0.jar
```

### 3. API 接口

#### 健康检查
```bash
curl http://localhost:8080/api/v1/rag/health
```

#### RAG 问答
基于检索到的文档内容回答问题。
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你的问题",
    "useRag": true,
    "sessionId": null
  }'
```

**参数说明：**
- `message` (必填): 用户问题
- `useRag` (必填): 是否使用 RAG，设为 `true`
- `sessionId` (可选): 会话ID，不传则自动生成

**响应示例：**
```json
{
  "answer": "根据文档内容，答案是...",
  "sessionId": "a1b2c3d4",
  "sources": ["相关文档片段1...", "相关文档片段2..."],
  "processingTimeMs": 2350
}
```

#### 普通聊天（不使用RAG）
直接与 LLM 对话，不检索文档。
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/direct \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好"
  }'
```

**参数说明：**
- `message` (必填): 用户问题

#### 上传文档（文本形式）
将文本内容索引到向量存储。
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "这是要索引的文档内容...",
    "documentName": "文档名称",
    "documentType": "txt",
    "description": "文档描述"
  }'
```

**参数说明：**
- `content` (必填): 文档文本内容
- `documentName` (可选): 文档名称，用于标识来源
- `documentType` (可选): 文档类型，如 txt, md, json
- `description` (可选): 文档描述

#### 上传文档（文件形式）
上传文件并自动提取内容和元数据。支持多种格式：
- **文本文件**: txt, md, markdown, json, xml, html, csv, yaml, sql
- **办公文档**: pdf, doc, docx, xls, xlsx, ppt, pptx

```bash
# 上传 PDF 文件
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/path/to/document.pdf" \
  -F "description=产品手册"

# 上传 Word 文档
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/path/to/document.docx" \
  -F "description=技术规范"

# 上传 Excel 表格
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/path/to/data.xlsx" \
  -F "description=数据分析"
```

**参数说明：**
- `file` (必填): 文件，支持多种格式
- `description` (可选): 文件描述

**响应示例：**
```
文件 "document.pdf" 上传并索引成功
- 字符数: 15234
- 文件类型: application/pdf
- 元数据: title=产品手册 author=张三 page-count=15
```

#### 检索内容（不生成回答）
仅检索相关文档片段，不调用 LLM 生成回答。
```bash
curl "http://localhost:8080/api/v1/rag/search?query=查询关键词"
```

**参数说明：**
- `query` (必填): 检索关键词/问题

**响应示例：**
```json
[
  "相关文档片段1...",
  "相关文档片段2...",
  "相关文档片段3..."
]
```

#### 多模态 - 图像描述
上传图像并获取AI生成的描述。
```bash
curl -X POST http://localhost:8080/api/v1/rag/multimodal/describe \
  -F "image=@/path/to/image.jpg" \
  -F "prompt=请详细描述这张图片"
```

**参数说明：**
- `image` (必填): 图像文件，支持 jpg, png, gif, webp
- `prompt` (可选): 自定义描述提示词

**响应示例：**
```json
{
  "success": true,
  "filename": "image.jpg",
  "description": "这张图片展示了一座山峰...",
  "metadata": {
    "width": 1920,
    "height": 1080,
    "format": "JPEG"
  }
}
```

#### 多模态 - 图像问答
基于图像内容进行问答。
```bash
curl -X POST http://localhost:8080/api/v1/rag/multimodal/chat \
  -F "image=@/path/to/image.jpg" \
  -F "question=这张图片中有哪些物体？"
```

**参数说明：**
- `image` (必填): 图像文件
- `question` (可选): 关于图像的问题

#### 多模态 - 上传图像到知识库
将图像描述索引到知识库，支持后续检索。
```bash
curl -X POST http://localhost:8080/api/v1/rag/multimodal/images \
  -F "image=@/path/to/image.jpg" \
  -F "prompt=请描述这张图片的主要内容"
```

#### 增强搜索（支持重排序）
使用两阶段检索（向量检索 + LLM重排序）提高结果相关性。
```bash
curl "http://localhost:8080/api/v1/rag/search/enhanced?query=Spring Boot&maxResults=5&rerank=true"
```

**参数说明：**
- `query` (必填): 检索关键词
- `maxResults` (可选): 返回结果数，默认3
- `minScore` (可选): 最小相似度分数，默认0.7
- `rerank` (可选): 是否启用重排序，默认false

**响应示例：**
```json
{
  "query": "Spring Boot",
  "results": [
    {
      "content": "Spring Boot 提供了自动配置...",
      "vectorScore": 0.85,
      "rerankScore": 9.2
    }
  ],
  "totalResults": 1,
  "usedRerank": true
}
```

#### 多查询搜索
使用多个相关查询提高召回率，自动合并去重并重排序。
```bash
curl -X POST http://localhost:8080/api/v1/rag/search/multi-query \
  -H "Content-Type: application/json" \
  -d '{
    "queries": ["Spring Boot特点", "Spring Boot优势", "Spring Boot功能"],
    "maxResults": 5
  }'
```

**参数说明：**
- `queries` (必填): 多个查询字符串数组
- `maxResults` (可选): 返回结果数，默认5

## 配置文件

`application.yml` 中可配置：

```yaml
langchain4j:
  ollama:
    base-url: http://localhost:11434
    chat-model:
      model-name: llama3.1    # 聊天模型
      temperature: 0.7
    embedding-model:
      model-name: nomic-embed-text  # 嵌入模型

rag:
  chunk-size: 1000      # 文档分块大小
  chunk-overlap: 200    # 分块重叠大小（保持上下文连贯）
  max-results: 3        # 检索结果数量（Top-k）
  min-score: 0.7        # 最小相似度分数
```

## 向量数据库配置

项目支持 5 种向量数据库，可通过配置灵活切换：

| 类型 | 部署方式 | 数据持久化 | 适用场景 |
|------|----------|------------|----------|
| `memory` | 内置 | ❌ 应用重启丢失 | 快速测试、开发调试 |
| `redis` | Docker | ✅ 持久化 | 生产环境、高性能需求 |
| `chroma` | Docker | ✅ 持久化 | 轻量级向量存储 |
| `qdrant` | Docker | ✅ 持久化 | 高性能检索、过滤查询 |
| `pgvector` | Docker | ✅ 持久化 | 已有 PostgreSQL 环境 |

### 配置切换

修改 `application.yml`：

```yaml
rag:
  vector-store:
    type: redis   # 切换为: memory | redis | chroma | qdrant | pgvector
```

### 1. Memory（默认）

无需额外部署，适合开发和测试。

```yaml
rag:
  vector-store:
    type: memory
```

### 2. Redis

**启动 Redis：**
```bash
docker run -d \
  --name redis-vector \
  -p 6379:6379 \
  redis/redis-stack-server:latest
```

**配置：**
```yaml
rag:
  vector-store:
    type: redis
    redis:
      host: localhost
      port: 6379
      index-name: rag-index
```

### 3. Chroma

**启动 Chroma：**
```bash
docker run -d \
  --name chroma \
  -p 8000:8000 \
  chromadb/chroma:latest
```

**配置：**
```yaml
rag:
  vector-store:
    type: chroma
    chroma:
      base-url: http://localhost:8000
      collection-name: rag-collection
```

### 4. Qdrant

**启动 Qdrant：**
```bash
docker run -d \
  --name qdrant \
  -p 6334:6334 \
  -p 6333:6333 \
  -v $(pwd)/qdrant_storage:/qdrant/storage \
  qdrant/qdrant:latest
```

**配置：**
```yaml
rag:
  vector-store:
    type: qdrant
    qdrant:
      host: localhost
      port: 6334
      collection-name: rag-collection
```

### 5. PGVector

**启动 PostgreSQL + PGVector：**
```bash
docker run -d \
  --name pgvector \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  ankane/pgvector:latest
```

**配置：**
```yaml
rag:
  vector-store:
    type: pgvector
    pgvector:
      host: localhost
      port: 5432
      database: postgres
      user: postgres
      password: postgres
      table: embedding_store
```

### 向量维度配置

不同嵌入模型需要设置对应的向量维度：

```yaml
rag:
  embedding-dimension: 768   # nomic-embed-text = 768
```

常见模型维度：
- `nomic-embed-text`: 768
- `mxbai-embed-large`: 1024
- OpenAI `text-embedding-3-small`: 1536

## 数据加载（参考 Datawhale All-In-RAG）

项目实现了多格式文档加载功能，参考 [Datawhale All-In-RAG 数据加载教程](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter2/04_data_load.md)。

### 支持的文档格式

| 类别 | 格式 | 说明 |
|------|------|------|
| **文本文件** | .txt, .md, .json, .xml, .html, .csv, .yaml | 直接读取，保留格式 |
| **办公文档** | .pdf, .doc, .docx, .xls, .xlsx, .ppt, .pptx | Apache Tika 解析提取内容 |
| **开放文档** | .rtf, .odt, .ods, .odp | OpenDocument 格式支持 |

### 文档元数据提取

上传文档时自动提取以下元数据：
- `title` - 文档标题
- `author` - 作者信息
- `creation-date` - 创建时间
- `last-modified` - 最后修改时间
- `page-count` - 页数（PDF/Word）
- `word-count` - 字数统计
- `content-type` - MIME 类型

### 技术实现

使用 **Apache Tika** 进行文档解析：
- 自动检测文件类型
- 提取纯文本内容
- 保留文档元数据
- 支持大文件（10MB+）安全解析

```
┌─────────────┐    ┌─────────────────┐    ┌─────────────┐
│  原始文件    │ → │  Apache Tika    │ → │  结构化数据  │
│ (多种格式)   │    │  - 类型检测      │    │  - 纯文本    │
│             │    │  - 内容提取      │    │  - 元数据    │
│             │    │  - 元数据解析    │    │  - 来源信息  │
└─────────────┘    └─────────────────┘    └─────────────┘
```

## 实现细节参考

### 1. Prompt 模板设计

参考 LangChain 最佳实践，使用结构化提示：

```
你是一个专业的智能助手，专门基于提供的参考资料回答用户问题。

回答要求：
1. 严格基于以下提供的参考资料进行回答
2. 如果参考资料不足以回答问题，请明确告知
3. 回答应准确、简洁、有条理

====================
参考资料：
====================
{context}

====================
用户问题：{question}
====================
```

### 2. 分块策略（参考 Datawhale All-In-RAG）

支持多种分块策略，参考 [Datawhale All-In-RAG 文本分块教程](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter2/05_text_chunking.md)。

| 策略 | 名称 | 适用场景 |
|------|------|----------|
| `recursive` | 递归字符分块 | **默认**，通用场景，优先保持语义结构 |
| `fixed` | 固定大小分块 | 快速原型、日志处理 |
| `semantic` | 语义分块 | 高质量语义检索，主题聚焦 |
| `code` | 代码分块 | 代码文件，按类/函数分割 |

**配置示例：**
```yaml
rag:
  chunk:
    strategy: recursive   # 切换策略: recursive | fixed | semantic | code
    size: 1000
    overlap: 200
```

#### 递归分块（默认）
分层分隔符优先级：段落 → 句子 → 单词 → 字符
```yaml
rag:
  chunk:
    strategy: recursive
    size: 1000
    overlap: 200
```

#### 固定大小分块
```yaml
rag:
  chunk:
    strategy: fixed
    size: 1000
    overlap: 200
    separator: "\n\n"   # 段落分隔符
```

#### 语义分块
基于嵌入向量相似度检测断点，主题聚焦度高。
```yaml
rag:
  chunk:
    strategy: semantic
    semantic:
      threshold-type: percentile      # percentile | standard_deviation | interquartile
      threshold-amount: 95.0          # 百分位阈值
      buffer-size: 1                  # 上下文缓冲
```

#### 代码分块
针对编程语言优化，按类/函数分割。
```yaml
rag:
  chunk:
    strategy: code
    size: 500
    overlap: 50
```

### 3. 多模态支持（参考 Datawhale All-In-RAG）

项目实现了多模态功能，参考 Datawhale All-In-RAG 多模态嵌入教程。

**支持的模态：**
- **图像理解**：使用视觉模型生成图像描述
- **图像问答**：基于图像内容进行问答
- **图像索引**：将图像描述加入知识库

**支持的图像格式：**
- JPEG / JPG
- PNG
- GIF
- WebP

**配置：**
```yaml
rag:
  multimodal:
    enabled: true                 # 启用多模态功能
    vision-model: llava           # 视觉模型名称
```

**支持的视觉模型（Ollama）：**
- `llava` - LLaVA 1.5 视觉模型
- `llava-phi3` - 基于 Phi-3 的轻量级视觉模型
- `bakllava` - BakLLaVA 视觉模型
- `llava-llama3` - 基于 Llama 3 的视觉模型

**安装视觉模型：**
```bash
ollama pull llava
```

### 4. 检索策略（参考 Datawhale All-In-RAG）

项目实现了多种检索优化策略，参考 Datawhale All-In-RAG 向量数据库章节。

#### 基础检索
- **相似度搜索**: 基于向量相似度
- **Top-k**: 取最相关的3条内容
- **上下文拼接**: 使用 `"\n\n"` 分隔，让LLM更清晰识别段落边界

#### 增强检索 - 重排序（Reranking）
两阶段检索策略：
1. **第一阶段（召回）**: 向量检索获取候选集（Top-K×2）
2. **第二阶段（精排）**: 使用LLM对候选集重排序，返回Top-K

**优势**: 提高检索结果的相关性，减少不相关内容干扰

**配置**:
```yaml
rag:
  reranker:
    enabled: true        # 启用重排序
    top-k: 5            # 返回结果数
  search:
    rerank: false       # 是否默认启用（会影响响应时间）
```

**API使用**:
```bash
# 增强搜索（带重排序）
curl "http://localhost:8080/api/v1/rag/search/enhanced?query=查询&rerank=true"

# 多查询搜索（使用多个相关查询提高召回率）
curl -X POST http://localhost:8080/api/v1/rag/search/multi-query \
  -H "Content-Type: application/json" \
  -d '{
    "queries": ["Spring Boot特点", "Spring Boot优势", "Spring Boot功能"],
    "maxResults": 5
  }'
```

### 6. 支持的模型

#### 聊天模型
- llama3.1 / llama3
- mistral
- qwen2（通义千问）
- gemma2

#### 嵌入模型
- nomic-embed-text
- mxbai-embed-large

## 项目结构

```
felix-ai-rag/
├── src/main/java/com/felix/ai/rag/
│   ├── FelixAiRagApplication.java      # 启动类
│   ├── config/
│   │   └── RagConfiguration.java       # RAG配置
│   ├── controller/
│   │   └── RagController.java          # API控制器
│   ├── service/
│   │   └── RagService.java             # RAG业务逻辑
│   ├── loader/
│   │   └── DocumentLoader.java         # 文档加载器
│   └── dto/
│       ├── ChatRequest.java
│       ├── ChatResponse.java
│       └── DocumentUploadRequest.java
├── src/main/resources/
│   └── application.yml                 # 配置文件
└── pom.xml                             # Maven配置
```

## 使用示例

### 1. 上传知识文档

#### 方式1：直接上传文本
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Spring Boot 是一个用于简化 Spring 应用开发的框架。它提供了自动配置、起步依赖、内嵌服务器等特性，让开发者可以快速搭建和运行 Spring 应用。",
    "documentName": "spring-boot-intro.txt",
    "documentType": "txt"
  }'
```

#### 方式2：上传文本文件
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/Users/xxx/documents/intro.md" \
  -F "description=Spring Boot 介绍文档"
```

#### 方式3：上传 PDF 文档（自动提取内容和元数据）
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/Users/xxx/documents/manual.pdf" \
  -F "description=产品使用手册"

# 响应示例：
# 文件 "manual.pdf" 上传并索引成功
# - 字符数: 32560
# - 文件类型: application/pdf
# - 元数据: title=产品使用手册 author=产品经理 page-count=42
```

#### 方式4：上传 Word 文档
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/Users/xxx/documents/spec.docx" \
  -F "description=技术规范文档"
```

#### 方式5：上传 Excel 表格
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/Users/xxx/data/analysis.xlsx" \
  -F "description=数据分析报表"
```

### 2. 基于文档问答
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Spring Boot 有什么特点？",
    "useRag": true
  }'
```

### 3. 检索相关内容
```bash
curl "http://localhost:8080/api/v1/rag/search?query=Spring Boot 特点"
```

### 4. 直接对话（不使用知识库）
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/direct \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好，请介绍一下自己"
  }'
```

## 参考资源

- [Datawhale All-In-RAG 教程](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter1/03_get_start_rag.md)
- [LangChain4J 官方文档](https://docs.langchain4j.dev/)
- [Ollama 官方文档](https://ollama.com/)

## License

MIT
