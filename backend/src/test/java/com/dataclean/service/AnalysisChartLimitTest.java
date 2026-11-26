package com.dataclean.service;

import com.dataclean.dto.ChartRequestDTO;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.StatsCacheMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 图表扩展配置校验与数据点上限：Top N、分桶、时间粒度、模式与 2000 点硬上限。
 */
@ExtendWith(MockitoExtension.class)
class AnalysisChartLimitTest {

    @Mock
    private DatasetMapper datasetMapper;
    @Mock
    private DatasetColumnMapper datasetColumnMapper;
    @Mock
    private OwnershipService ownershipService;
    @Mock
    private PublicQuotaService publicQuotaService;
    @Mock
    private StatsCacheMapper statsCacheMapper;

    private AnalysisService analysisService;
    private Method validateMethod;
    private Method limitMethod;

    @BeforeEach
    void setUp() throws Exception {
        analysisService = new AnalysisService(datasetMapper, datasetColumnMapper,
                mock(DatasetVersionMapper.class), ownershipService, publicQuotaService,
                mock(JobService.class), statsCacheMapper, new ObjectMapper());
        validateMethod = AnalysisService.class.getDeclaredMethod("validateExtendedConfig",
                ChartRequestDTO.class, String.class, Map.class, Dataset.class);
        validateMethod.setAccessible(true);
        limitMethod = AnalysisService.class.getDeclaredMethod("resolveEffectiveLimit",
                ChartRequestDTO.class, String.class, Map.class, Dataset.class);
        limitMethod.setAccessible(true);
    }

    private Dataset dataset(int rows) {
        Dataset dataset = new Dataset();
        dataset.setId(1L);
        dataset.setRowCount(rows);
        dataset.setCurrentVersionId(1L);
        return dataset;
    }

    private DatasetColumn column(String alias, String type) {
        DatasetColumn column = new DatasetColumn();
        column.setColumnAlias(alias);
        column.setDataType(type);
        return column;
    }

    private Map<String, DatasetColumn> columns(DatasetColumn... cols) {
        Map<String, DatasetColumn> map = new LinkedHashMap<>();
        for (DatasetColumn col : cols) {
            map.put(col.getColumnAlias(), col);
        }
        return map;
    }

    private void validate(ChartRequestDTO request, String chartType, Map<String, DatasetColumn> cols,
                          Dataset dataset) {
        try {
            validateMethod.invoke(analysisService, request, chartType, cols, dataset);
        } catch (InvocationTargetException e) {
            throw (RuntimeException) e.getTargetException();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private int limit(ChartRequestDTO request, String chartType, Map<String, DatasetColumn> cols,
                      Dataset dataset) {
        try {
            return (int) limitMethod.invoke(analysisService, request, chartType, cols, dataset);
        } catch (InvocationTargetException e) {
            throw (RuntimeException) e.getTargetException();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private BusinessException expectBadRequest(Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(400, ex.getCode());
        return ex;
    }

    @Test
    void rejectsTopNOutOfRange() {
        Map<String, DatasetColumn> cols = columns(column("city", "VARCHAR"));
        ChartRequestDTO request = new ChartRequestDTO();
        request.setXAxis("city");
        request.setTopN(0);
        expectBadRequest(() -> validate(request, "bar", cols, dataset(10)));

        request.setTopN(501);
        expectBadRequest(() -> validate(request, "bar", cols, dataset(10)));
    }

    @Test
    void rejectsBucketCountOutOfRange() {
        Map<String, DatasetColumn> cols = columns(column("amount", "DECIMAL"));
        ChartRequestDTO request = new ChartRequestDTO();
        request.setXAxis("amount");
        request.setMode("distribution");
        request.setBucketCount(4);
        expectBadRequest(() -> validate(request, "bar", cols, dataset(10)));

        request.setBucketCount(51);
        expectBadRequest(() -> validate(request, "bar", cols, dataset(10)));
    }

    @Test
    void rejectsUnknownModeAndNonNumericDistributionAxis() {
        Map<String, DatasetColumn> cols = columns(column("city", "VARCHAR"));
        ChartRequestDTO request = new ChartRequestDTO();
        request.setXAxis("city");
        request.setMode("unknown");
        expectBadRequest(() -> validate(request, "bar", cols, dataset(10)));

        request.setMode("distribution");
        expectBadRequest(() -> validate(request, "bar", cols, dataset(10)));
    }

    @Test
    void rejectsGranularityOnNonDateAxis() {
        Map<String, DatasetColumn> cols = columns(column("city", "VARCHAR"));
        ChartRequestDTO request = new ChartRequestDTO();
        request.setXAxis("city");
        request.setTimeGranularity("MONTH");
        expectBadRequest(() -> validate(request, "line", cols, dataset(10)));
    }

    @Test
    void autoGranularityDependsOnRowCount() {
        Map<String, DatasetColumn> cols = columns(column("created_at", "DATETIME"));
        ChartRequestDTO small = new ChartRequestDTO();
        small.setXAxis("created_at");
        small.setTimeGranularity("AUTO");
        validate(small, "line", cols, dataset(800));
        assertEquals("DAY", small.getTimeGranularity());

        ChartRequestDTO large = new ChartRequestDTO();
        large.setXAxis("created_at");
        large.setTimeGranularity("AUTO");
        validate(large, "line", cols, dataset(5000));
        assertEquals("MONTH", large.getTimeGranularity());
    }

    @Test
    void limitIsCappedAtTwoThousandPoints() {
        Map<String, DatasetColumn> category = columns(column("city", "VARCHAR"));
        ChartRequestDTO scatter = new ChartRequestDTO();
        scatter.setXAxis("city");
        scatter.setLimit(5000);
        assertEquals(2000, limit(scatter, "scatter", category, dataset(100)));

        ChartRequestDTO heatmap = new ChartRequestDTO();
        heatmap.setXAxis("city");
        heatmap.setLimit(5000);
        assertEquals(500, limit(heatmap, "heatmap", category, dataset(100)));

        Map<String, DatasetColumn> date = columns(column("created_at", "DATETIME"));
        ChartRequestDTO line = new ChartRequestDTO();
        line.setXAxis("created_at");
        line.setLimit(100);
        assertEquals(200, limit(line, "line", date, dataset(1000)));

        ChartRequestDTO bar = new ChartRequestDTO();
        bar.setXAxis("city");
        bar.setLimit(100);
        assertEquals(50, limit(bar, "bar", category, dataset(100)));
        bar.setTopN(30);
        assertEquals(30, limit(bar, "bar", category, dataset(100)));
    }

    @Test
    void pieAndDistributionResolveOwnLimit() {
        Map<String, DatasetColumn> category = columns(column("city", "VARCHAR"));
        ChartRequestDTO pie = new ChartRequestDTO();
        pie.setXAxis("city");
        pie.setLimit(100);
        assertEquals(12, limit(pie, "pie", category, dataset(100)));
        pie.setTopN(30);
        assertEquals(30, limit(pie, "pie", category, dataset(100)));

        Map<String, DatasetColumn> numeric = columns(column("amount", "DECIMAL"));
        ChartRequestDTO distribution = new ChartRequestDTO();
        distribution.setXAxis("amount");
        distribution.setMode("distribution");
        assertEquals(20, limit(distribution, "bar", numeric, dataset(100)));
        distribution.setBucketCount(30);
        assertEquals(30, limit(distribution, "bar", numeric, dataset(100)));
    }
}
