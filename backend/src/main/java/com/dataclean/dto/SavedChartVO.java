package com.dataclean.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class SavedChartVO {
    private Long id;
    private Long datasetId;
    private String datasetName;
    private String title;
    private String chartType;
    private Map<String, Object> chartConfig;
    private Long savedVersionId;
    private Integer savedVersionNo;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private Boolean stale;
    /** 当前数据集中已不存在的字段。 */
    private List<String> missingFields;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
