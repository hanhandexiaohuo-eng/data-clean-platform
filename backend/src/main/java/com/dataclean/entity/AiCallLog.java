package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用审计。
 *
 * <p>严禁写入：明文 Key、完整 Prompt、完整模型响应、Cookie、Token、原始敏感数据。
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_DEGRADED = "DEGRADED";
    public static final String RESULT_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scene;
    private String providerName;
    private String modelCode;
    private String principalHash;
    private Long datasetId;
    private Long datasetVersionId;
    private Long savedChartId;
    private LocalDateTime requestAt;
    private Integer latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal cost;
    private String result;
    private String errorType;
    private Integer degraded;
    private String quotaScope;
    private LocalDateTime createdAt;
}
