package com.dataclean.service;

import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.StatsCacheEntry;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.StatsCacheMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 统计下推与版本化缓存测试：验证单次聚合、抽样标记与缓存命中。
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceStatsTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(datasetMapper, datasetColumnMapper,
                org.mockito.Mockito.mock(com.dataclean.mapper.DatasetVersionMapper.class),
                ownershipService, publicQuotaService, org.mockito.Mockito.mock(JobService.class),
                statsCacheMapper, objectMapper);
    }

    private Dataset dataset(long rows) {
        Dataset dataset = new Dataset();
        dataset.setId(1L);
        dataset.setTableName("data_test_1");
        dataset.setRowCount((int) rows);
        dataset.setCurrentVersionId(7L);
        return dataset;
    }

    private DatasetColumn column(String name, String type) {
        DatasetColumn column = new DatasetColumn();
        column.setColumnName(name);
        column.setColumnAlias(name);
        column.setDataType(type);
        return column;
    }

    private Map<String, Object> aggregateRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("total", 1000L);
        row.put("c0_nn", 900L);
        row.put("c0_u", 50L);
        row.put("c0_min", "a");
        row.put("c0_max", "z");
        row.put("c1_nn", 800L);
        row.put("c1_u", 20L);
        row.put("c1_min", "b");
        row.put("c1_max", "y");
        return row;
    }

    @Test
    void statistics_usesSingleAggregateAndServesFromCache() {
        when(ownershipService.requireDataset(1L)).thenReturn(dataset(1000));
        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(List.of(
                column("name", "VARCHAR(500)"), column("city", "VARCHAR(500)")));
        when(datasetMapper.selectDynamicSql(anyString())).thenReturn(List.of(aggregateRow()));

        Map<String, Object> first = analysisService.getStatistics(1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetMapper, times(1)).selectDynamicSql(sql.capture());
        assertTrue(sql.getValue().contains("COUNT(DISTINCT"), "统计应在单条 SQL 内完成去重计数");
        assertEquals(2, ((List<?>) first.get("columns")).size());
        assertEquals(false, first.get("approximate"));

        ArgumentCaptor<StatsCacheEntry> saved = ArgumentCaptor.forClass(StatsCacheEntry.class);
        verify(statsCacheMapper).insert(saved.capture());

        StatsCacheEntry cache = new StatsCacheEntry();
        cache.setVersionId(7L);
        cache.setPayload(saved.getValue().getPayload());
        cache.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(5));
        when(statsCacheMapper.selectOne(any())).thenReturn(cache);

        Map<String, Object> second = analysisService.getStatistics(1L);

        // 第二次命中缓存，不再触发聚合查询
        verify(datasetMapper, times(1)).selectDynamicSql(anyString());
        assertEquals(String.valueOf(first.get("rowCount")), String.valueOf(second.get("rowCount")));
    }

    @Test
    void statistics_largeDatasetUsesBoundedSample() {
        when(ownershipService.requireDataset(1L)).thenReturn(dataset(200_000));
        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(List.of(column("name", "VARCHAR(500)")));
        Map<String, Object> row = new HashMap<>();
        row.put("total", 50_000L);
        row.put("c0_nn", 49_000L);
        row.put("c0_u", 100L);
        row.put("c0_min", "a");
        row.put("c0_max", "z");
        when(datasetMapper.selectDynamicSql(anyString())).thenReturn(List.of(row));

        Map<String, Object> result = analysisService.getStatistics(1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetMapper).selectDynamicSql(sql.capture());
        assertTrue(sql.getValue().contains("LIMIT 50000"), "大数据集必须使用受限抽样");
        assertEquals(true, result.get("approximate"));
        assertEquals(50_000, result.get("sampleRows"));
    }
}
