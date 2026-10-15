package com.dataclean.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 图表生成请求DTO
 */
@Data
public class ChartRequestDTO {

    @JsonProperty("datasetId")
    private Long datasetId;

    @JsonProperty("chartType")
    private String chartType;

    @JsonProperty("xAxis")
    private String xAxis;

    @JsonProperty("yAxis")
    private String yAxis;

    @JsonProperty("groupBy")
    private String groupBy;

    @JsonProperty("aggregation")
    private String aggregation;

    @JsonProperty("filters")
    private List<FilterConditionDTO> filters;

    @JsonProperty("limit")
    private Integer limit = 100;

    @Data
    public static class FilterConditionDTO {
        @JsonProperty("column")
        private String column;
        @JsonProperty("operator")
        private String operator;
        @JsonProperty("value")
        private String value;
    }
}
