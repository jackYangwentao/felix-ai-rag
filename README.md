# Felix AI RAG

基于 LangChain4J 的 Spring Boot RAG (Retrieval-Augmented Generation) 应用，深度参考 [Datawhale All-In-RAG](https://github.com/datawhalechina/all-in-rag) 教程实现，整合了索引优化、查询构建、查询重写、高级检索、混合搜索等全方位RAG技术。

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
- **查询重写**：4种重写技术（提示工程、多查询分解、Step-Back、HyDE）
- **查询扩展**：多查询变体、HyDE假设文档

### 高级检索（参考 Datawhale All-In-RAG）
- **上下文压缩**：提取相关内容，去除噪音
- **C-RAG校正检索**：自我反思机制（检索-评估-行动）
- **父文档检索**：小块匹配，大块上下文
- **集成检索器**：多路召回 + RRF融合

### 高级检索（参考 Datawhale All-In-RAG）
- **上下文压缩**：提取相关内容，去除噪音
- **C-RAG校正检索**：自我反思机制（检索-评估-行动）
- **父文档检索**：小块匹配，大块上下文
- **集成检索器**：多路召回 + RRF融合

### Text2SQL（参考 Datawhale All-In-RAG）
- **RAG增强知识库**：DDL + 字段描述 + 查询示例 + 业务术语
- **自然语言转SQL**：将用户问题转换为可执行SQL
- **错误自动修复**：执行失败时自动分析并修复SQL
- **安全执行策略**：只读模式、自动LIMIT、超时控制
- **内置H2数据库**：开箱即用，包含示例数据

### 检索优化
- **重排序（Reranking）**：两阶段检索提高相关性
- **MMR多样性搜索**：确保结果多样性
- **批量搜索**：并行处理多个查询
- **RAG增强知识库**：DDL + 字段描述 + 查询示例 + 业务术语
- **自然语言转SQL**：将用户问题转换为可执行SQL
- **错误自动修复**：执行失败时自动分析并修复SQL
- **安全执行策略**：只读模式、自动LIMIT、超时控制

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
- H2 Database（Text2SQL示例数据库）

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
│      ↓
│  ┌──────────────────────────────────────────────┐
│  │           查询重写技术（4种）                  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐     │
│  │  │提示工程  │ │多查询分解│ │Step-Back │     │
│  │  │(排序/比较)│ │(复杂问题)│ │(推理问题)│     │
│  │  └──────────┘ └──────────┘ └──────────┘     │
│  │  ┌──────────┐                                │
│  │  │  HyDE   │ - 假设文档嵌入                 │
│  │  └──────────┘                                │
│  └──────────────────────────────────────────────┘
│      ↓
│  ┌──────────────────────────────────────────────┐
│  │           高级检索技术（4种）                  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐     │
│  │  │上下文压缩│ │ C-RAG   │ │父文档检索│     │
│  │  │(去噪音)  │ │(自校正) │ │(块+上下文)│     │
│  │  └──────────┘ └──────────┘ └──────────┘     │
│  │  ┌──────────┐                                │
│  │  │集成检索器│ - 多路召回+RRF融合             │
│  │  └──────────┘                                │
│  └──────────────────────────────────────────────┘
│      ↓
│  ┌──────────────────────┐
│  │   混合检索 (Hybrid)   │
│  │  ┌────────┐ ┌──────┐ │
│  │  │稠密检索│ │稀疏检索│ │
│  │  │(向量) │ │(BM25)│ │
│  │  └───┬────┘ └──┬───┘ │
│  │      └────┬────┘     │
│  │      [RRF融合排序]    │
│  └───────────┼──────────┘
│              ↓
│  最终答案 ← LLM生成 ← 提示工程 ← [句子窗口] ← [重排序] ← 检索结果
│
│  ┌──────────────────────────────────────────────┐
│  │           Text2SQL 流程（独立模块）           │
│  │                                              │
│  │  自然语言问题 → [知识库检索] → [SQL生成]      │
│  │       ↓                                    │
│  │  [安全执行] → 成功? → 是 → 返回结果          │
│  │       ↓                                    │
│  │       否 → [错误修复] → 重试                │
│  └──────────────────────────────────────────────┘
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

**方式1：使用curl上传文件**
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@/path/to/document.pdf"
```

**方式2：手动复制后上传（解决中文文件名问题）**
```bash
# 1. 复制文件到项目目录（避免中文路径问题）
cp "/Users/yangwentao01/Downloads/k8s命令操作手册.pdf" ~/Documents/wtyang/felix-ai-rag/

# 2. 使用curl上传
cd ~/Documents/wtyang/felix-ai-rag
curl -X POST http://localhost:8080/api/v1/rag/documents/file \
  -F "file=@k8s命令操作手册.pdf;type=application/pdf"
```

**方式3：使用API工具（Postman等）**
```
POST http://localhost:8080/api/v1/rag/documents/file
Content-Type: multipart/form-data
file: [选择文件]
```

**方式4：上传文本内容（无需文件）**
```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "粘贴文档文本内容...",
    "documentName": "k8s命令操作手册.txt",
    "documentType": "text",
    "description": "Kubernetes命令手册"
  }'
