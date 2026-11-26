package com.dataclean.dto;

import lombok.Data;

/**
 * AI 供应商保存请求。
 *
 * <p>API Key 只写不读：新增必填，编辑留空表示保持原密钥。
 */
@Data
public class AiProviderDTO {

    private Long id;
    private String name;
    private String apiUrl;
    private String apiKey;
    private Integer status;
}
