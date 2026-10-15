package com.dataclean.service;

import com.dataclean.dto.ChartDataVO;
import com.dataclean.dto.ChartRequestDTO;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据分析业务服务层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;

    /** 简单内存缓存：key = datasetId, value = column list */
    private final Map<Long, List<DatasetColumn>> columnCache = new ConcurrentHashMap<>();

    /**
     * 生成图表数据
     */
    public ChartDataVO generateChartData(ChartRequestDTO requestDTO) {
        Dataset dataset = datasetMapper.selectById(requestDTO.getDatasetId());
        if (dataset == null) {
            throw new BusinessException("数据集不存在");
        }

        String tableName = dataset.getTableName();
        String chartType = requestDTO.getChartType();

        // 校验并获取合法列名映射
        Map<String, String> validColumns = getValidColumnMap(requestDTO.getDatasetId());

        // 校验请求中的列名安全
        validateColumn(requestDTO.getXAxis(), validColumns, "X轴");
        validateColumn(requestDTO.getYAxis(), validColumns, "Y轴");
        validateColumn(requestDTO.getGroupBy(), validColumns, "分组");
        if (requestDTO.getFilters() != null) {
            for (ChartRequestDTO.FilterConditionDTO filter : requestDTO.getFilters()) {
                validateColumn(filter.getColumn(), validColumns, "过滤");
            }
        }

        if (requestDTO.getLimit() == null || requestDTO.getLimit() <= 0) {
            requestDTO.setLimit(100);
        }
        if (requestDTO.getLimit() > 5000) {
            requestDTO.setLimit(5000);
        }

        return switch (chartType) {
            case "bar", "line" -> generateBarOrLineData(requestDTO, tableName);
            case "pie" -> generatePieData(requestDTO, tableName);
            case "scatter" -> generateScatterData(requestDTO, tableName);
            case "heatmap" -> generateHeatmapData(requestDTO, tableName);
            default -> throw new BusinessException("不支持的图表类型: " + chartType);
        };
    }

    /**
     * 获取合法列名映射（带缓存）
     * key = columnAlias, value = columnName
     */
    private Map<String, String> getValidColumnMap(Long datasetId) {
        List<DatasetColumn> columns = columnCache.computeIfAbsent(datasetId, id -> {
            List<DatasetColumn> cols = datasetColumnMapper.selectByDatasetId(id);
            return cols != null ? cols : Collections.emptyList();
        });
        Map<String, String> map = new LinkedHashMap<>();
        for (DatasetColumn col : columns) {
            map.put(col.getColumnAlias(), col.getColumnName());
        }
        return map;
    }

    /**
     * 校验列名合法性，防止SQL注入
     */
    private void validateColumn(String column, Map<String, String> validColumns, String label) {
        if (column == null || column.isEmpty()) return;
        if (!validColumns.containsKey(column)) {
            throw new BusinessException(label + "字段 '" + column + "' 不存在于数据集中");
        }
    }

    /**
     * 获取统计数据（使用缓存避免重复查询列信息）
     */
    public Map<String, Object> getStatistics(Long datasetId) {
        Dataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BusinessException("数据集不存在");
        }

        List<DatasetColumn> columns = columnCache.computeIfAbsent(datasetId, id -> {
            List<DatasetColumn> cols = datasetColumnMapper.selectByDatasetId(id);
            return cols != null ? cols : Collections.emptyList();
        });
        String tableName = dataset.getTableName();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", dataset.getRowCount());
        result.put("columnCount", dataset.getColumnCount());

        List<Map<String, Object>> columnStats = new ArrayList<>();
        for (DatasetColumn col : columns) {
            Map<String, Object> colStat = new LinkedHashMap<>();
            colStat.put("columnName", col.getColumnName());
            colStat.put("dataType", col.getDataType());
            colStat.put("nullCount", col.getNullCount());
            colStat.put("nullRate", dataset.getRowCount() > 0
                    ? BigDecimal.valueOf((double) col.getNullCount() / dataset.getRowCount() * 100)
                    .setScale(2, RoundingMode.HALF_UP) + "%"
                    : "0%");
            colStat.put("uniqueCount", col.getUniqueCount());

            // 数值字段添加详细统计
            if (col.getDataType().contains("INT") || col.getDataType().contains("DECIMAL")
                    || col.getDataType().contains("DOUBLE") || col.getDataType().contains("FLOAT")) {
                try {
                    Map<String, Object> calcStats = datasetMapper.selectColumnStatistics(
                            tableName, col.getColumnAlias());
                    if (calcStats != null) {
                        colStat.putAll(calcStats);

                        // 计算分位数
                        List<Map<String, Object>> sortedValues = datasetMapper.selectColumnValues(
                                tableName, col.getColumnAlias());
                        if (!sortedValues.isEmpty()) {
                            int size = sortedValues.size();
                            colStat.put("q1", sortedValues.get(size / 4).get("val"));
                            colStat.put("median", sortedValues.get(size / 2).get("val"));
                            colStat.put("q3", sortedValues.get(size * 3 / 4).get("val"));
                        }
                    }
                } catch (Exception e) {
                    log.warn("计算统计信息失败: {}", col.getColumnName());
                }
            }

            columnStats.add(colStat);
        }

        result.put("columns", columnStats);
        return result;
    }

    /**
     * 生成柱状图/折线图数据
     */
    private ChartDataVO generateBarOrLineData(ChartRequestDTO request, String tableName) {
        String xAxis = request.getXAxis();
        String yAxis = request.getYAxis();
        String aggregation = request.getAggregation();
        String groupBy = request.getGroupBy();

        String aggFunc = aggregation != null ? aggregation : "SUM";

        StringBuilder sql = new StringBuilder("SELECT ");
        if (groupBy != null && !groupBy.isEmpty()) {
            sql.append("`").append(groupBy).append("` as group_col, ");
        }
        sql.append("`").append(xAxis).append("` as x_val, ");
        sql.append(aggFunc).append("(`").append(yAxis).append("`) as y_val ");
        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        sql.append("AND `").append(yAxis).append("` IS NOT NULL ");

        // 添加过滤条件
        appendFilters(sql, request.getFilters());

        sql.append("GROUP BY ");
        if (groupBy != null && !groupBy.isEmpty()) {
            sql.append("`").append(groupBy).append("`, ");
        }
        sql.append("`").append(xAxis).append("` ");
        sql.append("ORDER BY y_val DESC ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSql(sql.toString());

        ChartDataVO vo = new ChartDataVO();

        // 处理分组数据
        if (groupBy != null && !groupBy.isEmpty()) {
            // 按分组组织数据
            Map<String, List<Map<String, Object>>> groupedData = data.stream()
                    .collect(Collectors.groupingBy(r -> String.valueOf(r.getOrDefault("group_col", "default"))));

            Set<String> xSet = new LinkedHashSet<>();
            List<ChartDataVO.SeriesData> seriesList = new ArrayList<>();

            for (Map.Entry<String, List<Map<String, Object>>> entry : groupedData.entrySet()) {
                List<String> xData = new ArrayList<>();
                List<Object> yData = new ArrayList<>();

                for (Map<String, Object> row : entry.getValue()) {
                    String x = String.valueOf(row.get("x_val"));
                    xData.add(x);
                    xSet.add(x);
                    yData.add(row.get("y_val"));
                }

                ChartDataVO.SeriesData series = new ChartDataVO.SeriesData();
                series.setName(entry.getKey());
                series.setType(request.getChartType());
                series.setData(yData);
                seriesList.add(series);
            }

            vo.setXAxisData(new ArrayList<>(xSet));
            vo.setSeries(seriesList);
        } else {
            List<String> xData = data.stream()
                    .map(r -> String.valueOf(r.get("x_val")))
                    .collect(Collectors.toList());
            List<Object> yData = data.stream()
                    .map(r -> r.get("y_val"))
                    .collect(Collectors.toList());

            ChartDataVO.SeriesData series = new ChartDataVO.SeriesData();
            series.setName(yAxis);
            series.setType(request.getChartType());
            series.setData(yData);

            vo.setXAxisData(xData);
            vo.setSeries(List.of(series));
        }

        vo.setRawData(data);
        vo.setStatistics(calculateBasicStats(data, "y_val"));

        return vo;
    }

    /**
     * 生成饼图数据
     */
    private ChartDataVO generatePieData(ChartRequestDTO request, String tableName) {
        String xAxis = request.getXAxis();
        String yAxis = request.getYAxis();
        String aggregation = request.getAggregation() != null ? request.getAggregation() : "COUNT";

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("`").append(xAxis).append("` as name, ");

        if ("COUNT".equals(aggregation)) {
            sql.append("COUNT(*) as value ");
        } else {
            sql.append(aggregation).append("(`").append(yAxis).append("`) as value ");
        }

        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");

        appendFilters(sql, request.getFilters());

        sql.append("GROUP BY `").append(xAxis).append("` ");
        sql.append("ORDER BY value DESC ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSql(sql.toString());

        ChartDataVO vo = new ChartDataVO();
        List<String> xData = data.stream()
                .map(r -> String.valueOf(r.get("name")))
                .collect(Collectors.toList());
        vo.setXAxisData(xData);
        vo.setRawData(data);

        // 饼图系列数据
        List<Object> pieData = data.stream()
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", r.get("name"));
                    item.put("value", r.get("value"));
                    return item;
                })
                .collect(Collectors.toList());

        ChartDataVO.SeriesData series = new ChartDataVO.SeriesData();
        series.setName(xAxis);
        series.setType("pie");
        series.setData(pieData);
        vo.setSeries(List.of(series));

        return vo;
    }

    /**
     * 生成散点图数据
     */
    private ChartDataVO generateScatterData(ChartRequestDTO request, String tableName) {
        String xAxis = request.getXAxis();
        String yAxis = request.getYAxis();

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("`").append(xAxis).append("` as x_val, ");
        sql.append("`").append(yAxis).append("` as y_val ");
        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        sql.append("AND `").append(yAxis).append("` IS NOT NULL ");

        appendFilters(sql, request.getFilters());

        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSql(sql.toString());

        ChartDataVO vo = new ChartDataVO();
        List<Object> scatterData = data.stream()
                .map(r -> {
                    List<Object> point = new ArrayList<>();
                    point.add(r.get("x_val"));
                    point.add(r.get("y_val"));
                    return point;
                })
                .collect(Collectors.toList());

        List<String> xData = data.stream()
                .map(r -> String.valueOf(r.get("x_val")))
                .collect(Collectors.toList());
        vo.setXAxisData(xData);

        ChartDataVO.SeriesData series = new ChartDataVO.SeriesData();
        series.setName(xAxis + " vs " + yAxis);
        series.setType("scatter");
        series.setData(scatterData);
        vo.setSeries(List.of(series));
        vo.setRawData(data);

        return vo;
    }

    /**
     * 生成热力图数据
     */
    private ChartDataVO generateHeatmapData(ChartRequestDTO request, String tableName) {
        String xAxis = request.getXAxis();
        String yAxis = request.getYAxis();

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("`").append(xAxis).append("` as x_val, ");
        sql.append("`").append(yAxis).append("` as y_val, ");
        sql.append("COUNT(*) as count ");
        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        sql.append("AND `").append(yAxis).append("` IS NOT NULL ");

        appendFilters(sql, request.getFilters());

        sql.append("GROUP BY `").append(xAxis).append("`, `").append(yAxis).append("` ");
        sql.append("ORDER BY count DESC ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSql(sql.toString());

        ChartDataVO vo = new ChartDataVO();

        // 获取唯一值
        Set<String> xSet = new LinkedHashSet<>();
        Set<String> ySet = new LinkedHashSet<>();

        for (Map<String, Object> row : data) {
            xSet.add(String.valueOf(row.get("x_val")));
            ySet.add(String.valueOf(row.get("y_val")));
        }

        List<String> xList = new ArrayList<>(xSet);
        List<String> yList = new ArrayList<>(ySet);

        // 构建热力图数据 [xIndex, yIndex, value]
        List<Object> heatmapData = data.stream()
                .map(r -> {
                    List<Object> point = new ArrayList<>();
                    point.add(xList.indexOf(String.valueOf(r.get("x_val"))));
                    point.add(yList.indexOf(String.valueOf(r.get("y_val"))));
                    point.add(r.get("count"));
                    return point;
                })
                .collect(Collectors.toList());

        vo.setXAxisData(xList);

        ChartDataVO.SeriesData series = new ChartDataVO.SeriesData();
        series.setName("热力分布");
        series.setType("heatmap");
        series.setData(heatmapData);
        vo.setSeries(List.of(series));
        vo.setRawData(data);

        // 保存y轴数据到statistics
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("yAxisData", yList);
        vo.setStatistics(stats);

        return vo;
    }

    /**
     * 追加过滤条件
     */
    private void appendFilters(StringBuilder sql, List<ChartRequestDTO.FilterConditionDTO> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (ChartRequestDTO.FilterConditionDTO filter : filters) {
            String operator = switch (filter.getOperator()) {
                case "eq" -> "=";
                case "ne" -> "!=";
                case "gt" -> ">";
                case "gte" -> ">=";
                case "lt" -> "<";
                case "lte" -> "<=";
                case "like" -> "LIKE";
                default -> "=";
            };

            String value = filter.getValue();
            if ("LIKE".equals(operator)) {
                value = "'%" + value.replace("'", "''") + "%'";
            } else {
                value = "'" + value.replace("'", "''") + "'";
            }

            sql.append("AND `").append(filter.getColumn()).append("` ")
                    .append(operator).append(" ").append(value).append(" ");
        }
    }

    /**
     * 计算基础统计量
     */
    private Map<String, Object> calculateBasicStats(List<Map<String, Object>> data, String valueKey) {
        if (data.isEmpty()) {
            return Map.of();
        }

        List<Double> values = data.stream()
                .map(r -> {
                    Object v = r.get(valueKey);
                    if (v instanceof Number) {
                        return ((Number) v).doubleValue();
                    }
                    return 0.0;
                })
                .toList();

        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double avg = sum / values.size();
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);

        // 方差
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - avg, 2))
                .average().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", values.size());
        stats.put("sum", BigDecimal.valueOf(sum).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("mean", BigDecimal.valueOf(avg).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("variance", BigDecimal.valueOf(variance).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("max", BigDecimal.valueOf(max).setScale(2, java.math.RoundingMode.HALF_UP));
        stats.put("min", BigDecimal.valueOf(min).setScale(2, java.math.RoundingMode.HALF_UP));

        return stats;
    }
}
