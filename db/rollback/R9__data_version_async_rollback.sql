-- ============================================================
-- R9：回滚 V9（数据集版本化 / 异步任务 / 统计缓存 / 审计）
-- ============================================================
-- !! 破坏性回滚，执行前必须完成数据库全量备份 !!
--   mysqldump --single-transaction --routines --triggers \
--     --host=<HOST> --port=<PORT> --user=<USER> --password=<PWD> <DB> > backup_before_R9.sql
--
-- 影响范围（回滚后将永久丢失，无法从库内恢复）：
--   1. dataset_version / dataset_version_rule：全部版本历史与规则快照；
--   2. async_job：全部异步任务记录（运行中的任务会变成不可见的"孤儿"）；
--   3. stats_cache：统计缓存（可重新计算，无业务损失）；
--   4. audit_log：第五阶段新增的操作审计记录（合规留痕，建议先导出）；
--   5. backup_table：备份表登记（物理备份表不会被本脚本删除）；
--   6. dataset.current_version_id：当前版本指针列。
--
-- 不会删除的内容：
--   - dataset 表与各数据集的物理数据表（data_*、demo_*）保持不变；
--     dataset.table_name 始终指向"当前生效版本"的物理表，因此旧版应用仍可正常读取；
--   - 历史版本物理表（data_*_v2/_v3 ...）不会被本脚本删除，将变为孤儿表，
--     由孤儿表上报接口提示，需人工确认后清理。
--
-- 执行前置条件（强烈建议）：
--   A. 停止后端服务或设置 JOBS_ENABLED=false，确保没有 RUNNING/CANCEL_REQUESTED 任务；
--   B. 记录并导出 audit_log、dataset_version、async_job（见下方"回滚前取证"）；
--   C. 确认已完成 mysqldump 全量备份。
--
-- 回滚后如需重新升级：重新执行 V9 会重建元数据表，并为每个数据集按
-- dataset.table_name 回填 version_no=1 的 ACTIVE 版本（历史版本链无法找回）。
-- ============================================================

-- ---------- 0. 回滚前取证（请先单独执行并保存结果） ----------
-- SELECT COUNT(*) AS versions FROM `dataset_version`;
-- SELECT COUNT(*) AS job_rows FROM `async_job`;
-- SELECT COUNT(*) AS audit_rows FROM `audit_log`;
-- SELECT id, dataset_id, version_no, table_name, status, source FROM `dataset_version` ORDER BY id;
-- SELECT id, job_type, dataset_id, status, created_at FROM `async_job` ORDER BY id;
-- SELECT id, action, resource_type, resource_id, result, created_at FROM `audit_log` ORDER BY id;

-- ---------- 1. 运行中任务检查（有结果则先停止任务再回滚） ----------
SELECT COUNT(*) AS active_jobs FROM `async_job`
WHERE `status` IN ('PENDING','RUNNING','CANCEL_REQUESTED');

-- ---------- 2. 删除第五阶段新增元数据表（先子表后主表） ----------
DROP TABLE IF EXISTS `dataset_version_rule`;
DROP TABLE IF EXISTS `stats_cache`;
DROP TABLE IF EXISTS `backup_table`;
DROP TABLE IF EXISTS `audit_log`;
DROP TABLE IF EXISTS `async_job`;
DROP TABLE IF EXISTS `dataset_version`;

-- ---------- 3. 移除 dataset.current_version_id 列（存在才删，幂等） ----------
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'current_version_id'
);
SET @ddl := IF(@col_exists > 0,
    'ALTER TABLE `dataset` DROP COLUMN `current_version_id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. 回滚后校验 ----------
-- 期望：以下查询均返回 0 行
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('dataset_version','dataset_version_rule','async_job','backup_table','stats_cache','audit_log');
SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'current_version_id';

-- ---------- 5. 遗留孤儿版本表（仅提示，不自动删除） ----------
-- 回滚后这些表不再有元数据归属，请人工核对后再决定是否删除：
-- SELECT TABLE_NAME, TABLE_ROWS, CREATE_TIME FROM INFORMATION_SCHEMA.TABLES
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME REGEXP '^data_.*_v[0-9]+$';

-- 注意：clean_log.backup_table 在第五阶段以空串表示"无备份表（版本化清洗）"，
-- 回滚到旧版后旧版界面不会再使用该字段，无需数据修复。
