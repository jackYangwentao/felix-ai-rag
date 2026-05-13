package com.felix.ai.rag.loader;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一文档处理器
 * 整合所有文档处理功能：文本提取、图片提取、分块、元数据提取
 */
@Component
@Slf4j
public class DocumentProcessor {

    private final DocumentLoader documentLoader;
    private final EnhancedPdfLoader enhancedPdfLoader;

    public DocumentProcessor() {
        this.documentLoader = new DocumentLoader();
        this.enhancedPdfLoader = new EnhancedPdfLoader();
    }

    /**
     * 处理文档（自动检测类型并选择最佳处理方式）
     *
     * @param file 上传的文件
     * @return 处理后的文档内容
     */
    public ProcessedDocument process(MultipartFile file) throws IOException {
        return process(file, ProcessingOptions.builder().build());
    }

    /**
     * 处理文档（带选项）
     *
     * @param file    上传的文件
     * @param options 处理选项
     * @return 处理后的文档内容
     */
    public ProcessedDocument process(MultipartFile file, ProcessingOptions options) throws IOException {
        String filename = file.getOriginalFilename();
        String extension = getFileExtension(filename);

        log.info("处理文档: {}, 扩展名: {}, 选项: {}", filename, extension, options);

        // 根据文件类型选择处理方式
        switch (extension.toLowerCase()) {
            case "pdf":
                return processPdf(file, options);
            case "md":
            case "markdown":
                return processMarkdown(file, options);
            case "txt":
            case "json":
            case "xml":
            case "csv":
                return processTextFile(file, options);
            case "doc":
            case "docx":
            case "xls":
            case "xlsx":
            case "ppt":
            case "pptx":
                return processOfficeDocument(file, options);
            default:
                // 使用通用处理方式
                return processGeneric(file, options);
        }
    }

    /**
     * 处理 PDF 文档
     */
    private ProcessedDocument processPdf(MultipartFile file, ProcessingOptions options) throws IOException {
        log.debug("使用 PDF 专用处理器: {}", file.getOriginalFilename());

        byte[] fileBytes = file.getBytes();

        // 如果需要提取图片，使用增强版加载器
        if (options.isExtractImages()) {
            EnhancedPdfLoader.EnhancedDocumentData pdfData =
                    enhancedPdfLoader.loadDocument(fileBytes, file.getOriginalFilename());

            List<ContentBlock> blocks = new ArrayList<>();

            // 添加文本块
            if (pdfData.getTextContent() != null && !pdfData.getTextContent().isEmpty()) {
                blocks.add(ContentBlock.builder()
                        .type(ContentType.TEXT)
                        .content(pdfData.getTextContent())
                        .metadata(pdfData.getMetadata())
                        .build());
            }

            // 添加图片块
            if (pdfData.isHasImages()) {
                int index = 1;
                for (PdfImageExtractor.ExtractedImage image : pdfData.getImages()) {
                    blocks.add(ContentBlock.builder()
                            .type(ContentType.IMAGE)
                            .content(String.format("[PDF图片 %d - 第%d页]", index, image.getPageNumber()))
                            .imageData(image.getBase64Data())
                            .metadata(createImageMetadata(image))
                            .build());
                    index++;
                }
            }

            return ProcessedDocument.builder()
                    .filename(file.getOriginalFilename())
                    .contentType("application/pdf")
                    .fileSize(file.getSize())
                    .blocks(blocks)
                    .hasImages(pdfData.isHasImages())
                    .imageCount(pdfData.getImageCount())
                    .metadata(pdfData.getMetadata())
                    .build();
        } else {
            // 仅提取文本
            DocumentLoader.DocumentData docData = documentLoader.loadDocument(file);

            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(ContentBlock.builder()
                    .type(ContentType.TEXT)
                    .content(docData.getContent())
                    .metadata(docData.getMetadata())
                    .build());

            return ProcessedDocument.builder()
                    .filename(file.getOriginalFilename())
                    .contentType(docData.getContentType())
                    .fileSize(file.getSize())
                    .blocks(blocks)
                    .hasImages(false)
                    .imageCount(0)
                    .metadata(docData.getMetadata())
                    .build();
        }
    }

    /**
     * 处理 Markdown 文档
     */
    private ProcessedDocument processMarkdown(MultipartFile file, ProcessingOptions options) throws IOException {
        log.debug("使用 Markdown 处理器: {}", file.getOriginalFilename());

        DocumentLoader.DocumentData docData = documentLoader.loadDocument(file);

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(ContentBlock.builder()
                .type(ContentType.MARKDOWN)
                .content(docData.getContent())
                .metadata(docData.getMetadata())
                .build());

        return ProcessedDocument.builder()
                .filename(file.getOriginalFilename())
                .contentType("text/markdown")
                .fileSize(file.getSize())
                .blocks(blocks)
                .hasImages(false)
                .metadata(docData.getMetadata())
                .build();
    }

