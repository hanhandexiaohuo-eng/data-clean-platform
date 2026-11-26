-- ============================================================
-- V9：数据集版本化、异步任务、统计缓存与审计（纯增量）
-- ------------------------------------------------------------
-- 说明：
--   1. 所有 DDL 均为幂等增量，可重复执行；
--   2. 不修改/删除任何历史数据集数据，只为存量数据集补建初始版本记录；
--   3. 新增表仅记录元信息，不复制原始数据。
-- ============================================================

-- ---------- 1. 数据集当前版本指针 ----------
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = 'current_version_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `dataset` ADD COLUMN `current_version_id` BIGINT NULL COMMENT ''当前可用版本ID'' AFTER `table_name`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. 数据集版本 ----------
CREATE TABLE IF NOT EXISTS `dataset_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '版本ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `version_no` INT NOT NULL COMMENT '版本序号（从1递增）',
    `table_name` VARCHAR(64) NOT NULL COMMENT '该版本对应的物理表',
    `row_count` BIGINT NOT NULL DEFAULT 0 COMMENT '行数',
    `column_count` INT NOT NULL DEFAULT 0 COMMENT '列数',
    `checksum` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '数据校验摘要（行数+表校验和）',
    `status` VARCHAR(16) NOT NULL DEFAULT 'BUILDING' COMMENT 'BUILDING/ACTIVE/ARCHIVED/FAILED',
    `source` VARCHAR(16) NOT NULL DEFAULT 'IMPORT' COMMENT 'IMPORT/CLEAN/RESTORE/BACKFILL',
    `job_id` BIGINT NULL COMMENT '产生该版本的任务ID',
    `owner_type` VARCHAR(16) NOT NULL COMMENT 'private/demo/anon',
    `anon_session_id` BIGINT NULL COMMENT '匿名会话ID（private/demo 为空）',
    `note` VARCHAR(255) NULL COMMENT '备注（失败原因摘要等）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `activated_at` DATETIME NULL COMMENT '成为当前版本的时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dv_dataset_version` (`dataset_id`, `version_no`),
    KEY `idx_dv_dataset_status` (`dataset_id`, `status`),
    KEY `idx_dv_owner_session` (`owner_type`, `anon_session_id`),
    KEY `idx_dv_job` (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据集版本';

-- ---------- 3. 版本规则快照 ----------
CREATE TABLE IF NOT EXISTS `dataset_version_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `version_id` BIGINT NOT NULL COMMENT '版本ID',
    `rule_id` BIGINT NULL COMMENT '原始规则ID（规则可能被删除，快照仍保留）',
    `rule_type` VARCHAR(40) NOT NULL COMMENT '规则类型',
    `rule_config` LONGTEXT NOT NULL COMMENT '规则配置快照（JSON）',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '执行顺序',
    `snapshot_hash` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '规则快照摘要',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dvr_version` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本清洗规则快照';

-- ---------- 4. 持久化异步任务 ----------
CREATE TABLE IF NOT EXISTS `async_job` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `job_type` VARCHAR(16) NOT NULL COMMENT 'IMPORT/CLEAN/RESTORE',
    `dataset_id` BIGINT NULL COMMENT '数据集ID（导入任务在建表后回填）',
    `input_version_id` BIGINT NULL COMMENT '输入版本ID',
    `output_version_id` BIGINT NULL COMMENT '输出/目标版本ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED/RECOVERABLE',
    `phase` VARCHAR(40) NOT NULL DEFAULT '' COMMENT '当前阶段（扫描/解析/写入/规则/校验/切换）',
    `progress` INT NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    `processed_rows` BIGINT NOT NULL DEFAULT 0 COMMENT '已处理行数',
    `total_rows` BIGINT NOT NULL DEFAULT 0 COMMENT '总行数（未知时为0）',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retries` INT NOT NULL DEFAULT 3,
    `error_code` VARCHAR(40) NULL COMMENT '失败类型（不含内部细节）',
    `error_summary` VARCHAR(500) NULL COMMENT '脱敏失败摘要',
    `payload` LONGTEXT NULL COMMENT '任务参数（临时文件路径、规则ID等，不含凭据）',
    `result_summary` LONGTEXT NULL COMMENT '结果摘要（版本、行数、耗时）',
    `owner_type` VARCHAR(16) NOT NULL COMMENT 'private/demo/anon',
    `anon_session_id` BIGINT NULL COMMENT '匿名会话ID',
    `user_id` BIGINT NULL COMMENT '私有模式操作者ID',
    `cancel_requested_at` DATETIME NULL COMMENT '取消请求时间',
    `started_at` DATETIME NULL,
    `finished_at` DATETIME NULL,
    `heartbeat_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_job_status_created` (`status`, `created_at`),
    KEY `idx_job_dataset` (`dataset_id`),
    KEY `idx_job_owner_session` (`owner_type`, `anon_session_id`),
    KEY `idx_job_session_status` (`anon_session_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='持久化异步任务';

-- ---------- 5. 备份表登记 ----------
CREATE TABLE IF NOT EXISTS `backup_table` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `dataset_id` BIGINT NULL,
    `version_id` BIGINT NULL,
    `table_name` VARCHAR(64) NOT NULL,
    `checksum` VARCHAR(64) NOT NULL DEFAULT '',
    `row_count` BIGINT NOT NULL DEFAULT 0,
    `owner_type` VARCHAR(16) NOT NULL,
    `anon_session_id` BIGINT NULL,
    `job_id` BIGINT NULL,
    `state` VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT 'READY/EXPIRED/DROPPED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at` DATETIME NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bt_table` (`table_name`),
    KEY `idx_bt_owner_session` (`owner_type`, `anon_session_id`),
    KEY `idx_bt_dataset` (`dataset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='备份表归属登记';

-- ---------- 6. 统计缓存 ----------
CREATE TABLE IF NOT EXISTS `stats_cache` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `dataset_id` BIGINT NOT NULL,
    `version_id` BIGINT NULL COMMENT '缓存对应的数据版本，版本切换后失效',
    `payload` LONGTEXT NOT NULL COMMENT '统计结果 JSON',
    `row_count` BIGINT NOT NULL DEFAULT 0,
    `computed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stats_dataset` (`dataset_id`),
    KEY `idx_stats_version` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统计结果缓存';

-- ---------- 7. 审计日志 ----------
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `action` VARCHAR(40) NOT NULL COMMENT 'UPLOAD_ASYNC/CLEAN_START/CLEAN_CANCEL/JOB_RETRY/VERSION_RESTORE/VERSION_DELETE等',
    `resource_type` VARCHAR(20) NOT NULL COMMENT 'JOB/DATASET/VERSION',
    `resource_id` BIGINT NULL,
    `dataset_id` BIGINT NULL,
    `job_id` BIGINT NULL,
    `version_id` BIGINT NULL,
    `owner_type` VARCHAR(16) NOT NULL,
    `anon_session_id` BIGINT NULL,
    `user_id` BIGINT NULL,
    `result` VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED/REJECTED',
    `error_type` VARCHAR(40) NULL,
    `detail` VARCHAR(1000) NULL COMMENT '脱敏摘要，不含原始数据/凭据/SQL',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_owner_session` (`owner_type`, `anon_session_id`),
    KEY `idx_audit_dataset` (`dataset_id`),
    KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计';

-- ---------- 8. 存量数据集初始版本记录 ----------
-- 只为已有物理表建立 version_no=1 的 ACTIVE 记录；行数/校验和由应用在首次访问时校正。
INSERT INTO `dataset_version`
    (`dataset_id`, `version_no`, `table_name`, `row_count`, `column_count`, `checksum`,
     `status`, `source`, `owner_type`, `anon_session_id`, `created_at`, `activated_at`)
SELECT d.`id`, 1, d.`table_name`, COALESCE(d.`row_count`, 0), COALESCE(d.`column_count`, 0), '',
       'ACTIVE', 'BACKFILL', d.`owner_type`, d.`anon_session_id`, d.`created_at`, CURRENT_TIMESTAMP
FROM `dataset` d
WHERE d.`table_name` IS NOT NULL AND d.`table_name` <> ''
  AND NOT EXISTS (
    SELECT 1 FROM `dataset_version` v WHERE v.`dataset_id` = d.`id`
);

-- 回填当前版本指针
UPDATE `dataset` d
JOIN `dataset_version` v ON v.`dataset_id` = d.`id` AND v.`version_no` = 1
SET d.`current_version_id` = v.`id`
WHERE d.`current_version_id` IS NULL;
