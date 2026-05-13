package com.felix.ai.rag.chunker;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构分块器
 * 基于 Markdown 文档结构进行智能分块
 *
 * 分块策略：
 * 1. 优先按标题层级分块（# ## ###）
 * 2. 代码块保持完整（```）
 * 3. 列表项适当合并
 * 4. 表格保持完整
 * 5. 段落作为最小单位
 *
 * 优势：
 * - 保持文档结构完整性
 * - 代码块不会被截断
 * - 标题和内容关联性强
 */
@Slf4j
public class MarkdownChunker implements TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    // Markdown 标题正则
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    // 代码块正则
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");
    // 表格正则
    private static final Pattern TABLE_PATTERN = Pattern.compile("(\\|[^\\n]+\\|\\n)+(\\|[-:\\s|]+\\|\\n)(\\|[^\\n]+\\|\\n*)+");

    public MarkdownChunker(int chunkSize, int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;

        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be less than chunkSize");
        }
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        log.debug("使用Markdown结构分块，文本长度: {}, chunkSize: {}", text.length(), chunkSize);

        // 1. 提取特殊块（代码块、表格）
        List<SpecialBlock> specialBlocks = extractSpecialBlocks(text);
        String processedText = maskSpecialBlocks(text, specialBlocks);

        // 2. 按标题分割文档
        List<DocumentSection> sections = splitByHeadings(processedText);

        // 3. 合并小节并恢复特殊块
        List<String> chunks = mergeSections(sections, specialBlocks);

        log.info("Markdown分块完成: {} 个块", chunks.size());
        return chunks;
    }

    /**
     * 提取特殊块（代码块、表格）
     */
    private List<SpecialBlock> extractSpecialBlocks(String text) {
        List<SpecialBlock> blocks = new ArrayList<>();

        // 提取代码块
        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(text);
        while (codeMatcher.find()) {
            blocks.add(new SpecialBlock(
                    codeMatcher.start(),
                    codeMatcher.end(),
                    codeMatcher.group(),
                    BlockType.CODE
            ));
        }

        // 提取表格
        Matcher tableMatcher = TABLE_PATTERN.matcher(text);
        while (tableMatcher.find()) {
            // 确保不与代码块重叠
            boolean overlaps = blocks.stream().anyMatch(b ->
                    b.overlaps(tableMatcher.start(), tableMatcher.end()));
            if (!overlaps) {
                blocks.add(new SpecialBlock(
                        tableMatcher.start(),
                        tableMatcher.end(),
                        tableMatcher.group(),
                        BlockType.TABLE
                ));
            }
        }

        // 按位置排序
        blocks.sort((a, b) -> Integer.compare(a.start, b.start));
        return blocks;
    }

    /**
     * 用占位符替换特殊块
     */
    private String maskSpecialBlocks(String text, List<SpecialBlock> blocks) {
        StringBuilder sb = new StringBuilder(text);
        int offset = 0;

        for (int i = 0; i < blocks.size(); i++) {
            SpecialBlock block = blocks.get(i);
            int start = block.start + offset;
            int end = block.end + offset;
            String placeholder = "<<<SPECIAL_BLOCK_" + i + ">>>";

            sb.replace(start, end, placeholder);
            offset += placeholder.length() - (end - start);
        }

        return sb.toString();
    }

    /**
     * 按标题分割文档
     */
    private List<DocumentSection> splitByHeadings(String text) {
        List<DocumentSection> sections = new ArrayList<>();

        Matcher matcher = HEADING_PATTERN.matcher(text);
        int lastEnd = 0;
        String lastHeading = "";
        int lastLevel = 0;

        while (matcher.find()) {
            // 保存上一个章节
            if (lastEnd < matcher.start()) {
                String content = text.substring(lastEnd, matcher.start()).trim();
                if (!content.isEmpty()) {
                    sections.add(new DocumentSection(lastHeading, lastLevel, content));
                }
            }

            // 记录新标题
            lastHeading = matcher.group(2).trim();
            lastLevel = matcher.group(1).length();
            lastEnd = matcher.end();
        }

        // 保存最后一个章节
        if (lastEnd < text.length()) {
            String content = text.substring(lastEnd).trim();
            if (!content.isEmpty()) {
                sections.add(new DocumentSection(lastHeading, lastLevel, content));
            }
        }

        return sections;
    }

    /**
     * 合并小节为块
     */
    private List<String> mergeSections(List<DocumentSection> sections, List<SpecialBlock> specialBlocks) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentLevel = 0;

        for (DocumentSection section : sections) {
            String sectionContent = restoreSpecialBlocks(section.content, specialBlocks);
            String sectionText = section.heading.isEmpty()
                    ? sectionContent
                    : section.heading + "\n" + sectionContent;

            // 如果当前块为空，直接添加
            if (currentChunk.length() == 0) {
                currentChunk.append(sectionText);
                currentLevel = section.level;
                continue;
            }

            // 检查是否需要新开一个块
            boolean shouldStartNewChunk = false;

            // 1. 当前块已接近上限
            if (currentChunk.length() + sectionText.length() > chunkSize * 0.8) {
                shouldStartNewChunk = true;
            }

            // 2. 遇到更高级别的标题（层级更小）
            if (section.level <= currentLevel && section.level <= 2) {
                shouldStartNewChunk = true;
            }

            // 3. 单个章节就超过限制
            if (sectionText.length() > chunkSize) {
                // 先保存当前块
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                // 分割大章节
                chunks.addAll(splitLargeSection(sectionText));
                currentLevel = section.level;
                continue;
            }

            if (shouldStartNewChunk) {
                chunks.add(currentChunk.toString());
                // 保留重叠内容（上一个章节的标题）
                currentChunk = new StringBuilder();
                if (!section.heading.isEmpty() && chunkOverlap > 0) {
                    currentChunk.append(section.heading).append("\n");
                }
                currentChunk.append(sectionText);
            } else {
                currentChunk.append("\n\n").append(sectionText);
            }

            currentLevel = section.level;
        }

        // 保存最后一个块
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 恢复特殊块
     */
    private String restoreSpecialBlocks(String text, List<SpecialBlock> blocks) {
        String result = text;
        for (int i = 0; i < blocks.size(); i++) {
            String placeholder = "<<<SPECIAL_BLOCK_" + i + ">>>";
            result = result.replace(placeholder, blocks.get(i).content);
        }
        return result;
    }

    /**
     * 分割大章节
     */
    private List<String> splitLargeSection(String sectionText) {
        List<String> parts = new ArrayList<>();

        // 尝试按段落分割
        String[] paragraphs = sectionText.split("\n\n");
        StringBuilder currentPart = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (currentPart.length() + paragraph.length() > chunkSize) {
                if (currentPart.length() > 0) {
                    parts.add(currentPart.toString());
                    currentPart = new StringBuilder();
                }
                // 如果单个段落还太大，强制切分
                if (paragraph.length() > chunkSize) {
                    for (int i = 0; i < paragraph.length(); i += chunkSize - chunkOverlap) {
                        int end = Math.min(i + chunkSize, paragraph.length());
                        parts.add(paragraph.substring(i, end));
                    }
                } else {
                    currentPart.append(paragraph);
                }
            } else {
                if (currentPart.length() > 0) {
                    currentPart.append("\n\n");
                }
                currentPart.append(paragraph);
            }
        }

        if (currentPart.length() > 0) {
            parts.add(currentPart.toString());
        }

        return parts;
    }

    @Override
    public String getStrategyName() {
        return "markdown";
    }

    // ==================== 内部类 ====================

    private enum BlockType {
        CODE, TABLE
    }

    private static class SpecialBlock {
        final int start;
        final int end;
        final String content;
        final BlockType type;

        SpecialBlock(int start, int end, String content, BlockType type) {
            this.start = start;
            this.end = end;
            this.content = content;
            this.type = type;
        }

        boolean overlaps(int otherStart, int otherEnd) {
            return start < otherEnd && end > otherStart;
        }
    }

    private static class DocumentSection {
        final String heading;
        final int level;
        final String content;

        DocumentSection(String heading, int level, String content) {
            this.heading = heading;
            this.level = level;
            this.content = content;
        }
    }
}
