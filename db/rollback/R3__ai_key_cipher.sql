-- ============================================================
-- R3 回滚：AI 供应商 API Key 密文存储
-- ------------------------------------------------------------
-- 重要：
--   本迁移只新增列，不删除任何数据。正常回滚方式是「恢复迁移前备份」。
--   只有在确认可以丢弃所有密文（即所有 AI 配置都需要重新填写 API Key）
--   时，才执行下面的原地回滚。执行前务必备份 ai_config。
-- ============================================================

-- 1. 确认不存在仍需保留的密文（结果应为 0）
SELECT COUNT(*) AS cipher_rows
FROM `ai_config`
WHERE `api_key_cipher` IS NOT NULL AND `api_key_cipher` <> '';

-- 2. 如果上面的结果为 0，可手动执行以下 DDL：
-- ALTER TABLE `ai_config` DROP COLUMN `api_key_mask`;
-- ALTER TABLE `ai_config` DROP COLUMN `api_key_cipher`;
-- -- api_key 保持可空，避免旧版本 NOT NULL 约束报错；回滚后需在界面重新填写 API Key。
-- ALTER TABLE `ai_config` MODIFY COLUMN `api_key` VARCHAR(500) NULL COMMENT 'API密钥';

-- 3. 应用侧回滚：
--    停止服务 → 回退到上一版本镜像/jar → 恢复迁移前配置 → 启动
