package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 持久化异步任务。
 *
 * <p>状态机：
 * PENDING → RUNNING → SUCCEEDED
 *                    → FAILED → （重试）PENDING
 *                    → RECOVERABLE（进程重启时由 RUNNING 降级）
 * PENDING/RUNNING → CANCEL_REQUESTED → CANCELLED
 */
@Data
@TableName("async_job")
public class AsyncJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobType;
    private Long datasetId;
    private Long inputVersionId;
    private Long outputVersionId;
    private String status;
    private String phase;
    private Integer progress;
    private Long processedRows;
    private Long totalRows;
    private Integer retryCount;
    private Integer maxRetries;
    private String errorCode;
    private String errorSummary;
    /** 任务参数 JSON（临时文件路径、规则ID、导入参数等），不含任何凭据。 */
    @JsonIgnore
    private String payload;
    private String resultSummary;
    private String ownerType;
    @JsonIgnore
    private Long anonSessionId;
    @JsonIgnore
    private Long userId;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
