package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** 匿名会话ID，仅服务端归属校验使用，不返回前端 */
    @JsonIgnore
    private Long anonSessionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