```

### 查询重写接口

#### 1. 结构化查询分析（排序/比较查询）
```bash
curl -X POST http://localhost:8080/api/v1/rag/query-rewrite/structured \
  -H "Content-Type: application/json" \
  -d '{"query": "时间最短的视频"}'

# 返回:
# {
#   "isStructured": true,
#   "sortBy": "length",
#   "order": "asc",
#   "description": "按length 升序排序，取前1个"
# }
```

#### 2. 多查询分解
```bash
curl -X POST http://localhost:8080/api/v1/rag/query-rewrite/multi-query \
  -H "Content-Type: application/json" \
  -d '{"query": "在《流浪地球》中，刘慈欣对人工智能和未来社会结构有何看法？"}'

# 返回:
# {
#   "subQueries": [
#     "《流浪地球》中描述的人工智能技术有哪些？",
#     "《流浪地球》中描绘的未来社会结构是怎样的？",
#     "刘慈欣关于人工智能的观点是什么？"
#   ]
# }
```

#### 3. Step-Back退步提示
```bash
curl -X POST http://localhost:8080/api/v1/rag/query-rewrite/step-back \
  -H "Content-Type: application/json" \
  -d '{"query": "在一个密闭容器中，加热气体后压力会如何变化？"}'

# 返回:
# {
#   "stepBackQuestion": "气体状态变化遵循什么物理定律？",
#   "strategy": "先检索通用原理，再结合原问题推理"
# }
```

#### 4. HyDE假设文档嵌入
```bash
curl -X POST http://localhost:8080/api/v1/rag/query-rewrite/hyde \
  -H "Content-Type: application/json" \
  -d '{"query": "什么是RAG技术？"}'

# 返回:
# {
#   "hypotheticalDocument": "RAG（Retrieval-Augmented Generation）是一种...",
#   "strategy": "使用假设文档的向量进行语义检索"
# }
```

#### 5. 综合查询重写（所有技术）
```bash
curl -X POST http://localhost:8080/api/v1/rag/query-rewrite/comprehensive \
  -H "Content-Type: application/json" \
  -d '{"query": "2023年播放量最高的技术视频"}'

# 返回所有适用的重写结果和策略
```

### 高级检索接口（新增）

#### 1. 上下文压缩检索
```bash
curl -X POST http://localhost:8080/api/v1/rag/advanced-retrieval/compress \
  -H "Content-Type: application/json" \
  -d '{"query": "机器学习", "maxResults": 5}'

# 返回:
# {
#   "originalCount": 10,
#   "compressedCount": 5,
#   "filteredCount": 3,
#   "averageCompressionRatio": "35.2%",
#   "compressedContents": [...]
# }
```

#### 2. C-RAG校正检索
```bash
curl -X POST http://localhost:8080/api/v1/rag/advanced-retrieval/crag \
  -H "Content-Type: application/json" \
  -d '{"query": "量子计算最新进展", "maxResults": 5}'

# 返回:
# {
#   "assessment": {
#     "grade": "AMBIGUOUS",
#     "reason": "检索到的文档信息不完整"
#   },
#   "actionTaken": "知识搜索（原始查询）",
#   "finalContentCount": 5
# }
```

### Text2SQL接口（新增）

#### 1. 自然语言查询
```bash
curl -X POST http://localhost:8080/api/v1/rag/text2sql/query \
  -H "Content-Type: application/json" \
  -d '{"question": "年龄大于30的用户有哪些"}'

