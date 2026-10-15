package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("dataset_column")
public class DatasetColumn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String columnName;
    private String columnAlias;
    private String dataType;
    private String javaType;
    private Integer sortOrder;
    private Integer nullable;
    private Integer uniqueCount;
    private Integer nullCount;
    private String minValue;
    private String maxValue;
    private BigDecimal meanValue;
    private BigDecimal stdValue;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
