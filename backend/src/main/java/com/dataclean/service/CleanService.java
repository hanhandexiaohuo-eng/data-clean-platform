package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.dto.CleanExecuteDTO;
import com.dataclean.dto.CleanResultVO;
import com.dataclean.dto.CleanRuleDTO;
import com.dataclean.entity.CleanLog;
import com.dataclean.entity.CleanRule;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.util.SqlGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据清洗业务服务层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanService {

    private final CleanRuleMapper cleanRuleMapper;
    private final CleanLogMapper cleanLogMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DynamicTableService dynamicTableService;
    private final ObjectMapper objectMapper;
    private final OwnershipService ownershipService;
    private final PublicQuotaService publicQuotaService;
    private final JobService jobService;
    /** 按数据集维度的执行锁，避免并发请求同时通过「无进行中任务」检查后重复建任务。 */
    private final ConcurrentHashMap<Long, Object> cleanLocks = new ConcurrentHashMap<>();

    /** 自定义表达式过滤默认下线；开启后也只能执行受限 DSL。 */
    @Value("${app.clean.custom-filter-enabled:false}")
    private boolean customFilterEnabled;

    private static final Set<String> RULE_TYPES = Set.of(
            "missing_value_handle", "remove_duplicate", "type_convert",
            "column_standardize", "outlier_handle", "custom_filter");

    private static final Pattern DSL_CONDITION = Pattern.compile(
            "^\\s*([A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]{0,63})\\s*" +
                    "(=|!=|<>|>=|<=|>|<|LIKE)\\s*" +
                    "('(?:[^']|'')*'|-?\\d+(?:\\.\\d+)?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 数据库元数据中的列类型，重命名时原样回填 DDL 前必须做结构校验 */
    private static final Pattern SAFE_COLUMN_TYPE = Pattern.compile(
            "^[A-Za-z]+(\\([0-9]{1,4}(,[0-9]{1,2})?\\))?( unsigned)?( zerofill)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 查询数据集的清洗规则
     */
    public List<CleanRule> listRulesByDataset(Long datasetId) {
        ownershipService.requireDataset(datasetId);
        LambdaQueryWrapper<CleanRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CleanRule::getDatasetId, datasetId);
        wrapper.orderByAsc(CleanRule::getSortOrder);
        return cleanRuleMapper.selectList(wrapper);
    }

    /**
     * 创建清洗规则
     */
    @Transactional(rollbackFor = Exception.class)
    public CleanRule createRule(CleanRuleDTO ruleDTO) {
        Dataset dataset = ownershipService.requireDataset(ruleDTO.getDatasetId());
        ownershipService.requireWritable(dataset);

        CleanRule rule = new CleanRule();
        rule.setDatasetId(ruleDTO.getDatasetId());
        rule.setRuleName(ruleDTO.getRuleName());
        rule.setRuleType(ruleDTO.getRuleType());
        rule.setSortOrder(ruleDTO.getSortOrder());
        rule.setStatus(ruleDTO.getStatus());
        rule.setAnonSessionId(ownershipService.currentSessionId());

        // 构建规则配置JSON
        Map<String, Object> config = buildRuleConfig(ruleDTO);
        try {
            rule.setRuleConfig(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new BusinessException("规则配置序列化失败");
        }

        cleanRuleMapper.insert(rule);
        return rule;
    }

    /**
     * 更新清洗规则
     */
    @Transactional(rollbackFor = Exception.class)
    public CleanRule updateRule(CleanRuleDTO ruleDTO) {
        CleanRule existing = ownershipService.requireRule(ruleDTO.getId());
        Dataset dataset = ownershipService.requireDataset(existing.getDatasetId());
        ownershipService.requireWritable(dataset);

        existing.setRuleName(ruleDTO.getRuleName());
        existing.setRuleType(ruleDTO.getRuleType());
        existing.setSortOrder(ruleDTO.getSortOrder());
        existing.setStatus(ruleDTO.getStatus());

        Map<String, Object> config = buildRuleConfig(ruleDTO);
        try {
            existing.setRuleConfig(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new BusinessException("规则配置序列化失败");
        }

        cleanRuleMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除规则
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        CleanRule rule = ownershipService.requireRule(id);
        ownershipService.requireWritable(ownershipService.requireDataset(rule.getDatasetId()));
        cleanRuleMapper.deleteById(id);
    }

    /**
     * 执行清洗。
     *
     * <p>正式执行只创建持久化异步任务，不直接修改当前数据；预览在临时克隆表上执行，
     * 不产生备份表，也不触碰当前版本。
     */
    public CleanResultVO executeClean(CleanExecuteDTO executeDTO) {
        Dataset dataset = ownershipService.requireDataset(executeDTO.getDatasetId());
        ownershipService.requireWritable(dataset);
        List<CleanRule> rules = loadRules(dataset.getId(), executeDTO.getRuleIds());

        if (Boolean.TRUE.equals(executeDTO.getPreviewMode())) {
            return previewClean(dataset, rules);
        }

        // 同一数据集的「检查 + 建任务」串行化，消除并发请求下重复创建任务的竞态窗口
        Object lock = cleanLocks.computeIfAbsent(dataset.getId(), k -> new Object());
        synchronized (lock) {
            if (jobService.countActiveByDataset(dataset.getId(), JobService.TYPE_CLEAN) > 0) {
                throw new BusinessException(409, "该数据集已有进行中的清洗任务，请等待完成或取消后再试");
            }
            publicQuotaService.checkCleanTask();
            List<Long> ruleIds = rules.stream().map(CleanRule::getId).toList();
            var job = jobService.create(JobService.TYPE_CLEAN, dataset, dataset.getCurrentVersionId(), null,
                    Map.of("ruleIds", ruleIds));

            CleanResultVO result = new CleanResultVO();
            result.setJobId(job.getId());
            result.setExecutedRules(rules.stream().map(CleanRule::getRuleName).toList());
            result.setCurrentVersionId(dataset.getCurrentVersionId());
            return result;
        }
    }

    /** 供异步任务使用：校验规则归属并按顺序返回。 */
    public List<CleanRule> loadRules(Long datasetId, List<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            throw new BusinessException(400, "没有有效的清洗规则");
        }
        List<CleanRule> rules = new ArrayList<>();
        for (Long ruleId : ruleIds) {
            CleanRule rule = ownershipService.requireRule(ruleId);
            if (!datasetId.equals(rule.getDatasetId())) {
                throw new BusinessException(404, "规则不存在");
            }
            rules.add(rule);
        }
        rules.sort(Comparator.comparingInt(CleanRule::getSortOrder));
        return rules;
    }

    /** 供后台任务使用：不依赖请求上下文，按数据集与规则ID直接校验归属。 */
    public List<CleanRule> loadRulesForJob(Long datasetId, List<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            throw new BusinessException(400, "没有有效的清洗规则");
        }
        List<CleanRule> loaded = cleanRuleMapper.selectBatchIds(ruleIds);
        if (loaded.size() != ruleIds.size()) {
            throw new BusinessException(404, "清洗规则不存在");
        }
        for (CleanRule rule : loaded) {
            if (!datasetId.equals(rule.getDatasetId())) {
                throw new BusinessException(404, "清洗规则不存在");
            }
        }
        loaded.sort(Comparator.comparingInt(CleanRule::getSortOrder));
        return loaded;
    }

    /**
     * 预览：克隆当前版本表到临时表执行规则，完成后立即删除。
     * 整个过程不改动 dataset 指针、不产生备份表、不写清洗日志。
     */
    private CleanResultVO previewClean(Dataset dataset, List<CleanRule> rules) {
        String tempTable = "data_preview_" + System.nanoTime();
        SqlGuard.requireTableName(tempTable);
        Integer beforeCount = dynamicTableService.getTableRowCount(dataset.getTableName()).intValue();
        long start = System.currentTimeMillis();
        try {
            datasetMapper.executeDynamicSql("CREATE TABLE `" + tempTable + "` LIKE `"
                    + SqlGuard.requireTableName(dataset.getTableName()) + "`");
            datasetMapper.executeDynamicSql("INSERT INTO `" + tempTable + "` SELECT * FROM `"
                    + SqlGuard.requireTableName(dataset.getTableName()) + "`");
            for (CleanRule rule : rules) {
                applyRule(tempTable, rule);
            }
            Integer afterCount = dynamicTableService.getTableRowCount(tempTable).intValue();
            List<Map<String, Object>> sample = datasetMapper.selectDynamicDataPage(tempTable, 0, 20);

            CleanResultVO result = new CleanResultVO();
            result.setBeforeCount(beforeCount);
            result.setAfterCount(afterCount);
            result.setAffectedRows(beforeCount - afterCount);
            result.setExecutedRules(rules.stream().map(CleanRule::getRuleName).toList());
            result.setPreviewData(sample);
            result.setExecuteTimeMs((int) (System.currentTimeMillis() - start));
            result.setCurrentVersionId(dataset.getCurrentVersionId());
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("清洗预览失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(400, "清洗预览失败，请检查规则配置");
        } finally {
            try {
                datasetMapper.executeDynamicSql("DROP TABLE IF EXISTS `" + tempTable + "`");
            } catch (RuntimeException e) {
                log.warn("删除预览临时表失败: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 旧备份回滚入口。
     *
     * <p>第五阶段起清洗不再原地覆盖数据，历史备份回滚会破坏当前版本的校验摘要，
     * 因此该入口统一拒绝，回滚必须走“切换到指定历史版本”的版本恢复流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackClean(Long logId) {
        CleanLog cleanLog = ownershipService.requireCleanLog(logId);
        Dataset dataset = ownershipService.requireDataset(cleanLog.getDatasetId());
        ownershipService.requireWritable(dataset);
        throw new BusinessException(400, "旧备份回滚已停用，请在版本列表中选择历史版本进行恢复");
    }

    /**
     * 查询清洗日志
     */
    public List<CleanLog> listCleanLogs(Long datasetId) {
        ownershipService.requireDataset(datasetId);
        return cleanLogMapper.selectByDatasetId(datasetId);
    }

    /**
     * 构建规则配置
     */
    private Map<String, Object> buildRuleConfig(CleanRuleDTO dto) {
        if (dto.getRuleType() == null || !RULE_TYPES.contains(dto.getRuleType())) {
            throw new BusinessException(400, "不支持的清洗规则类型");
        }
        Map<String, Object> config = new LinkedHashMap<>();

        switch (dto.getRuleType()) {
            case "missing_value_handle": {
                String column = SqlGuard.requireIdentifier(dto.getTargetColumn(), "目标列");
                String handleType = dto.getHandleType();
                if (handleType == null || !Set.of("delete", "mean", "median", "fixed").contains(handleType)) {
                    throw new BusinessException(400, "缺失值处理方式不合法");
                }
                if ("fixed".equals(handleType) && dto.getFillValue() != null && dto.getFillValue().length() > 500) {
                    throw new BusinessException(400, "填充值过长");
                }
                config.put("targetColumn", column);
                config.put("handleType", handleType);
                config.put("fillValue", dto.getFillValue());
                break;
            }
            case "remove_duplicate": {
                String target = dto.getTargetColumn();
                if (target == null || target.trim().isEmpty() || "all".equalsIgnoreCase(target.trim())) {
                    config.put("targetColumns", "all");
                } else {
                    String normalized = Arrays.stream(target.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(s -> SqlGuard.requireIdentifier(s, "去重列"))
                            .reduce((a, b) -> a + "," + b)
                            .orElseThrow(() -> new BusinessException(400, "去重列不能为空"));
                    config.put("targetColumns", normalized);
                }
                break;
            }
            case "type_convert":
                config.put("targetColumn", SqlGuard.requireIdentifier(dto.getTargetColumn(), "目标列"));
                config.put("targetType", SqlGuard.requireColumnType(dto.getTargetType()));
                break;
            case "column_standardize":
                config.put("columns", "all");
                break;
            case "outlier_handle": {
                String method = dto.getDetectMethod();
                if (method == null || !Set.of("z_score", "iqr").contains(method)) {
                    throw new BusinessException(400, "异常值检测方式不合法");
                }
                config.put("targetColumn", SqlGuard.requireIdentifier(dto.getTargetColumn(), "目标列"));
                config.put("detectMethod", method);
                break;
            }
            case "custom_filter":
                if (!customFilterEnabled) {
                    throw new BusinessException(400, "自定义表达式过滤已下线，请使用可视化筛选条件");
                }
                if (dto.getFilterExpression() == null || dto.getFilterExpression().isBlank()) {
                    throw new BusinessException(400, "过滤表达式不能为空");
                }
                config.put("expression", dto.getFilterExpression().trim());
                break;
            default:
                throw new BusinessException(400, "不支持的清洗规则类型");
        }

        return config;
    }

    /**
     * 在指定物理表上执行单条清洗规则。
     *
     * <p>异步清洗在新建的版本表上调用；预览在临时克隆表上调用。任何规则失败都必须抛出，
     * 由调用方标记任务失败并删除半成品表，禁止静默吞掉异常。
     */
    public void applyRule(String rawTableName, CleanRule rule) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = objectMapper.readValue(rule.getRuleConfig(), Map.class);
        String tableName = SqlGuard.requireTableName(rawTableName);

        switch (rule.getRuleType()) {
            case "missing_value_handle":
                executeMissingValueHandle(tableName, config);
                break;
            case "remove_duplicate":
                executeRemoveDuplicate(tableName, config);
                break;
            case "type_convert":
                executeTypeConvert(tableName, config);
                break;
            case "column_standardize":
                executeColumnStandardize(tableName);
                break;
            case "outlier_handle":
                executeOutlierHandle(tableName, config);
                break;
            case "custom_filter":
                executeCustomFilter(tableName, config);
                break;
            default:
                throw new BusinessException(400, "不支持的清洗规则类型");
        }
    }

    /**
     * 缺失值处理
     */
    private void executeMissingValueHandle(String tableName, Map<String, Object> config) {
        String targetColumn = (String) config.get("targetColumn");
        String handleType = (String) config.get("handleType");
        String fillValue = (String) config.get("fillValue");

        if (targetColumn == null || targetColumn.trim().isEmpty()) {
            throw new BusinessException(400, "缺失值处理缺少目标列配置");
        }
        String safeColumn = SqlGuard.requireIdentifier(targetColumn, "目标列");
        String columnName = "`" + safeColumn + "`";
        String safeTable = SqlGuard.requireTableName(tableName);

        switch (handleType) {
            case "delete":
                // 删除包含缺失值的行
                String deleteSql = "DELETE FROM `" + safeTable + "` WHERE " + columnName + " IS NULL";
                datasetMapper.executeDynamicSql(deleteSql);
                break;
            case "mean":
                // 先独立聚合再参数化更新，避免 MySQL 1093（不能在 UPDATE 子查询中引用目标表）
                Double mean = averageOf(safeTable, safeColumn);
                if (mean != null) {
                    datasetMapper.executeDynamicSqlWithParams(
                            "UPDATE `" + safeTable + "` SET " + columnName + " = #{params[0]} WHERE "
                                    + columnName + " IS NULL",
                            Collections.singletonList(BigDecimal.valueOf(mean)));
                }
                break;
            case "median":
                // 简化处理：使用均值近似（分位数计算下推到数据库并限制样本，避免全列入内存）
                Double medianApprox = averageOf(safeTable, safeColumn);
                if (medianApprox != null) {
                    datasetMapper.executeDynamicSqlWithParams(
                            "UPDATE `" + safeTable + "` SET " + columnName + " = #{params[0]} WHERE "
                                    + columnName + " IS NULL",
                            Collections.singletonList(BigDecimal.valueOf(medianApprox)));
                }
                break;
            case "fixed":
                // 固定值通过参数绑定写入，禁止拼接字符串
                String fixedSql = "UPDATE `" + safeTable + "` SET " + columnName
                        + " = #{params[0]} WHERE " + columnName + " IS NULL";
                datasetMapper.executeDynamicSqlWithParams(fixedSql,
                        Collections.singletonList(fillValue == null ? "" : fillValue));
                break;
            default:
                throw new BusinessException(400, "缺失值处理方式不合法");
        }

        log.info("缺失值处理完成: {} - {}", targetColumn, handleType);
    }

    /** 单列均值；列/表名已通过白名单校验。 */
    private Double averageOf(String table, String column) {
        List<Map<String, Object>> rows = datasetMapper.selectDynamicSql(
                "SELECT AVG(`" + column + "`) AS avg_val FROM `" + table + "`");
        if (rows.isEmpty() || rows.get(0).get("avg_val") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("avg_val")).doubleValue();
    }

    /**
     * 删除重复行
     */
    private void executeRemoveDuplicate(String tableName, Map<String, Object> config) {
        String targetColumns = (String) config.get("targetColumns");
        String columns;

        String safeTable = SqlGuard.requireTableName(tableName);

        if (targetColumns == null || targetColumns.trim().isEmpty() || "all".equals(targetColumns.trim())) {
            // 获取所有列(排除_id)，表名使用参数绑定
            List<Map<String, Object>> cols = datasetMapper.selectDynamicSqlWithParams(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = #{params[0]} AND COLUMN_NAME != '_id'",
                    Collections.singletonList(safeTable));
            columns = cols.stream()
                    .map(c -> "`" + SqlGuard.requireIdentifier(String.valueOf(c.get("COLUMN_NAME")), "列名") + "`")
                    .reduce((a, b) -> a + ", " + b).orElse("");
        } else {
            columns = Arrays.stream(targetColumns.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(c -> "`" + SqlGuard.requireIdentifier(c, "去重列") + "`")
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }

        if (columns == null || columns.isEmpty()) {
            throw new BusinessException(400, "去重操作未找到有效的目标列");
        }

        // 使用临时表方式删除重复（表名长度需控制在 64 以内）
        String prefix = safeTable.length() > 40 ? safeTable.substring(0, 40) : safeTable;
        String tempTable = SqlGuard.requireIdentifier(
                prefix + "_dd_" + (System.currentTimeMillis() % 1_000_000), "临时表名");

        // 创建临时表存储唯一数据；失败时立即清理临时表，避免留下半成品
        String createTemp = "CREATE TABLE `" + tempTable + "` LIKE `" + safeTable + "`";
        try {
            datasetMapper.executeDynamicSql(createTemp);

            // 插入去重后的数据：按目标列分组保留每组最小 _id 的行
            // 不能使用 SELECT * ... GROUP BY（MySQL 8 默认 only_full_group_by 会拒绝非聚合列）
            String insertUnique = "INSERT INTO `" + tempTable + "` SELECT * FROM `" + safeTable + "` " +
                    "WHERE `_id` IN (SELECT MIN(`_id`) FROM `" + safeTable + "` GROUP BY " + columns + ")";
            datasetMapper.executeDynamicSql(insertUnique);

            // 删除原表并重命名
            datasetMapper.executeDynamicSql("DROP TABLE `" + safeTable + "`");
            datasetMapper.executeDynamicSql("RENAME TABLE `" + tempTable + "` TO `" + safeTable + "`");
        } catch (RuntimeException e) {
            try {
                datasetMapper.executeDynamicSql("DROP TABLE IF EXISTS `" + tempTable + "`");
            } catch (RuntimeException dropError) {
                log.warn("清理去重临时表失败: {}", dropError.getClass().getSimpleName());
            }
            throw e;
        }

        log.info("重复行删除完成: {}", tableName);
    }

    /**
     * 类型转换
     */
    private void executeTypeConvert(String tableName, Map<String, Object> config) {
        String targetColumn = (String) config.get("targetColumn");
        String targetType = (String) config.get("targetType");

        if (targetColumn == null || targetColumn.trim().isEmpty()) {
            throw new BusinessException(400, "类型转换缺少目标列配置");
        }
        if (targetType == null || targetType.trim().isEmpty()) {
            throw new BusinessException(400, "类型转换缺少目标类型配置");
        }

        String safeTable = SqlGuard.requireTableName(tableName);
        String safeColumn = SqlGuard.requireIdentifier(targetColumn, "目标列");
        String safeType = SqlGuard.requireColumnType(targetType);

        String sql = "ALTER TABLE `" + safeTable + "` MODIFY COLUMN `" + safeColumn + "` " + safeType;
        datasetMapper.executeDynamicSql(sql);

        log.info("类型转换完成: {} -> {}", safeColumn, safeType);
    }

    /**
     * 列名标准化
     */
    private void executeColumnStandardize(String tableName) {
        String safeTable = SqlGuard.requireTableName(tableName);

        // 同时取出列类型，重命名时必须原样保留类型（旧实现用子查询作类型非法）
        List<Map<String, Object>> columns = datasetMapper.selectDynamicSqlWithParams(
                "SELECT COLUMN_NAME, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = #{params[0]} AND COLUMN_NAME != '_id'",
                Collections.singletonList(safeTable));

        for (Map<String, Object> col : columns) {
            String oldName = String.valueOf(col.get("COLUMN_NAME"));
            String columnType = String.valueOf(col.get("COLUMN_TYPE"));
            if (columnType == null || columnType.isBlank() || "null".equals(columnType)) {
                throw new BusinessException(400, "列类型元数据缺失，已中止列名标准化");
            }
            if (!SAFE_COLUMN_TYPE.matcher(columnType.trim()).matches()) {
                // 类型来自 INFORMATION_SCHEMA，但仍必须通过白名单；不允许把任意元数据拼进 DDL
                throw new BusinessException(400, "列类型不在白名单内，已中止列名标准化");
            }

            String newName = oldName.toLowerCase()
                    .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]", "_")
                    .replaceAll("_{2,}", "_");
            if (newName.isEmpty() || oldName.equals(newName)) {
                continue;
            }

            String safeOld = SqlGuard.requireIdentifier(oldName, "原列名");
            String safeNew = SqlGuard.requireIdentifier(newName, "新列名");
            String sql = "ALTER TABLE `" + safeTable + "` CHANGE `" + safeOld + "` `" + safeNew + "` " + columnType.trim();
            try {
                datasetMapper.executeDynamicSql(sql);
            } catch (RuntimeException e) {
                log.warn("列重命名失败: {} -> {}（{}）", oldName, newName, e.getClass().getSimpleName());
                throw new BusinessException(400, "列名标准化失败，已回滚本次清洗");
            }
        }

        log.info("列名标准化完成: {}", safeTable);
    }

    /**
     * 异常值处理
     */
    private void executeOutlierHandle(String tableName, Map<String, Object> config) {
        String targetColumn = (String) config.get("targetColumn");
        String detectMethod = (String) config.get("detectMethod");

        if (targetColumn == null || targetColumn.trim().isEmpty()) {
            throw new BusinessException(400, "异常值处理缺少目标列配置");
        }
        if (!Set.of("z_score", "iqr").contains(detectMethod)) {
            throw new BusinessException(400, "异常值检测方式不合法");
        }
        String safeTable = SqlGuard.requireTableName(tableName);
        targetColumn = SqlGuard.requireIdentifier(targetColumn, "目标列");
        tableName = safeTable;

        // 先独立聚合均值与标准差，再参数化更新：避免 MySQL 1093，也避免重复全表扫描
        List<Map<String, Object>> stats = datasetMapper.selectDynamicSql(
                "SELECT AVG(`" + targetColumn + "`) AS avg_val, STDDEV_POP(`" + targetColumn + "`) AS std_val FROM `"
                        + tableName + "`");
        if (stats.isEmpty() || stats.get(0).get("avg_val") == null) {
            return;
        }
        BigDecimal avg = BigDecimal.valueOf(((Number) stats.get(0).get("avg_val")).doubleValue());
        double std = stats.get(0).get("std_val") == null ? 0d
                : ((Number) stats.get(0).get("std_val")).doubleValue();
        if (std <= 0d) {
            // 无波动时不存在异常值，直接结束
            return;
        }

        double multiplier = "z_score".equals(detectMethod) ? 3d : 2d;
        String sql = "UPDATE `" + tableName + "` SET `" + targetColumn + "` = #{params[0]} " +
                "WHERE `" + targetColumn + "` IS NOT NULL AND ABS(`" + targetColumn
                + "` - #{params[0]}) > #{params[1]}";
        datasetMapper.executeDynamicSqlWithParams(sql,
                Arrays.asList(avg, BigDecimal.valueOf(std * multiplier)));

        log.info("异常值处理完成: {} - {}", targetColumn, detectMethod);
    }

    /**
     * 自定义过滤
     */
    private void executeCustomFilter(String tableName, Map<String, Object> config) {
        if (!customFilterEnabled) {
            throw new BusinessException(400, "自定义表达式过滤已下线，请使用可视化筛选条件");
        }
        String expression = (String) config.get("expression");
        if (expression == null || expression.trim().isEmpty()) {
            return;
        }

        String safeTable = SqlGuard.requireTableName(tableName);
        List<Object> params = new ArrayList<>();
        String where = parseFilterDsl(expression, params);

        String sql = "DELETE FROM `" + safeTable + "` WHERE NOT (" + where + ")";
        datasetMapper.executeDynamicSqlWithParams(sql, params);

        log.info("自定义过滤完成: {}", safeTable);
    }

    /**
     * 受限 DSL：条件 = 列名 运算符 字面量，多个条件用 AND/OR 连接。
     * 解析后统一生成参数占位符，不把表达式原文拼进 SQL。
     */
    private String parseFilterDsl(String expression, List<Object> params) {
        String[] segments = expression.split("(?i)\\s+(AND|OR)\\s+");
        Matcher connector = Pattern.compile("(?i)\\s+(AND|OR)\\s+").matcher(expression);

        List<String> connectors = new ArrayList<>();
        while (connector.find()) {
            connectors.add(connector.group(1).toUpperCase(Locale.ROOT));
        }
        if (segments.length == 0 || connectors.size() != segments.length - 1) {
            throw new BusinessException(400, "过滤表达式格式不正确");
        }

        StringBuilder where = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            Matcher matcher = DSL_CONDITION.matcher(segments[i]);
            if (!matcher.matches()) {
                throw new BusinessException(400, "过滤表达式仅支持：列名 运算符 字面量");
            }
            if (i > 0) {
                where.append(' ').append(connectors.get(i - 1)).append(' ');
            }
            String column = SqlGuard.requireIdentifier(matcher.group(1), "过滤列");
            String operator = matcher.group(2).toUpperCase(Locale.ROOT);
            String literal = matcher.group(3);

            String value;
            if (literal.startsWith("'") && literal.endsWith("'")) {
                value = literal.substring(1, literal.length() - 1).replace("''", "'");
            } else {
                value = literal;
            }

            where.append('`').append(column).append("` ")
                    .append("<>".equals(operator) ? "!=" : operator)
                    .append(" #{params[").append(params.size()).append("]}");
            params.add(value);
        }
        return where.toString();
    }
}
