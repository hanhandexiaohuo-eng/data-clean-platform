-- ============================================================
-- R6 回滚：审计预留字段（破坏性）
-- ------------------------------------------------------------
-- 警告：本脚本会删除审计预留列及其中的历史值。
--       当前应用版本不写入这些列；如未来已启用审计，请优先恢复备份。
-- 每个列都通过 INFORMATION_SCHEMA.COLUMNS 判断，可重复执行。
-- ============================================================

-- 1. dataset.updated_by
SET @exists_col_1 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'updated_by'
);
SET @ddl_col_1 := IF(@exists_col_1 > 0,
    'ALTER TABLE `dataset` DROP COLUMN `updated_by`',
    'SELECT 1');
PREPARE stmt_col_1 FROM @ddl_col_1;
EXECUTE stmt_col_1;
DEALLOCATE PREPARE stmt_col_1;

-- 2. dataset.created_by
SET @exists_col_2 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'created_by'
);
SET @ddl_col_2 := IF(@exists_col_2 > 0,
    'ALTER TABLE `dataset` DROP COLUMN `created_by`',
    'SELECT 1');
PREPARE stmt_col_2 FROM @ddl_col_2;
EXECUTE stmt_col_2;
DEALLOCATE PREPARE stmt_col_2;

-- 3. clean_rule.created_by
SET @exists_col_3 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_rule' AND COLUMN_NAME = 'created_by'
);
SET @ddl_col_3 := IF(@exists_col_3 > 0,
    'ALTER TABLE `clean_rule` DROP COLUMN `created_by`',
    'SELECT 1');
PREPARE stmt_col_3 FROM @ddl_col_3;
EXECUTE stmt_col_3;
DEALLOCATE PREPARE stmt_col_3;

-- 4. clean_log.operator_id
SET @exists_col_4 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_log' AND COLUMN_NAME = 'operator_id'
);
SET @ddl_col_4 := IF(@exists_col_4 > 0,
    'ALTER TABLE `clean_log` DROP COLUMN `operator_id`',
    'SELECT 1');
PREPARE stmt_col_4 FROM @ddl_col_4;
EXECUTE stmt_col_4;
DEALLOCATE PREPARE stmt_col_4;
