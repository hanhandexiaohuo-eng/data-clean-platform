package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_config")
public class AiConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String apiUrl;

    /**
     * 已废弃的明文字段：仅用于存量数据迁移，迁移后置空。
     * 禁止序列化、禁止打印到日志。
     */
    @JsonIgnore
    @ToString.Exclude
    private String apiKey;

    /** AES-256-GCM 密文，禁止序列化、禁止打印。 */
    @JsonIgnore
    @ToString.Exclude
    private String apiKeyCipher;

    /** 掩码展示，例如 sk-****abcd。 */
    private String apiKeyMask;

    private String modelName;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer timeout;
    private Integer isDefault;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
