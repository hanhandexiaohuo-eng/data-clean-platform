package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_quota")
public class AiQuota {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeType;
    private String scene;
    private String modelCode;
    private String period;
    private Long maxCalls;
    private Long maxTokens;
    private BigDecimal maxCost;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
