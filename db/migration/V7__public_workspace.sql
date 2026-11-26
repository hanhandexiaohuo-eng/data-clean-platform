-- ============================================================
-- V7 公开演示与临时工作区（纯增量、可重复执行）
-- ------------------------------------------------------------
-- 内容：
--   1) 新增 7 张公开模式专用表；
--   2) 既有 4 张表新增归属列与索引（历史行 owner_type='private'）。
-- 约束：
--   - 不删除、不重建任何既有表，不修改既有数据；
--   - 全部 DDL 通过 INFORMATION_SCHEMA 判断，已存在则跳过；
--   - 回滚：db/rollback/R7__public_workspace.sql（执行前务必备份）。
-- ============================================================

-- ---------- 1. 匿名会话 ----------
CREATE TABLE IF NOT EXISTS `anon_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `token_hash` CHAR(64) NOT NULL COMMENT '匿名令牌 SHA-256',
    `created_at` DATETIME NULL COMMENT '创建时间',
    `last_seen_at` DATETIME NULL COMMENT '最近访问时间',
    `expires_at` DATETIME NOT NULL COMMENT '过期时间',
    `ip_hash` CHAR(64) NULL COMMENT '来源 IP HMAC 摘要',
    `revoked` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已注销',
    `cleanup_state` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active/purging/failed/purged',
    `cleanup_attempts` INT NOT NULL DEFAULT 0 COMMENT '清理尝试次数',
    `purge_started_at` DATETIME NULL COMMENT '本轮清理开始时间',
    `purged_at` DATETIME NULL COMMENT '清理完成时间',
    `last_cleanup_error` VARCHAR(200) NULL COMMENT '最近一次清理失败类型',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_expires_at` (`expires_at`),
    KEY `idx_cleanup_state` (`cleanup_state`),
    KEY `idx_ip_hash` (`ip_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='匿名会话';

-- ---------- 2. 清理明细 ----------
CREATE TABLE IF NOT EXISTS `cleanup_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `anon_session_id` BIGINT NOT NULL COMMENT '匿名会话ID',
    `resource_type` VARCHAR(32) NOT NULL COMMENT 'dataset_table/backup_table/dataset_meta/...',
    `resource_key` VARCHAR(128) NOT NULL COMMENT '精确表名或主键',
    `state` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/done/failed',
    `error_type` VARCHAR(100) NULL COMMENT '失败异常类型',
    `updated_at` DATETIME NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_resource` (`anon_session_id`, `resource_type`, `resource_key`),
    KEY `idx_state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='清理明细';

-- ---------- 3. AI 人机挑战 ----------
CREATE TABLE IF NOT EXISTS `ai_challenge` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `challenge_hash` CHAR(64) NOT NULL COMMENT 'SHA-256(secret)',
    `anon_session_id` BIGINT NOT NULL COMMENT '绑定匿名会话',
    `ip_hash` CHAR(64) NOT NULL COMMENT '绑定 IP 摘要',
    `expires_at` DATETIME NOT NULL COMMENT '过期时间',
    `used_at` DATETIME NULL COMMENT '核销时间（非空即已使用）',
    `created_at` DATETIME NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_challenge_hash` (`challenge_hash`),
    KEY `idx_session` (`anon_session_id`),
    KEY `idx_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 人机挑战';

-- ---------- 4. 图表视图 ----------
CREATE TABLE IF NOT EXISTS `chart_view` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `anon_session_id` BIGINT NOT NULL COMMENT '匿名会话ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `chart_type` VARCHAR(20) NULL COMMENT '图表类型',
    `chart_config` JSON NULL COMMENT '图表配置',
    `created_at` DATETIME NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_anon` (`anon_session_id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图表视图';

-- ---------- 5. 导出记录 ----------
CREATE TABLE IF NOT EXISTS `export_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `anon_session_id` BIGINT NOT NULL COMMENT '匿名会话ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `export_type` VARCHAR(20) NOT NULL DEFAULT 'csv' COMMENT '导出类型',
    `row_count` INT NULL COMMENT '导出行数',
    `created_at` DATETIME NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_anon` (`anon_session_id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导出记录';

-- ---------- 6. 每日用量（会话/IP/全站） ----------
CREATE TABLE IF NOT EXISTS `anon_usage_daily` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scope_type` VARCHAR(10) NOT NULL COMMENT 'session/ip/global',
    `scope_key` CHAR(64) NOT NULL COMMENT '会话ID字符串 / IP 哈希 / global',
    `metric` VARCHAR(30) NOT NULL COMMENT 'ai_call/ai_token/session_create',
    `usage_date` DATE NOT NULL COMMENT '统计日期',
    `counter` BIGINT NOT NULL DEFAULT 0 COMMENT '累计值',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scope_metric_date` (`scope_type`, `scope_key`, `metric`, `usage_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公开模式每日用量';

-- ---------- 7. 清理日志 ----------
CREATE TABLE IF NOT EXISTS `cleanup_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `finished_at` DATETIME NULL COMMENT '结束时间',
    `sessions_cleaned` INT NULL COMMENT '清理会话数',
    `datasets_removed` INT NULL COMMENT '删除数据集数',
    `tables_dropped` INT NULL COMMENT '删除表数',
    `rows_removed` INT NULL COMMENT '删除记录数',
    `failures` INT NULL COMMENT '失败项数',
    `orphan_tables_reported` INT NULL COMMENT '孤儿表数量（仅报告）',
    `detail` VARCHAR(2000) NULL COMMENT '脱敏统计明细',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公开数据清理日志';

-- ---------- 8. dataset.owner_type ----------
SET @exists_dataset_owner := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'owner_type'
);
SET @ddl_dataset_owner := IF(@exists_dataset_owner = 0,
    'ALTER TABLE `dataset` ADD COLUMN `owner_type` VARCHAR(16) NOT NULL DEFAULT ''private'' COMMENT ''归属类型：private/demo/anon''',
    'SELECT 1');
PREPARE stmt_dataset_owner FROM @ddl_dataset_owner;
EXECUTE stmt_dataset_owner;
DEALLOCATE PREPARE stmt_dataset_owner;

-- ---------- 9. dataset.anon_session_id ----------
SET @exists_dataset_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_dataset_anon := IF(@exists_dataset_anon = 0,
    'ALTER TABLE `dataset` ADD COLUMN `anon_session_id` BIGINT NULL COMMENT ''匿名会话ID（私有/演示为 NULL）''',
    'SELECT 1');
PREPARE stmt_dataset_anon FROM @ddl_dataset_anon;
EXECUTE stmt_dataset_anon;
DEALLOCATE PREPARE stmt_dataset_anon;

-- ---------- 10. dataset.idx_anon_session ----------
SET @exists_idx_ds_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND INDEX_NAME = 'idx_anon_session'
);
SET @ddl_idx_ds_anon := IF(@exists_idx_ds_anon = 0,
    'ALTER TABLE `dataset` ADD INDEX `idx_anon_session` (`anon_session_id`)',
    'SELECT 1');
PREPARE stmt_idx_ds_anon FROM @ddl_idx_ds_anon;
EXECUTE stmt_idx_ds_anon;
DEALLOCATE PREPARE stmt_idx_ds_anon;

-- ---------- 11. dataset.idx_owner_type ----------
SET @exists_idx_ds_owner := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND INDEX_NAME = 'idx_owner_type'
);
SET @ddl_idx_ds_owner := IF(@exists_idx_ds_owner = 0,
    'ALTER TABLE `dataset` ADD INDEX `idx_owner_type` (`owner_type`)',
    'SELECT 1');
PREPARE stmt_idx_ds_owner FROM @ddl_idx_ds_owner;
EXECUTE stmt_idx_ds_owner;
DEALLOCATE PREPARE stmt_idx_ds_owner;

-- ---------- 12/13. clean_rule / clean_log / ai_generation_log.anon_session_id ----------
SET @exists_cr_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_rule' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_cr_anon := IF(@exists_cr_anon = 0,
    'ALTER TABLE `clean_rule` ADD COLUMN `anon_session_id` BIGINT NULL COMMENT ''匿名会话ID''',
    'SELECT 1');
PREPARE stmt_cr_anon FROM @ddl_cr_anon;
EXECUTE stmt_cr_anon;
DEALLOCATE PREPARE stmt_cr_anon;

SET @exists_cl_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_log' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_cl_anon := IF(@exists_cl_anon = 0,
    'ALTER TABLE `clean_log` ADD COLUMN `anon_session_id` BIGINT NULL COMMENT ''匿名会话ID''',
    'SELECT 1');
PREPARE stmt_cl_anon FROM @ddl_cl_anon;
EXECUTE stmt_cl_anon;
DEALLOCATE PREPARE stmt_cl_anon;

SET @exists_al_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log' AND COLUMN_NAME = 'anon_session_id'
);
SET @ddl_al_anon := IF(@exists_al_anon = 0,
    'ALTER TABLE `ai_generation_log` ADD COLUMN `anon_session_id` BIGINT NULL COMMENT ''匿名会话ID''',
    'SELECT 1');
PREPARE stmt_al_anon FROM @ddl_al_anon;
EXECUTE stmt_al_anon;
DEALLOCATE PREPARE stmt_al_anon;

-- ---------- 14/15/16. 三个索引 ----------
SET @exists_idx_cr_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_rule' AND INDEX_NAME = 'idx_anon_session'
);
SET @ddl_idx_cr_anon := IF(@exists_idx_cr_anon = 0,
    'ALTER TABLE `clean_rule` ADD INDEX `idx_anon_session` (`anon_session_id`)',
    'SELECT 1');
PREPARE stmt_idx_cr_anon FROM @ddl_idx_cr_anon;
EXECUTE stmt_idx_cr_anon;
DEALLOCATE PREPARE stmt_idx_cr_anon;

SET @exists_idx_cl_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'clean_log' AND INDEX_NAME = 'idx_anon_session'
);
SET @ddl_idx_cl_anon := IF(@exists_idx_cl_anon = 0,
    'ALTER TABLE `clean_log` ADD INDEX `idx_anon_session` (`anon_session_id`)',
    'SELECT 1');
PREPARE stmt_idx_cl_anon FROM @ddl_idx_cl_anon;
EXECUTE stmt_idx_cl_anon;
DEALLOCATE PREPARE stmt_idx_cl_anon;

SET @exists_idx_al_anon := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generation_log' AND INDEX_NAME = 'idx_anon_session'
);
SET @ddl_idx_al_anon := IF(@exists_idx_al_anon = 0,
    'ALTER TABLE `ai_generation_log` ADD INDEX `idx_anon_session` (`anon_session_id`)',
    'SELECT 1');
PREPARE stmt_idx_al_anon FROM @ddl_idx_al_anon;
EXECUTE stmt_idx_al_anon;
DEALLOCATE PREPARE stmt_idx_al_anon;
