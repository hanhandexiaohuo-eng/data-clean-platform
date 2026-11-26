-- ============================================================
-- V6 审计预留字段（纯增量、可重复执行）
-- ------------------------------------------------------------
-- 为后续接入账号体系与操作审计预留字段，全部为可空列，
-- 不修改既有数据、不改变既有查询语义。应用当前版本不使用这些列。
-- 每个列都通过 INFORMATION_SCHEMA.COLUMNS 判断，已存在则跳过。
--
-- 回滚：db/rollback/R6__audit_fields.sql（会丢弃审计列，执行前务必备份）
-- ============================================================

-- 1. dataset.created_by
SET @exists_col_1 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'created_by'
);
SET @ddl_col_1 := IF(@exists_col_1 = 0,
    'ALTER TABLE `dataset` ADD COLUMN `created_by` BIGINT NULL COMMENT ''创建人ID(审计预留)'' AFTER `status`',
    'SELECT 1');
PREPARE stmt_col_1 FROM @ddl_col_1;
EXECUTE stmt_col_1;
DEALLOCATE PREPARE stmt_col_1;

-- 2. dataset.updated_by
SET @exists_col_2 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'updated_by'
);
SET @ddl_col_2 := IF(@exists_col_2 = 0,
    'ALTER TABLE `dataset` ADD COLUMN `updated_by` BIGINT NULL COMMENT ''最后修改人ID(审计预留)'' AFTER `created_by`',
    'SELECT 1');
PREPARE stmt_col_2 FROM @ddl_col_2;
EXECUTE stmt_col_2;
DEALLOCATE PREPARE stmt_col_2;

-- 3. clean_rule.created_by
SET @exists_col_3 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_rule' AND COLUMN_NAME = 'created_by'
);
SET @ddl_col_3 := IF(@exists_col_3 = 0,
    'ALTER TABLE `clean_rule` ADD COLUMN `created_by` BIGINT NULL COMMENT ''创建人ID(审计预留)'' AFTER `status`',
    'SELECT 1');
PREPARE stmt_col_3 FROM @ddl_col_3;
EXECUTE stmt_col_3;
DEALLOCATE PREPARE stmt_col_3;

-- 4. clean_log.operator_id
SET @exists_col_4 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_log' AND COLUMN_NAME = 'operator_id'
);
SET @ddl_col_4 := IF(@exists_col_4 = 0,
    'ALTER TABLE `clean_log` ADD COLUMN `operator_id` BIGINT NULL COMMENT ''操作人ID(审计预留)'' AFTER `status`',
    'SELECT 1');
PREPARE stmt_col_4 FROM @ddl_col_4;
EXECUTE stmt_col_4;
DEALLOCATE PREPARE stmt_col_4;
