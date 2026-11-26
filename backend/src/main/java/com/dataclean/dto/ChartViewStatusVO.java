package com.dataclean.dto;

import lombok.Data;

import java.util.List;

/**
 * 图表版本状态：数据已更新/字段失效时给出可恢复提示。
 */
@Data
public class ChartViewStatusVO {
    private Long savedChartId;
    private Long savedVersionId;
    private Integer savedVersionNo;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private Boolean stale;
    private List<String> missingFields;
    private String message;
}
