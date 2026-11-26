package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计。只记录操作类型、资源标识、结果与脱敏摘要。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String action;
    private String resourceType;
    private Long resourceId;
    private Long datasetId;
    private Long jobId;
    private Long versionId;
    private String ownerType;
    private Long anonSessionId;
    private Long userId;
    private String result;
    private String errorType;
    private String detail;
    private LocalDateTime createdAt;
}
