-- ============================================================
-- V8 公开模式上线前加固（纯增量、可重复执行）
-- ------------------------------------------------------------
-- 内容：
--   1) ai_challenge 增加 purpose 列，区分 ai=AI 调用、session=会话引导挑战；
--   2) ai_challenge.anon_session_id 允许为 NULL（会话引导挑战尚未建立会话）；
--   3) 新增 (purpose, expires_at) 索引，便于过期挑战清理。
-- 约束：
--   - 不删除、不重建任何表，不改写既有业务数据；
--   - 存量行 purpose 默认 'ai'，与 V7 之前的行为一致；
--   - 全部 DDL 通过 INFORMATION_SCHEMA 判断，已存在则跳过；
--   - 回滚：db/rollback/R8__public_hardening.sql（执行前务必备份）。
-- ============================================================

-- ---------- 1. ai_challenge.purpose ----------
SET @exists_al_purpose := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_challenge' AND COLUMN_NAME = 'purpose'
);
SET @ddl_al_purpose := IF(@exists_al_purpose = 0,
    'ALTER TABLE `ai_challenge` ADD COLUMN `purpose` VARCHAR(16) NOT NULL DEFAULT ''ai'' COMMENT ''用途：ai=AI调用，session=会话引导''',
    'SELECT 1');
PREPARE stmt_al_purpose FROM @ddl_al_purpose;
EXECUTE stmt_al_purpose;
DEALLOCATE PREPARE stmt_al_purpose;

-- ---------- 2. ai_challenge.anon_session_id 允许 NULL ----------
SET @al_anon_nullable := (
    SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_challenge' AND COLUMN_NAME = 'anon_session_id'
    LIMIT 1
);
SET @ddl_al_anon_null := IF(@al_anon_nullable = 'NO',
    'ALTER TABLE `ai_challenge` MODIFY COLUMN `anon_session_id` BIGINT NULL COMMENT ''绑定匿名会话（会话引导挑战为 NULL）''',
    'SELECT 1');
PREPARE stmt_al_anon_null FROM @ddl_al_anon_null;
EXECUTE stmt_al_anon_null;
DEALLOCATE PREPARE stmt_al_anon_null;

-- ---------- 3. ai_challenge.idx_purpose_expires ----------
SET @exists_idx_al_purpose := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_challenge' AND INDEX_NAME = 'idx_purpose_expires'
);
SET @ddl_idx_al_purpose := IF(@exists_idx_al_purpose = 0,
    'ALTER TABLE `ai_challenge` ADD INDEX `idx_purpose_expires` (`purpose`, `expires_at`)',
    'SELECT 1');
PREPARE stmt_idx_al_purpose FROM @ddl_idx_al_purpose;
EXECUTE stmt_idx_al_purpose;
DEALLOCATE PREPARE stmt_idx_al_purpose;
