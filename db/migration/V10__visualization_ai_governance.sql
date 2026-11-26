-- ============================================================
-- V10：可视化保存/看板与 AI 调用治理（纯增量）
-- ------------------------------------------------------------
-- 说明：
--   1. 只新增表与索引，不修改、不删除任何既有表和数据；
--   2. 所有 DDL 均为 IF NOT EXISTS，可重复执行；
--   3. 不写入任何示例业务数据；AI 治理表为空时自动回退既有 ai_config；
--   4. 既有 chart_view / ai_config / ai_generation_log 语义保持不变。
-- ============================================================

-- ---------- 1. 保存的图表视图 ----------
CREATE TABLE IF NOT EXISTS `saved_chart` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '图表视图ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `dataset_version_id` BIGINT NULL COMMENT '保存时的数据版本ID',
    `owner_type` VARCHAR(16) NOT NULL COMMENT 'private/demo/anon',
    `anon_session_id` BIGINT NULL COMMENT '匿名会话ID（私有为空）',
    `user_id` BIGINT NULL COMMENT '私有模式用户ID',
    `title` VARCHAR(200) NOT NULL COMMENT '图表标题',
    `chart_type` VARCHAR(20) NOT NULL COMMENT 'bar/line/area/pie/scatter/heatmap',
    `chart_config` JSON NOT NULL COMMENT '图表配置（仅白名单键）',
    `config_hash` CHAR(64) NOT NULL DEFAULT '' COMMENT '配置摘要',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_sc_dataset` (`dataset_id`),
    KEY `idx_sc_owner_session` (`owner_type`, `anon_session_id`),
    KEY `idx_sc_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='保存的图表视图';

-- ---------- 2. 看板 ----------
CREATE TABLE IF NOT EXISTS `dashboard` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '看板ID',
    `owner_type` VARCHAR(16) NOT NULL COMMENT 'private/anon',
    `anon_session_id` BIGINT NULL,
    `user_id` BIGINT NULL,
    `title` VARCHAR(200) NOT NULL,
    `description` VARCHAR(500) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_db_owner_session` (`owner_type`, `anon_session_id`),
    KEY `idx_db_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='看板';

