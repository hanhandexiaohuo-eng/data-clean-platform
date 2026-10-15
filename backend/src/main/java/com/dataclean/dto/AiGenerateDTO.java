package com.dataclean.dto;

import lombok.Data;

import java.util.Map;

/**
 * AI 生成请求DTO
 */
@Data
public class AiGenerateDTO {

    /**
     * 数据集ID
     */
    private Long datasetId;

    /**
     * 模板标识
     */
    private String templateKey;

    /**
     * 上下文类型
     */
    private String contextType;

    /**
     * 自定义提示词(覆盖模板)
     */
    private String customPrompt;

    /**
     * 模板变量值
     */
    private Map<String, String> variables;

    /**
     * 是否流式输出
     */
    private Boolean stream = false;

    /**
     * AI配置ID(为空使用默认配置)
     */
    private Long configId;

    /**
     * 额外上下文数据(图表类型、坐标轴等)
     */
    private Map<String, Object> extraContext;
}
