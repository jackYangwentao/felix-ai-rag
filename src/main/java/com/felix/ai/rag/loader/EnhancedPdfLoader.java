package com.felix.ai.rag.loader;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 增强版 PDF 加载器
 * 支持提取文本和图片（用于多模态处理）
 */
@Slf4j
public class EnhancedPdfLoader {

    private final Parser parser;

    public EnhancedPdfLoader() {
        this.parser = new AutoDetectParser();
    }

    /**
     * 加载 PDF 文档（包含文本和图片）
     *
     * @param pdfBytes   PDF 文件字节数组
     * @param filename   文件名
     * @return 包含文本和图片的文档数据
     */
    public EnhancedDocumentData loadDocument(byte[] pdfBytes, String filename) throws IOException {
        log.info("加载 PDF 文档: {}, 大小: {} bytes", filename, pdfBytes.length);

        // 1. 提取文本内容
        String textContent = extractText(pdfBytes, filename);

        // 2. 提取图片
        List<PdfImageExtractor.ExtractedImage> images = PdfImageExtractor.extractImages(pdfBytes);

        // 3. 构建文档数据
        EnhancedDocumentData documentData = EnhancedDocumentData.builder()
                .filename(filename)
                .textContent(textContent)
                .images(images)
                .hasImages(!images.isEmpty())
                .imageCount(images.size())
                .metadata(extractMetadata(pdfBytes, filename))
                .build();

        log.info("PDF 加载完成: {}, 文本长度: {}, 图片数量: {}",
                filename, textContent.length(), images.size());

        return documentData;
    }

    /**
     * 提取 PDF 文本内容
     */
    private String extractText(byte[] pdfBytes, String filename) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set("resourceName", filename);

        BodyContentHandler handler = new BodyContentHandler(10 * 1024 * 1024);
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(pdfBytes)) {
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        } catch (Exception e) {
            log.error("提取 PDF 文本失败: {}", filename, e);
            throw new IOException("提取 PDF 文本失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取元数据
     */
    private Map<String, String> extractMetadata(byte[] pdfBytes, String filename) {
        Map<String, String> meta = new HashMap<>();
        meta.put("filename", filename);
        meta.put("size", String.valueOf(pdfBytes.length));
        return meta;
    }

    /**
     * 将文档转换为文本块（包含图片描述占位符）
     *
     * @param documentData 文档数据
     * @return 文本块列表
     */
    public List<TextBlock> convertToTextBlocks(EnhancedDocumentData documentData) {
        List<TextBlock> blocks = new ArrayList<>();

        // 添加文本内容块
        if (documentData.getTextContent() != null && !documentData.getTextContent().isEmpty()) {
            blocks.add(TextBlock.builder()
                    .type(BlockType.TEXT)
                    .content(documentData.getTextContent())
                    .pageNumber(0)
                    .build());
        }

        // 为每张图片添加一个块
        int imageIndex = 1;
        for (PdfImageExtractor.ExtractedImage image : documentData.getImages()) {
            blocks.add(TextBlock.builder()
                    .type(BlockType.IMAGE)
                    .content(String.format("[图片 %d - 第%d页 - %dx%d]",
                            imageIndex, image.getPageNumber(), image.getWidth(), image.getHeight()))
                    .pageNumber(image.getPageNumber())
                    .imageData(image.getBase64Data())
                    .build());
            imageIndex++;
        }

        return blocks;
    }

    /**
     * 增强版文档数据
     */
    @Data
    @Builder
    public static class EnhancedDocumentData {
        private String filename;
        private String textContent;
        private List<PdfImageExtractor.ExtractedImage> images;
        private boolean hasImages;
        private int imageCount;
        private Map<String, String> metadata;
    }

    /**
     * 文本块
     */
    @Data
    @Builder
    public static class TextBlock {
        private BlockType type;
        private String content;
        private int pageNumber;
        private String imageData;  // 仅图片类型有值
    }

    /**
     * 块类型
     */
    public enum BlockType {
        TEXT,   // 文本
        IMAGE   // 图片
    }
}