-- ---------- 3. 看板项 ----------
CREATE TABLE IF NOT EXISTS `dashboard_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `dashboard_id` BIGINT NOT NULL,
    `saved_chart_id` BIGINT NOT NULL,
    `sort_order` INT NOT NULL DEFAULT 0,
    `grid_x` INT NOT NULL DEFAULT 0 COMMENT '栅格列 0-11',
    `grid_y` INT NOT NULL DEFAULT 0,
    `grid_w` INT NOT NULL DEFAULT 4 COMMENT '宽度 2-12',
    `grid_h` INT NOT NULL DEFAULT 4 COMMENT '高度 2-12',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_di_dashboard_chart` (`dashboard_id`, `saved_chart_id`),
    KEY `idx_di_dashboard_sort` (`dashboard_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='看板项';

-- ---------- 4. AI 供应商 ----------
CREATE TABLE IF NOT EXISTS `ai_provider` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL COMMENT '供应商标识（仅名称）',
    `api_url` VARCHAR(500) NOT NULL,
    `api_key_cipher` VARCHAR(1000) NULL COMMENT 'AES-256-GCM 密文，禁止序列化',
    `api_key_mask` VARCHAR(64) NULL COMMENT '掩码',
    `status` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ap_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 供应商';

-- ---------- 5. AI 模型目录 ----------
CREATE TABLE IF NOT EXISTS `ai_model` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `provider_id` BIGINT NOT NULL,
    `model_code` VARCHAR(120) NOT NULL COMMENT '发送给供应商的模型标识',
    `display_name` VARCHAR(120) NOT NULL,
    `temperature` DECIMAL(3,2) NOT NULL DEFAULT 0.70,
    `max_output_tokens` INT NOT NULL DEFAULT 1024,
    `timeout_ms` INT NOT NULL DEFAULT 30000,
    `retry_count` INT NOT NULL DEFAULT 1,
    `concurrency_limit` INT NOT NULL DEFAULT 4,
    `cost_per_1k_input` DECIMAL(12,6) NOT NULL DEFAULT 0,
    `cost_per_1k_output` DECIMAL(12,6) NOT NULL DEFAULT 0,
    `status` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_am_provider_code` (`provider_id`, `model_code`),
    KEY `idx_am_provider` (`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型目录';

-- ---------- 6. AI 场景路由 ----------
CREATE TABLE IF NOT EXISTS `ai_scene_route` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `scene` VARCHAR(40) NOT NULL COMMENT 'DATASET_SUMMARY/FIELD_RELATION/OUTLIER_ANALYSIS/CHART_INSIGHT/CLEAN_SUGGESTION',
    `primary_model_id` BIGINT NOT NULL,
    `fallback_model_id` BIGINT NULL,
    `temperature` DECIMAL(3,2) NULL,
    `max_output_tokens` INT NULL,
    `timeout_ms` INT NULL,
    `retry_count` INT NULL,
    `concurrency_limit` INT NULL,
    `status` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_asr_scene` (`scene`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 场景路由';

-- ---------- 7. AI 调用审计 ----------
CREATE TABLE IF NOT EXISTS `ai_call_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `scene` VARCHAR(40) NOT NULL,
    `provider_name` VARCHAR(100) NOT NULL COMMENT '仅名称，不含地址/密钥',
    `model_code` VARCHAR(120) NOT NULL,
    `principal_hash` CHAR(64) NOT NULL COMMENT '主体不可逆摘要',
    `dataset_id` BIGINT NULL,
    `dataset_version_id` BIGINT NULL,
    `saved_chart_id` BIGINT NULL,
    `request_at` DATETIME NOT NULL,
    `latency_ms` INT NOT NULL DEFAULT 0,
    `input_tokens` INT NOT NULL DEFAULT 0,
    `output_tokens` INT NOT NULL DEFAULT 0,
    `cost` DECIMAL(12,6) NOT NULL DEFAULT 0,
    `result` VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED/DEGRADED/REJECTED',
    `error_type` VARCHAR(40) NULL,
    `degraded` TINYINT NOT NULL DEFAULT 0,
    `quota_scope` VARCHAR(20) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_acl_time` (`request_at`),
    KEY `idx_acl_scene` (`scene`),
    KEY `idx_acl_model` (`model_code`),
    KEY `idx_acl_result` (`result`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用审计（不含 Prompt/响应/密钥）';

-- ---------- 8. AI 配额配置 ----------
CREATE TABLE IF NOT EXISTS `ai_quota` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `scope_type` VARCHAR(24) NOT NULL COMMENT 'private/public_session/public_ip/public_global',
    `scene` VARCHAR(40) NOT NULL DEFAULT '*',
    `model_code` VARCHAR(120) NOT NULL DEFAULT '*',
    `period` VARCHAR(8) NOT NULL DEFAULT 'DAY',
    `max_calls` BIGINT NULL,
    `max_tokens` BIGINT NULL,
    `max_cost` DECIMAL(12,6) NULL,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_aq_scope` (`scene`, `model_code`, `period`, `scope_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 配额与预算';

-- ---------- 9. AI 用量计数 ----------
CREATE TABLE IF NOT EXISTS `ai_usage` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `scope_type` VARCHAR(24) NOT NULL,
    `scope_key_hash` CHAR(64) NOT NULL COMMENT '会话/IP/主体摘要；global 固定 global',
    `scene` VARCHAR(40) NOT NULL,
    `model_code` VARCHAR(120) NOT NULL,
    `period_key` VARCHAR(16) NOT NULL COMMENT 'yyyy-MM-dd',
    `calls` BIGINT NOT NULL DEFAULT 0,
    `input_tokens` BIGINT NOT NULL DEFAULT 0,
    `output_tokens` BIGINT NOT NULL DEFAULT 0,
    `cost` DECIMAL(12,6) NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_au_scope_period` (`scope_type`, `scope_key_hash`, `scene`, `model_code`, `period_key`),
    KEY `idx_au_period` (`period_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 用量计数';

-- ---------- 10. AI 熔断状态 ----------
CREATE TABLE IF NOT EXISTS `ai_circuit_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `circuit_key` VARCHAR(120) NOT NULL COMMENT 'model:<id> / provider:<id>',
    `state` VARCHAR(16) NOT NULL DEFAULT 'CLOSED' COMMENT 'CLOSED/OPEN/HALF_OPEN',
    `failure_count` INT NOT NULL DEFAULT 0,
    `opened_at` DATETIME NULL,
    `cooldown_until` DATETIME NULL,
    `last_error_type` VARCHAR(40) NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_acs_key` (`circuit_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 熔断状态';
