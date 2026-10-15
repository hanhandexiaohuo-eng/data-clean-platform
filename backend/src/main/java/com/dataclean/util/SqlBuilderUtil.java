package com.dataclean.util;

import com.dataclean.entity.DatasetColumn;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL 构建工具类
 */
@Slf4j
public class SqlBuilderUtil {

    /**
     * 构建建表SQL
     */
    public static String buildCreateTableSql(String tableName, List<DatasetColumn> columns) {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS `");
        sql.append(tableName).append("` (\n");
        sql.append("  `_id` BIGINT NOT NULL AUTO_INCREMENT,\n");

        for (DatasetColumn col : columns) {
            sql.append("  `").append(col.getColumnAlias()).append("` ");
            sql.append(col.getDataType());
            if (col.getNullable() != null && col.getNullable() == 0) {
                sql.append(" NOT NULL");
            } else {
                sql.append(" DEFAULT NULL");
            }
            sql.append(",\n");
        }

        sql.append("  PRIMARY KEY (`_id`)\n");
        sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        return sql.toString();
    }

    /**
     * 构建插入SQL
     */
    public static String buildInsertSql(String tableName, List<String> columns) {
        String colStr = columns.stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));
        String valPlaceholder = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        return "INSERT INTO `" + tableName + "` (" + colStr + ") VALUES (" + valPlaceholder + ")";
    }

    /**
     * 构建重命名表SQL
     */
    public static String buildRenameTableSql(String oldName, String newName) {
        return "RENAME TABLE `" + oldName + "` TO `" + newName + "`";
    }

    /**
     * 构建删除表SQL
     */
    public static String buildDropTableSql(String tableName) {
        return "DROP TABLE IF EXISTS `" + tableName + "`";
    }

    /**
     * 构建复制表SQL
     */
    public static String buildCopyTableSql(String sourceTable, String targetTable) {
        return "CREATE TABLE `" + targetTable + "` LIKE `" + sourceTable + "`";
    }

    /**
     * 构建插入复制数据SQL
     */
    public static String buildCopyDataSql(String sourceTable, String targetTable) {
        return "INSERT INTO `" + targetTable + "` SELECT * FROM `" + sourceTable + "`";
    }

    /**
     * 构建添加列SQL
     */
    public static String buildAddColumnSql(String tableName, String columnName, String dataType) {
        return "ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + dataType;
    }

    /**
     * 安全表名检查
     */
    public static boolean isValidTableName(String tableName) {
        return tableName != null && tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    /**
     * 生成备份表名
     */
    public static String generateBackupTableName(String originalTableName) {
        return originalTableName + "_backup_" + System.currentTimeMillis();
    }
}
