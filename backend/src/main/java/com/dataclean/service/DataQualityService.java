package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.DataQualityReport;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DataQualityReportMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.util.SqlGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据质量真实统计：重复率与类型不一致。
 *
 * <p>所有计算都下推到数据库聚合，禁止把全量数据拉入应用内存；
 * 未采集 / 计算中 / 失败 / 无重复 四种状态严格区分，不使用 0 或模拟数据伪装完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private static final int MAX_COLUMNS = 20;
    private static final int DEFAULT_SAMPLE_LIMIT = 5;
    private static final int MAX_SAMPLE_LIMIT = 10;
    private static final int CACHE_MINUTES = 10;

    private static final String NUMERIC_REGEX =
            "^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$";
    /** 日期格式校验：限制月/日取值范围，避免 2026/13/45 这类非法日期被误判为正常。 */
    private static final String DATE_REGEX =
            "^[0-9]{4}[-/](0?[1-9]|1[0-2])[-/](0?[1-9]|[12][0-9]|3[01])"
                    + "([ T]([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?)?$";    private static final String BOOLEAN_VALUES =
            "('true','false','0','1','yes','no','y','n','是','否')";

    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DataQualityReportMapper reportMapper;
    private final OwnershipService ownershipService;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    /** 按数据集维度的计算锁，避免并发计算同时插入质量报表触发唯一键冲突。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> computeLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 读取已采集报表；无记录返回 NOT_COLLECTED，绝不返回伪造的 0。 */
    public Map<String, Object> getReport(Long datasetId, Long versionId) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        VersionContext context = resolveVersion(dataset, versionId);
        DataQualityReport report = findReport(datasetId, context.versionKey);
        if (report == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("status", "NOT_COLLECTED");
            empty.put("datasetId", datasetId);
            empty.put("versionId", context.versionKey);
            empty.put("versionNo", context.versionNo);
            empty.put("message", "尚未采集质量统计，请点击“开始统计”");
            return empty;
        }
        return toPayload(report);
    }

    /** 执行真实统计（数据库聚合），并写入任务中心。按数据集串行，避免并发写入报表产生唯一键冲突。 */
    public Map<String, Object> compute(Long datasetId, Long versionId, String columnsParam,
                                       Integer sampleLimit, boolean refresh) {
        Object lock = computeLocks.computeIfAbsent(datasetId, k -> new Object());
        synchronized (lock) {
            return doCompute(datasetId, versionId, columnsParam, sampleLimit, refresh);
        }
    }

    private Map<String, Object> doCompute(Long datasetId, Long versionId, String columnsParam,
                                          Integer sampleLimit, boolean refresh) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        VersionContext context = resolveVersion(dataset, versionId);
        int limit = clampSampleLimit(sampleLimit);
        List<SelectedColumn> columns = selectColumns(dataset, context, columnsParam);
        String columnsKey = columns.stream().map(SelectedColumn::name)
                .reduce((a, b) -> a + "," + b).orElse("");

        DataQualityReport existing = findReport(datasetId, context.versionKey);
        if (!refresh && existing != null && DataQualityReport.STATUS_DONE.equals(existing.getStatus())
                && columnsKey.equals(existing.getColumnsKey())
                && existing.getExpiresAt() != null
                && existing.getExpiresAt().isAfter(LocalDateTime.now())) {
            return toPayload(existing);
        }

        markComputing(dataset, context, columnsKey);

        AsyncJob job = jobService.create(JobService.TYPE_QUALITY_STATS, dataset, context.versionId, null,
                Map.of("versionId", context.versionKey, "columns", columnsKey));
        jobService.begin(job);
        try {
            Map<String, Object> result = computeReport(dataset, context, columns, limit);
            String json = writeJson(result);
            markDone(dataset, context, columnsKey, json, result);
            long duplicated = toLong(((Map<?, ?>) result.get("duplicate")).get("duplicateRows"));
            Object mismatchColumns = ((Map<?, ?>) result.get("typeMismatch")).get("mismatchColumnCount");
            jobService.markSucceeded(job, null, context.rowCount,
                    "重复行=" + duplicated + "，类型不一致字段=" + mismatchColumns);
            return result;
        } catch (BusinessException e) {
            markFailed(dataset, context, e.getMessage());
            jobService.markFailedSafely(job, errorCodeOf(e), e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("质量统计失败: datasetId={}, error={}", datasetId, e.getClass().getSimpleName());
            String safe = "质量统计计算失败，请稍后重试";
            markFailed(dataset, context, safe);
            jobService.markFailedSafely(job, "QUALITY_STATS_FAILED", new BusinessException(500, safe));
            throw new BusinessException(500, safe);
        }
    }

    private String errorCodeOf(BusinessException e) {
        return e.getCode() == 429 ? "QUOTA_EXCEEDED" : "QUALITY_STATS_FAILED";
    }

    // ------------------------------------------------------------------ 计算

    private Map<String, Object> computeReport(Dataset dataset, VersionContext context,
                                              List<SelectedColumn> columns, int sampleLimit) {
        String table = context.tableName;
        long total = countRows(table);
        int queryCount = 1;

        Map<String, Object> duplicate = computeDuplicate(table, columns, total, sampleLimit);
        queryCount += 2;

        List<Map<String, Object>> profiles = new ArrayList<>();
        List<Map<String, Object>> draftRules = new ArrayList<>();
        int mismatchColumns = 0;
        long totalMismatch = 0;
        double scoreSum = 0;

        for (SelectedColumn column : columns) {
            Map<String, Object> profile = profileColumn(table, column, total);
            queryCount++;
            String status = String.valueOf(profile.get("status"));
            long mismatch = toLong(profile.get("mismatchCount"));
            if (mismatch > 0) {
                mismatchColumns++;
                totalMismatch += mismatch;
                List<Object> samples = sampleBadValues(table, column, profile, sampleLimit);
                queryCount++;
                profile.put("samples", samples);
                draftRules.add(draftRule(column, profile, mismatch));
            }
            double score = 100.0;
            if (total > 0) {
                long nullCount = toLong(profile.get("nullCount"));
                long blank = toLong(profile.get("blankCount"));
                score -= (nullCount + blank) * 100.0 / total;
                score -= mismatch * 100.0 / total;
                score = Math.max(0, Math.min(100, score));
                profile.put("score", round(score));
                scoreSum += score;
            } else {
                // 0 行数据：没有可评估的样本，评分必须为 null，不能用 100 分冒充高质量
                profile.put("score", null);
            }
            profiles.add(profile);
            if ("ANOMALY".equals(status)) {
                // 异常列在概览里标记为高优先级
                profile.put("priority", mismatch * 100.0 / Math.max(1, total) >= 5 ? "HIGH" : "MEDIUM");
            }
        }

        if (toLong(duplicate.get("duplicateRows")) > 0) {
            draftRules.add(0, duplicateRule(columns, duplicate));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("score", total == 0 || profiles.isEmpty() ? null : round(scoreSum / profiles.size()));
        summary.put("level", total == 0 ? "EMPTY" : (totalMismatch > 0 ? "ABNORMAL" : "NORMAL"));
        summary.put("duplicateRate", duplicate.get("duplicateRate"));
        summary.put("duplicateRows", duplicate.get("duplicateRows"));
        summary.put("mismatchColumnCount", mismatchColumns);
        summary.put("totalMismatchRows", totalMismatch);
        summary.put("notice", total == 0 ? "数据集没有数据行，质量评分不可用" : null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", DataQualityReport.STATUS_DONE);
        result.put("datasetId", dataset.getId());
        result.put("versionId", context.versionKey);
        result.put("versionNo", context.versionNo);
        result.put("rowCount", total);
        result.put("approximate", false);
        result.put("computedAt", LocalDateTime.now().toString());
        result.put("duplicate", duplicate);
        result.put("typeMismatch", Map.of(
                "status", "DONE",
                "checkedColumns", columns.size(),
                "mismatchColumnCount", mismatchColumns,
                "columns", profiles));
        result.put("summary", summary);
        result.put("draftRules", draftRules);
        result.put("queryCount", queryCount);
        result.put("notice", total == 0
                ? "数据集没有数据行，无法评估质量；请先导入有效数据"
                : "统计基于数据库聚合与受限抽样示例，不会把全量数据拉入应用内存");
        return result;
    }

    private Map<String, Object> computeDuplicate(String table, List<SelectedColumn> columns,
                                                 long total, int sampleLimit) {
        String groupBy = columns.stream().map(c -> "`" + c.name() + "`")
                .reduce((a, b) -> a + "," + b).orElseThrow();
        String sql = "SELECT COALESCE(SUM(cnt),0) AS dup_rows, COUNT(*) AS dup_groups, "
                + "COALESCE(MAX(cnt),0) AS max_group FROM (SELECT COUNT(*) AS cnt FROM `"
                + table + "` GROUP BY " + groupBy + " HAVING COUNT(*) > 1) g";
        Map<String, Object> row = first(datasetMapper.selectDynamicSql(sql));
        long dupRows = toLong(row.get("dup_rows"));
        long dupGroups = toLong(row.get("dup_groups"));
        long maxGroup = toLong(row.get("max_group"));
        double rate = total > 0 ? dupRows * 100.0 / total : 0;

        List<Map<String, Object>> samples = new ArrayList<>();
        if (dupRows > 0) {
            String selectList = columns.stream().map(c -> "`" + c.name() + "`")
                    .reduce((a, b) -> a + "," + b).orElseThrow();
            String sampleSql = "SELECT " + selectList + ", COUNT(*) AS duplicate_count FROM `"
                    + table + "` GROUP BY " + groupBy + " HAVING COUNT(*) > 1 "
                    + "ORDER BY COUNT(*) DESC LIMIT " + sampleLimit;
            samples = datasetMapper.selectDynamicSql(sampleSql);
        }

        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("status", dupRows > 0 ? "DUPLICATES_FOUND" : "NO_DUPLICATES");
        duplicate.put("scopeColumns", columns.stream().map(SelectedColumn::alias).toList());
        duplicate.put("duplicateRows", dupRows);
        duplicate.put("duplicateGroups", dupGroups);
        duplicate.put("maxGroupSize", maxGroup);
        duplicate.put("duplicateRateValue", round(rate));
        duplicate.put("duplicateRate", formatRate(rate));
        duplicate.put("affectedColumnCount", columns.size());
        duplicate.put("samples", samples);
        duplicate.put("notice", dupRows > 0
                ? "重复行数按所选字段组合统计；勾选更多字段会改变重复口径"
                : "当前字段组合下没有重复行");
        return duplicate;
    }

    /**
     * 单列聚合：一次 SQL 同时得到空值、空串、去重值数量与“疑似数值/日期/布尔”的匹配数量。
     */
    private Map<String, Object> profileColumn(String table, SelectedColumn column, long total) {
        String col = "`" + column.name() + "`";
        String text = "TRIM(CAST(" + col + " AS CHAR))";
        String sql = "SELECT COUNT(" + col + ") AS non_null, "
                + "COALESCE(SUM(CASE WHEN " + text + " = '' THEN 1 ELSE 0 END),0) AS blank_count, "
                + "COUNT(DISTINCT " + col + ") AS distinct_count, "
                + "COALESCE(SUM(CASE WHEN " + text + " <> '' AND " + text + " REGEXP '" + NUMERIC_REGEX
                + "' THEN 1 ELSE 0 END),0) AS num_like, "
                + "COALESCE(SUM(CASE WHEN " + text + " <> '' AND " + text + " REGEXP '" + DATE_REGEX
                + "' AND " + dateValidExpr(text) + " THEN 1 ELSE 0 END),0) AS date_like, "
                + "COALESCE(SUM(CASE WHEN LOWER(" + text + ") IN " + BOOLEAN_VALUES
                + " THEN 1 ELSE 0 END),0) AS bool_like "
                + "FROM `" + table + "`";
        Map<String, Object> row = first(datasetMapper.selectDynamicSql(sql));

        long nonNull = toLong(row.get("non_null"));
        long blank = toLong(row.get("blank_count"));
        long distinct = toLong(row.get("distinct_count"));
        long valid = Math.max(0, nonNull - blank);
        long numLike = toLong(row.get("num_like"));
        long dateLike = toLong(row.get("date_like"));
        long boolLike = toLong(row.get("bool_like"));

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("columnName", column.name());
        profile.put("columnAlias", column.alias());
        profile.put("declaredType", column.dataType());
        profile.put("nullCount", Math.max(0, total - nonNull));
        profile.put("blankCount", blank);
        profile.put("distinctCount", distinct);
        profile.put("checkedCount", valid);
        profile.put("enumLike", valid > 0 && distinct > 0 && distinct <= 30);

        String declared = column.dataType() == null ? "" : column.dataType().toUpperCase(Locale.ROOT);
        if (isNumericType(declared)) {
            profile.put("expectedType", "NUMERIC");
            profile.put("status", "CONSISTENT");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "物理类型为数值，数据库已约束格式");
            return profile;
        }
        if (isDateType(declared)) {
            profile.put("expectedType", "DATE");
            profile.put("status", "CONSISTENT");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "物理类型为日期，数据库已约束格式");
            return profile;
        }
        if (declared.startsWith("TINYINT")) {
            profile.put("expectedType", "BOOLEAN");
            profile.put("status", "CONSISTENT");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "布尔字段已按 0/1 存储");
            return profile;
        }

        if (valid == 0) {
            profile.put("expectedType", "UNKNOWN");
            profile.put("status", "NO_DATA");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "该字段没有可检查的非空值");
            return profile;
        }

        String expected;
        long matched;
        if (numLike >= dateLike && numLike >= boolLike) {
            expected = "NUMERIC";
            matched = numLike;
        } else if (dateLike >= boolLike) {
            expected = "DATE";
            matched = dateLike;
        } else {
            expected = "BOOLEAN";
            matched = boolLike;
        }
        double ratio = (double) matched / valid;
        long mismatch = valid - matched;

        if (matched == 0) {
            profile.put("expectedType", "TEXT");
            profile.put("status", "CONSISTENT");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "未发现占主导的格式类型，按文本处理");
            return profile;
        }

        profile.put("expectedType", expected);
        profile.put("matchedCount", matched);
        if (ratio >= 0.9 && mismatch > 0) {
            profile.put("status", "ANOMALY");
            profile.put("mismatchCount", mismatch);
            profile.put("mismatchRate", formatRate(mismatch * 100.0 / valid));
            profile.put("suggestion", suggestionOf(expected));
        } else if (ratio >= 1.0) {
            profile.put("status", "CONSISTENT");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "全部取值符合 " + expected + " 格式，可考虑转换为对应类型");
        } else {
            profile.put("status", "CONSISTENT");
            profile.put("mismatchCount", 0L);
            profile.put("mismatchRate", "0%");
            profile.put("message", "未发现占主导的格式类型，按文本处理");
        }
        return profile;
    }

    @SuppressWarnings("unchecked")
    private List<Object> sampleBadValues(String table, SelectedColumn column,
                                         Map<String, Object> profile, int limit) {
        String expected = String.valueOf(profile.get("expectedType"));
        String condition = switch (expected) {
            case "NUMERIC" -> "NOT (" + textOf(column) + " REGEXP '" + NUMERIC_REGEX + "')";
            case "DATE" -> "NOT (" + textOf(column) + " REGEXP '" + DATE_REGEX
                    + "' AND " + dateValidExpr(textOf(column)) + ")";
            case "BOOLEAN" -> "LOWER(" + textOf(column) + ") NOT IN " + BOOLEAN_VALUES;
            default -> null;
        };
        if (condition == null) {
            return List.of();
        }
        String sql = "SELECT DISTINCT `" + column.name() + "` AS sample FROM `" + table + "` "
                + "WHERE `" + column.name() + "` IS NOT NULL AND " + textOf(column) + " <> '' AND "
                + condition + " LIMIT " + limit;
        List<Map<String, Object>> rows = datasetMapper.selectDynamicSql(sql);
        List<Object> samples = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get("sample");
            if (value != null) {
                String text = String.valueOf(value);
                samples.add(text.length() > 80 ? text.substring(0, 80) + "…" : text);
            }
        }
        return samples;
    }

    private String textOf(SelectedColumn column) {
        return "TRIM(CAST(`" + column.name() + "` AS CHAR))";
    }

    /**
     * 日期真实性校验（在格式匹配之后执行）：STR_TO_DATE 对不存在的日期（如 2026-02-30、2025-02-29）返回 NULL，
     * 因此能区分“格式一致”与“日期合法”，并正确处理闰年。兼容 yyyy-MM-dd / yyyy/MM/dd 及可选时间部分。
     */
    private String dateValidExpr(String text) {
        return "STR_TO_DATE(REPLACE(SUBSTRING_INDEX(SUBSTRING_INDEX(" + text
                + ", 'T', 1), ' ', 1), '/', '-'), '%Y-%m-%d') IS NOT NULL";
    }

    private Map<String, Object> draftRule(SelectedColumn column, Map<String, Object> profile, long mismatch) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleType", "TYPE_CONVERT");
        rule.put("column", column.alias());
        rule.put("targetType", String.valueOf(profile.get("expectedType")));
        rule.put("action", "CONVERT_OR_NULL");
        rule.put("expectedAffectedRows", mismatch);
        rule.put("requiresConfirm", true);
        return rule;
    }

    private Map<String, Object> duplicateRule(List<SelectedColumn> columns, Map<String, Object> duplicate) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleType", "REMOVE_DUPLICATE");
        rule.put("columns", columns.stream().map(SelectedColumn::alias).toList());
        rule.put("keep", "FIRST");
        rule.put("expectedAffectedRows", duplicate.get("duplicateRows"));
        rule.put("requiresConfirm", true);
        return rule;
    }

    private String suggestionOf(String expected) {
        return switch (expected) {
            case "NUMERIC" -> "建议转换为数值类型；无法转换的值置空，并保留异常值清单待人工确认";
            case "DATE" -> "建议统一为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss；无法解析的值置空待确认";
            case "BOOLEAN" -> "建议统一为 true/false 或 1/0；其他取值置空待确认";
            default -> "建议先人工确认字段业务含义，再决定转换策略";
        };
    }

    // ------------------------------------------------------------------ 元数据

    private VersionContext resolveVersion(Dataset dataset, Long versionId) {
        DatasetVersion version = null;
        if (versionId != null) {
            version = datasetVersionMapper.selectById(versionId);
            if (version == null || !dataset.getId().equals(version.getDatasetId())) {
                throw new BusinessException(404, "数据版本不存在");
            }
        } else if (dataset.getCurrentVersionId() != null) {
            version = datasetVersionMapper.selectById(dataset.getCurrentVersionId());
        }
        String table = version != null && version.getTableName() != null
                ? version.getTableName() : dataset.getTableName();
        long versionKey = version == null || version.getId() == null ? 0L : version.getId();
        long rowCount = version != null && version.getRowCount() != null
                ? version.getRowCount()
                : (dataset.getRowCount() == null ? 0L : dataset.getRowCount());
        Integer versionNo = version == null ? null : version.getVersionNo();
        if (table == null || table.isBlank()) {
            throw new BusinessException(400, "数据集尚未完成导入，无法统计");
        }
        return new VersionContext(version == null ? null : version.getId(), versionKey,
                SqlGuard.requireTableName(table), rowCount, versionNo);
    }

    private List<SelectedColumn> selectColumns(Dataset dataset, VersionContext context, String columnsParam) {
        List<DatasetColumn> metadata = datasetColumnMapper.selectByDatasetId(dataset.getId());
        if (metadata == null || metadata.isEmpty()) {
            throw new BusinessException(400, "数据集没有可用字段");
        }
        Set<String> physical = new LinkedHashSet<>(datasetMapper.selectTableColumns(context.tableName));
        if (physical.isEmpty()) {
            throw new BusinessException(400, "数据表不存在或尚未完成导入");
        }
        Map<String, DatasetColumn> byAlias = new LinkedHashMap<>();
        for (DatasetColumn column : metadata) {
            byAlias.put(column.getColumnAlias(), column);
        }

        List<DatasetColumn> chosen = new ArrayList<>();
        if (columnsParam != null && !columnsParam.isBlank()) {
            for (String raw : columnsParam.split(",")) {
                String alias = raw.trim();
                if (alias.isEmpty()) {
                    continue;
                }
                DatasetColumn column = byAlias.get(alias);
                if (column == null || !physical.contains(column.getColumnName())) {
                    throw new BusinessException(400, "字段不存在: " + alias);
                }
                chosen.add(column);
            }
            if (chosen.isEmpty()) {
                throw new BusinessException(400, "请选择至少一个字段");
            }
        } else {
            for (DatasetColumn column : metadata) {
                if (chosen.size() >= MAX_COLUMNS) {
                    break;
                }
                if (physical.contains(column.getColumnName())) {
                    chosen.add(column);
                }
            }
        }
        if (chosen.size() > MAX_COLUMNS) {
            throw new BusinessException(400, "单次最多统计 " + MAX_COLUMNS + " 个字段");
        }
        List<SelectedColumn> result = new ArrayList<>();
        for (DatasetColumn column : chosen) {
            String name = SqlGuard.requireIdentifier(column.getColumnName(), "字段");
            result.add(new SelectedColumn(name, column.getColumnAlias(), column.getDataType()));
        }
        return result;
    }

    // ------------------------------------------------------------------ 持久化

    private DataQualityReport findReport(Long datasetId, long versionKey) {
        return reportMapper.selectOne(new LambdaQueryWrapper<DataQualityReport>()
                .eq(DataQualityReport::getDatasetId, datasetId)
                .eq(DataQualityReport::getVersionId, versionKey));
    }

    private void markComputing(Dataset dataset, VersionContext context, String columnsKey) {
        DataQualityReport report = findReport(dataset.getId(), context.versionKey);
        if (report == null) {
            report = new DataQualityReport();
            report.setDatasetId(dataset.getId());
            report.setVersionId(context.versionKey);
        }
        report.setStatus(DataQualityReport.STATUS_COMPUTING);
        report.setColumnsKey(columnsKey);
        report.setErrorSummary(null);
        report.setOwnerType(ownershipService.currentOwnerType());
        report.setAnonSessionId(ownershipService.isPublic() ? ownershipService.currentSessionId() : null);
        save(report);
    }

    private void markDone(Dataset dataset, VersionContext context, String columnsKey,
                          String payload, Map<String, Object> result) {
        DataQualityReport report = findReport(dataset.getId(), context.versionKey);
        if (report == null) {
            report = new DataQualityReport();
            report.setDatasetId(dataset.getId());
            report.setVersionId(context.versionKey);
        }
        report.setStatus(DataQualityReport.STATUS_DONE);
        report.setColumnsKey(columnsKey);
        report.setPayload(payload);
        report.setComputedAt(LocalDateTime.now());
        report.setExpiresAt(LocalDateTime.now().plusMinutes(CACHE_MINUTES));
        report.setErrorSummary(null);
        report.setOwnerType(ownershipService.currentOwnerType());
        report.setAnonSessionId(ownershipService.isPublic() ? ownershipService.currentSessionId() : null);
        save(report);
    }

    private void markFailed(Dataset dataset, VersionContext context, String summary) {
        DataQualityReport report = findReport(dataset.getId(), context.versionKey);
        if (report == null) {
            report = new DataQualityReport();
            report.setDatasetId(dataset.getId());
            report.setVersionId(context.versionKey);
        }
        report.setStatus(DataQualityReport.STATUS_FAILED);
        report.setPayload(null);
        report.setErrorSummary(summary == null ? "统计失败" : summary.substring(0, Math.min(500, summary.length())));
        save(report);
    }

    private void save(DataQualityReport report) {
        if (report.getId() == null) {
            reportMapper.insert(report);
        } else {
            reportMapper.updateById(report);
        }
    }

    private Map<String, Object> toPayload(DataQualityReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", report.getStatus());
        payload.put("datasetId", report.getDatasetId());
        payload.put("versionId", report.getVersionId());
        payload.put("computedAt", report.getComputedAt());
        payload.put("expiresAt", report.getExpiresAt());
        payload.put("stale", report.getExpiresAt() != null
                && report.getExpiresAt().isBefore(LocalDateTime.now()));
        if (report.getPayload() != null && !report.getPayload().isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(report.getPayload(), Map.class);
                payload.putAll(parsed);
                // 数据库状态优先，避免缓存 payload 中的旧 status 覆盖真实状态
                payload.put("status", report.getStatus());
                payload.put("computedAt", report.getComputedAt());
                payload.put("expiresAt", report.getExpiresAt());
            } catch (JsonProcessingException e) {
                payload.put("status", DataQualityReport.STATUS_FAILED);
                payload.put("message", "历史统计结果已损坏，请重新统计");
            }
        } else if (DataQualityReport.STATUS_COMPUTING.equals(report.getStatus())) {
            payload.put("message", "统计计算中，请稍候刷新");
        } else if (DataQualityReport.STATUS_FAILED.equals(report.getStatus())) {
            payload.put("message", report.getErrorSummary() == null ? "统计失败，请重试" : report.getErrorSummary());
        }
        return payload;
    }

    // ------------------------------------------------------------------ 工具

    private long countRows(String table) {
        Map<String, Object> row = first(datasetMapper.selectDynamicSql(
                "SELECT COUNT(*) AS total FROM `" + table + "`"));
        return toLong(row.get("total"));
    }

    private Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    private int clampSampleLimit(Integer sampleLimit) {
        if (sampleLimit == null || sampleLimit < 1) {
            return DEFAULT_SAMPLE_LIMIT;
        }
        return Math.min(sampleLimit, MAX_SAMPLE_LIMIT);
    }

    private boolean isNumericType(String type) {
        return type.contains("INT") || type.contains("DECIMAL") || type.contains("DOUBLE")
                || type.contains("FLOAT") || type.contains("NUMERIC");
    }

    private boolean isDateType(String type) {
        return type.contains("DATE") || type.contains("TIME") || type.contains("TIMESTAMP");
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return new BigDecimal(value.toString()).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String formatRate(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "统计结果序列化失败");
        }
    }

    private record SelectedColumn(String name, String alias, String dataType) {
    }

    private record VersionContext(Long versionId, long versionKey, String tableName,
                                  long rowCount, Integer versionNo) {
    }
}
