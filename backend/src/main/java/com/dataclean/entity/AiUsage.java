package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_usage")
public class AiUsage {

    public static final String SCOPE_PRIVATE = "private";
    public static final String SCOPE_PUBLIC_SESSION = "public_session";
    public static final String SCOPE_PUBLIC_IP = "public_ip";
    public static final String SCOPE_PUBLIC_GLOBAL = "public_global";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeType;
    private String scopeKeyHash;
    private String scene;
    private String modelCode;
    private String periodKey;
    private Long calls;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal cost;
    private LocalDateTime updatedAt;
}
