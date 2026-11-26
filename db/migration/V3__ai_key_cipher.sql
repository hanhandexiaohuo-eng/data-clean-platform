-- ============================================================
-- V3 AI 供应商 API Key 密文存储（纯增量、可重复执行）
-- ------------------------------------------------------------
-- 对应历史 sql/migration_v4_ai_key_cipher.sql：
--   1. 只新增 api_key_cipher / api_key_mask 两列，不删除任何数据；
--   2. 通过 INFORMATION_SCHEMA 判断列是否存在，重复执行安全；
--   3. 放宽 api_key 为 NULL，便于应用迁移后清空明文；
--   4. 明文 → 密文的数据转换由应用启动时的 AiKeyMigrationRunner 完成，
--      因为加密依赖环境变量 AI_SECRET_KEY，SQL 不持有主密钥。
--
-- 回滚：优先恢复迁移前备份；原地回滚见 db/rollback/R3__ai_key_cipher.sql
-- ============================================================

-- 1. 新增密文字段
SET @exists_cipher := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_config' AND COLUMN_NAME = 'api_key_cipher'
);
SET @ddl_cipher := IF(@exists_cipher = 0,
    'ALTER TABLE `ai_config` ADD COLUMN `api_key_cipher` VARCHAR(1024) NULL COMMENT ''API密钥密文(AES-256-GCM)'' AFTER `api_url`',
    'SELECT 1');
PREPARE stmt_cipher FROM @ddl_cipher;
EXECUTE stmt_cipher;
DEALLOCATE PREPARE stmt_cipher;

-- 2. 新增掩码字段
SET @exists_mask := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_config' AND COLUMN_NAME = 'api_key_mask'
);
SET @ddl_mask := IF(@exists_mask = 0,
    'ALTER TABLE `ai_config` ADD COLUMN `api_key_mask` VARCHAR(64) NULL COMMENT ''API密钥掩码(仅展示)'' AFTER `api_key_cipher`',
    'SELECT 1');
PREPARE stmt_mask FROM @ddl_mask;
EXECUTE stmt_mask;
DEALLOCATE PREPARE stmt_mask;

-- 3. 放宽旧明文字段约束（幂等）
ALTER TABLE `ai_config`
    MODIFY COLUMN `api_key` VARCHAR(500) NULL COMMENT '已废弃：明文密钥，仅用于存量迁移，迁移后置空';
