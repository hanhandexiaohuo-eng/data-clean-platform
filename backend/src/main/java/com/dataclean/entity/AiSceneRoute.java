package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_scene_route")
public class AiSceneRoute {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scene;
    private Long primaryModelId;
    private Long fallbackModelId;
    private BigDecimal temperature;
    private Integer maxOutputTokens;
    private Integer timeoutMs;
    private Integer retryCount;
    private Integer concurrencyLimit;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
