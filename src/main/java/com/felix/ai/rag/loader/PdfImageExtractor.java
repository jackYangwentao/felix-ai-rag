package com.felix.ai.rag.loader;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * PDF 图片提取器
 * 从 PDF 文档中提取图片，用于多模态处理
 */
@Slf4j
public class PdfImageExtractor {

    /**
     * 从 PDF 字节数组中提取所有图片
     *
     * @param pdfBytes PDF 文件字节数组
     * @return 图片列表（Base64 编码）
     */
    public static List<ExtractedImage> extractImages(byte[] pdfBytes) {
        List<ExtractedImage> images = new ArrayList<>();

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            int pageNum = 0;
            for (PDPage page : document.getPages()) {
                pageNum++;
                try {
                    PDResources resources = page.getResources();
                    if (resources == null) continue;

                    for (COSName name : resources.getXObjectNames()) {
                        PDXObject xObject = resources.getXObject(name);
                        if (xObject instanceof PDImageXObject) {
                            PDImageXObject image = (PDImageXObject) xObject;
                            ExtractedImage extracted = processImage(image, pageNum);
                            if (extracted != null) {
                                images.add(extracted);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("提取第 {} 页图片失败: {}", pageNum, e.getMessage());
                }
            }

            log.info("从 PDF 中提取了 {} 张图片", images.size());
        } catch (IOException e) {
            log.error("PDF 图片提取失败", e);
        }

        return images;
    }

    /**
     * 处理单张图片
     */
    private static ExtractedImage processImage(PDImageXObject image, int pageNum) {
        try {
            BufferedImage bufferedImage = image.getImage();

            // 过滤掉太小的图片（可能是图标或装饰）
            if (bufferedImage.getWidth() < 100 || bufferedImage.getHeight() < 100) {
                return null;
            }

            // 转换为 PNG 格式
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            // Base64 编码
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            return ExtractedImage.builder()
                    .pageNumber(pageNum)
                    .width(bufferedImage.getWidth())
                    .height(bufferedImage.getHeight())
                    .format("PNG")
                    .base64Data(base64Image)
                    .size(imageBytes.length)
                    .build();

        } catch (Exception e) {
            log.warn("处理图片失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取的图片数据类
     */
    @lombok.Builder
    @lombok.Data
    public static class ExtractedImage {
        private int pageNumber;      // 所在页码
        private int width;           // 图片宽度
        private int height;          // 图片高度
        private String format;       // 图片格式
        private String base64Data;   // Base64 编码数据
        private int size;            // 图片大小（字节）
    }
}
