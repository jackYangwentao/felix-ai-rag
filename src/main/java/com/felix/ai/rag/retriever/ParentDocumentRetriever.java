package com.felix.ai.rag.retriever;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 父文档检索器 (Parent Document Retrieval)
 * 参考 Datawhale All-In-RAG 高级检索技术
 *
 * 核心思想：
 * - 检索时返回细粒度文本块（如句子/段落）用于匹配
 * - 但将更大的父文档（如整页/整节）作为上下文提供给LLM
 *
 * 优势：
 * - 小块保证检索精度
 * - 大块保证上下文丰富性
 * - 避免信息碎片化
 */
@Component
@Slf4j
public class ParentDocumentRetriever {

    // 子块到父文档的映射
    private final Map<String, ParentDocument> childToParentMap;

    // 子块向量存储（用于检索）
    private final EmbeddingStore<TextSegment> childEmbeddingStore;

    public ParentDocumentRetriever() {
        this.childToParentMap = new HashMap<>();
        this.childEmbeddingStore = new InMemoryEmbeddingStore<>();
    }

    /**
     * 索引文档 - 将文档分块并建立父子关系
     */
    public void indexDocument(String documentId, String content, int chunkSize, int chunkOverlap) {
        log.info("父文档检索器索引文档: id={}, length={}", documentId, content.length());

        // 创建父文档
        ParentDocument parentDoc = ParentDocument.builder()
                .id(documentId)
                .content(content)
                .metadata(Map.of("source", documentId, "type", "parent"))
                .build();

        // 分块（简化实现，实际应使用更复杂的分块策略）
        List<TextChunk> chunks = splitIntoChunks(content, chunkSize, chunkOverlap);

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String childId = documentId + "_chunk_" + i;

            // 建立子块到父文档的映射
            childToParentMap.put(childId, parentDoc);

            // 存储子块（实际应该计算嵌入向量后存储）
            // 这里简化处理
            log.debug("建立映射: {} -> {}", childId, documentId);
        }

        log.info("文档索引完成: {} 个子块", chunks.size());
    }

    /**
     * 检索 - 返回父文档
     */
    public List<RetrievalResult> retrieve(List<String> childChunkIds, int maxResults) {
        log.info("父文档检索: {} 个子块ID", childChunkIds.size());

        Set<String> seenParentIds = new HashSet<>();
        List<RetrievalResult> results = new ArrayList<>();

        for (String childId : childChunkIds) {
            ParentDocument parent = childToParentMap.get(childId);

            if (parent != null && !seenParentIds.contains(parent.getId())) {
                seenParentIds.add(parent.getId());

                results.add(RetrievalResult.builder()
                        .parentDocument(parent)
                        .matchedChildId(childId)
                        .build());

                if (results.size() >= maxResults) {
                    break;
                }
            }
        }

        log.info("父文档检索完成: 返回 {} 个父文档", results.size());
        return results;
    }

    /**
     * 获取父文档内容（用于上下文）
     */
    public String getParentContent(String childChunkId) {
        ParentDocument parent = childToParentMap.get(childChunkId);
        return parent != null ? parent.getContent() : null;
    }

    /**
     * 简单的分块实现
     */
    private List<TextChunk> splitIntoChunks(String content, int chunkSize, int chunkOverlap) {
        List<TextChunk> chunks = new ArrayList<>();

        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());

            // 尝试在句子边界处截断
            if (end < content.length()) {
                int lastPeriod = content.lastIndexOf("。", end);
                int lastNewline = content.lastIndexOf("\n", end);
                int breakPoint = Math.max(lastPeriod, lastNewline);

                if (breakPoint > start) {
                    end = breakPoint + 1;
                }
            }

            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(TextChunk.builder()
                        .content(chunk)
                        .startIndex(start)
                        .endIndex(end)
                        .build());
            }

            start = end - chunkOverlap;
            if (start >= end) {
                start = end;
            }
        }

        return chunks;
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class ParentDocument {
        private String id;
        private String content;
        private Map<String, String> metadata;
    }

    @Data
    @Builder
    public static class TextChunk {
        private String content;
        private int startIndex;
        private int endIndex;
    }

    @Data
    @Builder
    public static class RetrievalResult {
        private ParentDocument parentDocument;
        private String matchedChildId;
    }
}
