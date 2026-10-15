package com.dataclean.service;

import com.dataclean.entity.DatasetColumn;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.util.SqlBuilderUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态表管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicTableService {

    private final DatasetMapper datasetMapper;

    /**
     * 创建动态表
     */
    @Transactional(rollbackFor = Exception.class)
    public void createTable(String tableName, List<DatasetColumn> columns) {
        String sql = SqlBuilderUtil.buildCreateTableSql(tableName, columns);
        log.debug("创建表SQL: {}", sql);
        datasetMapper.executeDynamicSql(sql);
    }

    /**
     * 批量插入数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void insertData(String tableName, List<DatasetColumn> columns, List<Map<String, Object>> data) {
        if (data.isEmpty()) {
            return;
        }

        List<String> colNames = columns.stream()
                .map(DatasetColumn::getColumnAlias)
                .toList();

        // 构建列名到原始列名的映射
        Map<String, String> colToOriginal = columns.stream()
                .collect(Collectors.toMap(
                        DatasetColumn::getColumnAlias,
                        DatasetColumn::getColumnName,
                        (a, b) -> a));

        // 批量插入，每次最多1000行，避免单条SQL过长
        int batchSize = 1000;
        for (int batchStart = 0; batchStart < data.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, data.size());
            List<Map<String, Object>> batch = data.subList(batchStart, batchEnd);

            StringBuilder insertSql = new StringBuilder("INSERT INTO `");
            insertSql.append(tableName).append("` (");
            insertSql.append(colNames.stream()
                    .map(c -> "`" + c + "`")
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            insertSql.append(") VALUES ");

            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    insertSql.append(", ");
                }
                Map<String, Object> row = batch.get(i);
                insertSql.append("(");
                for (int j = 0; j < colNames.size(); j++) {
                    if (j > 0) {
                        insertSql.append(", ");
                    }
                    String col = colNames.get(j);
                    String originalName = colToOriginal.getOrDefault(col, col);
                    insertSql.append(formatValue(row.get(originalName)));
                }
                insertSql.append(")");
            }

            datasetMapper.executeDynamicSql(insertSql.toString());
        }

        log.info("插入数据完成: {} 行", data.size());
    }

    /**
     * 删除表
     */
    @Transactional(rollbackFor = Exception.class)
    public void dropTable(String tableName) {
        String sql = SqlBuilderUtil.buildDropTableSql(tableName);
        datasetMapper.executeDynamicSql(sql);
        log.info("删除表: {}", tableName);
    }

    /**
     * 备份表
     */
    @Transactional(rollbackFor = Exception.class)
    public String backupTable(String originalTable) {
        String backupTable = SqlBuilderUtil.generateBackupTableName(originalTable);

        // 创建相同结构的表
        String createSql = SqlBuilderUtil.buildCopyTableSql(originalTable, backupTable);
        datasetMapper.executeDynamicSql(createSql);

        // 复制数据
        String copySql = SqlBuilderUtil.buildCopyDataSql(originalTable, backupTable);
        datasetMapper.executeDynamicSql(copySql);

        log.info("备份表创建成功: {} -> {}", originalTable, backupTable);
        return backupTable;
    }

    /**
     * 恢复表(回滚)
     */
    @Transactional(rollbackFor = Exception.class)
    public void restoreTable(String currentTable, String backupTable) {
        // 删除当前表
        dropTable(currentTable);

        // 将备份表重命名为当前表
        String renameSql = SqlBuilderUtil.buildRenameTableSql(backupTable, currentTable);
        datasetMapper.executeDynamicSql(renameSql);

        log.info("表恢复成功: {} -> {}", backupTable, currentTable);
    }

    /**
     * 获取表行数
     */
    public Long getTableRowCount(String tableName) {
        return datasetMapper.selectDynamicDataCount(tableName);
    }

    /**
     * 格式化值用于SQL（安全转义）
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        // 转义特殊字符：反斜杠和单引号
        String str = value.toString()
                .replace("\\", "\\\\")
                .replace("'", "''");
        return "'" + str + "'";
    }
}
