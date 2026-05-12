package com.felix.ai.rag.chunker;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 语义分块器
 * 参考 LangChain Experimental SemanticChunker 实现
 *
 * 特点：
 * - 基于语义相似度检测断点
 * - 使用嵌入向量计算句子间余弦相似度
 * - 动态阈值识别语义跳跃点
 */
@Slf4j
public class SemanticChunker implements TextChunker {

    private final EmbeddingModel embeddingModel;
    private final BreakpointThresholdType thresholdType;
    private final double thresholdAmount;
    private final int bufferSize;

    /**
     * 断点识别方法类型
     */
    public enum BreakpointThresholdType {
        /**
         * 百分位方法（默认）
         * 使用第 N 百分位作为阈值
         */
        PERCENTILE,

        /**
         * 标准差方法
         * 使用均值 + N 倍标准差作为阈值
         */
        STANDARD_DEVIATION,

        /**
         * 四分位距方法
         * 使用 Q3 + N * IQR 作为阈值
         */
        INTERQUARTILE
    }

    public SemanticChunker(EmbeddingModel embeddingModel) {
        this(embeddingModel, BreakpointThresholdType.PERCENTILE, 95.0, 1);
    }

    public SemanticChunker(EmbeddingModel embeddingModel,
                           BreakpointThresholdType thresholdType,
                           double thresholdAmount,
                           int bufferSize) {
        this.embeddingModel = embeddingModel;
        this.thresholdType = thresholdType != null ? thresholdType : BreakpointThresholdType.PERCENTILE;
        this.thresholdAmount = thresholdAmount;
        this.bufferSize = bufferSize;

        if (bufferSize < 0) {
            throw new IllegalArgumentException("bufferSize must be non-negative");
        }
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        log.debug("使用语义分块，文本长度: {}, 阈值类型: {}, 阈值: {}, bufferSize: {}",
                text.length(), thresholdType, thresholdAmount, bufferSize);

        // 1. 将文本分割为句子
        List<String> sentences = splitIntoSentences(text);
        if (sentences.size() <= 1) {
            return sentences.isEmpty() ? new ArrayList<>() : sentences;
        }

        log.debug("分割为 {} 个句子", sentences.size());

        // 2. 为每个句子生成嵌入向量（带上下文缓冲）
        List<float[]> embeddings = generateEmbeddings(sentences);

        // 3. 计算相邻句子间的余弦距离
        List<Double> distances = calculateCosineDistances(embeddings);

        // 4. 根据阈值类型确定断点
        double threshold = calculateThreshold(distances);

        // 5. 根据断点合并句子为块
        List<String> chunks = mergeSentencesByBreakpoints(sentences, distances, threshold);

        log.debug("语义分块完成，生成 {} 个块", chunks.size());
        return chunks;
    }

    /**
     * 将文本分割为句子
     */
    private List<String> splitIntoSentences(String text) {
        // 使用中英文句子分隔符分割
        String[] splitters = {"。", "？", "！", "\n", ".", "?", "!"};

        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);

            // 检查是否是句子结束符
            boolean isSentenceEnd = false;
            for (String splitter : splitters) {
                if (splitter.length() == 1 && c == splitter.charAt(0)) {
                    isSentenceEnd = true;
                    break;
                }
            }

