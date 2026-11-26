package com.dataclean.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 图表推荐结果。
 *
 * <p>config 可直接作为自由配置的初始值；用户始终可以修改或切换到其他图表类型。
 */
@Data
public class ChartRecommendationVO {

    private String chartType;
    private String xAxis;
    private String yAxis;
    private String groupBy;
    private String aggregation;
    private String timeGranularity;
    private Integer topN;
    private String mode;
    private String title;
    private String reason;
    /** high/medium/low */
    private String confidence;
    private Map<String, Object> config;

    private List<Item> items;
    private String notice;

    @Data
    public static class Item {
        private String chartType;
        private String xAxis;
        private String yAxis;
        private String aggregation;
        private String timeGranularity;
        private Integer topN;
        private String mode;
        private String title;
        private String reason;
        private String confidence;
        private Map<String, Object> config;
    }
}
