package com.felix.ai.rag.filter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 元数据过滤器
 * 支持多种过滤条件：等于、不等于、包含、范围等
 *
 * 参考 Datawhale All-In-RAG 元数据过滤 + 向量搜索
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataFilter {

    private List<FilterCondition> conditions;

    @Builder.Default
    private LogicalOperator operator = LogicalOperator.AND;

    public enum LogicalOperator {
        AND, OR
    }

    public enum Operator {
        EQUALS,           // 等于
        NOT_EQUALS,       // 不等于
        CONTAINS,         // 包含
        STARTS_WITH,      // 开头是
        ENDS_WITH,        // 结尾是
        GREATER_THAN,     // 大于
        LESS_THAN,        // 小于
        EXISTS,           // 存在
        IN                // 在列表中
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterCondition {
        private String key;           // 元数据键
        private Operator operator;    // 操作符
        private String value;         // 比较值
        private List<String> values;  // 用于 IN 操作符
    }

    /**
     * 创建等于条件
     */
    public static FilterCondition eq(String key, String value) {
        return FilterCondition.builder()
                .key(key)
                .operator(Operator.EQUALS)
                .value(value)
                .build();
    }

    /**
     * 创建不等于条件
     */
    public static FilterCondition ne(String key, String value) {
        return FilterCondition.builder()
                .key(key)
                .operator(Operator.NOT_EQUALS)
                .value(value)
                .build();
    }

    /**
     * 创建包含条件
     */
    public static FilterCondition contains(String key, String value) {
        return FilterCondition.builder()
                .key(key)
                .operator(Operator.CONTAINS)
                .value(value)
                .build();
    }

    /**
     * 创建 IN 条件
     */
    public static FilterCondition in(String key, List<String> values) {
        return FilterCondition.builder()
                .key(key)
                .operator(Operator.IN)
                .values(values)
                .build();
    }

    /**
     * 添加条件（链式调用）
     */
    public MetadataFilter addCondition(FilterCondition condition) {
        if (conditions == null) {
            conditions = new ArrayList<>();
        }
        conditions.add(condition);
        return this;
    }

    /**
     * 评估元数据是否匹配过滤条件
     */
    public boolean matches(Map<String, String> metadata) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        if (operator == LogicalOperator.AND) {
            return conditions.stream().allMatch(c -> evaluateCondition(c, metadata));
        } else {
            return conditions.stream().anyMatch(c -> evaluateCondition(c, metadata));
        }
    }

    /**
     * 评估单个条件
     */
    private boolean evaluateCondition(FilterCondition condition, Map<String, String> metadata) {
        String actualValue = metadata.get(condition.getKey());

        switch (condition.getOperator()) {
            case EQUALS:
                return condition.getValue().equals(actualValue);
            case NOT_EQUALS:
                return !condition.getValue().equals(actualValue);
            case CONTAINS:
                return actualValue != null && actualValue.contains(condition.getValue());
            case STARTS_WITH:
                return actualValue != null && actualValue.startsWith(condition.getValue());
            case ENDS_WITH:
                return actualValue != null && actualValue.endsWith(condition.getValue());
            case EXISTS:
                return actualValue != null;
            case IN:
                return actualValue != null && condition.getValues() != null
                        && condition.getValues().contains(actualValue);
            case GREATER_THAN:
                return compareValues(actualValue, condition.getValue()) > 0;
            case LESS_THAN:
                return compareValues(actualValue, condition.getValue()) < 0;
            default:
                return false;
        }
    }

    /**
     * 比较两个值（尝试数字比较，失败则字符串比较）
     */
    private int compareValues(String v1, String v2) {
        try {
            double d1 = Double.parseDouble(v1);
            double d2 = Double.parseDouble(v2);
            return Double.compare(d1, d2);
        } catch (NumberFormatException e) {
            // 不是数字，使用字符串比较
            if (v1 == null) return -1;
            if (v2 == null) return 1;
            return v1.compareTo(v2);
        }
    }

    /**
     * 转换为 Predicate（用于流过滤）
     */
    public Predicate<Map<String, String>> toPredicate() {
        return this::matches;
    }
}
