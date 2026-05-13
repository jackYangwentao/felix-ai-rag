package com.felix.ai.rag.service.incremental;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.felix.ai.rag.model.DocumentMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文档注册表
 * 管理已导入文档的元数据，支持增量导入
 */
@Component
@Slf4j
public class DocumentRegistry {

    private final Map<String, DocumentMetadata> registry = new ConcurrentHashMap<>();
    private final Path registryFile;
    private final ObjectMapper objectMapper;

    public DocumentRegistry(@Value("${rag.registry.path:data/document-registry.json}") String path) {
        this.registryFile = Paths.get(path);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 初始化时加载注册表
     */
    @PostConstruct
    public void loadRegistry() {
        if (Files.exists(registryFile)) {
            try {
                String json = Files.readString(registryFile);
                List<DocumentMetadata> documents = objectMapper.readValue(json,
                        new TypeReference<List<DocumentMetadata>>() {});

                documents.forEach(doc -> {
                    // 只加载ACTIVE状态的文档
                    if (doc.getStatus() != DocumentMetadata.DocumentStatus.DELETED) {
                        registry.put(doc.getDocumentId(), doc);
                    }
                });

                log.info("加载文档注册表成功: {} 个文档", registry.size());
            } catch (IOException e) {
                log.error("加载文档注册表失败", e);
            }
        } else {
            log.info("文档注册表文件不存在，将创建新注册表");
        }
    }

    /**
     * 销毁时保存注册表
     */
    @PreDestroy
    public void saveRegistry() {
        try {
            Files.createDirectories(registryFile.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new ArrayList<>(registry.values()));
            Files.writeString(registryFile, json);
            log.info("保存文档注册表成功: {} 个文档", registry.size());
        } catch (IOException e) {
            log.error("保存文档注册表失败", e);
        }
    }

    /**
     * 注册或更新文档
     *
     * @param metadata 文档元数据
     */
    public void register(DocumentMetadata metadata) {
        registry.put(metadata.getDocumentId(), metadata);
        saveRegistry();
        log.debug("注册文档: {}, 版本: {}", metadata.getDocumentId(), metadata.getVersion());
    }

    /**
     * 移除文档
     *
     * @param documentId 文档ID
     */
    public void remove(String documentId) {
        registry.remove(documentId);
        saveRegistry();
        log.debug("移除文档: {}", documentId);
    }

    /**
     * 标记文档为删除状态（逻辑删除）
     *
     * @param documentId 文档ID
     */
    public void markAsDeleted(String documentId) {
        DocumentMetadata metadata = registry.get(documentId);
        if (metadata != null) {
            metadata.setStatus(DocumentMetadata.DocumentStatus.DELETED);
            metadata.setUpdateTime(java.time.LocalDateTime.now());
            saveRegistry();
            log.debug("标记文档为删除状态: {}", documentId);
        }
    }

    /**
     * 获取文档元数据
     *
     * @param documentId 文档ID
     * @return 文档元数据，不存在返回null
     */
    public DocumentMetadata get(String documentId) {
        return registry.get(documentId);
    }

    /**
     * 检查文档是否存在
     *
     * @param documentId 文档ID
     * @return 是否存在
     */
    public boolean exists(String documentId) {
        return registry.containsKey(documentId);
    }

    /**
     * 检查文档是否存在且处于活动状态
     *
     * @param documentId 文档ID
     * @return 是否存在且活动
     */
    public boolean existsAndActive(String documentId) {
        DocumentMetadata metadata = registry.get(documentId);
        return metadata != null && metadata.getStatus() == DocumentMetadata.DocumentStatus.ACTIVE;
    }

    /**
     * 获取所有文档
     *
     * @return 文档列表
     */
    public List<DocumentMetadata> listAll() {
        return new ArrayList<>(registry.values());
    }

    /**
     * 获取所有活动状态的文档
     *
     * @return 活动文档列表
     */
    public List<DocumentMetadata> listActive() {
        return registry.values().stream()
                .filter(doc -> doc.getStatus() == DocumentMetadata.DocumentStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    /**
     * 根据文件夹路径获取文档
     *
     * @param folder 文件夹路径前缀
     * @return 文档列表
     */
    public List<DocumentMetadata> listByFolder(String folder) {
        return registry.values().stream()
                .filter(doc -> doc.getDocumentId() != null && doc.getDocumentId().startsWith(folder))
                .collect(Collectors.toList());
    }

    /**
     * 根据存储类型获取文档
     *
     * @param storageType 存储类型
     * @return 文档列表
     */
    public List<DocumentMetadata> listByStorageType(String storageType) {
        return registry.values().stream()
                .filter(doc -> storageType.equals(doc.getStorageType()))
                .collect(Collectors.toList());
    }

    /**
     * 获取文档数量
     *
     * @return 文档总数
     */
    public int size() {
        return registry.size();
    }

    /**
     * 清空注册表
     */
    public void clear() {
        registry.clear();
        saveRegistry();
        log.info("清空文档注册表");
    }

    /**
     * 根据内容哈希查找文档
     *
     * @param contentHash 内容哈希
     * @return 文档元数据，不存在返回null
     */
    public DocumentMetadata findByContentHash(String contentHash) {
        return registry.values().stream()
                .filter(doc -> contentHash.equals(doc.getContentHash()))
                .findFirst()
                .orElse(null);
    }
}
