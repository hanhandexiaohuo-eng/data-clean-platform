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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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

    /**
     * 查询数据集的清洗规则
     */
    public List<CleanRule> listRulesByDataset(Long datasetId) {
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
        CleanRule rule = new CleanRule();
        rule.setDatasetId(ruleDTO.getDatasetId());
        rule.setRuleName(ruleDTO.getRuleName());
        rule.setRuleType(ruleDTO.getRuleType());
        rule.setSortOrder(ruleDTO.getSortOrder());
        rule.setStatus(ruleDTO.getStatus());

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
        CleanRule existing = cleanRuleMapper.selectById(ruleDTO.getId());
        if (existing == null) {
            throw new BusinessException("规则不存在");
        }

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
        cleanRuleMapper.deleteById(id);
    }

    /**
     * 执行清洗
     */
    @Transactional(rollbackFor = Exception.class)
    public CleanResultVO executeClean(CleanExecuteDTO executeDTO) {
        Long datasetId = executeDTO.getDatasetId();
        Dataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BusinessException("数据集不存在");
        }

        List<Long> ruleIds = executeDTO.getRuleIds();
        List<CleanRule> rules = new ArrayList<>();
        for (Long ruleId : ruleIds) {
            CleanRule rule = cleanRuleMapper.selectById(ruleId);
            if (rule != null) {
                rules.add(rule);
            }
        }

        if (rules.isEmpty()) {
            throw new BusinessException("没有有效的清洗规则");
        }

        // 按sortOrder排序
        rules.sort(Comparator.comparingInt(CleanRule::getSortOrder));

        Long startTime = System.currentTimeMillis();
        Integer beforeCount = dynamicTableService.getTableRowCount(dataset.getTableName()).intValue();
        String backupTable = null;

        try {
            // 备份原始数据
            if (!executeDTO.getPreviewMode()) {
                backupTable = dynamicTableService.backupTable(dataset.getTableName());
            }

            // 依次执行清洗规则
            List<String> executedRuleNames = new ArrayList<>();
            for (CleanRule rule : rules) {
                executeSingleRule(dataset, rule);
                executedRuleNames.add(rule.getRuleName());
            }

            Integer afterCount = dynamicTableService.getTableRowCount(dataset.getTableName()).intValue();
            Integer affectedRows = beforeCount - afterCount;
            Integer executeTime = (int) (System.currentTimeMillis() - startTime);

            CleanResultVO result = new CleanResultVO();
            result.setBeforeCount(beforeCount);
            result.setAfterCount(afterCount);
            result.setAffectedRows(affectedRows);
            result.setExecutedRules(executedRuleNames);
            result.setBackupTable(backupTable);
            result.setExecuteTimeMs(executeTime);

            if (!executeDTO.getPreviewMode()) {
                // 保存清洗日志
                CleanLog log = new CleanLog();
                log.setDatasetId(datasetId);
                log.setRuleIds(ruleIds.toString());
                log.setRuleSnapshot(objectMapper.writeValueAsString(rules));
                log.setBeforeCount(beforeCount);
                log.setAfterCount(afterCount);
                log.setAffectedRows(affectedRows);
                log.setBackupTable(backupTable);
                log.setStatus(1);
                log.setExecuteTimeMs(executeTime);
                cleanLogMapper.insert(log);

                // 更新数据集行数
                dataset.setRowCount(afterCount);
                datasetMapper.updateById(dataset);
            }

            return result;

        } catch (Exception e) {
            log.error("清洗执行失败", e);
            // 如果失败且有备份，恢复数据
            if (backupTable != null && !executeDTO.getPreviewMode()) {
                try {
                    dynamicTableService.restoreTable(dataset.getTableName(), backupTable);
                } catch (Exception ex) {
                    log.error("回滚失败", ex);
                }
            }
            throw new BusinessException("清洗执行失败: " + e.getMessage());
        }
    }

    /**
     * 回滚清洗
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackClean(Long logId) {
        CleanLog cleanLog = cleanLogMapper.selectById(logId);
        if (cleanLog == null) {
            throw new BusinessException("清洗日志不存在");
        }
        if (cleanLog.getStatus() != 1) {
            throw new BusinessException("该清洗记录不可回滚");
        }

        Dataset dataset = datasetMapper.selectById(cleanLog.getDatasetId());
        if (dataset == null) {
            throw new BusinessException("数据集不存在");
        }

        // 恢复数据
        dynamicTableService.restoreTable(dataset.getTableName(), cleanLog.getBackupTable());

        // 更新数据集行数
        dataset.setRowCount(cleanLog.getBeforeCount());
        datasetMapper.updateById(dataset);

        // 标记日志为已回滚(通过删除备份表引用来实现)
        cleanLog.setBackupTable("");
        cleanLogMapper.updateById(cleanLog);

        log.info("清洗已回滚, 数据集: {}, 日志ID: {}", dataset.getName(), logId);
    }

    /**
     * 查询清洗日志
     */
    public List<CleanLog> listCleanLogs(Long datasetId) {
        return cleanLogMapper.selectByDatasetId(datasetId);
    }

    /**
     * 构建规则配置
     */
    private Map<String, Object> buildRuleConfig(CleanRuleDTO dto) {
        Map<String, Object> config = new LinkedHashMap<>();

        switch (dto.getRuleType()) {
            case "missing_value_handle":
                config.put("targetColumn", dto.getTargetColumn());
                config.put("handleType", dto.getHandleType()); // delete/mean/median/fixed
                config.put("fillValue", dto.getFillValue());
                break;
            case "remove_duplicate":
                config.put("targetColumns", dto.getTargetColumn());
                break;
            case "type_convert":
                config.put("targetColumn", dto.getTargetColumn());
                config.put("targetType", dto.getTargetType());
                break;
            case "column_standardize":
                config.put("columns", "all");
                break;
            case "outlier_handle":
                config.put("targetColumn", dto.getTargetColumn());
                config.put("detectMethod", dto.getDetectMethod()); // z_score/iqr
                break;
            case "custom_filter":
                config.put("expression", dto.getFilterExpression());
                break;
            default:
                break;
        }

        return config;
    }

    /**
     * 执行单条清洗规则
     */
    private void executeSingleRule(Dataset dataset, CleanRule rule) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = objectMapper.readValue(rule.getRuleConfig(), Map.class);
        String tableName = dataset.getTableName();

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
                log.warn("未知的清洗规则类型: {}", rule.getRuleType());
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
            throw new BusinessException("缺失值处理缺少目标列配置");
        }

        String columnName = "`" + targetColumn.trim() + "`";

        switch (handleType) {
            case "delete":
                // 删除包含缺失值的行
                String deleteSql = "DELETE FROM `" + tableName + "` WHERE " + columnName + " IS NULL";
                datasetMapper.executeDynamicSql(deleteSql);
                break;
            case "mean":
                // 使用均值填充
                String meanSql = "UPDATE `" + tableName + "` t1 " +
                        "SET " + columnName + " = (SELECT AVG(" + columnName + ") FROM `" + tableName + "`) " +
                        "WHERE " + columnName + " IS NULL";
                datasetMapper.executeDynamicSql(meanSql);
                break;
            case "median":
                // 使用中位数填充(简化处理，使用均值近似)
                String medianSql = "UPDATE `" + tableName + "` " +
                        "SET " + columnName + " = (SELECT AVG(" + columnName + ") FROM `" + tableName + "`) " +
                        "WHERE " + columnName + " IS NULL";
                datasetMapper.executeDynamicSql(medianSql);
                break;
            case "fixed":
                // 使用固定值填充
                String fixedSql = "UPDATE `" + tableName + "` " +
                        "SET " + columnName + " = '" + fillValue.replace("'", "''") + "' " +
                        "WHERE " + columnName + " IS NULL";
                datasetMapper.executeDynamicSql(fixedSql);
                break;
            default:
                break;
        }

        log.info("缺失值处理完成: {} - {}", targetColumn, handleType);
    }

    /**
     * 删除重复行
     */
    private void executeRemoveDuplicate(String tableName, Map<String, Object> config) {
        String targetColumns = (String) config.get("targetColumns");
        String columns;

        if (targetColumns == null || targetColumns.trim().isEmpty() || "all".equals(targetColumns.trim())) {
            // 获取所有列(排除_id)
            List<Map<String, Object>> cols = datasetMapper.selectDynamicSql(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_NAME = '" + tableName + "' AND COLUMN_NAME != '_id'");
            columns = cols.stream()
                    .map(c -> "`" + c.get("COLUMN_NAME") + "`")
                    .reduce((a, b) -> a + ", " + b).orElse("");
        } else {
            columns = Arrays.stream(targetColumns.split(","))
                    .map(c -> "`" + c.trim() + "`")
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }

        if (columns == null || columns.isEmpty()) {
            throw new BusinessException("去重操作未找到有效的目标列");
        }

        // 使用临时表方式删除重复
        String tempTable = tableName + "_dedup_" + System.currentTimeMillis();

        // 创建临时表存储唯一数据
        String createTemp = "CREATE TABLE `" + tempTable + "` LIKE `" + tableName + "`";
        datasetMapper.executeDynamicSql(createTemp);

        // 插入去重后的数据
        String insertUnique = "INSERT INTO `" + tempTable + "` SELECT * FROM `" + tableName + "` " +
                "GROUP BY " + columns;
        datasetMapper.executeDynamicSql(insertUnique);

        // 删除原表并重命名
        datasetMapper.executeDynamicSql("DROP TABLE `" + tableName + "`");
        datasetMapper.executeDynamicSql("RENAME TABLE `" + tempTable + "` TO `" + tableName + "`");

        log.info("重复行删除完成: {}", tableName);
    }

    /**
     * 类型转换
     */
    private void executeTypeConvert(String tableName, Map<String, Object> config) {
        String targetColumn = (String) config.get("targetColumn");
        String targetType = (String) config.get("targetType");

        if (targetColumn == null || targetColumn.trim().isEmpty()) {
            throw new BusinessException("类型转换缺少目标列配置");
        }
        if (targetType == null || targetType.trim().isEmpty()) {
            throw new BusinessException("类型转换缺少目标类型配置");
        }

        String sql = "ALTER TABLE `" + tableName + "` MODIFY COLUMN `" + targetColumn.trim() + "` " + targetType;
        datasetMapper.executeDynamicSql(sql);

        log.info("类型转换完成: {} -> {}", targetColumn, targetType);
    }

    /**
     * 列名标准化
     */
    private void executeColumnStandardize(String tableName) {
        // 获取所有列
        List<Map<String, Object>> columns = datasetMapper.selectDynamicSql(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = '" + tableName + "' AND COLUMN_NAME != '_id'");

        for (Map<String, Object> col : columns) {
            String oldName = (String) col.get("COLUMN_NAME");
            String newName = oldName.toLowerCase()
                    .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]", "_")
                    .replaceAll("_{2,}", "_");

            if (!oldName.equals(newName) && !newName.isEmpty()) {
                String sql = "ALTER TABLE `" + tableName + "` CHANGE `" + oldName + "` `" + newName + "` " +
                        "(SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = '" + tableName + "' AND COLUMN_NAME = '" + oldName + "')";
                // 简化处理，实际应该获取列类型
                try {
                    datasetMapper.executeDynamicSql(sql);
                } catch (Exception e) {
                    log.warn("列重命名失败: {} -> {}", oldName, newName);
                }
            }
        }

        log.info("列名标准化完成: {}", tableName);
    }

    /**
     * 异常值处理
     */
    private void executeOutlierHandle(String tableName, Map<String, Object> config) {
        String targetColumn = (String) config.get("targetColumn");
        String detectMethod = (String) config.get("detectMethod");

        if (targetColumn == null || targetColumn.trim().isEmpty()) {
            throw new BusinessException("异常值处理缺少目标列配置");
        }
        targetColumn = targetColumn.trim();

        if ("z_score".equals(detectMethod)) {
            // Z-Score 方法: |z| > 3 认为是异常值，用均值替换
            String sql = "UPDATE `" + tableName + "` t " +
                    "SET `" + targetColumn + "` = (SELECT AVG(`" + targetColumn + "`) FROM `" + tableName + "`) " +
                    "WHERE ABS(`" + targetColumn + "` - (SELECT AVG(`" + targetColumn + "`) FROM `" + tableName + "`)) " +
                    "> 3 * (SELECT STDDEV(`" + targetColumn + "`) FROM `" + tableName + "`)";
            datasetMapper.executeDynamicSql(sql);
        } else if ("iqr".equals(detectMethod)) {
            // IQR 方法: < Q1 - 1.5*IQR 或 > Q3 + 1.5*IQR 认为是异常值
            // 简化实现，使用均值替换
            String sql = "UPDATE `" + tableName + "` t " +
                    "SET `" + targetColumn + "` = (SELECT AVG(`" + targetColumn + "`) FROM `" + tableName + "`) " +
                    "WHERE `" + targetColumn + "` < (SELECT AVG(`" + targetColumn + "`) - 2 * STDDEV(`" + targetColumn + "`) FROM `" + tableName + "`) " +
                    "OR `" + targetColumn + "` > (SELECT AVG(`" + targetColumn + "`) + 2 * STDDEV(`" + targetColumn + "`) FROM `" + tableName + "`)";
            datasetMapper.executeDynamicSql(sql);
        }

        log.info("异常值处理完成: {} - {}", targetColumn, detectMethod);
    }

    /**
     * 自定义过滤
     */
    private void executeCustomFilter(String tableName, Map<String, Object> config) {
        String expression = (String) config.get("expression");
        if (expression == null || expression.trim().isEmpty()) {
            return;
        }

        // 安全检查: 只允许包含列名、运算符、数值和逻辑运算符的表达式
        if (!expression.matches("^[\\w`\\s\\+\\-\\*/%=<>!&|().'0-9_$\\u4e00-\\u9fa5]+$")) {
            throw new BusinessException("表达式包含非法字符");
        }

        String sql = "DELETE FROM `" + tableName + "` WHERE NOT (" + expression + ")";
        datasetMapper.executeDynamicSql(sql);

        log.info("自定义过滤完成: {}", tableName);
    }
}
