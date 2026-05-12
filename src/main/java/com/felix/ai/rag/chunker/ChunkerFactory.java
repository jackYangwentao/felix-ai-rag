package com.felix.ai.rag.chunker;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 分块器工厂
 * 根据配置创建对应的分块策略实现
 */
@Component
@Slf4j
public class ChunkerFactory {

    @Value("${rag.chunk.strategy:recursive}")
    private String chunkStrategy;

    @Value("${rag.chunk.size:1000}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:200}")
    private int chunkOverlap;

    @Value("${rag.chunk.separator:}")
    private String separator;

    @Value("${rag.chunk.semantic.threshold-type:percentile}")
    private String semanticThresholdType;

    @Value("${rag.chunk.semantic.threshold-amount:95.0}")
    private double semanticThresholdAmount;

    @Value("${rag.chunk.semantic.buffer-size:1}")
    private int semanticBufferSize;

    private final EmbeddingModel embeddingModel;

    public ChunkerFactory(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 创建分块器
     *
     * @return 对应策略的分块器
     */
    public TextChunker createChunker() {
        log.info("创建分块器，策略: {}, chunkSize: {}, chunkOverlap: {}",
                chunkStrategy, chunkSize, chunkOverlap);

        switch (chunkStrategy.toLowerCase()) {
            case "fixed":
            case "fixed-size":
                return createFixedSizeChunker();

            case "recursive":
                return createRecursiveChunker();

            case "semantic":
                return createSemanticChunker();

            case "code":
            case "programming":
                return createCodeChunker();

            default:
                log.warn("未知的分块策略: {}，使用默认的递归分块", chunkStrategy);
                return createRecursiveChunker();
        }
    }

    /**
     * 创建固定大小分块器
     */
    private TextChunker createFixedSizeChunker() {
        String sep = separator.isEmpty() ? "\n\n" : separator;
        log.debug("创建固定大小分块器，分隔符: '{}'", sep.replace("\n", "\\n"));
        return new FixedSizeChunker(chunkSize, chunkOverlap, sep);
    }

    /**
     * 创建递归分块器
     */
    private TextChunker createRecursiveChunker() {
        log.debug("创建递归分块器");
        return new RecursiveChunker(chunkSize, chunkOverlap, null);
    }

    /**
     * 创建代码分块器（使用代码专用分隔符）
     */
    private TextChunker createCodeChunker() {
        log.debug("创建代码分块器");
        List<String> codeSeparators = Arrays.asList(
                "\nclass ",
                "\npublic class ",
                "\ninterface ",
                "\npublic interface ",
                "\nenum ",
                "\npublic enum ",
                "\n\n",
                "\n",
                " ",
                ""
        );
        return new RecursiveChunker(chunkSize, chunkOverlap, codeSeparators);
    }

    /**
     * 创建语义分块器
     */
    private TextChunker createSemanticChunker() {
        log.debug("创建语义分块器，阈值类型: {}, 阈值: {}, bufferSize: {}",
                semanticThresholdType, semanticThresholdAmount, semanticBufferSize);

        SemanticChunker.BreakpointThresholdType thresholdType;
        try {
            thresholdType = SemanticChunker.BreakpointThresholdType.valueOf(
                    semanticThresholdType.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的阈值类型: {}，使用默认 PERCENTILE", semanticThresholdType);
            thresholdType = SemanticChunker.BreakpointThresholdType.PERCENTILE;
        }

        return new SemanticChunker(embeddingModel, thresholdType,
                semanticThresholdAmount, semanticBufferSize);
    }

    /**
     * 获取当前分块策略名称
     */
    public String getCurrentStrategy() {
        return chunkStrategy;
    }
}
