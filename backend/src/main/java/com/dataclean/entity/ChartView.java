package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chart_view")
public class ChartView {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long anonSessionId;
    private Long datasetId;
    private String chartType;
    private String chartConfig;
    private LocalDateTime createdAt;
}
