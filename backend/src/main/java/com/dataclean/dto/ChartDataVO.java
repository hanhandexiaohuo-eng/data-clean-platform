package com.dataclean.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 图表数据视图对象。
 *
 * <p>除数据本身外，显式返回数据版本、行数、聚合方式、截断/抽样与空态，
 * 便于前端准确解释“这张图代表什么数据”。
 */
@Data
public class ChartDataVO {

    private List<String> xAxisData;
    private List<SeriesData> series;
    /** 原始聚合结果（用于导出 CSV），不是全量原始数据。 */
    private List<Map<String, Object>> rawData;
    private Map<String, Object> statistics;
    /** 结果提示：数据点被截断/抽样/分桶时的说明，前端原样展示。 */
    private String notice;

    private String chartType;
    private Long datasetId;
    private Long dataVersionId;
    private Integer dataVersionNo;
    private Long rowCount;
    private String aggregation;
    private Boolean truncated;
    private Boolean sampled;
    private Integer sampleSize;
    private Boolean empty;
    private LocalDateTime generatedAt;

    @Data
    public static class SeriesData {
        private String name;
        private String type;
        private List<Object> data;
    }
}