    /**
     * 处理文本文件
     */
    private ProcessedDocument processTextFile(MultipartFile file, ProcessingOptions options) throws IOException {
        log.debug("使用文本处理器: {}", file.getOriginalFilename());

        DocumentLoader.DocumentData docData = documentLoader.loadDocument(file);

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(ContentBlock.builder()
                .type(ContentType.TEXT)
                .content(docData.getContent())
                .metadata(docData.getMetadata())
                .build());

        return ProcessedDocument.builder()
                .filename(file.getOriginalFilename())
                .contentType(docData.getContentType())
                .fileSize(file.getSize())
                .blocks(blocks)
                .metadata(docData.getMetadata())
                .build();
    }

    /**
     * 处理 Office 文档
     */
    private ProcessedDocument processOfficeDocument(MultipartFile file, ProcessingOptions options) throws IOException {
        log.debug("使用 Office 文档处理器: {}", file.getOriginalFilename());

        DocumentLoader.DocumentData docData = documentLoader.loadDocument(file);

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(ContentBlock.builder()
                .type(ContentType.TEXT)
                .content(docData.getContent())
                .metadata(docData.getMetadata())
                .build());

        return ProcessedDocument.builder()
                .filename(file.getOriginalFilename())
                .contentType(docData.getContentType())
                .fileSize(file.getSize())
                .blocks(blocks)
                .metadata(docData.getMetadata())
                .build();
    }

    /**
     * 通用文档处理
     */
    private ProcessedDocument processGeneric(MultipartFile file, ProcessingOptions options) throws IOException {
        log.debug("使用通用处理器: {}", file.getOriginalFilename());

        DocumentLoader.DocumentData docData = documentLoader.loadDocument(file);

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(ContentBlock.builder()
                .type(ContentType.TEXT)
                .content(docData.getContent())
                .metadata(docData.getMetadata())
                .build());

        return ProcessedDocument.builder()
                .filename(file.getOriginalFilename())
                .contentType(docData.getContentType())
                .fileSize(file.getSize())
                .blocks(blocks)
                .metadata(docData.getMetadata())
                .build();
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    /**
     * 创建图片元数据
     */
    private java.util.Map<String, String> createImageMetadata(PdfImageExtractor.ExtractedImage image) {
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        meta.put("pageNumber", String.valueOf(image.getPageNumber()));
        meta.put("width", String.valueOf(image.getWidth()));
        meta.put("height", String.valueOf(image.getHeight()));
        meta.put("format", image.getFormat());
        meta.put("size", String.valueOf(image.getSize()));
        return meta;
    }

    /**
     * 处理后的文档
     */
    @Data
    @Builder
    public static class ProcessedDocument {
        private String filename;
        private String contentType;
        private long fileSize;
        private List<ContentBlock> blocks;
        private boolean hasImages;
        private int imageCount;
        private java.util.Map<String, String> metadata;

        /**
         * 获取所有文本内容
         */
        public String getFullText() {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : blocks) {
                if (block.getType() == ContentType.TEXT || block.getType() == ContentType.MARKDOWN) {
                    sb.append(block.getContent()).append("\n\n");
                }
            }
            return sb.toString().trim();
        }

        /**
         * 获取所有图片
         */
        public List<ContentBlock> getImageBlocks() {
            List<ContentBlock> images = new ArrayList<>();
            for (ContentBlock block : blocks) {
                if (block.getType() == ContentType.IMAGE) {
                    images.add(block);
                }
            }
            return images;
        }
    }

    /**
     * 内容块
     */
    @Data
    @Builder
    public static class ContentBlock {
        private ContentType type;
        private String content;
        private String imageData;  // Base64，仅图片类型
        private java.util.Map<String, String> metadata;
    }

    /**
     * 内容类型
     */
    public enum ContentType {
        TEXT,       // 纯文本
        MARKDOWN,   // Markdown
        IMAGE,      // 图片
        TABLE       // 表格
    }

    /**
     * 处理选项
     */
    @Data
    @Builder
    public static class ProcessingOptions {
        @Builder.Default
        private boolean extractImages = false;      // 是否提取图片
        @Builder.Default
        private boolean extractTables = false;      // 是否提取表格
        @Builder.Default
        private boolean ocrEnabled = false;         // 是否启用 OCR
        @Builder.Default
        private int maxImageSize = 10 * 1024 * 1024; // 最大图片大小（10MB）
    }
}
