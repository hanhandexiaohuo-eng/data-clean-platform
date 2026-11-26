package com.dataclean.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 图表生成请求 DTO。
 *
 * <p>所有字段进入 SQL 前必须经服务端白名单与参数绑定校验。
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

    /** 展示标题，仅用于前端展示，不进入 SQL。 */
    @JsonProperty("title")
    private String title;

    /** 颜色主题：default/warm/cool/dark/pastel。 */
    @JsonProperty("colorTheme")
    private String colorTheme;

    /** normal/distribution。distribution 仅对数值 X 轴做服务端分桶。 */
    @JsonProperty("mode")
    private String mode;

    /** AUTO/DAY/WEEK/MONTH/YEAR，仅允许日期列。 */
    @JsonProperty("timeGranularity")
    private String timeGranularity;

    /** 分类图 Top N（1-500）。 */
    @JsonProperty("topN")
    private Integer topN;

    /** 分布图分桶数（5-50）。 */
    @JsonProperty("bucketCount")
    private Integer bucketCount;

    /** 排序：field=x|y，direction=ASC|DESC。 */
    @JsonProperty("sort")
    private SortDTO sort;

    @Data
    public static class FilterConditionDTO {
        @JsonProperty("column")
        private String column;
        @JsonProperty("operator")
        private String operator;
        @JsonProperty("value")
        private String value;
    }

    @Data
    public static class SortDTO {
        /** x 或 y */
        @JsonProperty("field")
        private String field;
        /** ASC 或 DESC */
        @JsonProperty("direction")
        private String direction;
    }
}
