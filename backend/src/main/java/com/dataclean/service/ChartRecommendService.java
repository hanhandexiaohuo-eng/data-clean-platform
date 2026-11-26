package com.dataclean.service;

import com.dataclean.dto.ChartRecommendationVO;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.mapper.DatasetColumnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图表推荐：根据字段类型、唯一值数量与数据量给出保守、可解释的初始配置。
 *
 * <p>推荐只是起点，前端始终可以切换到自由配置；任何字段合法性仍由
 * {@link AnalysisService} 的服务端白名单再次校验。
 */
@Service
@RequiredArgsConstructor
public class ChartRecommendService {

    private static final int MAX_ITEMS = 6;
    private static final int CATEGORY_UNIQUE_LIMIT = 50;
    private static final int PIE_UNIQUE_LIMIT = 12;
    private static final int HEATMAP_UNIQUE_LIMIT = 20;

    private final DatasetColumnMapper datasetColumnMapper;
    private final OwnershipService ownershipService;

    public ChartRecommendationVO recommend(Long datasetId) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);
        ChartRecommendationVO result = new ChartRecommendationVO();
        List<ChartRecommendationVO.Item> items = new ArrayList<>();
        result.setItems(items);

        if (columns == null || columns.isEmpty()) {
            result.setNotice("数据集没有可用字段，无法生成推荐");
            return result;
        }

        List<DatasetColumn> dates = new ArrayList<>();
        List<DatasetColumn> numerics = new ArrayList<>();
        List<DatasetColumn> categories = new ArrayList<>();
        for (DatasetColumn column : columns) {
            if (isDate(column)) {
                dates.add(column);
            } else if (isNumeric(column)) {
                numerics.add(column);
            } else {
                categories.add(column);
            }
        }

        long rowCount = dataset.getRowCount() == null ? 0 : dataset.getRowCount();

        // 1. 趋势：日期 + 数值
        if (!dates.isEmpty() && !numerics.isEmpty()) {
            DatasetColumn date = dates.get(0);
            DatasetColumn value = numerics.get(0);
            items.add(item("line", date.getColumnAlias(), value.getColumnAlias(), "SUM",
                    rowCount <= 1000 ? "DAY" : "MONTH", null, null,
                    "按" + date.getColumnAlias() + "观察" + value.getColumnAlias() + "的趋势",
                    "high"));
        }

        // 2. 分类对比：分类唯一值 <= 50 + 数值
        DatasetColumn category = firstByUnique(categories, CATEGORY_UNIQUE_LIMIT);
        if (category != null) {
            DatasetColumn value = numerics.isEmpty() ? null : numerics.get(0);
            if (value != null) {
                items.add(item("bar", category.getColumnAlias(), value.getColumnAlias(), "SUM",
                        null, 20, null,
                        "对比不同" + category.getColumnAlias() + "的" + value.getColumnAlias() + "总和",
                        "high"));
            }

            // 3. 占比：分类唯一值 <= 12
            if (uniqueOf(category) > 0 && uniqueOf(category) <= PIE_UNIQUE_LIMIT) {
                items.add(item("pie", category.getColumnAlias(), null, "COUNT",
                        null, 12, null,
                        "查看" + category.getColumnAlias() + "的构成占比",
                        "high"));
            }
        }

        // 4. 分布：数值分桶
        if (!numerics.isEmpty()) {
            DatasetColumn value = numerics.get(0);
            items.add(item("bar", value.getColumnAlias(), null, "COUNT",
                    null, null, 20,
                    "查看" + value.getColumnAlias() + "的数值分布",
                    "medium"));
        }

        // 5. 相关性：两个数值
        if (numerics.size() >= 2) {
            DatasetColumn x = numerics.get(0);
            DatasetColumn y = numerics.get(1);
            items.add(item("scatter", x.getColumnAlias(), y.getColumnAlias(), "COUNT",
                    null, null, null,
                    "观察" + x.getColumnAlias() + "与" + y.getColumnAlias() + "的相关性",
                    "medium"));
        }

        // 6. 热力关系：两个低基数分类
        DatasetColumn heatX = firstByUnique(categories, HEATMAP_UNIQUE_LIMIT);
        DatasetColumn heatY = secondByUnique(categories, HEATMAP_UNIQUE_LIMIT);
        if (heatX != null && heatY != null) {
            items.add(item("heatmap", heatX.getColumnAlias(), heatY.getColumnAlias(), "COUNT",
                    null, null, null,
                    "观察" + heatX.getColumnAlias() + "与" + heatY.getColumnAlias() + "的交叉分布",
                    "low"));
        }

        if (items.size() > MAX_ITEMS) {
            items = new ArrayList<>(items.subList(0, MAX_ITEMS));
            result.setItems(items);
        }
        if (items.isEmpty()) {
            result.setNotice("可用字段不足以生成推荐，请使用自由配置");
        }
        return result;
    }

    private ChartRecommendationVO.Item item(String chartType, String x, String y, String aggregation,
                                            String timeGranularity, Integer topN, Integer bucketCount,
                                            String reason, String confidence) {
        ChartRecommendationVO.Item item = new ChartRecommendationVO.Item();
        item.setChartType(chartType);
        item.setXAxis(x);
        item.setYAxis(y);
        item.setAggregation(aggregation);
        item.setTimeGranularity(timeGranularity);
        item.setTopN(topN);
        item.setMode(bucketCount != null ? "distribution" : null);
        item.setTitle(reason);
        item.setReason(reason);
        item.setConfidence(confidence);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("chartType", chartType);
        config.put("xAxis", x);
        config.put("yAxis", y);
        config.put("aggregation", aggregation);
        if (timeGranularity != null) {
            config.put("timeGranularity", timeGranularity);
        }
        if (topN != null) {
            config.put("topN", topN);
        }
        if (bucketCount != null) {
            config.put("mode", "distribution");
            config.put("bucketCount", bucketCount);
        }
        item.setConfig(config);
        return item;
    }

    private DatasetColumn firstByUnique(List<DatasetColumn> columns, int maxUnique) {
        for (DatasetColumn column : columns) {
            if (uniqueOf(column) > 0 && uniqueOf(column) <= maxUnique) {
                return column;
            }
        }
        return null;
    }

    private DatasetColumn secondByUnique(List<DatasetColumn> columns, int maxUnique) {
        boolean first = false;
        for (DatasetColumn column : columns) {
            if (uniqueOf(column) > 0 && uniqueOf(column) <= maxUnique) {
                if (first) {
                    return column;
                }
                first = true;
            }
        }
        return null;
    }

    private int uniqueOf(DatasetColumn column) {
        return column.getUniqueCount() == null ? -1 : column.getUniqueCount();
    }

    private boolean isDate(DatasetColumn column) {
        String type = column.getDataType() == null ? "" : column.getDataType().toUpperCase();
        if (type.contains("DATE") || type.contains("TIME") || type.contains("TIMESTAMP")) {
            return true;
        }
        String name = (column.getColumnAlias() == null ? "" : column.getColumnAlias()).toLowerCase();
        return name.contains("date") || name.contains("time") || name.contains("day")
                || name.contains("month") || name.contains("year");
    }

    private boolean isNumeric(DatasetColumn column) {
        String type = column.getDataType() == null ? "" : column.getDataType().toUpperCase();
        return type.contains("INT") || type.contains("DECIMAL") || type.contains("DOUBLE")
                || type.contains("FLOAT") || type.contains("NUMERIC");
    }
}
