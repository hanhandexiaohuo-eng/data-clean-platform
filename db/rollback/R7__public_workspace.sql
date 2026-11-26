-- ============================================================
-- R7 回滚：删除公开模式新增表与新增列
-- ------------------------------------------------------------
-- 警告：
--   - 仅在确认不再需要公开临时数据、且已备份后执行；
--   - 会丢弃 7 张公开专用表与 4 张表的新增列（含历史 private 数据不受影响）；
--   - 执行前请停止公开模式应用并关闭清理任务。
-- ============================================================

DROP TABLE IF EXISTS `cleanup_log`;
DROP TABLE IF EXISTS `anon_usage_daily`;
DROP TABLE IF EXISTS `export_record`;
DROP TABLE IF EXISTS `chart_view`;
DROP TABLE IF EXISTS `ai_challenge`;
DROP TABLE IF EXISTS `cleanup_item`;
DROP TABLE IF EXISTS `anon_session`;

-- 删除索引（存在则删）
SET @exists_idx_ds_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND INDEX_NAME = 'idx_anon_session'
);
SET @ddl_idx_ds_anon := IF(@exists_idx_ds_anon > 0,
    'ALTER TABLE `dataset` DROP INDEX `idx_anon_session`', 'SELECT 1');
PREPARE stmt_idx_ds_anon FROM @ddl_idx_ds_anon;
EXECUTE stmt_idx_ds_anon;
DEALLOCATE PREPARE stmt_idx_ds_anon;

SET @exists_idx_ds_owner := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND INDEX_NAME = 'idx_owner_type'
);
SET @ddl_idx_ds_owner := IF(@exists_idx_ds_owner > 0,
    'ALTER TABLE `dataset` DROP INDEX `idx_owner_type`', 'SELECT 1');
PREPARE stmt_idx_ds_owner FROM @ddl_idx_ds_owner;
EXECUTE stmt_idx_ds_owner;
DEALLOCATE PREPARE stmt_idx_ds_owner;

-- 删除列（存在则删）
SET @exists_col_ds_owner := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'owner_type'
);
SET @ddl_col_ds_owner := IF(@exists_col_ds_owner > 0,
    'ALTER TABLE `dataset` DROP COLUMN `owner_type`', 'SELECT 1');
PREPARE stmt_col_ds_owner FROM @ddl_col_ds_owner;
EXECUTE stmt_col_ds_owner;
DEALLOCATE PREPARE stmt_col_ds_owner;

SET @exists_col_ds_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_col_ds_anon := IF(@exists_col_ds_anon > 0,
    'ALTER TABLE `dataset` DROP COLUMN `anon_session_id`', 'SELECT 1');
PREPARE stmt_col_ds_anon FROM @ddl_col_ds_anon;
EXECUTE stmt_col_ds_anon;
DEALLOCATE PREPARE stmt_col_ds_anon;

SET @exists_col_cr_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_rule' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_col_cr_anon := IF(@exists_col_cr_anon > 0,
    'ALTER TABLE `clean_rule` DROP COLUMN `anon_session_id`', 'SELECT 1');
PREPARE stmt_col_cr_anon FROM @ddl_col_cr_anon;
EXECUTE stmt_col_cr_anon;
DEALLOCATE PREPARE stmt_col_cr_anon;

SET @exists_col_cl_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_log' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_col_cl_anon := IF(@exists_col_cl_anon > 0,
    'ALTER TABLE `clean_log` DROP COLUMN `anon_session_id`', 'SELECT 1');
PREPARE stmt_col_cl_anon FROM @ddl_col_cl_anon;
EXECUTE stmt_col_cl_anon;
DEALLOCATE PREPARE stmt_col_cl_anon;

SET @exists_col_al_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_col_al_anon := IF(@exists_col_al_anon > 0,
    'ALTER TABLE `ai_generation_log` DROP COLUMN `anon_session_id`', 'SELECT 1');
PREPARE stmt_col_al_anon FROM @ddl_col_al_anon;
EXECUTE stmt_col_al_anon;
DEALLOCATE PREPARE stmt_col_al_anon;
