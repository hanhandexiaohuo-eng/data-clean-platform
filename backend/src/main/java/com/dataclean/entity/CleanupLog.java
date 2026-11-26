package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cleanup_log")
public class CleanupLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer sessionsCleaned;
    private Integer datasetsRemoved;
    private Integer tablesDropped;
    private Integer rowsRemoved;
    private Integer failures;
    private Integer orphanTablesReported;
    private String detail;
}
