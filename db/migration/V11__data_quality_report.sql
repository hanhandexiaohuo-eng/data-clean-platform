-- ============================================================
-- V11：真实数据质量报表（纯增量）
-- ------------------------------------------------------------
-- 说明：
--   1. 只新增表与索引，不修改、不删除任何既有表和数据；
--   2. DDL 使用 IF NOT EXISTS，可重复执行；
--   3. 不写入任何示例业务数据；无记录即表示“未采集”，不能用 0 伪装完成；
--   4. 报表按 dataset_id + version_id 唯一，版本切换后自然重新采集。
-- ============================================================

CREATE TABLE IF NOT EXISTS `data_quality_report` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `version_id` BIGINT NOT NULL DEFAULT 0 COMMENT '数据版本ID，0 表示无版本信息',
    `status` VARCHAR(20) NOT NULL COMMENT 'COMPUTING/DONE/FAILED',
    `columns_key` VARCHAR(1000) NULL COMMENT '参与统计的字段组合摘要',
    `payload` JSON NULL COMMENT '统计结果（不含原始敏感数据，仅抽样示例）',
    `computed_at` DATETIME NULL COMMENT '完成时间',
    `expires_at` DATETIME NULL COMMENT '缓存过期时间',
    `error_summary` VARCHAR(500) NULL COMMENT '安全失败摘要，不含堆栈/SQL',
    `owner_type` VARCHAR(16) NULL COMMENT 'private/demo/anon',
    `anon_session_id` BIGINT NULL COMMENT '匿名会话ID（私有为空）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dqr_dataset_version` (`dataset_id`, `version_id`),
    KEY `idx_dqr_status` (`status`),
    KEY `idx_dqr_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量真实统计报表';
