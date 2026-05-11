# Felix AI RAG

基于 LangChain4J 的 Spring Boot RAG (Retrieval-Augmented Generation) 应用，支持本地模型通过 Ollama 接入。

## 功能特性

- 基于 LangChain4J 框架的 Java 实现
- 支持本地大模型（通过 Ollama）
- 向量存储与相似度检索
- 文档自动分块与索引
- RESTful API 接口

## 技术栈

- Spring Boot 3.2+
- LangChain4J 0.31.0
- Ollama（本地模型服务）
- Java 17+
- Maven

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
mvn clean install
```

### 2. 运行应用

```bash
mvn spring-boot:run
```

或

```bash
java -jar target/felix-ai-rag-1.0.0.jar
```

### 3. API 接口

#### 健康检查
```bash
curl http://localhost:8080/api/v1/rag/health
```

#### RAG 问答
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你的问题",
    "useRag": true
  }'
```

#### 普通聊天
```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/direct \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好"
  }'
```

#### 上传文档
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "这是要索引的文档内容...",
    "documentName": "文档名称"
  }'
```

#### 检索内容
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
    embedding-model:
      model-name: nomic-embed-text  # 嵌入模型

rag:
  chunk-size: 500       # 文档分块大小
  chunk-overlap: 50     # 分块重叠大小
  max-results: 5        # 最大检索结果数
  min-score: 0.7        # 最小相似度分数
```

## 支持的模型

### 聊天模型
- llama3.1
- llama3
- mistral
- qwen2
- gemma2

### 嵌入模型
- nomic-embed-text
- mxbai-embed-large

## 项目结构

```
felix-ai-rag/
├── src/main/java/com/felix/ai/rag/
│   ├── FelixAiRagApplication.java    # 启动类
│   ├── config/
│   │   └── RagConfiguration.java     # 配置类
│   ├── controller/
│   │   └── RagController.java        # API控制器
│   ├── service/
│   │   └── RagService.java           # 业务服务
│   └── dto/
│       ├── ChatRequest.java          # 请求DTO
│       ├── ChatResponse.java         # 响应DTO
│       └── DocumentUploadRequest.java # 文档上传DTO
├── src/main/resources/
│   └── application.yml               # 配置文件
└── pom.xml                           # Maven配置
```

## License

MIT
# felix-ai-rag
