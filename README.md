# Felix AI RAG

基于 LangChain4J 的 Spring Boot RAG (Retrieval-Augmented Generation) 应用，参考 [Datawhale All-In-RAG](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter1/03_get_start_rag.md) 教程实现。

## 功能特性

- 基于 LangChain4J 框架的 Java 实现
- 支持本地大模型（通过 Ollama）
- 完整的 RAG 流程：文档加载 → 分块 → 向量化 → 索引 → 检索 → 生成
- 参考 LangChain 最佳实践的 Prompt 模板设计
- 支持文本上传和文件上传两种方式
- RESTful API 接口

## 技术栈

- Spring Boot 3.2+
- LangChain4J 0.31.0
- Ollama（本地模型服务）
- Java 16+
- Maven

## RAG 架构流程

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  原始文档    │ → │  文本分块    │ → │  嵌入向量化  │ → │  向量索引    │
│ (txt/md等)  │    │(Recursive   │    │(Ollama嵌入) │    │(InMemory)   │
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

#### 上传文档（文本形式）
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "这是要索引的文档内容...",
    "documentName": "文档名称"
  }'
```

#### 上传文档（文件形式）
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/path/to/your/document.txt" \
  -F "description=文档描述"
```

支持的文件类型：txt, md, markdown, json, xml, html, csv, yaml, sql 等

#### RAG 问答
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你的问题",
    "useRag": true
  }'
```

#### 普通聊天（不使用RAG）
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/direct \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好"
  }'
```

#### 检索内容（不生成回答）
```bash
curl "http://localhost:8080/api/v1/rag/search?query=查询关键词"
```

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

### 2. 分块策略

使用 `RecursiveCharacterTextSplitter` 递归分割：
- **Chunk Size**: 1000（参考文档使用4000，本地模型建议较小值）
- **Chunk Overlap**: 200（保持上下文连贯性）
- **分隔符优先级**: `["\n\n", "\n", " ", ""]`

### 3. 检索策略

- **相似度搜索**: 基于向量相似度
- **Top-k**: 取最相关的3条内容
- **上下文拼接**: 使用 `"\n\n"` 分隔，让LLM更清晰识别段落边界

### 4. 支持的模型

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
```bash
# 方式1：直接上传文本
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Spring Boot 是一个用于简化 Spring 应用开发的框架...",
    "documentName": "spring-boot-intro.txt"
  }'

# 方式2：上传文件
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/Users/xxx/documents/intro.md"
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

## 参考资源

- [Datawhale All-In-RAG 教程](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter1/03_get_start_rag.md)
- [LangChain4J 官方文档](https://docs.langchain4j.dev/)
- [Ollama 官方文档](https://ollama.com/)

## License

MIT
