package com.dataclean.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SavedChartDTO {
    private Long datasetId;
    private String title;
    private String chartType;
    /** 白名单化的图表配置。 */
    private Map<String, Object> chartConfig;
}
