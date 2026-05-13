package com.felix.ai.rag.processor;

import com.felix.ai.rag.chunker.SentenceWindowChunker;
import dev.langchain4j.rag.content.Content;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 句子窗口后处理器
 * 参考 LlamaIndex MetadataReplacementPostProcessor 实现
 *
 * 作用：检索后用完整窗口文本替换单一句子，实现上下文扩展
 * 解决小块检索精度高但上下文不足的问题
 */
@Component
@Slf4j
public class SentenceWindowProcessor {

    @Value("${rag.sentence-window.enabled:true}")
    private boolean enabled;

    /**
     * 处理检索结果，将句子窗口替换为完整上下文
     *
     * @param contents 检索到的内容列表
     * @return 替换后的内容列表
     */
    public List<Content> process(List<Content> contents) {
        if (!enabled) {
            log.debug("句子窗口处理器已禁用");
            return contents;
        }

        if (contents == null || contents.isEmpty()) {
            return contents;
        }

        log.debug("处理 {} 个检索结果的句子窗口", contents.size());

        return contents.stream()
                .map(this::replaceWithWindow)
                .collect(Collectors.toList());
    }

    /**
     * 将单个内容替换为完整窗口
     */
    private Content replaceWithWindow(Content content) {
        if (content == null || content.textSegment() == null) {
            return content;
        }

        String originalText = content.textSegment().text();

        // 检查是否是句子窗口格式
        if (!isSentenceWindowFormat(originalText)) {
            // 不是句子窗口格式，直接返回原内容
            return content;
        }

        // 提取完整窗口文本
        String fullWindow = SentenceWindowChunker.extractFullWindow(originalText);

        if (fullWindow.isEmpty()) {
            log.warn("无法提取窗口内容，返回原始文本");
            return content;
        }

        log.debug("句子窗口替换: 原始长度 {} -> 窗口长度 {}",
                originalText.length(), fullWindow.length());

        // 创建新的Content对象，使用完整窗口文本
        return Content.from(dev.langchain4j.data.segment.TextSegment.from(fullWindow));
    }

    /**
     * 检查文本是否是句子窗口格式
     */
    private boolean isSentenceWindowFormat(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 检查是否包含句子窗口的标记
        return text.contains("[CENTER]") && text.contains("[CENTER_END]");
    }

    /**
     * 提取中心句子（用于调试或日志）
     */
    public String extractCenterSentence(String serializedChunk) {
        return SentenceWindowChunker.extractCenterSentence(serializedChunk);
    }

    /**
     * 是否启用句子窗口处理
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置启用状态
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
