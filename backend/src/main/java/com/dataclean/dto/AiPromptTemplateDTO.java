package com.dataclean.dto;

import lombok.Data;

/**
 * AI Prompt 模板DTO
 */
@Data
public class AiPromptTemplateDTO {

    private Long id;

    /**
     * 模板标识
     */
    private String templateKey;

    /**
     * 模板名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 模板内容
     */
    private String content;

    /**
     * 变量定义
     */
    private String variables;

    /**
     * 是否内置
     */
    private Integer isBuiltin;

    /**
     * 状态
     */
    private Integer status;
}
