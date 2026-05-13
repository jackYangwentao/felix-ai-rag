package com.felix.ai.rag.controller;

import com.felix.ai.rag.query.SelfQueryRetriever;
import com.felix.ai.rag.query.StructuredQueryBuilder;
import com.felix.ai.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Self-Query控制器
 * 提供自然语言到结构化查询的转换API
 *
 * 参考 Datawhale All-In-RAG 查询构建章节
 */
@RestController
@RequestMapping("/api/v1/rag/self-query")
@RequiredArgsConstructor
@Slf4j
public class SelfQueryController {

    private final SelfQueryRetriever selfQueryRetriever;
    private final RagService ragService;

    /**
     * 解析自然语言查询
     * 将自然语言转换为语义查询 + 元数据过滤条件
     *
     * 示例:
     * 输入: "2023年张三写的关于机器学习的论文"
     * 输出: { semanticQuery: "机器学习 论文", filters: { year: "2023", author: "张三" } }
     */
    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseQuery(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("Self-Query解析: '{}'", query);
        long startTime = System.currentTimeMillis();

        SelfQueryRetriever.SelfQueryResult result = selfQueryRetriever.parse(query);

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", result.getOriginalQuery());
        response.put("semanticQuery", result.getSemanticQuery());
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        // 转换过滤器为更易读的格式
        if (result.getFilter() != null && result.getFilter().getConditions() != null) {
            List<Map<String, String>> filters = result.getFilter().getConditions().stream()
                    .map(c -> {
                        Map<String, String> map = new HashMap<>();
                        map.put("field", c.getKey());
                        map.put("operator", c.getOperator().name());
                        map.put("value", c.getValue());
                        return map;
                    })
                    .collect(Collectors.toList());
            response.put("filters", filters);

            // 生成SQL风格的表示
            String sqlWhere = StructuredQueryBuilder.toSqlWhere(result.getFilter());
            response.put("sqlWhereClause", sqlWhere.isEmpty() ? "无" : sqlWhere);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 使用Self-Query执行检索
     * 自动解析查询并应用元数据过滤
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer maxResults = (Integer) request.getOrDefault("maxResults", 5);

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "查询不能为空"));
        }

        log.info("Self-Query搜索: '{}'", query);
        long startTime = System.currentTimeMillis();

        // 1. 解析查询
        SelfQueryRetriever.SelfQueryResult parseResult = selfQueryRetriever.parse(query);

        // 2. 使用语义查询进行检索
        List<String> results = ragService.searchRelevantContent(
                parseResult.getSemanticQuery(),
                parseResult.getFilter(),
                maxResults,
                0.7
        );

        Map<String, Object> response = new HashMap<>();
        response.put("originalQuery", query);
        response.put("parsedQuery", parseResult.getSemanticQuery());
        response.put("results", results);
        response.put("resultCount", results.size());
        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取支持的元数据字段
     */
    @GetMapping("/metadata-fields")
    public ResponseEntity<List<Map<String, String>>> getMetadataFields() {
        // 返回默认的元数据字段定义
        List<Map<String, String>> fields = List.of(
                Map.of("name", "year", "description", "文档发布年份", "type", "string", "example", "2023"),
                Map.of("name", "quarter", "description", "季度", "type", "string", "example", "Q1"),
                Map.of("name", "author", "description", "文档作者", "type", "string", "example", "张三"),
                Map.of("name", "category", "description", "业务分类", "type", "string", "example", "技术"),
                Map.of("name", "documentType", "description", "文档类型", "type", "string", "example", "论文"),
                Map.of("name", "department", "description", "部门", "type", "string", "example", "研发部"),
                Map.of("name", "project", "description", "项目名称", "type", "string", "example", "AI平台")
        );

        return ResponseEntity.ok(fields);
    }

    /**
     * 构建结构化查询示例
     */
    @GetMapping("/examples")
    public ResponseEntity<Map<String, Object>> getExamples() {
        Map<String, Object> response = new HashMap<>();

        Map<String, String> examples = new HashMap<>();
        examples.put("2023年技术部的文档", "year=2023, department=技术部");
        examples.put("张三写的关于人工智能的论文", "author=张三, documentType=论文, 语义查询=人工智能");
        examples.put("Q1季度的产品报告", "quarter=Q1, category=产品, documentType=报告");
        examples.put("AI平台的项目文档", "project=AI平台");

        response.put("naturalLanguageExamples", examples);

        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("语义查询", "提取的核心概念，用于向量语义检索");
        descriptions.put("过滤条件", "结构化条件，用于元数据精确过滤");
        descriptions.put("合并效果", "先过滤后检索，提高效率和准确性");

        response.put("descriptions", descriptions);

        return ResponseEntity.ok(response);
    }
}
