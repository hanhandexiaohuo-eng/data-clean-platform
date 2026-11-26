package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.dto.ChartViewStatusVO;
import com.dataclean.dto.DashboardDTO;
import com.dataclean.dto.DashboardItemDTO;
import com.dataclean.dto.DashboardItemVO;
import com.dataclean.dto.DashboardVO;
import com.dataclean.entity.Dashboard;
import com.dataclean.entity.DashboardItem;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.entity.SavedChart;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DashboardItemMapper;
import com.dataclean.mapper.DashboardMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.SavedChartMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 看板：12 列栅格布局，只能引用当前会话/私有工作区可见的图表。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int GRID_COLUMNS = 12;

    private final DashboardMapper dashboardMapper;
    private final DashboardItemMapper dashboardItemMapper;
    private final SavedChartMapper savedChartMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final SavedChartService savedChartService;
    private final OwnershipService ownershipService;
    private final AuditService auditService;

    public List<DashboardVO> list() {
        List<Dashboard> dashboards = dashboardMapper.selectList(
                visibleWrapper().orderByDesc(Dashboard::getUpdatedAt));
        List<DashboardVO> result = new ArrayList<>();
        for (Dashboard dashboard : dashboards) {
            result.add(toVO(dashboard, false));
        }
        return result;
    }

    public DashboardVO detail(Long id) {
        Dashboard dashboard = requireVisible(id);
        return toVO(dashboard, true);
    }

    public DashboardVO create(DashboardDTO dto) {
        validateTitle(dto);
        Dashboard dashboard = new Dashboard();
        dashboard.setOwnerType(ownershipService.currentOwnerType());
        dashboard.setAnonSessionId(ownershipService.isPublic() ? ownershipService.currentSessionId() : null);
        dashboard.setTitle(dto.getTitle().trim());
        dashboard.setDescription(dto.getDescription());
        dashboard.setCreatedAt(LocalDateTime.now());
        dashboard.setUpdatedAt(LocalDateTime.now());
        dashboardMapper.insert(dashboard);
        auditService.record("DASHBOARD_CREATE", "dashboard", dashboard.getId(), null,
                null, null, dashboard.getOwnerType(), dashboard.getAnonSessionId(), null,
                "SUCCESS", null, "titleLength=" + dashboard.getTitle().length());
        return toVO(dashboard, true);
    }

    public DashboardVO rename(Long id, DashboardDTO dto) {
        Dashboard dashboard = requireVisible(id);
        validateTitle(dto);
        dashboard.setTitle(dto.getTitle().trim());
        dashboard.setDescription(dto.getDescription());
        dashboard.setUpdatedAt(LocalDateTime.now());
        dashboardMapper.updateById(dashboard);
        auditService.record("DASHBOARD_UPDATE", "dashboard", dashboard.getId(), null,
                null, null, dashboard.getOwnerType(), dashboard.getAnonSessionId(), null,
                "SUCCESS", null, "titleLength=" + dashboard.getTitle().length());
        return toVO(dashboard, true);
    }

    @Transactional
    public void delete(Long id) {
        Dashboard dashboard = requireVisible(id);
        LambdaQueryWrapper<DashboardItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DashboardItem::getDashboardId, id);
        dashboardItemMapper.delete(wrapper);
        dashboardMapper.deleteById(id);
        auditService.record("DASHBOARD_DELETE", "dashboard", id, null,
                null, null, dashboard.getOwnerType(), dashboard.getAnonSessionId(), null,
                "SUCCESS", null, "itemsRemoved=true");
    }

    @Transactional
    public DashboardItemVO addItem(Long dashboardId, DashboardItemDTO dto) {
        Dashboard dashboard = requireVisible(dashboardId);
        if (dto == null || dto.getSavedChartId() == null) {
            throw new BusinessException(400, "图表不能为空");
        }
        SavedChart chart = savedChartService.requireVisible(dto.getSavedChartId());
        Grid grid = validateGrid(dto);

        LambdaQueryWrapper<DashboardItem> duplicate = new LambdaQueryWrapper<>();
        duplicate.eq(DashboardItem::getDashboardId, dashboardId)
                .eq(DashboardItem::getSavedChartId, dto.getSavedChartId());
        if (dashboardItemMapper.selectCount(duplicate) > 0) {
            throw new BusinessException(400, "该图表已在看板中");
        }

        DashboardItem item = new DashboardItem();
        item.setDashboardId(dashboardId);
        item.setSavedChartId(chart.getId());
        item.setSortOrder(dto.getSortOrder() == null
                ? (int) dashboardItemMapper.countByDashboard(dashboardId) : dto.getSortOrder());
        item.setGridX(grid.x);
        item.setGridY(grid.y);
        item.setGridW(grid.w);
        item.setGridH(grid.h);
        item.setCreatedAt(LocalDateTime.now());
        dashboardItemMapper.insert(item);
        touch(dashboardId);
        auditService.record("DASHBOARD_ITEM_ADD", "dashboard_item", item.getId(), chart.getDatasetId(),
                null, chart.getDatasetVersionId(), dashboard.getOwnerType(), dashboard.getAnonSessionId(),
                null, "SUCCESS", null, "dashboardId=" + dashboardId);
        return toItemVO(item, chart);
    }

    public DashboardItemVO updateItem(Long dashboardId, Long itemId, DashboardItemDTO dto) {
        Dashboard dashboard = requireVisible(dashboardId);
        DashboardItem item = requireItem(dashboardId, itemId);
        Grid grid = validateGrid(dto);
        item.setGridX(grid.x);
        item.setGridY(grid.y);
        item.setGridW(grid.w);
        item.setGridH(grid.h);
        if (dto.getSortOrder() != null) {
            item.setSortOrder(dto.getSortOrder());
        }
        dashboardItemMapper.updateById(item);
        touch(dashboardId);
        SavedChart chart = savedChartService.findVisible(item.getSavedChartId());
        if (chart == null) {
            auditService.record("DASHBOARD_ITEM_UPDATE", "dashboard_item", item.getId(), null,
                    null, null, dashboard.getOwnerType(), dashboard.getAnonSessionId(),
                    null, "SUCCESS", null, "dashboardId=" + dashboardId + ",chartInvalid=true");
            return invalidItemVO(item, null, "图表已删除，无法展示");
        }
        Dataset dataset = ownershipService.findVisibleDataset(chart.getDatasetId());
        if (dataset == null) {
            auditService.record("DASHBOARD_ITEM_UPDATE", "dashboard_item", item.getId(), chart.getDatasetId(),
                    null, chart.getDatasetVersionId(), dashboard.getOwnerType(), dashboard.getAnonSessionId(),
                    null, "SUCCESS", null, "dashboardId=" + dashboardId + ",datasetInvalid=true");
            return invalidItemVO(item, chart, "数据集已删除，无法展示");
        }
        auditService.record("DASHBOARD_ITEM_UPDATE", "dashboard_item", item.getId(), chart.getDatasetId(),
                null, chart.getDatasetVersionId(), dashboard.getOwnerType(), dashboard.getAnonSessionId(),
                null, "SUCCESS", null, "dashboardId=" + dashboardId);
        return toItemVO(item, chart);
    }

    public void removeItem(Long dashboardId, Long itemId) {
        Dashboard dashboard = requireVisible(dashboardId);
        DashboardItem item = requireItem(dashboardId, itemId);
        dashboardItemMapper.deleteById(itemId);
        touch(dashboardId);
        auditService.record("DASHBOARD_ITEM_REMOVE", "dashboard_item", itemId, null,
                null, null, dashboard.getOwnerType(), dashboard.getAnonSessionId(), null,
                "SUCCESS", null, "dashboardId=" + dashboardId);
    }

    @Transactional
    public DashboardVO reorder(Long dashboardId, List<Long> itemIds) {
        Dashboard dashboard = requireVisible(dashboardId);
        if (itemIds == null) {
            throw new BusinessException(400, "排序列表不能为空");
        }
        List<DashboardItem> items = dashboardItemMapper.listByDashboard(dashboardId);
        if (items.size() != itemIds.size()
                || !items.stream().map(DashboardItem::getId).allMatch(itemIds::contains)) {
            throw new BusinessException(400, "排序列表与看板项不一致");
        }
        int order = 0;
        for (Long itemId : itemIds) {
            DashboardItem item = requireItem(dashboardId, itemId);
            item.setSortOrder(order++);
            dashboardItemMapper.updateById(item);
        }
        touch(dashboardId);
        auditService.record("DASHBOARD_REORDER", "dashboard", dashboardId, null,
                null, null, dashboard.getOwnerType(), dashboard.getAnonSessionId(), null,
                "SUCCESS", null, "itemCount=" + items.size());
        return toVO(dashboard, true);
    }

    public Dashboard requireVisible(Long id) {
        if (id == null) {
            throw new BusinessException(404, "看板不存在");
        }
        Dashboard dashboard = dashboardMapper.selectById(id);
        if (dashboard == null) {
            throw new BusinessException(404, "看板不存在");
        }
        if (ownershipService.isPublic()) {
            Long sessionId = ownershipService.currentSessionId();
            if (!OwnershipService.OWNER_ANON.equals(dashboard.getOwnerType())
                    || !sessionId.equals(dashboard.getAnonSessionId())) {
                throw new BusinessException(404, "看板不存在");
            }
        } else if (!OwnershipService.OWNER_PRIVATE.equals(dashboard.getOwnerType())) {
            throw new BusinessException(404, "看板不存在");
        }
        return dashboard;
    }

    private DashboardItem requireItem(Long dashboardId, Long itemId) {
        DashboardItem item = dashboardItemMapper.selectById(itemId);
        if (item == null || !dashboardId.equals(item.getDashboardId())) {
            throw new BusinessException(404, "看板项不存在");
        }
        return item;
    }

    private LambdaQueryWrapper<Dashboard> visibleWrapper() {
        LambdaQueryWrapper<Dashboard> wrapper = new LambdaQueryWrapper<>();
        if (ownershipService.isPublic()) {
            wrapper.eq(Dashboard::getOwnerType, OwnershipService.OWNER_ANON)
                    .eq(Dashboard::getAnonSessionId, ownershipService.currentSessionId());
        } else {
            wrapper.eq(Dashboard::getOwnerType, OwnershipService.OWNER_PRIVATE);
        }
        return wrapper;
    }

    private DashboardVO toVO(Dashboard dashboard, boolean withItems) {
        DashboardVO vo = new DashboardVO();
        vo.setId(dashboard.getId());
        vo.setTitle(dashboard.getTitle());
        vo.setDescription(dashboard.getDescription());
        vo.setOwnerType(dashboard.getOwnerType());
        vo.setItemCount(dashboardItemMapper.countByDashboard(dashboard.getId()));
        vo.setCreatedAt(dashboard.getCreatedAt());
        vo.setUpdatedAt(dashboard.getUpdatedAt());
        if (withItems) {
            vo.setItems(toItemVOs(dashboardItemMapper.listByDashboard(dashboard.getId())));
        }
        return vo;
    }

    /**
     * 批量装配看板项：图表、数据集、版本、列各查询一次，避免逐项查询造成 N+1。
     * 历史孤儿引用不抛 404，显式标记为失效项，保证看板其余内容可正常访问。
     */
    private List<DashboardItemVO> toItemVOs(List<DashboardItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<Long> chartIds = items.stream().map(DashboardItem::getSavedChartId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SavedChart> charts = chartIds.isEmpty() ? Map.of()
                : savedChartMapper.selectBatchIds(chartIds).stream()
                        .filter(savedChartService::isVisibleChart)
                        .collect(Collectors.toMap(SavedChart::getId, c -> c));

        Set<Long> datasetIds = charts.values().stream().map(SavedChart::getDatasetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<Dataset> loadedDatasets = datasetIds.isEmpty() ? List.of()
                : datasetMapper.selectBatchIds(datasetIds);
        if (ownershipService.isPublic()) {
            loadedDatasets = loadedDatasets.stream()
                    .filter(ownershipService::isDatasetVisible).toList();
        }
        Map<Long, Dataset> datasets = loadedDatasets.stream()
                .collect(Collectors.toMap(Dataset::getId, d -> d));

        Set<Long> versionIds = new HashSet<>();
        for (SavedChart chart : charts.values()) {
            if (chart.getDatasetVersionId() != null) {
                versionIds.add(chart.getDatasetVersionId());
            }
            Dataset dataset = datasets.get(chart.getDatasetId());
            if (dataset != null && dataset.getCurrentVersionId() != null) {
                versionIds.add(dataset.getCurrentVersionId());
            }
        }
        Map<Long, DatasetVersion> versions = versionIds.isEmpty() ? Map.of()
                : datasetVersionMapper.selectBatchIds(versionIds).stream()
                        .collect(Collectors.toMap(DatasetVersion::getId, v -> v));

        Map<Long, Map<String, DatasetColumn>> columnsByDataset = new HashMap<>();
        if (!datasets.isEmpty()) {
            List<DatasetColumn> columns = datasetColumnMapper.selectList(
                    new LambdaQueryWrapper<DatasetColumn>()
                            .in(DatasetColumn::getDatasetId, datasets.keySet()));
            for (DatasetColumn column : columns) {
                columnsByDataset.computeIfAbsent(column.getDatasetId(), k -> new LinkedHashMap<>())
                        .put(column.getColumnAlias(), column);
            }
        }

        List<DashboardItemVO> result = new ArrayList<>(items.size());
        for (DashboardItem item : items) {
            SavedChart chart = charts.get(item.getSavedChartId());
            if (chart == null) {
                result.add(invalidItemVO(item, null, "图表已删除，无法展示"));
                continue;
            }
            Dataset dataset = datasets.get(chart.getDatasetId());
            if (dataset == null) {
                result.add(invalidItemVO(item, chart, "数据集已删除，无法展示"));
                continue;
            }
            DatasetVersion saved = chart.getDatasetVersionId() == null
                    ? null : versions.get(chart.getDatasetVersionId());
            DatasetVersion current = dataset.getCurrentVersionId() == null
                    ? null : versions.get(dataset.getCurrentVersionId());
            Map<String, DatasetColumn> columns = columnsByDataset.getOrDefault(dataset.getId(), Map.of());
            ChartViewStatusVO status = savedChartService.buildStatus(chart, dataset, saved, current, columns);
            DashboardItemVO itemVO = baseItemVO(item, chart);
            itemVO.setStale(status.getStale());
            itemVO.setMissingFields(status.getMissingFields());
            itemVO.setSavedVersionId(status.getSavedVersionId());
            itemVO.setCurrentVersionId(status.getCurrentVersionId());
            result.add(itemVO);
        }
        return result;
    }

    /** 失效看板项：保留定位与布局信息，供前端展示异常态并允许用户移除。 */
    private DashboardItemVO invalidItemVO(DashboardItem item, SavedChart chart, String reason) {
        DashboardItemVO vo = baseItemVO(item, chart);
        vo.setStale(false);
        vo.setMissingFields(List.of());
        vo.setInvalid(true);
        vo.setInvalidReason(reason);
        return vo;
    }

    private DashboardItemVO baseItemVO(DashboardItem item, SavedChart chart) {
        DashboardItemVO vo = new DashboardItemVO();
        vo.setId(item.getId());
        vo.setDashboardId(item.getDashboardId());
        vo.setSavedChartId(item.getSavedChartId());
        vo.setTitle(chart != null ? chart.getTitle() : "已失效图表");
        vo.setChartType(chart != null ? chart.getChartType() : null);
        vo.setSortOrder(item.getSortOrder());
        vo.setGridX(item.getGridX());
        vo.setGridY(item.getGridY());
        vo.setGridW(item.getGridW());
        vo.setGridH(item.getGridH());
        return vo;
    }

    /** 单项装配：复用批量逻辑，保证与看板详情一致。 */
    private DashboardItemVO toItemVO(DashboardItem item, SavedChart chart) {
        return toItemVOs(List.of(item)).get(0);
    }

    private Grid validateGrid(DashboardItemDTO dto) {
        int x = dto.getGridX() == null ? 0 : dto.getGridX();
        int y = dto.getGridY() == null ? 0 : dto.getGridY();
        int w = dto.getGridW() == null ? 4 : dto.getGridW();
        int h = dto.getGridH() == null ? 4 : dto.getGridH();
        if (x < 0 || x >= GRID_COLUMNS) {
            throw new BusinessException(400, "栅格 X 必须在 0-" + (GRID_COLUMNS - 1) + " 之间");
        }
        if (w < 2 || w > GRID_COLUMNS) {
            throw new BusinessException(400, "栅格宽度必须在 2-" + GRID_COLUMNS + " 之间");
        }
        if (x + w > GRID_COLUMNS) {
            throw new BusinessException(400, "栅格超出 12 列边界");
        }
        if (y < 0) {
            throw new BusinessException(400, "栅格 Y 不能为负数");
        }
        if (h < 2 || h > 12) {
            throw new BusinessException(400, "栅格高度必须在 2-12 之间");
        }
        return new Grid(x, y, w, h);
    }

    private void validateTitle(DashboardDTO dto) {
        if (dto == null || dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BusinessException(400, "看板标题不能为空");
        }
        if (dto.getTitle().length() > 200) {
            throw new BusinessException(400, "看板标题不能超过 200 个字符");
        }
        if (dto.getDescription() != null && dto.getDescription().length() > 500) {
            throw new BusinessException(400, "看板描述不能超过 500 个字符");
        }
    }

    private void touch(Long dashboardId) {
        Dashboard dashboard = dashboardMapper.selectById(dashboardId);
        if (dashboard != null) {
            dashboard.setUpdatedAt(LocalDateTime.now());
            dashboardMapper.updateById(dashboard);
        }
    }

    private record Grid(int x, int y, int w, int h) {
    }
}
