package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据质量真实统计报表。
 *
 * <p>status 语义：
 * COMPUTING（计算中）/ DONE（已采集）/ FAILED（失败）。
 * 无记录等价于“未采集”，不得用 0 或模拟数据代替。
 */
@Data
@TableName("data_quality_report")
public class DataQualityReport {

    public static final String STATUS_COMPUTING = "COMPUTING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Long versionId;
    private String status;
    private String columnsKey;
    /** 统计结果 JSON；仅包含聚合值与受限抽样示例，不含全量原始数据。 */
    @JsonIgnore
    private String payload;
    private LocalDateTime computedAt;
    private LocalDateTime expiresAt;
    private String errorSummary;
    private String ownerType;
    @JsonIgnore
    private Long anonSessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