# 返回:
# {
#   "success": true,
#   "userQuestion": "年龄大于30的用户有哪些",
#   "generatedSql": "SELECT * FROM users WHERE age > 30 LIMIT 100",
#   "explanation": "查询用户表中年龄大于30岁的所有用户信息",
#   "columns": ["id", "name", "age", "city"],
#   "rows": [
#     {"id": "2", "name": "李四", "age": "32", "city": "上海"}
#   ],
#   "rowCount": 1,
#   "retryCount": 0
# }
```

#### 2. 添加DDL知识
```bash
curl -X POST http://localhost:8080/api/v1/rag/text2sql/knowledge/ddl \
  -H "Content-Type: application/json" \
  -d '{
    "tableName": "users",
    "ddl": "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100), age INT)",
    "description": "用户表"
  }'
```

#### 3. 添加查询示例
```bash
curl -X POST http://localhost:8080/api/v1/rag/text2sql/knowledge/example \
  -H "Content-Type: application/json" \
  -d '{
    "question": "查询所有用户",
    "sql": "SELECT * FROM users LIMIT 100",
    "description": "查询示例"
  }'
```

### 混合检索接口

#### 基础混合检索
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

#### Self-Query检索
```bash
# 解析查询
curl -X POST http://localhost:8080/api/v1/rag/self-query/parse \
  -H "Content-Type: application/json" \
  -d '{"query": "2023年张三写的关于机器学习的论文"}'

# 执行Self-Query搜索
curl -X POST http://localhost:8080/api/v1/rag/self-query/search \
  -H "Content-Type: application/json" \
  -d '{"query": "技术部2023年Q1的产品报告"}'
```

#### 优化版RAG（整合所有优化技术）
```bash
curl -X POST http://localhost:8080/api/v1/rag/optimized/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是RAG？", "useRag": true}'
```

## 高级配置

### 1. 查询重写配置

```yaml
rag:
  query-rewrite:
    enabled: true               # 启用查询重写
    intent-analysis: true       # 启用意图分析
    
    # 多查询分解配置
    multi-query:
      enabled: true             # 启用多查询分解
      variations: 3             # 生成的子查询数量
    
    # 退步提示配置
    step-back:
      enabled: true             # 启用退步提示
    
    # HyDE配置
    hyde:
      enabled: false            # 启用HyDE（会增加LLM调用和嵌入计算）
```

### 2. 高级检索配置

```yaml
rag:
  advanced-retrieval:
    contextual-compression:
      enabled: true             # 启用上下文压缩

    corrective-rag:
      enabled: true             # 启用C-RAG校正检索
      max-iterations: 3         # 最大迭代次数
      web-search-enabled: false # 是否启用Web搜索

    parent-document:
      enabled: true             # 启用父文档检索

    ensemble:
      enabled: true             # 启用集成检索器
      rrf-k: 60                 # RRF融合参数
```

### 3. Text2SQL配置

```yaml
rag:
  text2sql:
    enabled: true               # 启用Text2SQL功能
    temperature: 0.0            # SQL生成温度（0表示确定性输出）
    max-retry-count: 3          # SQL错误修复最大重试次数
    top-k-retrieval: 5          # 知识库检索数量
    max-result-rows: 100        # 查询结果最大行数限制
    read-only: true             # 是否只读模式（只允许SELECT）
```

### 4. 混合检索配置

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

### 5. 句子窗口配置

```yaml
rag:
  chunk:
    strategy: sentence-window   # 句子窗口策略
    sentence-window:
      size: 3                   # 前后各3个句子

  sentence-window:
    enabled: true               # 检索后扩展为完整窗口
