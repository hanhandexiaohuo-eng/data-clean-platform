-- ============================================================
-- V5 异步任务表（预留，纯增量）
-- ------------------------------------------------------------
-- 为后续导入/清洗/导出等长任务预留统一任务表，本阶段仅建表，
-- 不接入业务、不写入数据。不建立外键，避免与现有表耦合。
--
-- 回滚：db/rollback/R5__async_task.sql（会删除任务记录，执行前务必备份）
-- ============================================================

CREATE TABLE IF NOT EXISTS `async_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `task_type` VARCHAR(50) NOT NULL COMMENT '任务类型: import/clean/export/analysis',
    `dataset_id` BIGINT DEFAULT NULL COMMENT '关联数据集ID',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-待执行 1-执行中 2-成功 3-失败 4-已取消',
    `progress` TINYINT NOT NULL DEFAULT 0 COMMENT '进度百分比(0-100)',
    `payload` JSON DEFAULT NULL COMMENT '任务参数(禁止写入密钥/令牌)',
    `result_summary` JSON DEFAULT NULL COMMENT '结果摘要(禁止写入原始敏感数据)',
    `error_code` VARCHAR(50) DEFAULT NULL COMMENT '错误码',
    `error_msg` VARCHAR(500) DEFAULT NULL COMMENT '错误信息(脱敏)',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    `created_by` BIGINT DEFAULT NULL COMMENT '创建人ID(审计预留)',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始时间',
    `finished_at` DATETIME DEFAULT NULL COMMENT '结束时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_async_task_status_time` (`status`, `created_at`),
    KEY `idx_async_task_dataset` (`dataset_id`),
    KEY `idx_async_task_type_status` (`task_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步任务表(预留)';
