package com.felix.ai.rag.controller;

import com.felix.ai.rag.text2sql.Text2SqlAgent;
import com.felix.ai.rag.text2sql.Text2SqlKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Text2SQL 控制器
 * 提供自然语言转SQL的API接口
 *
 * 参考 Datawhale All-In-RAG Text2SQL章节
 */
@RestController
@RequestMapping("/api/v1/rag/text2sql")
@RequiredArgsConstructor
@Slf4j
public class Text2SqlController {

    private final Text2SqlAgent text2SqlAgent;
    private final Text2SqlKnowledgeBase knowledgeBase;

    /**
     * 自然语言查询
     * 将用户问题转换为SQL并执行
     */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "问题不能为空"));
        }

        log.info("Text2SQL查询: '{}'", question);
        long startTime = System.currentTimeMillis();

        Text2SqlAgent.QueryResult result = text2SqlAgent.query(question);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("userQuestion", result.getUserQuestion());
        response.put("generatedSql", result.getGeneratedSql());

        if (result.isSuccess()) {
            response.put("explanation", result.getExplanation());
            response.put("columns", result.getColumns());
            response.put("rows", result.getRows());
            response.put("rowCount", result.getRowCount());
            response.put("retryCount", result.getRetryCount());
        } else {
            response.put("errorMessage", result.getErrorMessage());
            response.put("retryCount", result.getRetryCount());
        }

        response.put("processingTimeMs", System.currentTimeMillis() - startTime);

        return ResponseEntity.ok(response);
    }

    /**
     * 仅生成SQL（不执行）
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateSql(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "问题不能为空"));
        }

        log.info("Text2SQL生成SQL: '{}'", question);

        // 检索知识库
        List<Text2SqlKnowledgeBase.KnowledgeSearchResult> knowledgeResults =
                knowledgeBase.search(question, 5);
        String context = knowledgeBase.buildContext(knowledgeResults);

        // 生成SQL
        var generationResult = text2SqlAgent.query(question);

        Map<String, Object> response = new HashMap<>();
        response.put("userQuestion", question);
        response.put("generatedSql", generationResult.getGeneratedSql());
        response.put("explanation", generationResult.getExplanation());
        response.put("context", context);

        return ResponseEntity.ok(response);
    }

    /**
     * 添加DDL知识
     */
    @PostMapping("/knowledge/ddl")
    public ResponseEntity<Map<String, Object>> addDdl(@RequestBody Map<String, String> request) {
        String tableName = request.get("tableName");
        String ddl = request.get("ddl");
        String description = request.get("description");

        if (tableName == null || ddl == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tableName和ddl不能为空"));
        }

        knowledgeBase.addDdl(tableName, ddl, description != null ? description : "");

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "DDL知识已添加: " + tableName
        ));
    }

    /**
     * 添加字段描述
     */
    @PostMapping("/knowledge/field")
    public ResponseEntity<Map<String, Object>> addFieldDescription(@RequestBody Map<String, Object> request) {
        String tableName = (String) request.get("tableName");
        String fieldName = (String) request.get("fieldName");
        String fieldType = (String) request.get("fieldType");
        String description = (String) request.get("description");
        List<String> synonyms = (List<String>) request.get("synonyms");

        if (tableName == null || fieldName == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tableName和fieldName不能为空"));
        }

        knowledgeBase.addFieldDescription(tableName, fieldName,
                fieldType != null ? fieldType : "VARCHAR",
                description != null ? description : "",
                synonyms);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "字段描述已添加: " + tableName + "." + fieldName
        ));
    }

    /**
     * 添加查询示例
     */
    @PostMapping("/knowledge/example")
    public ResponseEntity<Map<String, Object>> addQueryExample(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String sql = request.get("sql");
        String description = request.get("description");

        if (question == null || sql == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "question和sql不能为空"));
        }

        knowledgeBase.addQueryExample(question, sql, description != null ? description : "");

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "查询示例已添加"
        ));
    }

    /**
     * 添加业务术语
     */
    @PostMapping("/knowledge/term")
    public ResponseEntity<Map<String, Object>> addBusinessTerm(@RequestBody Map<String, String> request) {
        String term = request.get("term");
        String mapping = request.get("mapping");
        String explanation = request.get("explanation");

        if (term == null || mapping == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "term和mapping不能为空"));
        }

        knowledgeBase.addBusinessTerm(term, mapping, explanation != null ? explanation : "");

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "业务术语已添加: " + term
        ));
    }

    /**
     * 获取知识库统计
     */
    @GetMapping("/knowledge/stats")
    public ResponseEntity<Map<String, Object>> getKnowledgeStats() {
        Map<String, Integer> stats = knowledgeBase.getStatistics();

        Map<String, Object> response = new HashMap<>();
        response.put("statistics", stats);
        response.put("totalItems", stats.getOrDefault("TOTAL", 0));

        return ResponseEntity.ok(response);
    }

    /**
     * 清空知识库
     */
    @DeleteMapping("/knowledge")
    public ResponseEntity<Map<String, Object>> clearKnowledge() {
        knowledgeBase.clear();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "知识库已清空"
        ));
    }

    /**
     * 获取Text2SQL文档
     */
    @GetMapping("/docs")
    public ResponseEntity<Map<String, Object>> getDocumentation() {
        Map<String, Object> response = new HashMap<>();

        Map<String, Map<String, String>> overview = new HashMap<>();

        overview.put("text2sql", Map.of(
                "name", "Text2SQL",
                "description", "将自然语言问题转换为SQL查询语句",
                "核心能力", "自然语言理解 + SQL生成 + 错误修复",
                "适用场景", "非技术人员查询数据库、快速数据分析"
        ));

        overview.put("knowledgeBase", Map.of(
                "name", "知识库",
                "description", "RAG增强的数据库schema和查询示例",
                "组成", "DDL + 字段描述 + 查询示例 + 业务术语",
                "作用", "提供上下文，减少LLM幻觉"
        ));

        overview.put("errorFix", Map.of(
                "name", "错误修复",
                "description", "自动检测并修复SQL错误",
                "机制", "执行失败 → 分析错误 → 修复SQL → 重试",
                "最大重试", "3次"
        ));

        response.put("overview", overview);

        Map<String, String> workflow = new HashMap<>();
        workflow.put("1. 知识检索", "根据用户问题检索相关知识库内容");
        workflow.put("2. 上下文构建", "按DDL→描述→示例的顺序组织上下文");
        workflow.put("3. SQL生成", "LLM根据上下文生成SQL语句");
        workflow.put("4. 安全执行", "添加LIMIT，执行SQL查询");
        workflow.put("5. 错误修复", "失败时自动修复并重试");

        response.put("workflow", workflow);

        return ResponseEntity.ok(response);
    }

    /**
     * 使用示例
     */
    @GetMapping("/examples")
    public ResponseEntity<Map<String, Object>> getExamples() {
        Map<String, Object> response = new HashMap<>();

        Map<String, Map<String, String>> examples = new HashMap<>();

        examples.put("simpleQuery", Map.of(
                "question", "年龄大于30的用户有哪些",
                "sql", "SELECT * FROM users WHERE age > 30 LIMIT 100",
                "description", "简单条件查询"
        ));

        examples.put("aggregation", Map.of(
                "question", "每个城市的用户数量是多少",
                "sql", "SELECT city, COUNT(*) as user_count FROM users GROUP BY city LIMIT 100",
                "description", "聚合查询"
        ));

        examples.put("join", Map.of(
                "question", "查询订单金额大于1000的用户姓名",
                "sql", "SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id WHERE o.amount > 1000 LIMIT 100",
                "description", "多表JOIN查询"
        ));

        examples.put("timeRange", Map.of(
                "question", "查询最近7天创建的订单",
                "sql", "SELECT * FROM orders WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) LIMIT 100",
                "description", "时间范围查询"
        ));

        response.put("examples", examples);

        return ResponseEntity.ok(response);
    }
}
