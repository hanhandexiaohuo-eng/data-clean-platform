package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_generation_log")
public class AiGenerationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String templateKey;
    private String contextType;
    private String inputSummary;
    private String prompt;
    private String response;
    private Integer tokensUsed;
    private Integer status;
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
