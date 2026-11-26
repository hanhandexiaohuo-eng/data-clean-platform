package com.dataclean.dto;

import lombok.Data;

@Data
public class DashboardItemDTO {
    private Long savedChartId;
    private Integer sortOrder;
    private Integer gridX;
    private Integer gridY;
    private Integer gridW;
    private Integer gridH;
}
