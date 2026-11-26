package com.dataclean.service;

import com.dataclean.entity.Dashboard;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DashboardItemMapper;
import com.dataclean.mapper.DashboardMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.SavedChartMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 看板跨会话隔离：其他会话的看板与卡片统一 404。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceIsolationTest {

    @Mock
    private DashboardMapper dashboardMapper;
    @Mock
    private DashboardItemMapper dashboardItemMapper;
    @Mock
    private SavedChartMapper savedChartMapper;
    @Mock
    private DatasetMapper datasetMapper;
    @Mock
    private DatasetVersionMapper datasetVersionMapper;
    @Mock
    private DatasetColumnMapper datasetColumnMapper;
    @Mock
    private SavedChartService savedChartService;
    @Mock
    private OwnershipService ownershipService;
    @Mock
    private AuditService auditService;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(dashboardMapper, dashboardItemMapper, savedChartMapper,
                datasetMapper, datasetVersionMapper, datasetColumnMapper,
                savedChartService, ownershipService, auditService);
    }

    private Dashboard dashboard(String ownerType, Long anonSessionId) {
        Dashboard dashboard = new Dashboard();
        dashboard.setId(5L);
        dashboard.setTitle("d");
        dashboard.setOwnerType(ownerType);
        dashboard.setAnonSessionId(anonSessionId);
        return dashboard;
    }

    @Test
    void otherSessionDashboardIsNotFoundInPublicMode() {
        when(ownershipService.isPublic()).thenReturn(true);
        when(ownershipService.currentSessionId()).thenReturn(100L);
        when(dashboardMapper.selectById(5L)).thenReturn(dashboard(OwnershipService.OWNER_ANON, 200L));

        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(5L)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.delete(5L)).getCode());
    }

    @Test
    void privateModeCannotSeeAnonDashboard() {
        when(ownershipService.isPublic()).thenReturn(false);
        when(dashboardMapper.selectById(5L)).thenReturn(dashboard(OwnershipService.OWNER_ANON, 200L));

        assertEquals(404, assertThrows(BusinessException.class, () -> service.rename(5L, null)).getCode());
    }

    @Test
    void unknownDashboardIsNotFound() {
        when(dashboardMapper.selectById(anyLong())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(5L)).getCode());
    }

    @Test
    void removeItemCrossDashboardIsNotFound() {
        when(ownershipService.isPublic()).thenReturn(false);
        when(dashboardMapper.selectById(5L)).thenReturn(dashboard(OwnershipService.OWNER_PRIVATE, null));
        when(dashboardItemMapper.selectById(8L)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class, () -> service.removeItem(5L, 8L)).getCode());
    }
}
