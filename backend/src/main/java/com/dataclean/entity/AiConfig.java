package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_config")
public class AiConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String apiUrl;
    private String apiKey;
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
