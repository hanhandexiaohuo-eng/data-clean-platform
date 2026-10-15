package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("dataset")
public class Dataset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String tableName;
    private Integer rowCount;
    private Integer columnCount;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