```

## 优化技术详解

### 1. 查询重写技术（4种）

参考 Datawhale All-In-RAG 查询重写章节

#### 1.1 提示工程（Prompt Engineering）

**原理**：通过精心设计的提示词，引导LLM将原始查询改写得更清晰，或生成可执行的结构化指令

**适用场景**：排序/比较类查询（最值查询）

**示例**：
```
输入: "时间最短的视频"
输出: {"sortBy": "length", "order": "asc"}
```

**优势**：直接可控，支持排序、过滤操作

#### 1.2 多查询分解（Multi-Query）

**原理**：将复杂问题拆分成多个更简单、更具体的子问题，分别检索后合并结果

**工作流程**：
```
原始复杂问题 → LLM分解为多个子问题 → 并行检索 → 合并去重 → 综合上下文 → 生成答案
```

**示例**：
```
原问题: "在《流浪地球》中，刘慈欣对人工智能和未来社会结构有何看法？"
子问题1: "《流浪地球》中描述的人工智能技术有哪些？"
子问题2: "《流浪地球》中描绘的未来社会结构是怎样的？"
子问题3: "刘慈欣关于人工智能的观点是什么？"
```

**优势**：覆盖全面，召回率高

#### 1.3 Step-Back退步提示

**原理**：面对细节繁多或过于具体的问题时，先"退后一步"探寻背后的通用原理，再基于原理进行具体推理

**两步流程**：
1. 抽象化：生成更高层次的"退步问题"
2. 推理：先获取通用原理，再结合原问题推理

**示例**：
```
具体问题: "在一个密闭容器中，加热气体后压力会如何变化？"
退步问题: "气体状态变化遵循什么物理定律？"
推理: 理想气体定律 PV=nRT → 温度升高 → 压力增大
```

**优势**：提高复杂推理准确性

#### 1.4 HyDE（Hypothetical Document Embeddings）

**原理**：不直接使用用户查询向量，而是先让LLM生成一个"假设性的理想答案文档"，再用该文档的向量去检索真实文档

**三步流程**：
1. **生成**：LLM根据查询生成详细的假设性答案文档
2. **编码**：将假设文档编码为向量嵌入
3. **检索**：用假设文档向量搜索最相似的真实文档

**核心洞察**：将困难的"查询→文档"匹配转化为容易的"文档→文档"匹配

**适用场景**：查询短、文档长，语义鸿沟大的场景

**优势**：提升语义匹配质量

### 2. 高级检索技术（4种）

参考 Datawhale All-In-RAG 高级检索技术章节

#### 2.1 上下文压缩（Contextual Compression）

**原理**：从初步检索到的文档中提取与查询最相关的部分，去除无关噪音

**两种方式**：
- **内容提取**：从文档中只抽出与查询相关的句子或段落
- **文档过滤**：丢弃经判断后不相关的整个文档

**适用场景**：检索结果包含大量无关内容

**优势**：减少上下文噪音，提高LLM处理效率

**工作流程**：
```
初始检索 → 相关性判断 → 内容提取 → 压缩结果
```

#### 2.2 C-RAG校正检索（Corrective RAG）

**原理**：自我反思机制，"检索-评估-行动"三步流程

**核心流程**：
1. **检索**：从知识库获取文档
2. **评估**：判断文档相关性
   - `CORRECT`：相关且足以回答
   - `INCORRECT`：不相关或无法回答
   - `AMBIGUOUS`：部分相关，信息不完整
3. **行动**：
   - CORRECT → **知识精炼**（分解为知识片段）
   - INCORRECT → **知识搜索**（查询重写 + Web搜索）
   - AMBIGUOUS → **知识搜索**（原始查询）

**适用场景**：需要高可靠性答案的场景

**优势**：自动检测并修正检索质量问题

#### 2.3 父文档检索（Parent Document Retrieval）

**原理**：小块检索匹配，大块提供上下文

**核心思想**：
- 检索时返回细粒度文本块（如句子/段落）用于匹配
- 但将更大的父文档（如整页/整节）作为上下文提供给LLM

**实现方式**：
- 索引阶段：建立子块到父文档的映射
- 检索阶段：小块匹配，返回父文档完整内容

**适用场景**：需要精确匹配但又要保持上下文连贯性

**优势**：平衡检索精度和上下文丰富性

#### 2.4 集成检索器（Ensemble Retriever）

**原理**：组合多个不同的检索器，使用RRF融合多路召回结果

**支持的检索器类型**：
- 稠密向量检索器（语义理解）
- 稀疏关键词检索器（精确匹配）
- 图检索器（关系推理）
- 自定义检索器

**RRF融合公式**：
```
RRF_score(d) = Σ(1 / (rank_i(d) + k))
```

**适用场景**：需要高召回率的场景

**优势**：综合利用多种检索策略的优势

### 3. 混合检索（Hybrid Search）

**原理**：结合稠密向量检索（语义理解）和稀疏关键词检索（精确匹配）

**融合策略**：
- **RRF** (Reciprocal Rank Fusion): 基于排名的融合，无需归一化
- **Weighted Sum**: 加权线性组合，可精细控制权重

**BM25公式**：
```
Score(Q, D) = Σ IDF(q_i) · [f(q_i,D)·(k1+1)] / [f(q_i,D) + k1·(1-b+b·|D|/avgdl)]
```

### 4. 句子窗口检索（Sentence Window Retrieval）

**原理**：为检索精确性而索引小块（句子），为上下文丰富性而检索大块（窗口）

**流程**：
1. 索引阶段：文档分割为句子，每个句子独立索引
2. 检索阶段：在单一句子上执行相似度搜索（高精度）
3. 后处理：用完整窗口文本替换单一句子（丰富上下文）

### 5. Self-Query检索

**原理**：使用LLM将自然语言查询解析为语义查询+元数据过滤条件

**示例**：
```
输入: "2023年张三写的关于机器学习的论文"
输出:
  - 语义查询: "机器学习 论文"
  - 过滤条件: year=2023, author=张三
