package com.dataclean.dto;

import lombok.Data;

/**
 * 清洗规则DTO
 */
@Data
public class CleanRuleDTO {

    private Long id;

    /**
     * 数据集ID
     */
    private Long datasetId;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 规则类型
     */
    private String ruleType;

    /**
     * 规则配置JSON
     */
    private String ruleConfig;

    /**
     * 执行顺序
     */
    private Integer sortOrder;

    /**
     * 状态
     */
    private Integer status;

    // 规则配置快捷字段

    /**
     * 目标列(用于缺失值处理、类型转换等)
     */
    private String targetColumn;

    /**
     * 处理方式
     */
    private String handleType;

    /**
     * 填充值
     */
    private String fillValue;

    /**
     * 目标类型
     */
    private String targetType;

    /**
     * 异常值检测方法
     */
    private String detectMethod;

    /**
     * 过滤表达式
     */
    private String filterExpression;
}
