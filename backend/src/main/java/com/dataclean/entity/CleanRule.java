package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("clean_rule")
public class CleanRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String ruleName;
    private String ruleType;
    private String ruleConfig;
    private Integer sortOrder;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
