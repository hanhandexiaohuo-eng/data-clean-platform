package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.dto.ChartDataVO;
import com.dataclean.dto.ChartRequestDTO;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.entity.StatsCacheEntry;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.StatsCacheMapper;
import com.dataclean.util.SqlGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据分析和图表统计业务服务。
 *
 * <p>所有进入 SQL 的标识符（表名、列名、聚合函数、操作符）都必须先经过
 * {@link SqlGuard} 白名单校验；所有过滤值统一使用 {@code #{params[n]}} 参数绑定，
 * 不再拼接字符串。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final int DEFAULT_LIMIT = 100;
    /** 图表单次返回的最大数据点，避免大结果集拖垮浏览器与接口。 */
    private static final int CHART_MAX_POINTS = 2000;
    /** 超过该行数的数据集统计使用受限抽样，并标记为近似结果。 */
    private static final long STATS_SAMPLE_THRESHOLD = 100_000L;
    private static final int STATS_SAMPLE_ROWS = 50_000;
    /** 分位数只在受限样本上计算，避免整列排序与内存占用。 */
    private static final int QUANTILE_SAMPLE_ROWS = 5_000;
    private static final int STATS_CACHE_MINUTES = 10;
    private static final int TOP_N_MAX = 500;
    private static final int BUCKET_DEFAULT = 20;
    private static final int BUCKET_MAX = 50;
    private static final Set<String> TIME_GRANULARITIES = Set.of("AUTO", "DAY", "WEEK", "MONTH", "YEAR");
    private static final Set<String> COLOR_THEMES = Set.of("default", "warm", "cool", "dark", "pastel");

    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final OwnershipService ownershipService;
    private final PublicQuotaService publicQuotaService;
    private final JobService jobService;
    private final StatsCacheMapper statsCacheMapper;
    private final ObjectMapper objectMapper;

    /**
     * 生成图表数据（用户直接请求，记录 CHART 任务）。
     */
    public ChartDataVO generateChartData(ChartRequestDTO requestDTO) {
        return generateChartData(requestDTO, true);
    }

    /**
     * 生成图表数据。
     *
     * @param recordJob 是否登记独立 CHART 任务；导出流程内部调用时传 false，
     *                  避免在 EXPORT 任务运行期间再创建同类活跃任务触发会话并发配额。
     */
    public ChartDataVO generateChartData(ChartRequestDTO requestDTO, boolean recordJob) {
        if (requestDTO == null || requestDTO.getDatasetId() == null) {
            throw new BusinessException(400, "datasetId 不能为空");
        }
        // 统一归属校验：其他会话/私有数据统一 404
        Dataset dataset = ownershipService.requireDataset(requestDTO.getDatasetId());
        if (!recordJob) {
            return doGenerateChartData(requestDTO, dataset);
        }
        String requestedType = requestDTO.getChartType() == null ? "unknown" : requestDTO.getChartType();
        AsyncJob job = jobService.create(JobService.TYPE_CHART, dataset, dataset.getCurrentVersionId(), null,
                Map.of("chartType", requestedType, "mode",
                        requestDTO.getMode() == null ? "aggregate" : requestDTO.getMode()));
        jobService.begin(job);
        try {
            ChartDataVO vo = doGenerateChartData(requestDTO, dataset);
            jobService.markSucceeded(job, null, 0L,
                    "图表类型=" + requestedType + (vo == null ? "" : "，数据点=" + chartPointCount(vo)));
            return vo;
        } catch (BusinessException e) {
            jobService.markFailedSafely(job,
                    e.getCode() == 429 ? "QUOTA_EXCEEDED" : "CHART_GENERATION_FAILED", e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("图表生成失败: datasetId={}, error={}", requestDTO.getDatasetId(),
                    e.getClass().getSimpleName());
            BusinessException safe = new BusinessException(500, "图表生成失败，请稍后重试");
            jobService.markFailedSafely(job, "CHART_GENERATION_FAILED", safe);
            throw safe;
        }
    }

    private long chartPointCount(ChartDataVO vo) {
        if (vo.getSeries() == null) {
            return 0;
        }
        return vo.getSeries().stream()
                .filter(series -> series.getData() != null)
                .mapToLong(series -> series.getData().size())
                .sum();
    }

    private ChartDataVO doGenerateChartData(ChartRequestDTO requestDTO, Dataset dataset) {
        // 公开模式图表生成配额（演示数据允许生成图表）
        publicQuotaService.checkChart();

        String tableName = SqlGuard.requireTableName(dataset.getTableName());
        String chartType = SqlGuard.requireChartType(requestDTO.getChartType());

        Map<String, String> validColumns = getValidColumnMap(requestDTO.getDatasetId());
        if (validColumns.isEmpty()) {
            throw new BusinessException(400, "数据集没有可用字段");
        }
        Map<String, DatasetColumn> columnMeta = getColumnMeta(requestDTO.getDatasetId());

        validateColumn(requestDTO.getXAxis(), validColumns, "X轴");
        validateColumn(requestDTO.getYAxis(), validColumns, "Y轴");
        validateColumn(requestDTO.getGroupBy(), validColumns, "分组");
        if (requestDTO.getFilters() != null) {
            for (ChartRequestDTO.FilterConditionDTO filter : requestDTO.getFilters()) {
                if (filter == null) {
                    throw new BusinessException(400, "过滤条件不能为空");
                }
                validateColumn(filter.getColumn(), validColumns, "过滤");
                SqlGuard.requireFilterOperator(filter.getOperator());
            }
        }

        requestDTO.setLimit(SqlGuard.clampLimit(requestDTO.getLimit(), DEFAULT_LIMIT, CHART_MAX_POINTS));
        validateExtendedConfig(requestDTO, chartType, columnMeta, dataset);
        int effectiveLimit = resolveEffectiveLimit(requestDTO, chartType, columnMeta, dataset);
        requestDTO.setLimit(effectiveLimit);

        boolean distribution = "distribution".equalsIgnoreCase(requestDTO.getMode());
        ChartDataVO chartData;
        if (distribution) {
            chartData = generateDistributionData(requestDTO, tableName);
        } else {
            chartData = switch (chartType) {
                case "bar", "line", "area" -> generateBarOrLineData(requestDTO, tableName);
                case "pie" -> generatePieData(requestDTO, tableName);
                case "scatter" -> generateScatterData(requestDTO, tableName);
                case "heatmap" -> generateHeatmapData(requestDTO, tableName);
                default -> throw new BusinessException(400, "不支持的图表类型");
            };
        }
        applyMetadata(chartData, dataset, requestDTO, distribution);

        // 记录公开模式图表生成次数（私有模式为空操作）
        try {
            publicQuotaService.recordChart(requestDTO.getDatasetId(), chartType,
                    objectMapper.writeValueAsString(requestDTO));
        } catch (Exception e) {
            log.debug("记录图表视图失败: {}", e.getClass().getSimpleName());
        }
        return chartData;
    }

    /**
     * 获取合法列名映射
     * key = columnAlias, value = columnName
     *
     * <p>不再使用进程内缓存：改用数据库按 dataset_id 索引查询，避免版本切换后读到旧结构。
     */
    private Map<String, String> getValidColumnMap(Long datasetId) {
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);
        Map<String, String> map = new LinkedHashMap<>();
        if (columns != null) {
            for (DatasetColumn col : columns) {
                map.put(col.getColumnAlias(), col.getColumnName());
            }
        }
        return map;
    }

    /**
     * 校验列名合法性：必须存在于数据集元数据中。
     */
    private void validateColumn(String column, Map<String, String> validColumns, String label) {
        if (column == null || column.isEmpty()) {
            return;
        }
        if (!validColumns.containsKey(column)) {
            throw new BusinessException(400, label + "字段 '" + column + "' 不存在于数据集中");
        }
        SqlGuard.requireIdentifier(column, label + "字段");
    }

    /**
     * 获取列元数据映射（列别名 -> 列信息）。
     */
    private Map<String, DatasetColumn> getColumnMeta(Long datasetId) {
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);
        Map<String, DatasetColumn> map = new LinkedHashMap<>();
        if (columns != null) {
            for (DatasetColumn col : columns) {
                map.put(col.getColumnAlias(), col);
            }
        }
        return map;
    }

    /**
     * 扩展配置校验：Top N、时间粒度、分桶、排序、主题与标题。
     */
    private void validateExtendedConfig(ChartRequestDTO request, String chartType,
                                        Map<String, DatasetColumn> columnMeta, Dataset dataset) {
        if (request.getTitle() != null && request.getTitle().length() > 120) {
            throw new BusinessException(400, "图表标题不能超过 120 个字符");
        }
        if (request.getColorTheme() != null && !request.getColorTheme().isBlank()
                && !COLOR_THEMES.contains(request.getColorTheme())) {
            throw new BusinessException(400, "不支持的颜色主题");
        }
        if (request.getTopN() != null && (request.getTopN() < 1 || request.getTopN() > TOP_N_MAX)) {
            throw new BusinessException(400, "Top N 必须在 1-" + TOP_N_MAX + " 之间");
        }
        if (request.getBucketCount() != null
                && (request.getBucketCount() < 5 || request.getBucketCount() > BUCKET_MAX)) {
            throw new BusinessException(400, "分桶数必须在 5-" + BUCKET_MAX + " 之间");
        }
        if (request.getSort() != null) {
            String field = request.getSort().getField();
            if (field == null || (!"x".equalsIgnoreCase(field) && !"y".equalsIgnoreCase(field))) {
                throw new BusinessException(400, "排序字段只能是 x 或 y");
            }
            SqlGuard.requireSortDirection(request.getSort().getDirection());
        }
        String mode = request.getMode();
        if (mode != null && !mode.isBlank()
                && !"normal".equalsIgnoreCase(mode) && !"distribution".equalsIgnoreCase(mode)) {
            throw new BusinessException(400, "不支持的图表模式");
        }
        validateYAxisAggregation(request, chartType, columnMeta);
        if ("distribution".equalsIgnoreCase(mode)) {
            if (!"bar".equals(chartType) && !"line".equals(chartType) && !"area".equals(chartType)) {
                throw new BusinessException(400, "分布图仅支持柱状图/折线图");
            }
            DatasetColumn xColumn = columnMeta.get(request.getXAxis());
            if (xColumn == null || !isNumeric(xColumn.getDataType())) {
                throw new BusinessException(400, "分布图的 X 轴必须是数值字段");
            }
        }
        String granularity = request.getTimeGranularity();
        if (granularity != null && !granularity.isBlank()) {
            String normalized = granularity.trim().toUpperCase(Locale.ROOT);
            if (!TIME_GRANULARITIES.contains(normalized)) {
                throw new BusinessException(400, "不支持的时间粒度");
            }
            DatasetColumn xColumn = columnMeta.get(request.getXAxis());
            if (xColumn == null || !isDateColumn(xColumn)) {
                throw new BusinessException(400, "时间粒度只能用于日期字段");
            }
            if ("AUTO".equals(normalized)) {
                long rows = dataset.getRowCount() == null ? 0 : dataset.getRowCount();
                normalized = rows <= 1000 ? "DAY" : "MONTH";
            }
            request.setTimeGranularity(normalized);
        }
    }

    /**
     * 指标字段类型校验：SUM/AVG/MIN/MAX 只对数值字段有意义。
     * 文本字段直接执行 SUM 会被 MySQL 转成 0 并静默返回全 0 结果，这里改为明确报错；
     * 文本字段仍支持 COUNT（计数），数值字段的原有统计行为不变。
     */
    private void validateYAxisAggregation(ChartRequestDTO request, String chartType,
                                          Map<String, DatasetColumn> columnMeta) {
        if (!Set.of("bar", "line", "area", "pie").contains(chartType)) {
            return;
        }
        String yAxis = request.getYAxis();
        if (yAxis == null || yAxis.isBlank()) {
            return;
        }
        String aggregation = request.getAggregation();
        if (aggregation == null || aggregation.isBlank()) {
            aggregation = "pie".equals(chartType) ? "COUNT" : "SUM";
        }
        String normalized = aggregation.trim().toUpperCase(Locale.ROOT);
        if ("COUNT".equals(normalized)) {
            return;
        }
        DatasetColumn yColumn = columnMeta.get(yAxis);
        if (yColumn != null && !isNumeric(yColumn.getDataType())) {
            throw new BusinessException(400, "字段 " + yAxis
                    + " 为文本类型，仅支持 COUNT 统计；求和/平均等指标请选择数值字段");
        }
    }

    /**
     * 计算实际返回的数据点上限：分类图默认 Top N，时间序列与散点图受 2000 点硬上限保护。
     */
    private int resolveEffectiveLimit(ChartRequestDTO request, String chartType,
                                      Map<String, DatasetColumn> columnMeta, Dataset dataset) {
        int requested = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
        if ("distribution".equalsIgnoreCase(request.getMode())) {
            return request.getBucketCount() == null ? BUCKET_DEFAULT : request.getBucketCount();
        }
        if ("pie".equals(chartType)) {
            return request.getTopN() == null ? Math.min(requested, 12) : request.getTopN();
        }
        if ("scatter".equals(chartType)) {
            return Math.min(requested, CHART_MAX_POINTS);
        }
        if ("heatmap".equals(chartType)) {
            return Math.min(requested, 500);
        }
        DatasetColumn xColumn = columnMeta.get(request.getXAxis());
        boolean dateAxis = (xColumn != null && isDateColumn(xColumn)) || request.getTimeGranularity() != null;
        if (dateAxis) {
            return Math.min(Math.max(requested, 200), CHART_MAX_POINTS);
        }
        return request.getTopN() == null ? Math.min(requested, 50) : request.getTopN();
    }

    private String buildXExpression(ChartRequestDTO request) {
        String granularity = request.getTimeGranularity();
        String column = "`" + request.getXAxis() + "`";
        if (granularity == null || granularity.isBlank()) {
            return column;
        }
        return switch (granularity) {
            case "DAY" -> "DATE_FORMAT(" + column + ",'%Y-%m-%d')";
            case "WEEK" -> "DATE_FORMAT(" + column + ",'%x-%v')";
            case "MONTH" -> "DATE_FORMAT(" + column + ",'%Y-%m')";
            case "YEAR" -> "DATE_FORMAT(" + column + ",'%Y')";
            default -> column;
        };
    }

    private String resolveOrderBy(ChartRequestDTO request, boolean dateAxis) {
        ChartRequestDTO.SortDTO sort = request.getSort();
        if (sort != null && "x".equalsIgnoreCase(sort.getField())) {
            return "x_val " + SqlGuard.requireSortDirection(sort.getDirection());
        }
        if (sort != null && "y".equalsIgnoreCase(sort.getField())) {
            return "y_val " + SqlGuard.requireSortDirection(sort.getDirection());
        }
        return dateAxis ? "x_val ASC" : "y_val DESC";
    }

    private boolean isDateColumn(DatasetColumn column) {
        String type = column.getDataType() == null ? "" : column.getDataType().toUpperCase(Locale.ROOT);
        if (type.contains("DATE") || type.contains("TIME") || type.contains("TIMESTAMP")) {
            return true;
        }
        String name = (column.getColumnAlias() == null ? "" : column.getColumnAlias()).toLowerCase(Locale.ROOT);
        return name.contains("date") || name.contains("time") || name.contains("day")
                || name.contains("month") || name.contains("year");
    }

    /**
     * 填充图表解释所需的元信息：数据版本、行数、聚合、截断/抽样/空态。
     */
    private void applyMetadata(ChartDataVO vo, Dataset dataset, ChartRequestDTO request, boolean distribution) {
        vo.setChartType(request.getChartType());
        vo.setDatasetId(dataset.getId());
        vo.setDataVersionId(dataset.getCurrentVersionId());
        vo.setGeneratedAt(LocalDateTime.now());
        long rowCount = dataset.getRowCount() == null ? 0 : dataset.getRowCount();
        vo.setRowCount(rowCount);
        if (dataset.getCurrentVersionId() != null) {
            DatasetVersion version = datasetVersionMapper.selectById(dataset.getCurrentVersionId());
            if (version != null) {
                vo.setDataVersionNo(version.getVersionNo());
                if (version.getRowCount() != null) {
                    vo.setRowCount(version.getRowCount());
                }
            }
        }
        String aggregation = request.getAggregation();
        if (aggregation == null || aggregation.isBlank()) {
            aggregation = "pie".equals(request.getChartType()) ? "COUNT" : "SUM";
        }
        vo.setAggregation(aggregation.toUpperCase(Locale.ROOT));

        int points = vo.getRawData() == null ? 0 : vo.getRawData().size();
        vo.setEmpty(points == 0);
        vo.setTruncated(false);
        vo.setSampled(false);
        vo.setSampleSize(points);

        if (distribution) {
            vo.setNotice("已按 " + request.getLimit() + " 个区间分桶聚合，非逐行原始数据");
            return;
        }
        if ("scatter".equals(request.getChartType())) {
            if (points > 0 && points >= request.getLimit() && rowCount > request.getLimit()) {
                vo.setSampled(true);
                vo.setSampleSize(points);
                vo.setNotice("散点图按 " + points + " 个点抽样展示，非全量数据");
            }
            return;
        }
        if (points > 0 && points >= request.getLimit()) {
            vo.setTruncated(true);
            vo.setNotice("返回数据点已达上限 " + request.getLimit()
                    + "，请使用 Top N、聚合或分桶后再可视化");
        }
        if (points == 0) {
            vo.setNotice("当前条件下没有数据，请调整字段或过滤条件");
        }
    }

    /**
     * 服务端分桶分布：先取 MIN/MAX，再按参数绑定分桶，避免把整列数据拉入应用内存。
     */
    private ChartDataVO generateDistributionData(ChartRequestDTO request, String tableName) {
        String xAxis = request.getXAxis();
        StringBuilder rangeSql = new StringBuilder("SELECT MIN(`").append(xAxis).append("`) AS min_val, ")
                .append("MAX(`").append(xAxis).append("`) AS max_val ")
                .append("FROM `").append(tableName).append("` ")
                .append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        List<Object> rangeParams = new ArrayList<>();
        appendFilters(rangeSql, rangeParams, request.getFilters());
        List<Map<String, Object>> rangeRows = datasetMapper.selectDynamicSqlWithParams(rangeSql.toString(), rangeParams);

        ChartDataVO vo = new ChartDataVO();
        String notice = null;
        List<Map<String, Object>> data = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Object> counts = new ArrayList<>();

        double min = 0;
        double max = 0;
        if (!rangeRows.isEmpty()) {
            min = toDouble(rangeRows.get(0).get("min_val"));
            max = toDouble(rangeRows.get(0).get("max_val"));
        }
        int bucketCount = request.getLimit() == null ? BUCKET_DEFAULT : request.getLimit();
        if (max <= min) {
            notice = "字段取值过少或全部相同，无法生成有意义的分布";
        } else {
            double width = (max - min) / bucketCount;
            StringBuilder sql = new StringBuilder("SELECT FLOOR((`").append(xAxis).append("` - #{params[0]}) / #{params[1]}) AS bucket_idx, ")
                    .append("COUNT(*) AS cnt FROM `").append(tableName).append("` ")
                    .append("WHERE `").append(xAxis).append("` IS NOT NULL ");
            List<Object> params = new ArrayList<>();
            params.add(BigDecimal.valueOf(min));
            params.add(BigDecimal.valueOf(width));
            appendFilters(sql, params, request.getFilters());
            sql.append("GROUP BY bucket_idx ORDER BY bucket_idx ASC LIMIT ").append(bucketCount);
            data = datasetMapper.selectDynamicSqlWithParams(sql.toString(), params);

            Map<Integer, Long> countMap = new HashMap<>();
            for (Map<String, Object> row : data) {
                countMap.put((int) toDouble(row.get("bucket_idx")), toLong(row.get("cnt")));
            }
            for (int i = 0; i < bucketCount; i++) {
                double start = min + i * width;
                double end = i == bucketCount - 1 ? max : min + (i + 1) * width;
                labels.add(formatRange(start) + " ~ " + formatRange(end));
                counts.add(countMap.getOrDefault(i, 0L));
            }
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (int i = 0; i < bucketCount; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bucket", labels.get(i));
                item.put("count", counts.get(i));
                normalized.add(item);
            }
            data = normalized;
        }

        ChartDataVO.SeriesData series = new ChartDataVO.SeriesData();
        series.setName(xAxis + " 分布");
        series.setType("bar");
        series.setData(counts);
        vo.setXAxisData(labels);
        vo.setSeries(List.of(series));
        vo.setRawData(data);
        vo.setStatistics(Map.of("bucketCount", bucketCount));
        if (notice != null) {
            vo.setNotice(notice);
        }
        return vo;
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String formatRange(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 获取统计数据：单次聚合下推 + 版本化缓存。
     *
     * <p>一次 SQL 同时计算全部列的 COUNT/去重计数，以及数值列的 SUM/AVG/MIN/MAX/STDDEV_POP，
     * 避免逐字段全表扫描。超过阈值的数据集使用受限抽样，并在结果中明确标记 approximate=true。
     * 缓存以 datasetId + currentVersionId + 过期时间为失效条件，版本切换后必然重新计算。
     */
    public Map<String, Object> getStatistics(Long datasetId) {
        // 统计信息同样必须做归属校验，避免通过统计接口探测其他会话数据
        Dataset dataset = ownershipService.requireDataset(datasetId);
        Long versionId = dataset.getCurrentVersionId();

        Map<String, Object> cached = readStatsCache(datasetId, versionId);
        if (cached != null) {
            return cached;
        }
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);
        if (columns == null) {
            columns = Collections.emptyList();
        }
        Map<String, Object> result = computeStatistics(dataset, columns);
        writeStatsCache(datasetId, versionId, result);
        return result;
    }

    private Map<String, Object> readStatsCache(Long datasetId, Long versionId) {
        try {
            StatsCacheEntry entry = statsCacheMapper.selectOne(new LambdaQueryWrapper<StatsCacheEntry>()
                    .eq(StatsCacheEntry::getDatasetId, datasetId));
            if (entry == null || entry.getPayload() == null) {
                return null;
            }
            if (versionId != null && !versionId.equals(entry.getVersionId())) {
                return null;
            }
            if (entry.getExpiresAt() == null || entry.getExpiresAt().isBefore(LocalDateTime.now())) {
                return null;
            }
            return objectMapper.readValue(entry.getPayload(), Map.class);
        } catch (Exception e) {
            log.debug("读取统计缓存失败: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void writeStatsCache(Long datasetId, Long versionId, Map<String, Object> payload) {
        try {
            StatsCacheEntry entry = statsCacheMapper.selectOne(new LambdaQueryWrapper<StatsCacheEntry>()
                    .eq(StatsCacheEntry::getDatasetId, datasetId));
            String json = objectMapper.writeValueAsString(payload);
            long rows = toLong(payload.get("rowCount"));
            if (entry == null) {
                entry = new StatsCacheEntry();
                entry.setDatasetId(datasetId);
                entry.setVersionId(versionId);
                entry.setPayload(json);
                entry.setRowCount(rows);
                entry.setComputedAt(LocalDateTime.now());
                entry.setExpiresAt(LocalDateTime.now().plusMinutes(STATS_CACHE_MINUTES));
                statsCacheMapper.insert(entry);
            } else {
                entry.setVersionId(versionId);
                entry.setPayload(json);
                entry.setRowCount(rows);
                entry.setComputedAt(LocalDateTime.now());
                entry.setExpiresAt(LocalDateTime.now().plusMinutes(STATS_CACHE_MINUTES));
                statsCacheMapper.updateById(entry);
            }
        } catch (Exception e) {
            log.debug("写入统计缓存失败: {}", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> computeStatistics(Dataset dataset, List<DatasetColumn> columns) {
        long declaredRows = dataset.getRowCount() == null ? 0 : dataset.getRowCount();
        boolean sampled = declaredRows > STATS_SAMPLE_THRESHOLD;
        String table = SqlGuard.requireTableName(dataset.getTableName());
        String source = sampled
                ? "(SELECT * FROM `" + table + "` LIMIT " + STATS_SAMPLE_ROWS + ") s"
                : "`" + table + "`";

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total");
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            DatasetColumn col = columns.get(i);
            String alias = SqlGuard.requireIdentifier(col.getColumnName(), "列名");
            aliases.add(alias);
            sql.append(", COUNT(`").append(alias).append("`) AS c").append(i).append("_nn")
                    .append(", COUNT(DISTINCT `").append(alias).append("`) AS c").append(i).append("_u")
                    .append(", MIN(`").append(alias).append("`) AS c").append(i).append("_min")
                    .append(", MAX(`").append(alias).append("`) AS c").append(i).append("_max");
            if (isNumeric(col.getDataType())) {
                sql.append(", SUM(`").append(alias).append("`) AS c").append(i).append("_sum")
                        .append(", AVG(`").append(alias).append("`) AS c").append(i).append("_avg")
                        .append(", STDDEV_POP(`").append(alias).append("`) AS c").append(i).append("_std");
            }
        }
        sql.append(" FROM ").append(source);

        Map<String, Object> row;
        try {
            List<Map<String, Object>> rows = datasetMapper.selectDynamicSql(sql.toString());
            row = rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
        } catch (RuntimeException e) {
            log.warn("统计聚合失败: datasetId={}, error={}", dataset.getId(), e.getClass().getSimpleName());
            throw new BusinessException(500, "统计计算失败，请稍后重试");
        }
        long total = toLong(row.get("total"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", total);
        result.put("columnCount", columns.size());
        result.put("approximate", sampled);
        if (sampled) {
            result.put("sampleRows", STATS_SAMPLE_ROWS);
        }

        List<Map<String, Object>> stats = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            DatasetColumn col = columns.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("columnName", col.getColumnName());
            item.put("dataType", col.getDataType());
            long nonNull = toLong(row.get("c" + i + "_nn"));
            long nullCount = Math.max(0, total - nonNull);
            item.put("nonNullCount", nonNull);
            item.put("nullCount", nullCount);
            item.put("nullRate", total > 0
                    ? BigDecimal.valueOf((double) nullCount / total * 100).setScale(2, RoundingMode.HALF_UP) + "%"
                    : "0%");
            item.put("uniqueCount", toLong(row.get("c" + i + "_u")));
            item.put("min", row.get("c" + i + "_min"));
            item.put("max", row.get("c" + i + "_max"));
            if (isNumeric(col.getDataType())) {
                item.put("sum", toBigDecimal(row.get("c" + i + "_sum")));
                item.put("avg", toBigDecimal(row.get("c" + i + "_avg")));
                item.put("std", toBigDecimal(row.get("c" + i + "_std")));
                appendQuantiles(item, table, aliases.get(i), nonNull, sampled);
            }
            stats.add(item);
        }
        result.put("columns", stats);
        return result;
    }

    /**
     * 分位数在数据库侧按偏移量取单值：全量数据集不下拉整列，大数据集只在受限样本上近似计算。
     */
    private void appendQuantiles(Map<String, Object> item, String table, String column,
                                 long nonNull, boolean sampled) {
        long base = sampled ? Math.min(nonNull, QUANTILE_SAMPLE_ROWS) : nonNull;
        if (base <= 1) {
            return;
        }
        String prefix = sampled
                ? "SELECT val FROM (SELECT `" + column + "` AS val FROM `" + table
                + "` WHERE `" + column + "` IS NOT NULL LIMIT " + QUANTILE_SAMPLE_ROWS + ") q "
                + "ORDER BY val LIMIT 1 OFFSET "
                : "SELECT `" + column + "` AS val FROM `" + table + "` WHERE `" + column
                + "` IS NOT NULL ORDER BY `" + column + "` LIMIT 1 OFFSET ";
        item.put("q1", quantileValue(prefix, base / 4));
        item.put("median", quantileValue(prefix, base / 2));
        item.put("q3", quantileValue(prefix, Math.min(base - 1, base * 3 / 4)));
        item.put("quantileApproximate", sampled);
    }

    private Object quantileValue(String prefix, long offset) {
        try {
            List<Map<String, Object>> rows = datasetMapper.selectDynamicSql(prefix + offset);
            return rows.isEmpty() ? null : rows.get(0).get("val");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(4, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(value.toString()).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private boolean isNumeric(String dataType) {
        if (dataType == null) {
            return false;
        }
        return dataType.contains("INT") || dataType.contains("DECIMAL")
                || dataType.contains("DOUBLE") || dataType.contains("FLOAT");
    }

    /**
     * 生成柱状图/折线图数据
     */
    private ChartDataVO generateBarOrLineData(ChartRequestDTO request, String tableName) {
        String xAxis = request.getXAxis();
        String yAxis = request.getYAxis();
        String groupBy = request.getGroupBy();
        if (xAxis == null || yAxis == null) {
            throw new BusinessException(400, "柱状图/折线图必须指定 X 轴和 Y 轴");
        }

        String aggFunc = SqlGuard.requireAggregation(request.getAggregation(), "SUM");
        String xExpression = buildXExpression(request);
        boolean dateAxis = request.getTimeGranularity() != null;

        StringBuilder sql = new StringBuilder("SELECT ");
        if (groupBy != null && !groupBy.isEmpty()) {
            sql.append("`").append(groupBy).append("` AS group_col, ");
        }
        sql.append(xExpression).append(" AS x_val, ");
        sql.append(aggFunc).append("(`").append(yAxis).append("`) AS y_val ");
        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        sql.append("AND `").append(yAxis).append("` IS NOT NULL ");

        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, request.getFilters());

        sql.append("GROUP BY ");
        if (groupBy != null && !groupBy.isEmpty()) {
            sql.append("`").append(groupBy).append("`, ");
        }
        sql.append(xExpression).append(" ");
        sql.append("ORDER BY ").append(resolveOrderBy(request, dateAxis)).append(" ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSqlWithParams(sql.toString(), params);

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
                series.setType("area".equals(request.getChartType()) ? "line" : request.getChartType());
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
            series.setType("area".equals(request.getChartType()) ? "line" : request.getChartType());
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
        if (xAxis == null) {
            throw new BusinessException(400, "饼图必须指定分类字段");
        }
        String aggregation = SqlGuard.requireAggregation(request.getAggregation(), "COUNT");

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("`").append(xAxis).append("` AS name, ");

        if ("COUNT".equals(aggregation)) {
            sql.append("COUNT(*) AS value ");
        } else {
            if (yAxis == null) {
                throw new BusinessException(400, "饼图使用 " + aggregation + " 时必须指定数值字段");
            }
            sql.append(aggregation).append("(`").append(yAxis).append("`) AS value ");
        }

        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");

        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, request.getFilters());

        sql.append("GROUP BY `").append(xAxis).append("` ");
        String pieOrder = "value DESC";
        if (request.getSort() != null) {
            if ("x".equalsIgnoreCase(request.getSort().getField())) {
                pieOrder = "name " + SqlGuard.requireSortDirection(request.getSort().getDirection());
            } else if ("y".equalsIgnoreCase(request.getSort().getField())) {
                pieOrder = "value " + SqlGuard.requireSortDirection(request.getSort().getDirection());
            }
        }
        sql.append("ORDER BY ").append(pieOrder).append(" ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSqlWithParams(sql.toString(), params);

        ChartDataVO vo = new ChartDataVO();
        List<String> xData = data.stream()
                .map(r -> String.valueOf(r.get("name")))
                .collect(Collectors.toList());
        vo.setXAxisData(xData);
        vo.setRawData(data);

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
        if (xAxis == null || yAxis == null) {
            throw new BusinessException(400, "散点图必须指定 X 轴和 Y 轴");
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("`").append(xAxis).append("` AS x_val, ");
        sql.append("`").append(yAxis).append("` AS y_val ");
        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        sql.append("AND `").append(yAxis).append("` IS NOT NULL ");

        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, request.getFilters());

        sql.append("ORDER BY x_val ASC ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSqlWithParams(sql.toString(), params);

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
        if (xAxis == null || yAxis == null) {
            throw new BusinessException(400, "热力图必须指定 X 轴和 Y 轴");
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("`").append(xAxis).append("` AS x_val, ");
        sql.append("`").append(yAxis).append("` AS y_val, ");
        sql.append("COUNT(*) AS count ");
        sql.append("FROM `").append(tableName).append("` ");
        sql.append("WHERE `").append(xAxis).append("` IS NOT NULL ");
        sql.append("AND `").append(yAxis).append("` IS NOT NULL ");

        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, request.getFilters());

        sql.append("GROUP BY `").append(xAxis).append("`, `").append(yAxis).append("` ");
        String heatOrder = "count DESC";
        if (request.getSort() != null) {
            if ("x".equalsIgnoreCase(request.getSort().getField())) {
                heatOrder = "x_val " + SqlGuard.requireSortDirection(request.getSort().getDirection());
            } else if ("y".equalsIgnoreCase(request.getSort().getField())) {
                heatOrder = "y_val " + SqlGuard.requireSortDirection(request.getSort().getDirection());
            }
        }
        sql.append("ORDER BY ").append(heatOrder).append(" ");
        sql.append("LIMIT ").append(request.getLimit());

        List<Map<String, Object>> data = datasetMapper.selectDynamicSqlWithParams(sql.toString(), params);

        ChartDataVO vo = new ChartDataVO();

        Set<String> xSet = new LinkedHashSet<>();
        Set<String> ySet = new LinkedHashSet<>();

        for (Map<String, Object> row : data) {
            xSet.add(String.valueOf(row.get("x_val")));
            ySet.add(String.valueOf(row.get("y_val")));
        }

        List<String> xList = new ArrayList<>(xSet);
        List<String> yList = new ArrayList<>(ySet);
        Map<String, Integer> xIndex = new HashMap<>();
        Map<String, Integer> yIndex = new HashMap<>();
        for (int i = 0; i < xList.size(); i++) {
            xIndex.put(xList.get(i), i);
        }
        for (int i = 0; i < yList.size(); i++) {
            yIndex.put(yList.get(i), i);
        }

        List<Object> heatmapData = data.stream()
                .map(r -> {
                    List<Object> point = new ArrayList<>();
                    point.add(xIndex.get(String.valueOf(r.get("x_val"))));
                    point.add(yIndex.get(String.valueOf(r.get("y_val"))));
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

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("yAxisData", yList);
        vo.setStatistics(stats);

        return vo;
    }

    /**
     * 追加过滤条件：标识符已校验，值统一走参数绑定。
     */
    private void appendFilters(StringBuilder sql, List<Object> params,
                               List<ChartRequestDTO.FilterConditionDTO> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (ChartRequestDTO.FilterConditionDTO filter : filters) {
            String column = SqlGuard.requireIdentifier(filter.getColumn(), "过滤字段");
            String operator = SqlGuard.requireFilterOperator(filter.getOperator());
            String value = filter.getValue() == null ? "" : filter.getValue();

            String sqlOperator = switch (operator) {
                case "eq" -> "=";
                case "ne" -> "!=";
                case "gt" -> ">";
                case "gte" -> ">=";
                case "lt" -> "<";
                case "lte" -> "<=";
                case "like" -> "LIKE";
                default -> throw new BusinessException(400, "不支持的过滤操作符");
            };

            sql.append("AND `").append(column).append("` ").append(sqlOperator)
                    .append(" #{params[").append(params.size()).append("]} ");
            params.add("like".equals(operator) ? "%" + value + "%" : value);
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

        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - avg, 2))
                .average().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", values.size());
        stats.put("sum", BigDecimal.valueOf(sum).setScale(2, RoundingMode.HALF_UP));
        stats.put("mean", BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
        stats.put("variance", BigDecimal.valueOf(variance).setScale(2, RoundingMode.HALF_UP));
        stats.put("max", BigDecimal.valueOf(max).setScale(2, RoundingMode.HALF_UP));
        stats.put("min", BigDecimal.valueOf(min).setScale(2, RoundingMode.HALF_UP));

        return stats;
    }
}
