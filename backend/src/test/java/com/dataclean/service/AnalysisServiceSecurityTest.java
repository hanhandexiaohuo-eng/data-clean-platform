package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.dto.ChartRequestDTO;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.ChartViewMapper;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.ExportRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisServiceSecurityTest {

    @Mock
    private DatasetMapper datasetMapper;
    @Mock
    private DatasetColumnMapper datasetColumnMapper;
    @Mock
    private CleanRuleMapper cleanRuleMapper;
    @Mock
    private CleanLogMapper cleanLogMapper;
    @Mock
    private ChartViewMapper chartViewMapper;
    @Mock
    private ExportRecordMapper exportRecordMapper;

    private AnalysisService analysisService;
    private ChartRequestDTO request;

    @BeforeEach
    void setUp() {
        AppMode appMode = new AppMode("private");
        OwnershipService ownershipService = new OwnershipService(
                appMode, datasetMapper, cleanRuleMapper, cleanLogMapper);
        PublicQuotaService quotaService = new PublicQuotaService(
                appMode, new PublicModeProperties(), datasetMapper, cleanLogMapper,
                chartViewMapper, exportRecordMapper);
        analysisService = new AnalysisService(datasetMapper, datasetColumnMapper,
                org.mockito.Mockito.mock(com.dataclean.mapper.DatasetVersionMapper.class),
                ownershipService, quotaService,
                org.mockito.Mockito.mock(JobService.class),
                org.mockito.Mockito.mock(com.dataclean.mapper.StatsCacheMapper.class),
                new ObjectMapper());

        Dataset dataset = new Dataset();
        dataset.setId(1L);
        dataset.setTableName("data_sales_1");
        when(datasetMapper.selectById(1L)).thenReturn(dataset);

        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(List.of(
                column("city", "VARCHAR(50)"),
                column("sales", "DECIMAL(20,6)")));

        request = new ChartRequestDTO();
        request.setDatasetId(1L);
        request.setChartType("bar");
        request.setXAxis("city");
        request.setYAxis("sales");
    }

    private DatasetColumn column(String alias, String type) {
        DatasetColumn col = new DatasetColumn();
        col.setColumnAlias(alias);
        col.setColumnName(alias);
        col.setDataType(type);
        return col;
    }

    @Test
    void invalidAggregation_returns400_withoutSqlExecution() {
        request.setAggregation("SUM(sales); DROP TABLE user");
        BusinessException e = assertThrows(BusinessException.class,
                () -> analysisService.generateChartData(request));
        assertEquals(400, e.getCode());
        verify(datasetMapper, never()).selectDynamicSqlWithParams(anyString(), anyList());
    }

    @Test
    void invalidColumn_returns400() {
        request.setXAxis("city`; DROP TABLE user; --");
        BusinessException e = assertThrows(BusinessException.class,
                () -> analysisService.generateChartData(request));
        assertEquals(400, e.getCode());
        verify(datasetMapper, never()).selectDynamicSqlWithParams(anyString(), anyList());
    }

    @Test
    void invalidChartType_returns400() {
        request.setChartType("script");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> analysisService.generateChartData(request)).getCode());
    }

    @Test
    void excessiveLimit_returns400() {
        request.setLimit(999999);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> analysisService.generateChartData(request)).getCode());
    }

    @Test
    void invalidFilterOperator_returns400() {
        ChartRequestDTO.FilterConditionDTO filter = new ChartRequestDTO.FilterConditionDTO();
        filter.setColumn("city");
        filter.setOperator("; DROP");
        filter.setValue("x");
        request.setFilters(List.of(filter));

        assertEquals(400, assertThrows(BusinessException.class,
                () -> analysisService.generateChartData(request)).getCode());
    }

    @Test
    void filterValue_isBoundAsParameter_notConcatenated() {
        ChartRequestDTO.FilterConditionDTO filter = new ChartRequestDTO.FilterConditionDTO();
        filter.setColumn("city");
        filter.setOperator("eq");
        filter.setValue("北京' OR '1'='1");
        request.setFilters(List.of(filter));
        when(datasetMapper.selectDynamicSqlWithParams(anyString(), anyList())).thenReturn(List.of());

        analysisService.generateChartData(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> paramsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetMapper).selectDynamicSqlWithParams(sqlCaptor.capture(), paramsCaptor.capture());

        assertTrue(sqlCaptor.getValue().contains("#{params[0]}"));
        assertFalse(sqlCaptor.getValue().contains("北京"));
        assertEquals("北京' OR '1'='1", paramsCaptor.getValue().get(0));
    }

    @Test
    void validRequest_buildsWhitelistedSql() {
        when(datasetMapper.selectDynamicSqlWithParams(anyString(), anyList())).thenReturn(List.of());

        analysisService.generateChartData(request);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetMapper).selectDynamicSqlWithParams(sqlCaptor.capture(), anyList());
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("FROM `data_sales_1`"));
        assertTrue(sql.contains("SUM(`sales`)"));
        // 分类图默认 Top N = 50（超过时截断并标记 truncated）
        assertTrue(sql.contains("LIMIT 50"));
    }
}
