package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dashboard_item")
public class DashboardItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dashboardId;
    private Long savedChartId;
    private Integer sortOrder;
    private Integer gridX;
    private Integer gridY;
    private Integer gridW;
    private Integer gridH;
    private LocalDateTime createdAt;
}
