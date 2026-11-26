package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("export_record")
public class ExportRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long anonSessionId;
    private Long datasetId;
    private String exportType;
    private Integer rowCount;
    private LocalDateTime createdAt;
}
