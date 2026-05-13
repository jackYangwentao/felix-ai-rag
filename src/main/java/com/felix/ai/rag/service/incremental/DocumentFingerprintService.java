package com.felix.ai.rag.service.incremental;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 文档指纹服务
 * 计算文档内容哈希，用于增量导入检测
 */
@Service
@Slf4j
public class DocumentFingerprintService {

    /**
     * 计算文件内容哈希（MD5）
     *
     * @param file 上传的文件
     * @return MD5哈希值
     */
    public String calculateMd5(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return DigestUtils.md5Hex(is);
        } catch (IOException e) {
            log.error("计算文件MD5失败: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("计算文件哈希失败", e);
        }
    }

    /**
     * 计算文件内容哈希（SHA256）
     *
     * @param file 上传的文件
     * @return SHA256哈希值
     */
    public String calculateSha256(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return DigestUtils.sha256Hex(is);
        } catch (IOException e) {
            log.error("计算文件SHA256失败: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("计算文件哈希失败", e);
        }
    }

    /**
     * 计算文本内容哈希（MD5）
     *
     * @param content 文本内容
     * @return MD5哈希值
     */
    public String calculateMd5(String content) {
        return DigestUtils.md5Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算字节数组哈希（MD5）
     *
     * @param bytes 字节数组
     * @return MD5哈希值
     */
    public String calculateMd5(byte[] bytes) {
        return DigestUtils.md5Hex(bytes);
    }

    /**
     * 生成文档ID
     *
     * @param file     上传的文件
     * @param strategy ID生成策略
     * @return 文档ID
     */
    public String generateDocumentId(MultipartFile file, IdGenerationStrategy strategy) {
        String filename = file.getOriginalFilename();

        switch (strategy) {
            case PATH:
                // 使用文件路径/名称作为ID
                return filename != null ? filename : UUID.randomUUID().toString();

            case CONTENT_HASH:
                // 使用内容哈希作为ID（自动去重）
                return calculateMd5(file);

            case UUID:
            default:
                // 使用随机UUID
                return UUID.randomUUID().toString();
        }
    }

    /**
     * 生成文档ID（默认使用PATH策略）
     *
     * @param file 上传的文件
     * @return 文档ID
     */
    public String generateDocumentId(MultipartFile file) {
        return generateDocumentId(file, IdGenerationStrategy.PATH);
    }

    /**
     * 从自定义路径生成文档ID
     *
     * @param path 自定义路径
     * @return 文档ID
     */
    public String generateDocumentId(String path) {
        return path != null && !path.isEmpty() ? path : UUID.randomUUID().toString();
    }

    /**
     * ID生成策略
     */
    public enum IdGenerationStrategy {
        PATH,           // 使用文件路径
        CONTENT_HASH,   // 使用内容哈希
        UUID            // 使用随机UUID
    }
}
