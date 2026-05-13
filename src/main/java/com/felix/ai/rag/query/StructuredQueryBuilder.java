package com.felix.ai.rag.query;

import com.felix.ai.rag.filter.MetadataFilter;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 结构化查询构建器
 * 支持构建复杂的结构化查询条件
 *
 * 参考 Datawhale All-In-RAG 查询构建章节
 */
@Slf4j
public class StructuredQueryBuilder {

    /**
     * 构建复合过滤条件
     */
    public static MetadataFilter and(MetadataFilter... filters) {
        MetadataFilter result = new MetadataFilter();
        result.setOperator(MetadataFilter.LogicalOperator.AND);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();
        for (MetadataFilter filter : filters) {
            if (filter != null && filter.getConditions() != null) {
                conditions.addAll(filter.getConditions());
            }
        }

        result.setConditions(conditions);
        return result;
    }

    public static MetadataFilter or(MetadataFilter... filters) {
        MetadataFilter result = new MetadataFilter();
        result.setOperator(MetadataFilter.LogicalOperator.OR);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();
        for (MetadataFilter filter : filters) {
            if (filter != null && filter.getConditions() != null) {
                conditions.addAll(filter.getConditions());
            }
        }

        result.setConditions(conditions);
        return result;
    }

    /**
     * 时间范围过滤
     */
    public static MetadataFilter timeRange(String yearStart, String yearEnd) {
        MetadataFilter filter = new MetadataFilter();
        filter.setOperator(MetadataFilter.LogicalOperator.AND);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();

        if (yearStart != null && !yearStart.isEmpty()) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("year")
                    .operator(MetadataFilter.Operator.GREATER_THAN)
                    .value(yearStart)
                    .build());
        }

        if (yearEnd != null && !yearEnd.isEmpty()) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("year")
                    .operator(MetadataFilter.Operator.LESS_THAN)
                    .value(yearEnd)
                    .build());
        }

        filter.setConditions(conditions);
        return filter;
    }

    /**
     * 多值匹配（IN操作）
     */
    public static MetadataFilter in(String field, List<String> values) {
        return MetadataFilter.builder()
                .conditions(List.of(
                        MetadataFilter.FilterCondition.builder()
                                .key(field)
                                .operator(MetadataFilter.Operator.IN)
                                .values(values)
                                .build()
                ))
                .operator(MetadataFilter.LogicalOperator.AND)
                .build();
    }

    /**
     * 标签匹配（包含任意标签）
     */
    public static MetadataFilter hasAnyTag(String... tags) {
        MetadataFilter filter = new MetadataFilter();
        filter.setOperator(MetadataFilter.LogicalOperator.OR);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();
        for (String tag : tags) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("tags")
                    .operator(MetadataFilter.Operator.CONTAINS)
                    .value(tag)
                    .build());
        }

        filter.setConditions(conditions);
        return filter;
    }

    /**
     * 标签匹配（包含所有标签）
     */
    public static MetadataFilter hasAllTags(String... tags) {
        MetadataFilter filter = new MetadataFilter();
        filter.setOperator(MetadataFilter.LogicalOperator.AND);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();
        for (String tag : tags) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("tags")
                    .operator(MetadataFilter.Operator.CONTAINS)
                    .value(tag)
                    .build());
        }

        filter.setConditions(conditions);
        return filter;
    }

    /**
     * 部门层级过滤
     */
    public static MetadataFilter departmentPath(String... departments) {
        if (departments.length == 0) {
            return new MetadataFilter();
        }

        // 最具体的部门作为过滤条件
        String specificDept = departments[departments.length - 1];

        return MetadataFilter.builder()
                .conditions(List.of(
                        MetadataFilter.FilterCondition.builder()
                                .key("department")
                                .operator(MetadataFilter.Operator.EQUALS)
                                .value(specificDept)
                                .build()
                ))
                .operator(MetadataFilter.LogicalOperator.AND)
                .build();
    }

    /**
     * 文档类型过滤
     */
    public static MetadataFilter documentType(String... types) {
        if (types.length == 0) {
            return new MetadataFilter();
        }

        if (types.length == 1) {
            return MetadataFilter.builder()
                    .conditions(List.of(
                            MetadataFilter.FilterCondition.builder()
                                    .key("document_type")
                                    .operator(MetadataFilter.Operator.EQUALS)
                                    .value(types[0])
                                    .build()
                    ))
                    .operator(MetadataFilter.LogicalOperator.AND)
                    .build();
        }

        // 多个类型使用IN操作
        return MetadataFilter.builder()
                .conditions(List.of(
                        MetadataFilter.FilterCondition.builder()
                                .key("document_type")
                                .operator(MetadataFilter.Operator.IN)
                                .values(Arrays.asList(types))
                                .build()
                ))
                .operator(MetadataFilter.LogicalOperator.AND)
                .build();
    }

    /**
     * 作者过滤
     */
    public static MetadataFilter author(String... authors) {
        if (authors.length == 0) {
            return new MetadataFilter();
        }

        MetadataFilter filter = new MetadataFilter();
        filter.setOperator(MetadataFilter.LogicalOperator.OR);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();
        for (String author : authors) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("author")
                    .operator(MetadataFilter.Operator.EQUALS)
                    .value(author)
                    .build());
        }

        filter.setConditions(conditions);
        return filter;
    }

    /**
     * 项目过滤
     */
    public static MetadataFilter project(String projectName) {
        return MetadataFilter.builder()
                .conditions(List.of(
                        MetadataFilter.FilterCondition.builder()
                                .key("project")
                                .operator(MetadataFilter.Operator.EQUALS)
                                .value(projectName)
                                .build()
                ))
                .operator(MetadataFilter.LogicalOperator.AND)
                .build();
    }

    /**
     * 复合业务查询示例：查找某部门某年度某类型的文档
     */
    public static MetadataFilter businessQuery(String department, String year,
                                                String quarter, String docType) {
        MetadataFilter filter = new MetadataFilter();
        filter.setOperator(MetadataFilter.LogicalOperator.AND);

        List<MetadataFilter.FilterCondition> conditions = new ArrayList<>();

        if (department != null && !department.isEmpty()) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("department")
                    .operator(MetadataFilter.Operator.EQUALS)
                    .value(department)
                    .build());
        }

        if (year != null && !year.isEmpty()) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("year")
                    .operator(MetadataFilter.Operator.EQUALS)
                    .value(year)
                    .build());
        }

        if (quarter != null && !quarter.isEmpty()) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("quarter")
                    .operator(MetadataFilter.Operator.EQUALS)
                    .value(quarter)
                    .build());
        }

        if (docType != null && !docType.isEmpty()) {
            conditions.add(MetadataFilter.FilterCondition.builder()
                    .key("document_type")
                    .operator(MetadataFilter.Operator.EQUALS)
                    .value(docType)
                    .build());
        }

        filter.setConditions(conditions);
        return filter;
    }

    /**
     * 将过滤器转换为SQL WHERE子句（用于展示）
     */
    public static String toSqlWhere(MetadataFilter filter) {
        if (filter == null || filter.getConditions() == null || filter.getConditions().isEmpty()) {
            return "";
        }

        String operator = filter.getOperator() == MetadataFilter.LogicalOperator.AND ? " AND " : " OR ";

        List<String> parts = new ArrayList<>();
        for (MetadataFilter.FilterCondition condition : filter.getConditions()) {
            String part = conditionToSql(condition);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }

        return String.join(operator, parts);
    }

    private static String conditionToSql(MetadataFilter.FilterCondition condition) {
        String key = condition.getKey();
        String value = condition.getValue();

        return switch (condition.getOperator()) {
            case EQUALS -> key + " = '" + value + "'";
            case NOT_EQUALS -> key + " != '" + value + "'";
            case CONTAINS -> key + " LIKE '%" + value + "%'";
            case GREATER_THAN -> key + " > '" + value + "'";
            case LESS_THAN -> key + " < '" + value + "'";
            case IN -> key + " IN (" + String.join(", ", condition.getValues()) + ")";
            default -> "";
        };
    }
}
