package com.dataclean.util;

import com.dataclean.exception.BusinessException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态 SQL 标识符与关键字白名单。
 *
 * <p>所有会进入 SQL 的表名、列名、排序字段、聚合函数、列类型都必须先经过这里，
 * 校验失败统一抛出 400 业务异常，且不会执行任何 SQL。
 */
public final class SqlGuard {

    /** 与列名清洗规则保持一致：字母/数字/下划线/中文，且不能以数字开头，最长 64。 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]{0,63}$");

    private static final Pattern DECIMAL_TYPE = Pattern.compile("^DECIMAL\\((\\d{1,2}),(\\d{1,2})\\)$");
    private static final Pattern VARCHAR_TYPE = Pattern.compile("^VARCHAR\\((\\d{1,4})\\)$");

    private static final Set<String> AGGREGATIONS = Set.of("SUM", "AVG", "COUNT", "MAX", "MIN");
    private static final Set<String> CHART_TYPES = Set.of("bar", "line", "area", "pie", "scatter", "heatmap");
    private static final Set<String> FILTER_OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "like");
    private static final Set<String> SORT_DIRECTIONS = Set.of("ASC", "DESC");

    private SqlGuard() {
    }

    /**
     * 校验通用标识符（列名、别名等）。
     */
    public static String requireIdentifier(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, label + "不能为空");
        }
        String trimmed = name.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new BusinessException(400, label + "包含非法字符");
        }
        return trimmed;
    }

    /**
     * 校验表名：除通用标识符规则外，禁止系统库/内部前缀被外部传入。
     */
    public static String requireTableName(String name) {
        String trimmed = requireIdentifier(name, "表名");
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("information_schema")
                || lower.startsWith("mysql")
                || lower.startsWith("performance_schema")
                || lower.startsWith("sys")) {
            throw new BusinessException(400, "表名不合法");
        }
        return trimmed;
    }

    /**
     * 聚合函数白名单，返回大写形式。
     */
    public static String requireAggregation(String aggregation, String defaultAggregation) {
        String value = (aggregation == null || aggregation.isBlank())
                ? defaultAggregation
                : aggregation.trim().toUpperCase(Locale.ROOT);
        if (!AGGREGATIONS.contains(value)) {
            throw new BusinessException(400, "不支持的聚合方式: " + aggregation);
        }
        return value;
    }

    /**
     * 图表类型白名单。
     */
    public static String requireChartType(String chartType) {
        if (chartType == null || chartType.isBlank()) {
            throw new BusinessException(400, "图表类型不能为空");
        }
        String value = chartType.trim().toLowerCase(Locale.ROOT);
        if (!CHART_TYPES.contains(value)) {
            throw new BusinessException(400, "不支持的图表类型: " + chartType);
        }
        return value;
    }

    /**
     * 过滤操作符白名单。
     */
    public static String requireFilterOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(400, "过滤操作符不能为空");
        }
        String value = operator.trim().toLowerCase(Locale.ROOT);
        if (!FILTER_OPERATORS.contains(value)) {
            throw new BusinessException(400, "不支持的过滤操作符: " + operator);
        }
        return value;
    }

    /**
     * 排序列与方向白名单。
     */
    public static String requireSortDirection(String direction) {
        String value = (direction == null || direction.isBlank()) ? "ASC" : direction.trim().toUpperCase(Locale.ROOT);
        if (!SORT_DIRECTIONS.contains(value)) {
            throw new BusinessException(400, "排序方向只能是 ASC 或 DESC");
        }
        return value;
    }

    /**
     * 分页/取样条数限定在 [min, max]。
     */
    public static int clampLimit(Integer value, int defaultValue, int max) {
        int result = (value == null || value <= 0) ? defaultValue : value;
        if (result > max) {
            throw new BusinessException(400, "条数超过上限: " + max);
        }
        return result;
    }

    /**
     * 类型转换目标类型白名单，返回规范化后的 DDL 类型片段。
     * 只允许固定枚举与受长度约束的 DECIMAL/VARCHAR。
     */
    public static String requireColumnType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            throw new BusinessException(400, "目标类型不能为空");
        }
        String value = targetType.trim().toUpperCase(Locale.ROOT);
        switch (value) {
            case "INT", "BIGINT", "TINYINT", "DATETIME", "DATE", "TEXT":
                return value;
            default:
                break;
        }

        Matcher decimal = DECIMAL_TYPE.matcher(value);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            if (precision < 1 || precision > 65 || scale < 0 || scale > 30 || scale > precision) {
                throw new BusinessException(400, "DECIMAL 精度不合法");
            }
            return "DECIMAL(" + precision + "," + scale + ")";
        }

        Matcher varchar = VARCHAR_TYPE.matcher(value);
        if (varchar.matches()) {
            int length = Integer.parseInt(varchar.group(1));
            if (length < 1 || length > 2000) {
                throw new BusinessException(400, "VARCHAR 长度必须在 1-2000 之间");
            }
            return "VARCHAR(" + length + ")";
        }

        throw new BusinessException(400, "不支持的目标类型: " + targetType);
    }
}