```

### 6. Text2SQL

参考 Datawhale All-In-RAG Text2SQL章节

**原理**：利用LLM将自然语言问题转换为可执行的SQL查询语句

**核心流程**：
```
用户问题 → 知识库检索 → 构建上下文 → LLM生成SQL → 安全执行 → 返回结果
                ↓                              ↓
         [DDL/描述/示例]                  执行失败
                ↓                              ↓
         分层组织上下文                   错误修复 → 重试
```

**知识库组成**：
- **DDL**：表结构定义（CREATE TABLE语句）
- **字段描述**：表和字段的自然语言解释
- **查询示例**：问题-SQL示例对
- **业务术语**：同义词映射（如"花费"→cost字段）

**RAG增强优势**：
- 提供精确的数据库模式，减少LLM幻觉
- 检索相关表结构和示例，提高SQL准确性
- 支持业务术语映射，理解用户意图

**错误修复机制**：
- 执行SQL出错后，将错误信息反馈给LLM
- LLM分析错误原因并修复SQL
- 最多重试3次，确保最终成功

**安全策略**：
- 只读模式（只允许SELECT查询）
- 自动添加LIMIT限制结果数量
- 查询超时控制（30秒）

**内置数据库**：
项目内置H2内存数据库，开箱即用，包含以下示例表：
- **users** - 用户表（id, name, email, age, city, department）
- **orders** - 订单表（id, user_id, product_name, amount, status, order_date）
- **products** - 产品表（id, name, category, price, stock, description）

示例数据：8个用户、10个订单、8个产品

## 项目结构

```
felix-ai-rag/
├── src/main/java/com/felix/ai/rag/
│   ├── config/
│   │   └── RagConfiguration.java           # RAG配置
│   ├── controller/
│   │   ├── RagController.java              # 基础API
│   │   ├── QueryRewriteController.java     # 查询重写API
│   │   ├── AdvancedRetrievalController.java # 高级检索API
│   │   ├── Text2SqlController.java         # Text2SQL API
│   │   ├── HybridSearchController.java     # 混合检索API
│   │   ├── SelfQueryController.java        # Self-Query API
│   │   ├── OptimizedRagController.java     # 优化版RAG API
│   │   └── MultimodalController.java       # 多模态API
│   ├── service/
│   │   ├── RagService.java                 # 基础RAG服务
│   │   ├── OptimizedRagService.java        # 优化版RAG服务
│   │   ├── RerankerService.java            # 重排序服务
│   │   └── EnhancedSearchService.java      # 增强搜索服务
│   ├── rag/
│   │   └── CorrectiveRagService.java       # C-RAG服务
│   ├── text2sql/
│   │   ├── Text2SqlKnowledgeBase.java      # Text2SQL知识库
│   │   ├── Text2SqlGenerator.java          # SQL生成器
│   │   └── Text2SqlAgent.java              # Text2SQL Agent
│   ├── retriever/
│   │   ├── HybridRetriever.java            # 基础混合检索器
│   │   ├── AdvancedHybridRetriever.java    # 高级混合检索器
│   │   ├── ContextualCompressionRetriever.java # 上下文压缩（新增）
│   │   ├── ParentDocumentRetriever.java    # 父文档检索（新增）
│   │   └── EnsembleRetriever.java          # 集成检索器（新增）
│   ├── query/
│   │   ├── SelfQueryRetriever.java         # Self-Query解析
│   │   ├── QueryRewriteService.java        # 基础查询重写
│   │   ├── AdvancedQueryRewriteService.java # 高级查询重写
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

