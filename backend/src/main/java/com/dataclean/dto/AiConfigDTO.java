package com.dataclean.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 配置DTO
 */
@Data
public class AiConfigDTO {

    private Long id;

    /**
     * 配置名称
     */
    private String name;

    /**
     * API请求地址
     */
    private String apiUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 温度参数
     */
    private BigDecimal temperature;

    /**
     * 最大token数
     */
    private Integer maxTokens;

    /**
     * 超时时间
     */
    private Integer timeout;

    /**
     * 是否默认
     */
    private Integer isDefault;

    /**
     * 状态
     */
    private Integer status;
}
