package com.dataclean.dto;

import lombok.Data;

/**
 * AI 供应商对外视图：只暴露掩码与状态，绝不包含密文或明文 Key。
 */
@Data
public class AiProviderVO {

    private Long id;
    private String name;
    private String apiUrl;
    private String apiKeyMask;
    private Boolean keyConfigured;
    private Integer status;
    private Long modelCount;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;
}
