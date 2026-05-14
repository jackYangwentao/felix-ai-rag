# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
mvn clean install

# Run (requires Java 17)
mvn spring-boot:run

# If JAVA_HOME needs to be set explicitly
export JAVA_HOME=/Users/yangwentao01/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=RagServiceTest
```

> Tests require Ollama running at `localhost:11434` — they will fail without it.

## Prerequisites (Ollama)

```bash
brew install ollama
ollama pull llama3.2          # Chat model (configured in application.yml)
ollama pull nomic-embed-text  # Embedding model
ollama pull llava-phi3        # Vision model (for multimodal features)
ollama serve
```

## Architecture

The application is a Spring Boot RAG system built on **LangChain4J 0.31.0**. All major components are wired via Spring beans in `RagConfiguration.java`.

### RAG Pipeline

```
Document → Chunker (strategy-specific) → EmbeddingModel → EmbeddingStore
                                                                  ↓
User Query → EmbeddingModel → Similarity Search → SentenceWindowProcessor
                                                          ↓
                                              Context + Prompt Template → LLM → Answer
```

### Package Structure

| Package | Responsibility |
|---|---|
| `config/` | Spring bean wiring: models, vector stores (5 supported), content retriever |
| `loader/` | File loading + Apache Tika parsing + PDF image extraction |
| `chunker/` | Strategy pattern: `FixedSizeChunker`, `RecursiveChunker`, `SemanticChunker`, `SentenceWindowChunker`, `MarkdownChunker` — created via `ChunkerFactory` which auto-selects by file type |
| `service/` | Core logic: `RagService` (index + chat), `MultimodalService` (vision), `RerankerService`, `EnhancedSearchService` |
| `service/incremental/` | Incremental import: MD5/SHA256 fingerprinting, CRUD/UPDATED/SKIPPED/DELETED states; `InMemoryIncrementalDocumentService` for dev, `RedisIncrementalDocumentService` for prod (auto-switched by `@ConditionalOnProperty`) |
| `query/` | Query transforms: `QueryRewriteService`, `AdvancedQueryRewriteService`, `QueryExpansionService`, `SelfQueryRetriever` |
| `retriever/` | Retrieval strategies: `HybridRetriever` (BM25 + dense), `AdvancedHybridRetriever`, `EnsembleRetriever` (RRF fusion), `ContextualCompressionRetriever`, `ParentDocumentRetriever` |
| `rag/` | `CorrectiveRagService`: self-reflection loop (retrieve → evaluate → act, up to N iterations) |
| `text2sql/` | `Text2SqlKnowledgeBase` (DDL + examples as RAG), `Text2SqlGenerator`, `Text2SqlAgent` with auto-retry on SQL errors |
| `controller/` | REST API — one controller per feature domain |

### Vector Store Selection

Controlled by `rag.vector-store.type` in `application.yml`. Default is `memory` (in-process, non-persistent). Switch to `redis`, `chroma`, `qdrant`, or `pgvector` for persistence. All five are wired with `@ConditionalOnProperty`.

### Key Configuration (`application.yml`)

```yaml
langchain4j.ollama:
  chat-model.model-name: llama3.2       # swap model name here
  embedding-model.model-name: nomic-embed-text

rag:
  embedding-dimension: 768  # MUST match embedding model output (nomic-embed-text=768, OpenAI ada=1536)
  max-results: 3
  min-score: 0.7
  chunk.strategy: fixed     # fixed | recursive | semantic | sentence-window
  vector-store.type: memory # memory | redis | chroma | qdrant | pgvector
```

### REST API Endpoints

| Controller | Base Path |
|---|---|
| `RagController` | `/api/v1/rag` — chat, document upload, semantic search |
| `MultimodalController` | `/api/v1/rag/multimodal` — describe/chat with images |
| `HybridSearchController` | — BM25 + dense hybrid search |
| `QueryRewriteController` | — multi-query, step-back, HyDE rewrite |
| `SelfQueryController` | — natural language → metadata filter |
| `AdvancedRetrievalController` | — C-RAG, parent-doc, ensemble |
| `OptimizedRagController` | — combined pipeline (rewrite + hybrid + window + rerank) |
| `IncrementalDocumentController` | — incremental import/sync |
| `Text2SqlController` | — NL → SQL with auto-fix |

H2 console (Text2SQL dev database) is available at `http://localhost:8080/h2-console`.
