package com.dataclean.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 图表数据视图对象
 */
@Data
public class ChartDataVO {

    /**
     * X轴数据
     */
    private List<String> xAxisData;

    /**
     * 系列数据
     */
    private List<SeriesData> series;

    /**
     * 原始数据(用于下载)
     */
    private List<Map<String, Object>> rawData;

    /**
     * 统计信息
     */
    private Map<String, Object> statistics;

    /**
     * 系列数据
     */
    @Data
    public static class SeriesData {
        private String name;
        private String type;
        private List<Object> data;
    }
}
