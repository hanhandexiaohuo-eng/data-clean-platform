package com.dataclean.dto;

import lombok.Data;

import java.util.List;

@Data
public class DashboardItemVO {
    private Long id;
    private Long dashboardId;
    private Long savedChartId;
    private String title;
    private String chartType;
    private Integer sortOrder;
    private Integer gridX;
    private Integer gridY;
    private Integer gridW;
    private Integer gridH;
    private Boolean stale;
    private List<String> missingFields;
    private Long savedVersionId;
    private Long currentVersionId;
    /** 引用已失效（图表或数据集已被删除）：前端应展示异常态并允许移除，而不是让整块看板 404。 */
    private Boolean invalid;
    private String invalidReason;
}
