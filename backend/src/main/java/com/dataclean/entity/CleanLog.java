package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
