-- ============================================================
-- R8 回滚：撤销 V8 公开模式加固
-- ------------------------------------------------------------
-- 警告：
--   - 执行前请先停止公开模式应用，避免仍有请求写入 session 用途挑战；
--   - 若 ai_challenge 中仍存在 anon_session_id 为 NULL 的行（会话引导挑战），
--     必须先删除或补齐这些行，否则无法把该列改回 NOT NULL（本脚本不自动处理）；
--   - 本脚本只删除 V8 新增列/索引，不影响 V7 建立的表结构与业务数据。
-- ============================================================

-- 删除索引
SET @exists_idx_al_purpose := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_challenge' AND INDEX_NAME = 'idx_purpose_expires'
);
SET @ddl_idx_al_purpose := IF(@exists_idx_al_purpose > 0,
    'ALTER TABLE `ai_challenge` DROP INDEX `idx_purpose_expires`', 'SELECT 1');
PREPARE stmt_idx_al_purpose FROM @ddl_idx_al_purpose;
EXECUTE stmt_idx_al_purpose;
DEALLOCATE PREPARE stmt_idx_al_purpose;

-- 删除列
SET @exists_col_al_purpose := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_challenge' AND COLUMN_NAME = 'purpose'
);
SET @ddl_col_al_purpose := IF(@exists_col_al_purpose > 0,
    'ALTER TABLE `ai_challenge` DROP COLUMN `purpose`', 'SELECT 1');
PREPARE stmt_col_al_purpose FROM @ddl_col_al_purpose;
EXECUTE stmt_col_al_purpose;
DEALLOCATE PREPARE stmt_col_al_purpose;

-- anon_session_id 的可空性不自动收回：先确认无 NULL 行后手工执行
-- ALTER TABLE `ai_challenge` MODIFY COLUMN `anon_session_id` BIGINT NOT NULL COMMENT '绑定匿名会话';
