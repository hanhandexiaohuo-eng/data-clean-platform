package com.dataclean.dto;

import com.dataclean.entity.AiConfig;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 配置的对外视图。
 *
 * <p>只暴露「是否已配置」与掩码，结构上不存在明文字段，
 * 因此任何序列化路径都无法返回完整 API Key。
 */
@Data
public class AiConfigVO {

    private Long id;
    private String name;
    private String apiUrl;
    private String modelName;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer timeout;
    private Integer isDefault;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 是否已保存过 API Key。 */
    private Boolean keyConfigured;

    /** 掩码，例如 sk-****abcd；未配置时为 null。 */
    private String apiKeyMask;

    public static AiConfigVO from(AiConfig config) {
        AiConfigVO vo = new AiConfigVO();
        vo.setId(config.getId());
        vo.setName(config.getName());
        vo.setApiUrl(config.getApiUrl());
        vo.setModelName(config.getModelName());
        vo.setTemperature(config.getTemperature());
        vo.setMaxTokens(config.getMaxTokens());
        vo.setTimeout(config.getTimeout());
        vo.setIsDefault(config.getIsDefault());
        vo.setStatus(config.getStatus());
        vo.setCreatedAt(config.getCreatedAt());
        vo.setUpdatedAt(config.getUpdatedAt());

        boolean configured = (config.getApiKeyCipher() != null && !config.getApiKeyCipher().isBlank())
                || (config.getApiKey() != null && !config.getApiKey().isBlank());
        vo.setKeyConfigured(configured);
        vo.setApiKeyMask(config.getApiKeyMask());
        return vo;
    }
}
