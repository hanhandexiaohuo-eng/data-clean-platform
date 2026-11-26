package com.dataclean.service;

import com.dataclean.entity.Dataset;
import com.dataclean.entity.SavedChart;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DashboardItemMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.SavedChartMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 保存图表跨会话隔离与版本失效：其他会话统一 404，版本变化 409 不得静默套用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedChartServiceIsolationTest {

    @Mock
    private SavedChartMapper savedChartMapper;
    @Mock
    private DashboardItemMapper dashboardItemMapper;
    @Mock
    private DatasetMapper datasetMapper;
    @Mock
    private DatasetVersionMapper datasetVersionMapper;
    @Mock
    private DatasetColumnMapper datasetColumnMapper;
    @Mock
    private OwnershipService ownershipService;
    @Mock
    private AnalysisService analysisService;
    @Mock
    private AuditService auditService;

    private SavedChartService service;

    @BeforeEach
    void setUp() {
        service = new SavedChartService(savedChartMapper, dashboardItemMapper, datasetMapper, datasetVersionMapper,
                datasetColumnMapper, ownershipService, analysisService, auditService, new ObjectMapper());
        when(datasetColumnMapper.selectByDatasetId(anyLong())).thenReturn(List.of());
    }

    private SavedChart chart(String ownerType, Long anonSessionId, Long savedVersionId) {
        SavedChart chart = new SavedChart();
        chart.setId(1L);
        chart.setDatasetId(9L);
        chart.setOwnerType(ownerType);
        chart.setAnonSessionId(anonSessionId);
        chart.setDatasetVersionId(savedVersionId);
        chart.setChartType("bar");
        chart.setChartConfig("{}");
        chart.setTitle("t");
        return chart;
    }

    private Dataset dataset(Long currentVersionId) {
        Dataset dataset = new Dataset();
        dataset.setId(9L);
        dataset.setName("ds");
        dataset.setCurrentVersionId(currentVersionId);
        return dataset;
    }

    @Test
    void otherSessionChartInvisibleInPublicMode() {
        when(ownershipService.isPublic()).thenReturn(true);
        when(ownershipService.currentSessionId()).thenReturn(100L);
        when(savedChartMapper.selectById(1L)).thenReturn(chart(OwnershipService.OWNER_ANON, 200L, 1L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(1L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void anonChartInvisibleInPrivateMode() {
        when(ownershipService.isPublic()).thenReturn(false);
        when(savedChartMapper.selectById(1L)).thenReturn(chart(OwnershipService.OWNER_ANON, 200L, 1L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(1L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void missingChartReturns404() {
        when(savedChartMapper.selectById(anyLong())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(7L)).getCode());
    }

    @Test
    void staleVersionRefusesSilentRegeneration() {
        when(ownershipService.isPublic()).thenReturn(false);
        when(savedChartMapper.selectById(1L)).thenReturn(chart(OwnershipService.OWNER_PRIVATE, null, 1L));
        when(ownershipService.requireDataset(9L)).thenReturn(dataset(2L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.generate(1L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("数据已更新"));
        verify(analysisService, never()).generateChartData(any());
    }

    @Test
    void missingFieldReportsWhichColumnChanged() {
        when(ownershipService.isPublic()).thenReturn(false);
        SavedChart chart = chart(OwnershipService.OWNER_PRIVATE, null, 1L);
        chart.setChartConfig("{\"xAxis\":\"removed_col\"}");
        when(savedChartMapper.selectById(1L)).thenReturn(chart);
        when(ownershipService.requireDataset(9L)).thenReturn(dataset(1L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.generate(1L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("removed_col"));
    }
}
