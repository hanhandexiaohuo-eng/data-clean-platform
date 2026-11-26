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
        sql.append(SqlGuard.requireTableName(tableName)).append("` (\n");
        sql.append("  `_id` BIGINT NOT NULL AUTO_INCREMENT,\n");

        for (DatasetColumn col : columns) {
            String column = SqlGuard.requireIdentifier(col.getColumnAlias(), "列名");
            String dataType = SqlGuard.requireColumnType(col.getDataType());
            sql.append("  `").append(column).append("` ");
            sql.append(dataType);
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
        String safeTable = SqlGuard.requireTableName(tableName);
        String colStr = columns.stream()
                .map(c -> "`" + SqlGuard.requireIdentifier(c, "列名") + "`")
                .collect(Collectors.joining(", "));
        String valPlaceholder = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        return "INSERT INTO `" + safeTable + "` (" + colStr + ") VALUES (" + valPlaceholder + ")";
    }

    /**
     * 构建重命名表SQL
     */
    public static String buildRenameTableSql(String oldName, String newName) {
        return "RENAME TABLE `" + SqlGuard.requireTableName(oldName) + "` TO `"
                + SqlGuard.requireTableName(newName) + "`";
    }

    /**
     * 构建删除表SQL
     */
    public static String buildDropTableSql(String tableName) {
        return "DROP TABLE IF EXISTS `" + SqlGuard.requireTableName(tableName) + "`";
    }

    /**
     * 构建复制表SQL
     */
    public static String buildCopyTableSql(String sourceTable, String targetTable) {
        return "CREATE TABLE `" + SqlGuard.requireTableName(targetTable) + "` LIKE `"
                + SqlGuard.requireTableName(sourceTable) + "`";
    }

    /**
     * 构建插入复制数据SQL
     */
    public static String buildCopyDataSql(String sourceTable, String targetTable) {
        return "INSERT INTO `" + SqlGuard.requireTableName(targetTable) + "` SELECT * FROM `"
                + SqlGuard.requireTableName(sourceTable) + "`";
    }

    /**
     * 构建添加列SQL
     */
    public static String buildAddColumnSql(String tableName, String columnName, String dataType) {
        return "ALTER TABLE `" + SqlGuard.requireTableName(tableName) + "` ADD COLUMN `"
                + SqlGuard.requireIdentifier(columnName, "列名") + "` "
                + SqlGuard.requireColumnType(dataType);
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
        String base = SqlGuard.requireTableName(originalTableName);
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        return SqlGuard.requireTableName(base + "_backup_" + System.currentTimeMillis());
    }
}
