-- ============================================================
-- V4 查询索引补充（纯增量、可重复执行）
-- ------------------------------------------------------------
-- 仅新增二级索引，不修改列、不删除数据。
-- 每个索引都通过 INFORMATION_SCHEMA.STATISTICS 判断，已存在则跳过。
--
-- 回滚：db/rollback/R4__dataset_indexes.sql
-- ============================================================

-- 1. dataset_column(dataset_id, sort_order)
SET @exists_idx_1 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset_column' AND INDEX_NAME = 'idx_dataset_column_order'
);
SET @ddl_idx_1 := IF(@exists_idx_1 = 0,
    'ALTER TABLE `dataset_column` ADD INDEX `idx_dataset_column_order` (`dataset_id`, `sort_order`)',
    'SELECT 1');
PREPARE stmt_idx_1 FROM @ddl_idx_1;
EXECUTE stmt_idx_1;
DEALLOCATE PREPARE stmt_idx_1;

-- 2. clean_rule(dataset_id, sort_order)
SET @exists_idx_2 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_rule' AND INDEX_NAME = 'idx_clean_rule_order'
);
SET @ddl_idx_2 := IF(@exists_idx_2 = 0,
    'ALTER TABLE `clean_rule` ADD INDEX `idx_clean_rule_order` (`dataset_id`, `sort_order`)',
    'SELECT 1');
PREPARE stmt_idx_2 FROM @ddl_idx_2;
EXECUTE stmt_idx_2;
DEALLOCATE PREPARE stmt_idx_2;

-- 3. clean_log(dataset_id, created_at)
SET @exists_idx_3 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_log' AND INDEX_NAME = 'idx_clean_log_dataset_time'
);
SET @ddl_idx_3 := IF(@exists_idx_3 = 0,
    'ALTER TABLE `clean_log` ADD INDEX `idx_clean_log_dataset_time` (`dataset_id`, `created_at`)',
    'SELECT 1');
PREPARE stmt_idx_3 FROM @ddl_idx_3;
EXECUTE stmt_idx_3;
DEALLOCATE PREPARE stmt_idx_3;

-- 4. ai_generation_log(dataset_id, created_at)
SET @exists_idx_4 := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log' AND INDEX_NAME = 'idx_ai_generation_dataset_time'
);
SET @ddl_idx_4 := IF(@exists_idx_4 = 0,
    'ALTER TABLE `ai_generation_log` ADD INDEX `idx_ai_generation_dataset_time` (`dataset_id`, `created_at`)',
    'SELECT 1');
PREPARE stmt_idx_4 FROM @ddl_idx_4;
EXECUTE stmt_idx_4;
DEALLOCATE PREPARE stmt_idx_4;
