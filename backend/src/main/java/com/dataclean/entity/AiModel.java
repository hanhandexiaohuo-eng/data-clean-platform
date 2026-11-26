package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_model")
public class AiModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String modelCode;
    private String displayName;
    private BigDecimal temperature;
    private Integer maxOutputTokens;
    private Integer timeoutMs;
    private Integer retryCount;
    private Integer concurrencyLimit;
    private BigDecimal costPer1kInput;
    private BigDecimal costPer1kOutput;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
