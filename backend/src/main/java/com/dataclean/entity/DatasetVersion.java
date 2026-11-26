package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据集版本。
 *
 * <p>每个版本对应一张独立物理表，清洗/恢复只新增或切换版本，不原地覆盖已有数据。
 */
@Data
@TableName("dataset_version")
public class DatasetVersion {

    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_CLEAN = "CLEAN";
    public static final String SOURCE_RESTORE = "RESTORE";
    public static final String SOURCE_BACKFILL = "BACKFILL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Integer versionNo;
    private String tableName;
    private Long rowCount;
    private Integer columnCount;
    private String checksum;
    private String status;
    private String source;
    private Long jobId;
    private String ownerType;
    /** 仅服务端使用，不返回前端。 */
    @JsonIgnore
    private Long anonSessionId;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime activatedAt;
}