| 场景 | 推荐技术组合 |
|------|-------------|
| **通用知识问答** | 综合查询重写 + 高级检索(C-RAG) + 混合检索(RRF) + 句子窗口 |
| **排序/比较查询** | 结构化查询分析 + 元数据过滤 |
| **复杂多主题问题** | 多查询分解 + 集成检索器 + 混合检索 |
| **科学推理解题** | Step-Back退步提示 + 父文档检索 + 混合检索 |
| **短查询长文档** | HyDE + 上下文压缩 + 混合检索 |
| **精确术语搜索** | 提高关键词权重(0.7) + 元数据过滤 |
| **多主题探索** | MMR多样性搜索(diversity=0.5) |
| **结构化数据查询** | Self-Query + 元数据过滤 |
| **高可靠性要求** | C-RAG校正检索 + 上下文压缩 + 混合检索 |
| **长文档问答** | 父文档检索 + 句子窗口 + 上下文压缩 |
| **数据库自然语言查询** | Text2SQL + RAG增强知识库 |
| **数据分析报表** | Text2SQL + 查询重写 + 混合检索 |

## 技术选择指南

### 查询重写技术

| 技术 | 适用场景 | 开销 | 推荐度 |
|------|----------|------|--------|
| **提示工程** | 排序/比较查询 | 低 | ⭐⭐⭐⭐⭐ |
| **多查询分解** | 复杂多主题问题 | 中 | ⭐⭐⭐⭐ |
| **Step-Back** | 科学推理解题 | 低 | ⭐⭐⭐⭐ |
| **HyDE** | 短查询长文档 | 高 | ⭐⭐⭐ |

### 高级检索技术

| 技术 | 适用场景 | 开销 | 推荐度 |
|------|----------|------|--------|
| **上下文压缩** | 检索结果包含噪音 | 中 | ⭐⭐⭐⭐⭐ |
| **C-RAG** | 需要高可靠性 | 中 | ⭐⭐⭐⭐ |
| **父文档检索** | 需要精确+上下文 | 低 | ⭐⭐⭐⭐ |
| **集成检索器** | 需要高召回率 | 中 | ⭐⭐⭐⭐ |

### Text2SQL

| 配置项 | 说明 | 建议值 |
|--------|------|--------|
| **temperature** | SQL生成随机性 | 0.0（确定性） |
| **max-retry-count** | 错误修复重试次数 | 3 |
| **top-k-retrieval** | 知识库检索数量 | 5 |
| **max-result-rows** | 结果行数限制 | 100 |
| **read-only** | 只读模式 | true（生产环境） |

## 性能优化建议

### 1. 向量数据库选择
| 类型 | 适用场景 |
|------|----------|
| memory | 快速测试、开发调试 |
| redis | 生产环境、高性能需求 |
| qdrant | 高性能检索、过滤查询 |
| pgvector | 已有PostgreSQL环境 |

### 2. 查询重写性能
- **提示工程**：开销低，可默认启用
- **多查询分解**：会增加检索次数，适合复杂查询
- **Step-Back**：开销低，适合推理类查询
- **HyDE**：增加LLM调用和嵌入计算，按需启用

### 3. 高级检索性能
- **上下文压缩**：增加LLM调用，但减少后续处理token
- **C-RAG**：增加评估步骤，但提高答案质量
- **父文档检索**：索引时建立映射，检索时开销低
- **集成检索器**：多路并行，适合高召回场景

### 4. 检索优化
- 启用查询重写改善检索质量
- 使用批量搜索提高吞吐量
- 合理设置max-results和min-score
- 根据场景选择合适的技术组合

### 5. Text2SQL优化
- **知识库质量**：完善的DDL和字段描述是提高SQL准确性的关键
- **查询示例**：添加常见查询模式的示例，帮助LLM学习
- **业务术语**：建立业务术语到数据库字段的映射
- **错误修复**：利用自动修复机制处理边界情况
- **安全策略**：生产环境务必启用read-only模式

## 参考资源

- [Datawhale All-In-RAG 教程](https://github.com/datawhalechina/all-in-rag)
- [Datawhale All-In-RAG Text2SQL文档](https://github.com/datawhalechina/all-in-rag/blob/main/docs/chapter4/13_text2sql.md)
- [LangChain4J 官方文档](https://docs.langchain4j.dev/)
- [Ollama 官方文档](https://ollama.com/)

## License

MIT
