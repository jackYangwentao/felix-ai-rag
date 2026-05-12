package com.felix.ai.rag.chunker;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 递归字符分块器
 * 参考 LangChain RecursiveCharacterTextSplitter 实现
 *
 * 特点：
 * - 分层分隔符优先级递归处理
 * - 从粗粒度到细粒度：段落 → 句子 → 单词 → 字符
 * - 优先保持语义结构完整
 */
@Slf4j
public class RecursiveChunker implements TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;

    /**
     * 默认分隔符优先级（中文/英文通用）
     */
    public static final List<String> DEFAULT_SEPARATORS = Arrays.asList(
            "\n\n",   // 段落
            "\n",     // 换行
            "。",      // 中文句号
            "，",      // 中文逗号
            ".",      // 英文句号
            ",",      // 英文逗号
            " ",      // 空格
            ""        // 字符
    );

    /**
     * 编程语言专用分隔符
     */
    public static final List<String> CODE_SEPARATORS = Arrays.asList(
            "\nclass ",
            "\ndef ",
            "\n\tdef ",
            "\n\n",
            "\n",
            " ",
            ""
    );

    public RecursiveChunker(int chunkSize, int chunkOverlap, List<String> separators) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = separators != null ? separators : DEFAULT_SEPARATORS;

        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be less than chunkSize");
        }
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        log.debug("使用递归分块，文本长度: {}, chunkSize: {}, chunkOverlap: {}",
                text.length(), chunkSize, chunkOverlap);

        return recursiveSplit(text, 0);
    }

    /**
     * 递归分割文本
     *
     * @param text 待分割文本
     * @param separatorIndex 当前使用的分隔符索引
     * @return 分割后的块列表
     */
    private List<String> recursiveSplit(String text, int separatorIndex) {
        // 如果文本已经小于 chunkSize，直接返回
        if (text.length() <= chunkSize) {
            List<String> result = new ArrayList<>();
            result.add(text);
            return result;
        }

        // 如果已经没有分隔符可用，强制切分
        if (separatorIndex >= separators.size()) {
            return forceSplit(text);
        }

        String separator = separators.get(separatorIndex);
        List<String> chunks = new ArrayList<>();
        List<String> goodSplits = new ArrayList<>();
        int currentLength = 0;

        // 使用当前分隔符分割文本
        String[] splits;
        if (separator.isEmpty()) {
            // 空分隔符表示逐字符分割
            splits = text.split("");
        } else {
            // 注意：split 的参数是正则表达式，需要对特殊字符转义
            String regex = separator.replace("\\", "\\\\")
                    .replace(".", "\\.")
                    .replace("|", "\\|")
                    .replace("$", "\\$")
                    .replace("^", "\\^")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                    .replace("{", "\\{")
                    .replace("}", "\\}")
                    .replace("+", "\\+")
                    .replace("*", "\\*")
                    .replace("?", "\\?");
            splits = text.split(regex, -1);
        }

        for (String split : splits) {
            if (split.isEmpty()) {
                continue;
            }

            int splitLength = split.length();

            // 如果单个片段就超过 chunkSize，递归处理
            if (splitLength > chunkSize) {
                // 先保存已累积的合格片段
                if (!goodSplits.isEmpty()) {
                    chunks.addAll(mergeSplits(goodSplits, separator));
                    goodSplits.clear();
                    currentLength = 0;
                }

                // 递归使用更细粒度的分隔符分割这个超长片段
                chunks.addAll(recursiveSplit(split, separatorIndex + 1));
                continue;
            }

            // 如果加入当前片段会超过限制，先合并保存已累积的片段
            if (currentLength + splitLength + separator.length() > chunkSize && !goodSplits.isEmpty()) {
                chunks.addAll(mergeSplits(goodSplits, separator));

                // 保留重叠部分
                goodSplits = getOverlapSplits(goodSplits);
                currentLength = calculateTotalLength(goodSplits, separator);
            }

            goodSplits.add(split);
            currentLength += splitLength + (goodSplits.size() > 1 ? separator.length() : 0);
        }

        // 合并剩余的合格片段
        if (!goodSplits.isEmpty()) {
            chunks.addAll(mergeSplits(goodSplits, separator));
        }

        return chunks;
    }

    /**
     * 合并分割片段为块
     */
    private List<String> mergeSplits(List<String> splits, String separator) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < splits.size(); i++) {
            if (i > 0) {
                currentChunk.append(separator);
            }
            currentChunk.append(splits.get(i));
        }

        chunks.add(currentChunk.toString());
        return chunks;
    }

    /**
     * 获取重叠的片段
     */
    private List<String> getOverlapSplits(List<String> splits) {
        List<String> overlapSplits = new ArrayList<>();
        int overlapLength = 0;

        for (int i = splits.size() - 1; i >= 0; i--) {
            String split = splits.get(i);
            overlapSplits.add(0, split);
            overlapLength += split.length();

            if (overlapLength >= chunkOverlap) {
                break;
            }
        }

        return overlapSplits;
    }

    /**
     * 计算总长度
     */
    private int calculateTotalLength(List<String> splits, String separator) {
        if (splits.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String split : splits) {
            total += split.length();
        }
        total += (splits.size() - 1) * separator.length();
        return total;
    }

    /**
     * 强制切分（当所有分隔符都用尽时）
     */
    private List<String> forceSplit(String text) {
        List<String> result = new ArrayList<>();

        for (int i = 0; i < text.length(); i += chunkSize - chunkOverlap) {
            int end = Math.min(i + chunkSize, text.length());
            result.add(text.substring(i, end));

            if (end == text.length()) {
                break;
            }
        }

        return result;
    }

    @Override
    public String getStrategyName() {
        return "recursive";
    }
}
