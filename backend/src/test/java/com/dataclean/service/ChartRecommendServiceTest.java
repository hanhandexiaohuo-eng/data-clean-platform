package com.dataclean.service;

import com.dataclean.dto.ChartRecommendationVO;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.mapper.DatasetColumnMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 图表推荐：六类推荐覆盖、上限 6 条与空态提示。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChartRecommendServiceTest {

    @Mock
    private DatasetColumnMapper datasetColumnMapper;
    @Mock
    private OwnershipService ownershipService;

    private ChartRecommendService service;

    @BeforeEach
    void setUp() {
        service = new ChartRecommendService(datasetColumnMapper, ownershipService);
        Dataset dataset = new Dataset();
        dataset.setId(1L);
        dataset.setRowCount(5000);
        when(ownershipService.requireDataset(anyLong())).thenReturn(dataset);
    }

    private DatasetColumn column(String alias, String type, Integer unique) {
        DatasetColumn column = new DatasetColumn();
        column.setColumnAlias(alias);
        column.setDataType(type);
        column.setUniqueCount(unique);
        return column;
    }

    @Test
    void recommendsSixChartTypesForRichDataset() {
        List<DatasetColumn> columns = new ArrayList<>();
        columns.add(column("created_at", "DATETIME", 300));
        columns.add(column("amount", "DECIMAL(20,6)", 400));
        columns.add(column("qty", "INT", 20));
        columns.add(column("city", "VARCHAR(50)", 8));
        columns.add(column("status", "VARCHAR(20)", 5));
        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(columns);

        ChartRecommendationVO vo = service.recommend(1L);

        assertEquals(6, vo.getItems().size());
        List<String> types = vo.getItems().stream().map(ChartRecommendationVO.Item::getChartType).toList();
        assertTrue(types.contains("line"));
        assertTrue(types.contains("bar"));
        assertTrue(types.contains("pie"));
        assertTrue(types.contains("scatter"));
        assertTrue(types.contains("heatmap"));
        ChartRecommendationVO.Item trend = vo.getItems().get(0);
        assertEquals("line", trend.getChartType());
        assertEquals("MONTH", trend.getTimeGranularity());
        ChartRecommendationVO.Item distribution = vo.getItems().stream()
                .filter(i -> "distribution".equals(i.getMode())).findFirst().orElseThrow();
        assertEquals("bar", distribution.getChartType());
        assertEquals(20, distribution.getConfig().get("bucketCount"));
    }

    @Test
    void emptyColumnsReportsNotice() {
        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(List.of());
        ChartRecommendationVO vo = service.recommend(1L);
        assertTrue(vo.getItems().isEmpty());
        assertNotNull(vo.getNotice());
    }

    @Test
    void insufficientColumnsReportsNotice() {
        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(
                List.of(column("city", "VARCHAR(50)", 1000)));
        ChartRecommendationVO vo = service.recommend(1L);
        assertTrue(vo.getItems().isEmpty());
        assertTrue(vo.getNotice().contains("自由配置"));
    }

    @Test
    void smallDatasetUsesDayGranularity() {
        Dataset small = new Dataset();
        small.setId(1L);
        small.setRowCount(800);
        when(ownershipService.requireDataset(1L)).thenReturn(small);
        when(datasetColumnMapper.selectByDatasetId(1L)).thenReturn(List.of(
                column("created_at", "DATETIME", 50),
                column("amount", "DECIMAL(20,6)", 60)));

        ChartRecommendationVO vo = service.recommend(1L);
        assertEquals("DAY", vo.getItems().get(0).getTimeGranularity());
    }
}
