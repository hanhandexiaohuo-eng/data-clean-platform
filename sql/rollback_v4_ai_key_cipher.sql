-- ============================================================
-- 回滚方案 v4：AI 供应商 API Key 密文存储
-- ------------------------------------------------------------
-- 重要：
--   本迁移只新增列，不删除任何数据。因此正常回滚方式是「恢复备份」
--   （见 migration_v4_ai_key_cipher.sql 头部），而不是执行 DROP COLUMN。
--
--   只有在确认可以丢弃所有密文（即所有 AI 配置都需要重新填写 API Key）
--   时，才执行下面的原地回滚。执行前务必备份 ai_config。
-- ============================================================

-- 1. 确认不存在仍需保留的密文
SELECT COUNT(*) AS cipher_rows FROM `ai_config` WHERE `api_key_cipher` IS NOT NULL AND `api_key_cipher` <> '';

-- 2. 如果上面的结果为 0，可执行：
-- ALTER TABLE `ai_config` DROP COLUMN `api_key_mask`;
-- ALTER TABLE `ai_config` DROP COLUMN `api_key_cipher`;
-- -- 注意：迁移后 api_key 已被置空，回滚时保持可空，避免 NOT NULL 约束报错；
-- --       旧版本会按明文读取，需在界面重新填写 API Key。
-- ALTER TABLE `ai_config` MODIFY COLUMN `api_key` VARCHAR(500) NULL COMMENT 'API密钥';

-- 3. 应用侧回滚：
--    停止服务 → 回退到上一版本 jar → 重新配置 application.yml / .env → 启动
--    注意：旧版本会把 api_key 视为明文读取，因此回滚后需要在界面重新填写 API Key。
