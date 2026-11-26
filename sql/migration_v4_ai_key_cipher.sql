-- ============================================================
-- 迁移 v4：AI 供应商 API Key 密文存储
-- ------------------------------------------------------------
-- 特性：
--   1. 纯增量：只新增列 / 放宽约束，不删除任何列；
--   2. 可重复执行：通过 INFORMATION_SCHEMA 判断列是否已存在；
--   3. 不修改业务数据：明文转密文由应用启动时的 AiKeyMigrationRunner 完成，
--      因为加密需要环境变量中的主密钥，SQL 无法安全持有该密钥。
--
-- 执行前请先备份：
--   mysqldump -u <user> -p <db> ai_config > ai_config_backup_$(date +%Y%m%d%H%M%S).sql
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

-- 3. 放宽旧明文字段约束（可重复执行）
ALTER TABLE `ai_config`
    MODIFY COLUMN `api_key` VARCHAR(500) NULL COMMENT '已废弃：明文密钥，仅用于存量迁移，迁移后置空';

-- 4. 校验
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_config'
  AND COLUMN_NAME IN ('api_key', 'api_key_cipher', 'api_key_mask');
