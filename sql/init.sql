-- ============================================================
-- DataClean 平台数据库初始化脚本
-- 数据库: dataclean
-- 版本: 1.0
-- ============================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS dataclean
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE dataclean;

-- ============================================================
-- 1. 数据集主表
-- ============================================================
DROP TABLE IF EXISTS `dataset`;
CREATE TABLE `dataset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据集ID',
    `name` VARCHAR(200) NOT NULL COMMENT '数据集名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '数据集描述',
    `file_name` VARCHAR(300) NOT NULL COMMENT '原始文件名',
    `file_size` BIGINT NOT NULL COMMENT '文件大小(字节)',
    `file_type` VARCHAR(20) NOT NULL COMMENT '文件类型(csv/excel)',
    `table_name` VARCHAR(100) NOT NULL COMMENT '生成的数据库表名',
    `row_count` INT NOT NULL DEFAULT 0 COMMENT '数据行数',
    `column_count` INT NOT NULL DEFAULT 0 COMMENT '列数',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用 1-正常 2-清洗中',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_name` (`table_name`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据集信息表';

-- ============================================================
-- 2. 数据集列信息表
-- ============================================================
DROP TABLE IF EXISTS `dataset_column`;
CREATE TABLE `dataset_column` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '列ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `column_name` VARCHAR(100) NOT NULL COMMENT '原始列名',
    `column_alias` VARCHAR(100) DEFAULT NULL COMMENT '列别名(标准化后)',
    `data_type` VARCHAR(30) NOT NULL COMMENT '数据类型(VARCHAR/INT/BIGINT/DECIMAL/DATETIME)',
    `java_type` VARCHAR(50) DEFAULT NULL COMMENT 'Java数据类型',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序序号',
    `nullable` TINYINT NOT NULL DEFAULT 1 COMMENT '是否可空: 0-否 1-是',
    `unique_count` INT DEFAULT NULL COMMENT '唯一值数量',
    `null_count` INT DEFAULT NULL COMMENT '空值数量',
    `min_value` VARCHAR(200) DEFAULT NULL COMMENT '最小值',
    `max_value` VARCHAR(200) DEFAULT NULL COMMENT '最大值',
    `mean_value` DECIMAL(20,6) DEFAULT NULL COMMENT '平均值',
    `std_value` DECIMAL(20,6) DEFAULT NULL COMMENT '标准差',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_id` (`dataset_id`),
    KEY `idx_column_name` (`column_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据集列信息表';

-- ============================================================
-- 3. 清洗规则配置表
-- ============================================================
DROP TABLE IF EXISTS `clean_rule`;
CREATE TABLE `clean_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
    `rule_type` VARCHAR(50) NOT NULL COMMENT '规则类型:
        missing_value_handle-缺失值处理,
        remove_duplicate-删除重复行,
        type_convert-类型转换,
        column_standardize-列名标准化,
        outlier_handle-异常值处理,
        custom_filter-自定义过滤',
    `rule_config` JSON NOT NULL COMMENT '规则参数配置(JSON格式)',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '执行顺序',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用 1-启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_id` (`dataset_id`),
    KEY `idx_rule_type` (`rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据清洗规则表';

-- ============================================================
-- 4. 清洗执行日志表
-- ============================================================
DROP TABLE IF EXISTS `clean_log`;
CREATE TABLE `clean_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `dataset_id` BIGINT NOT NULL COMMENT '数据集ID',
    `rule_ids` VARCHAR(500) NOT NULL COMMENT '执行的规则ID列表',
    `rule_snapshot` JSON NOT NULL COMMENT '规则执行快照',
    `before_count` INT NOT NULL COMMENT '清洗前行数',
    `after_count` INT NOT NULL COMMENT '清洗后行数',
    `affected_rows` INT NOT NULL COMMENT '受影响行数',
    `backup_table` VARCHAR(100) NOT NULL COMMENT '备份表名(用于回滚)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-失败 1-成功',
    `execute_time_ms` INT DEFAULT NULL COMMENT '执行耗时(毫秒)',
    `error_msg` TEXT DEFAULT NULL COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_id` (`dataset_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='清洗执行日志表';

-- ============================================================
-- 5. AI 服务配置表
-- ============================================================
DROP TABLE IF EXISTS `ai_config`;
CREATE TABLE `ai_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    `name` VARCHAR(100) NOT NULL COMMENT '配置名称',
    `api_url` VARCHAR(500) NOT NULL COMMENT 'API请求地址',
    `api_key` VARCHAR(500) NOT NULL COMMENT 'API密钥(加密存储)',
    `model_name` VARCHAR(100) NOT NULL COMMENT '模型名称',
    `temperature` DECIMAL(3,2) NOT NULL DEFAULT 0.70 COMMENT '温度参数(0-2)',
    `max_tokens` INT NOT NULL DEFAULT 2048 COMMENT '最大生成token数',
    `timeout` INT NOT NULL DEFAULT 30000 COMMENT '请求超时(毫秒)',
    `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认: 0-否 1-是',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用 1-启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_is_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI服务配置表';

-- ============================================================
-- 6. AI Prompt 模板表
-- ============================================================
DROP TABLE IF EXISTS `ai_prompt_template`;
CREATE TABLE `ai_prompt_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    `template_key` VARCHAR(50) NOT NULL COMMENT '模板标识:
        dataset_summary-数据集摘要,
        field_relation-字段关系解读,
        outlier_analysis-异常值分析,
        chart_insight-图表洞察,
        clean_suggestion-清洗方案建议',
    `name` VARCHAR(100) NOT NULL COMMENT '模板名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '模板说明',
    `content` TEXT NOT NULL COMMENT '模板内容(支持占位符变量)',
    `variables` JSON DEFAULT NULL COMMENT '模板变量定义',
    `is_builtin` TINYINT NOT NULL DEFAULT 0 COMMENT '是否内置: 0-自定义 1-内置',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用 1-启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_key` (`template_key`),
    KEY `idx_is_builtin` (`is_builtin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Prompt模板表';

-- ============================================================
-- 7. AI 生成历史记录表
-- ============================================================
DROP TABLE IF EXISTS `ai_generation_log`;
CREATE TABLE `ai_generation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `dataset_id` BIGINT DEFAULT NULL COMMENT '关联数据集ID',
    `template_key` VARCHAR(50) DEFAULT NULL COMMENT '使用的模板标识',
    `context_type` VARCHAR(50) NOT NULL COMMENT '上下文类型:
        data_preview-数据预览,
        clean_result-清洗结果,
        chart_analysis-图表分析,
        config_page-配置页面',
    `input_summary` JSON NOT NULL COMMENT '输入摘要(数据集元信息、统计量)',
    `prompt` TEXT NOT NULL COMMENT '实际发送的Prompt',
    `response` LONGTEXT NOT NULL COMMENT 'AI生成的内容',
    `tokens_used` INT DEFAULT NULL COMMENT '消耗的token数',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-失败 1-成功',
    `error_msg` TEXT DEFAULT NULL COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_dataset_id` (`dataset_id`),
    KEY `idx_context_type` (`context_type`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI生成历史记录表';

-- ============================================================
-- 8. 系统配置表
-- ============================================================
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    `config_key` VARCHAR(100) NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(500) DEFAULT NULL COMMENT '配置值',
    `description` VARCHAR(200) DEFAULT NULL COMMENT '配置说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- ============================================================
-- 插入内置 AI Prompt 模板
-- ============================================================

INSERT INTO `ai_prompt_template` (`template_key`, `name`, `description`, `content`, `variables`, `is_builtin`, `status`) VALUES
(
    'dataset_summary',
    '数据集摘要',
    '基于数据集元信息和统计摘要，生成数据概览报告',
    '你是一位数据分析师。请基于以下数据集信息，生成一份简洁的数据摘要报告，包含数据规模、字段分布特征、数据质量评估和关键发现。\n\n## 数据集信息\n- 数据集名称: {{datasetName}}\n- 数据行数: {{rowCount}}\n- 字段数量: {{columnCount}}\n- 文件类型: {{fileType}}\n\n## 字段统计信息\n{{columnStats}}\n\n请用中文回答，使用Markdown格式，包含以下部分：\n1. 数据概览\n2. 字段分布特征\n3. 数据质量评估\n4. 关键发现与建议',
    '["datasetName", "rowCount", "columnCount", "fileType", "columnStats"]',
    1,
    1
),
(
    'field_relation',
    '字段关系解读',
    '分析字段之间的相关性和潜在关系',
    '你是一位数据分析师。请分析以下数据集字段之间的关系和潜在关联。\n\n## 数据集: {{datasetName}}\n## 字段信息\n{{columnStats}}\n\n请用中文回答，使用Markdown格式：\n1. 分析各字段间的潜在关系\n2. 识别可能的关联规则和依赖\n3. 提供字段组合建议',
    '["datasetName", "columnStats"]',
    1,
    1
),
(
    'outlier_analysis',
    '异常值分析',
    '识别数据中的异常模式并提供处理建议',
    '你是一位数据分析师。请基于以下统计信息，分析数据中的异常值情况。\n\n## 数据集: {{datasetName}}\n## 数值字段统计\n{{numericStats}}\n\n请用中文回答，使用Markdown格式：\n1. 各字段异常值识别（基于均值和标准差）\n2. 异常模式分析\n3. 处理建议',
    '["datasetName", "numericStats"]',
    1,
    1
),
(
    'chart_insight',
    '图表洞察',
    '解读图表数据中的趋势和关键发现',
    '你是一位数据分析师。请基于以下图表数据，提供深度洞察分析。\n\n## 数据集: {{datasetName}}\n## 图表配置\n- 图表类型: {{chartType}}\n- X轴字段: {{xAxis}}\n- Y轴字段: {{yAxis}}\n- 聚合方式: {{aggregation}}\n\n## 图表数据摘要\n{{chartDataSummary}}\n\n请用中文回答，使用Markdown格式：\n1. 图表趋势解读\n2. 关键数据点\n3. 业务洞察与建议',
    '["datasetName", "chartType", "xAxis", "yAxis", "aggregation", "chartDataSummary"]',
    1,
    1
),
(
    'clean_suggestion',
    '清洗方案建议',
    '基于数据特征推荐最优清洗策略',
    '你是一位数据清洗专家。请基于以下数据集质量评估，推荐最优的清洗方案。\n\n## 数据集: {{datasetName}}\n- 总行数: {{rowCount}}\n- 字段数: {{columnCount}}\n\n## 质量评估\n{{qualityReport}}\n\n请用中文回答，使用Markdown格式：\n1. 发现的数据质量问题\n2. 推荐清洗步骤（按优先级排序）\n3. 各步骤预期效果',
    '["datasetName", "rowCount", "columnCount", "qualityReport"]',
    1,
    1
);

-- ============================================================
-- 插入默认 AI 配置 (占位，需用户自行配置)
-- ============================================================
INSERT INTO `ai_config` (`name`, `api_url`, `api_key`, `model_name`, `temperature`, `max_tokens`, `is_default`, `status`) VALUES
(
    '默认配置',
    'https://api.openai.com/v1/chat/completions',
    'your-api-key-here',
    'gpt-3.5-turbo',
    0.70,
    2048,
    1,
    0
);
