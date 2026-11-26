package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 备份表归属登记：用于保留策略与清理时精确定位，无法登记归属的表只上报不删除。
 */
@Data
@TableName("backup_table")
public class BackupTableRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Long versionId;
    private String tableName;
    private String checksum;
    private Long rowCount;
    private String ownerType;
    @JsonIgnore
    private Long anonSessionId;
    private Long jobId;
    private String state;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
