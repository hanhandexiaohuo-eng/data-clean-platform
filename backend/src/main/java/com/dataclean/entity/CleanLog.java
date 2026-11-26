package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("clean_log")
public class CleanLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String ruleIds;
    private String ruleSnapshot;
    private Integer beforeCount;
    private Integer afterCount;
    private Integer affectedRows;
    private String backupTable;
    private Integer status;
    private Integer executeTimeMs;
    private String errorMsg;
    /** 匿名会话ID，仅服务端归属校验使用，不返回前端 */
    @JsonIgnore
    private Long anonSessionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