            if (isSentenceEnd && current.length() > 10) { // 至少10个字符才算一个句子
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current = new StringBuilder();
            }
        }

        // 添加最后剩余的文本
        if (current.length() > 0) {
            String sentence = current.toString().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    /**
     * 生成句子的嵌入向量
     * 带上下文缓冲：将前后 bufferSize 个句子组合后嵌入
     */
    private List<float[]> generateEmbeddings(List<String> sentences) {
        List<float[]> embeddings = new ArrayList<>();
        int embeddingDimension = -1;

        for (int i = 0; i < sentences.size(); i++) {
            // 构建带上下文的句子
            StringBuilder contextText = new StringBuilder();

            // 添加上下文（前面 bufferSize 个句子）
            for (int j = Math.max(0, i - bufferSize); j < i; j++) {
                contextText.append(sentences.get(j)).append(" ");
            }

            // 添加当前句子
            contextText.append(sentences.get(i));

            // 添加后文（后面 bufferSize 个句子）
            for (int j = i + 1; j < Math.min(sentences.size(), i + 1 + bufferSize); j++) {
                contextText.append(" ").append(sentences.get(j));
            }

            // 生成嵌入
            try {
                Embedding embedding = embeddingModel.embed(contextText.toString()).content();
                float[] vector = embedding.vector();
                if (embeddingDimension == -1) {
                    embeddingDimension = vector.length;
                }
                embeddings.add(vector);
            } catch (Exception e) {
                log.error("生成嵌入失败，句子索引: {}", i, e);
                // 使用零向量作为回退
                if (embeddingDimension == -1) {
                    // 首次失败时使用默认维度
                    embeddingDimension = 768; // nomic-embed-text 默认维度
                }
                embeddings.add(new float[embeddingDimension]);
            }
        }

        return embeddings;
    }

    /**
     * 计算余弦距离（1 - 余弦相似度）
     */
    private List<Double> calculateCosineDistances(List<float[]> embeddings) {
        List<Double> distances = new ArrayList<>();

        for (int i = 0; i < embeddings.size() - 1; i++) {
            float[] vec1 = embeddings.get(i);
            float[] vec2 = embeddings.get(i + 1);

            double similarity = cosineSimilarity(vec1, vec2);
            double distance = 1.0 - similarity;
            distances.add(distance);
        }

        return distances;
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("Vectors must have same dimension");
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 根据阈值类型计算断点阈值
     */
    private double calculateThreshold(List<Double> distances) {
        if (distances.isEmpty()) {
            return 0.0;
        }

        double[] sorted = distances.stream().mapToDouble(Double::doubleValue).sorted().toArray();

        switch (thresholdType) {
            case PERCENTILE:
                int index = (int) Math.ceil(sorted.length * thresholdAmount / 100.0) - 1;
                index = Math.max(0, Math.min(index, sorted.length - 1));
                return sorted[index];

            case STANDARD_DEVIATION:
                double mean = Arrays.stream(sorted).average().orElse(0.0);
                double variance = Arrays.stream(sorted)
                        .map(d -> Math.pow(d - mean, 2))
                        .average()
                        .orElse(0.0);
                double stdDev = Math.sqrt(variance);
                return mean + thresholdAmount * stdDev;

            case INTERQUARTILE:
                int q1Index = sorted.length / 4;
                int q3Index = sorted.length * 3 / 4;
                double q1 = sorted[q1Index];
                double q3 = sorted[q3Index];
                double iqr = q3 - q1;
                return q3 + thresholdAmount * iqr;

            default:
                return sorted[sorted.length - 1];
        }
    }

    /**
     * 根据断点合并句子为块
     */
    private List<String> mergeSentencesByBreakpoints(List<String> sentences,
                                                      List<Double> distances,
                                                      double threshold) {
        List<String> chunks = new ArrayList<>();
        List<String> currentChunk = new ArrayList<>();

        for (int i = 0; i < sentences.size(); i++) {
            currentChunk.add(sentences.get(i));

            // 检查是否需要在此处断点（除了最后一个句子）
            if (i < distances.size() && distances.get(i) > threshold) {
                // 这是一个语义断点，保存当前块
                chunks.add(String.join("", currentChunk));
                currentChunk = new ArrayList<>();
            }
        }

        // 添加最后一个块
        if (!currentChunk.isEmpty()) {
            chunks.add(String.join("", currentChunk));
        }

        return chunks;
    }

    @Override
    public String getStrategyName() {
        return "semantic";
    }
}
