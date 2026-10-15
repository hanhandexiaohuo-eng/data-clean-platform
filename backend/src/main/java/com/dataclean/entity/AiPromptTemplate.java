package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_prompt_template")
public class AiPromptTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateKey;
    private String name;
    private String description;
    private String content;
    private String variables;
    private Integer isBuiltin;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
