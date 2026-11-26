package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataclean.dto.ChartRequestDTO;
import com.dataclean.dto.ChartViewStatusVO;
import com.dataclean.dto.SavedChartDTO;
import com.dataclean.dto.SavedChartVO;
import com.dataclean.entity.DashboardItem;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.entity.SavedChart;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DashboardItemMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.SavedChartMapper;
import com.dataclean.util.SqlGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 保存的图表视图：配置、数据版本与可见性。
 *
 * <p>版本变化不静默套用：生成前必须显式确认当前版本；字段失效返回可恢复的 409。
 */
@Service
@RequiredArgsConstructor
public class SavedChartService {

    private static final Set<String> ALLOWED_CONFIG_KEYS = Set.of(
            "chartType", "xAxis", "yAxis", "groupBy", "aggregation", "filters", "limit",
            "title", "colorTheme", "mode", "timeGranularity", "topN", "bucketCount", "sort");

    private final SavedChartMapper savedChartMapper;
    private final DashboardItemMapper dashboardItemMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final OwnershipService ownershipService;
    private final AnalysisService analysisService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public List<SavedChartVO> list(Long datasetId) {
        ownershipService.requireDataset(datasetId);
        LambdaQueryWrapper<SavedChart> wrapper = visibleWrapper();
        wrapper.eq(SavedChart::getDatasetId, datasetId).orderByDesc(SavedChart::getUpdatedAt);
        List<SavedChart> charts = savedChartMapper.selectList(wrapper);
        Dataset dataset = ownershipService.requireDataset(datasetId);
        List<SavedChartVO> result = new ArrayList<>();
        for (SavedChart chart : charts) {
            result.add(toVO(chart, dataset));
        }
        return result;
    }

    public SavedChartVO detail(Long id) {
        SavedChart chart = requireVisible(id);
        Dataset dataset = ownershipService.requireDataset(chart.getDatasetId());
        return toVO(chart, dataset);
    }

    public ChartViewStatusVO status(Long id) {
        SavedChart chart = requireVisible(id);
        Dataset dataset = ownershipService.requireDataset(chart.getDatasetId());
        return buildStatus(chart, dataset);
    }

    public SavedChartVO create(SavedChartDTO dto) {
        if (dto == null || dto.getDatasetId() == null) {
            throw new BusinessException(400, "数据集不能为空");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BusinessException(400, "图表标题不能为空");
        }
        if (dto.getTitle().length() > 200) {
            throw new BusinessException(400, "图表标题不能超过 200 个字符");
        }
        Dataset dataset = ownershipService.requireDataset(dto.getDatasetId());
        Map<String, Object> config = normalizeConfig(dto, dataset);

        SavedChart chart = new SavedChart();
        chart.setDatasetId(dataset.getId());
        chart.setDatasetVersionId(dataset.getCurrentVersionId());
        chart.setOwnerType(ownershipService.currentOwnerType());
        chart.setAnonSessionId(ownershipService.isPublic() ? ownershipService.currentSessionId() : null);
        chart.setTitle(dto.getTitle().trim());
        chart.setChartType(dto.getChartType().trim().toLowerCase(Locale.ROOT));
        chart.setChartConfig(writeJson(config));
        chart.setConfigHash(sha256(writeJson(config)));
        chart.setCreatedAt(LocalDateTime.now());
        chart.setUpdatedAt(LocalDateTime.now());
        savedChartMapper.insert(chart);

        auditService.record("CHART_VIEW_SAVE", "saved_chart", chart.getId(), dataset.getId(),
                null, dataset.getCurrentVersionId(), chart.getOwnerType(), chart.getAnonSessionId(),
                null, "SUCCESS", null, "chartType=" + chart.getChartType());
        return toVO(chart, dataset);
    }

    public SavedChartVO update(Long id, SavedChartDTO dto) {
        SavedChart chart = requireVisible(id);
        Dataset dataset = ownershipService.requireDataset(chart.getDatasetId());
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BusinessException(400, "图表标题不能为空");
        }
        if (dto.getTitle().length() > 200) {
            throw new BusinessException(400, "图表标题不能超过 200 个字符");
        }
        Map<String, Object> config = normalizeConfig(dto, dataset);
        chart.setTitle(dto.getTitle().trim());
        chart.setChartType(dto.getChartType().trim().toLowerCase(Locale.ROOT));
        chart.setChartConfig(writeJson(config));
        chart.setConfigHash(sha256(writeJson(config)));
        // 用户显式保存时重新绑定当前版本
        chart.setDatasetVersionId(dataset.getCurrentVersionId());
        chart.setUpdatedAt(LocalDateTime.now());
        savedChartMapper.updateById(chart);

        auditService.record("CHART_VIEW_UPDATE", "saved_chart", chart.getId(), dataset.getId(),
                null, dataset.getCurrentVersionId(), chart.getOwnerType(), chart.getAnonSessionId(),
                null, "SUCCESS", null, "chartType=" + chart.getChartType());
        return toVO(chart, dataset);
    }

    /**
     * 删除图表：同事务内移除所有看板对该图表的引用，避免看板详情因孤儿引用整体 404。
     * 审计记录保留（写入 audit_log），历史导出/AI 记录不随图表删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SavedChart chart = requireVisible(id);
        dashboardItemMapper.delete(new LambdaQueryWrapper<DashboardItem>()
                .eq(DashboardItem::getSavedChartId, id));
        savedChartMapper.deleteById(id);
        auditService.record("CHART_VIEW_DELETE", "saved_chart", id, chart.getDatasetId(),
                null, chart.getDatasetVersionId(), chart.getOwnerType(), chart.getAnonSessionId(),
                null, "SUCCESS", null, "chartType=" + chart.getChartType() + ",dashboardItemsRemoved=true");
    }

    /**
     * 按保存配置重新生成数据；stale 时拒绝静默套用。
     */
    public com.dataclean.dto.ChartDataVO generate(Long id) {
        return generate(id, null, true);
    }

    /**
     * 用户确认使用指定数据集版本后刷新图表。
     *
     * <p>仅当 {@code confirmedVersionId} 与数据集当前版本一致时才允许重绑并生成；
     * 否则保持原版本关联并返回 409，要求用户重新确认。
     */
    public com.dataclean.dto.ChartDataVO generate(Long id, Long confirmedVersionId) {
        return generate(id, confirmedVersionId, true);
    }

    /**
     * 导出流程内部使用：不额外登记 CHART 任务，导出任务本身已记录到任务中心。
     */
    public com.dataclean.dto.ChartDataVO generateForExport(Long id) {
        return generate(id, null, false);
    }

    private com.dataclean.dto.ChartDataVO generate(Long id, Long confirmedVersionId, boolean recordJob) {
        SavedChart chart = requireVisible(id);
        Dataset dataset = ownershipService.requireDataset(chart.getDatasetId());
        ChartViewStatusVO status = buildStatus(chart, dataset);
        List<String> missingFields = status.getMissingFields();
        if (missingFields != null && !missingFields.isEmpty()) {
            throw new BusinessException(409, "字段已变更，请重新配置: "
                    + String.join(", ", missingFields));
        }
        Long currentVersionId = dataset.getCurrentVersionId();
        boolean stale = !Objects.equals(chart.getDatasetVersionId(), currentVersionId);
        if (stale) {
            if (confirmedVersionId == null) {
                throw new BusinessException(409, "数据已更新，需要确认是否使用最新版本");
            }
            if (!Objects.equals(confirmedVersionId, currentVersionId)) {
                throw new BusinessException(409, "数据版本已再次变化，请重新确认");
            }
            Long previousVersionId = chart.getDatasetVersionId();
            // 条件更新：仅当图表仍绑定旧版本时才重绑，避免并发刷新互相覆盖
            LambdaUpdateWrapper<SavedChart> bind = new LambdaUpdateWrapper<>();
            bind.eq(SavedChart::getId, id)
                    .set(SavedChart::getDatasetVersionId, currentVersionId)
                    .set(SavedChart::getUpdatedAt, LocalDateTime.now());
            if (previousVersionId == null) {
                bind.isNull(SavedChart::getDatasetVersionId);
            } else {
                bind.eq(SavedChart::getDatasetVersionId, previousVersionId);
            }
            int updated = savedChartMapper.update(null, bind);
            if (updated == 0) {
                SavedChart latestChart = savedChartMapper.selectById(id);
                if (latestChart == null
                        || !Objects.equals(latestChart.getDatasetVersionId(), currentVersionId)) {
                    throw new BusinessException(409, "图表版本已变化，请重新确认");
                }
                chart = latestChart;
            } else {
                chart.setDatasetVersionId(currentVersionId);
            }
            Dataset latestDataset = ownershipService.requireDataset(chart.getDatasetId());
            if (!Objects.equals(latestDataset.getCurrentVersionId(), currentVersionId)) {
                // 检查与重绑之间数据集又产生了新版本：回滚本次绑定，避免绑定到已过期版本
                LambdaUpdateWrapper<SavedChart> revert = new LambdaUpdateWrapper<>();
                revert.eq(SavedChart::getId, id)
                        .eq(SavedChart::getDatasetVersionId, currentVersionId)
                        .set(SavedChart::getDatasetVersionId, previousVersionId)
                        .set(SavedChart::getUpdatedAt, LocalDateTime.now());
                savedChartMapper.update(null, revert);
                throw new BusinessException(409, "数据版本已再次变化，请重新确认");
            }
            dataset = latestDataset;
            auditService.record("CHART_VIEW_REFRESH", "saved_chart", chart.getId(), chart.getDatasetId(),
                    null, currentVersionId, chart.getOwnerType(), chart.getAnonSessionId(),
                    null, "SUCCESS", null,
                    "fromVersion=" + previousVersionId + ",toVersion=" + currentVersionId);
        }
        ChartRequestDTO request = readRequest(chart, dataset);
        com.dataclean.dto.ChartDataVO data = analysisService.generateChartData(request, recordJob);
        // 生成期间若数据集再次产生新版本，本次结果已不可信：拒绝返回并要求重新确认
        Dataset afterGenerate = ownershipService.requireDataset(chart.getDatasetId());
        if (!Objects.equals(afterGenerate.getCurrentVersionId(), currentVersionId)) {
            throw new BusinessException(409, "刷新过程中数据版本发生变化，请重新确认");
        }
        return data;
    }

    public SavedChart requireVisible(Long id) {
        SavedChart chart = findVisible(id);
        if (chart == null) {
            throw new BusinessException(404, "图表不存在");
        }
        return chart;
    }

    /**
     * 非抛异常的可见性查询：供看板装配等“允许降级”的场景使用。
     * 不存在或不属于当前会话/私有工作区时返回 null。
     */
    public SavedChart findVisible(Long id) {
        if (id == null) {
            return null;
        }
        SavedChart chart = savedChartMapper.selectById(id);
        return isVisibleChart(chart) ? chart : null;
    }

    /** 判断已加载的图表是否属于当前会话/私有工作区（供看板批量装配复用）。 */
    public boolean isVisibleChart(SavedChart chart) {
        if (chart == null) {
            return false;
        }
        if (ownershipService.isPublic()) {
            Long sessionId = ownershipService.currentSessionId();
            return OwnershipService.OWNER_ANON.equals(chart.getOwnerType())
                    && sessionId.equals(chart.getAnonSessionId());
        }
        return OwnershipService.OWNER_PRIVATE.equals(chart.getOwnerType());
    }

    public ChartViewStatusVO buildStatus(SavedChart chart, Dataset dataset) {
        DatasetVersion saved = chart.getDatasetVersionId() == null
                ? null : datasetVersionMapper.selectById(chart.getDatasetVersionId());
        DatasetVersion current = dataset.getCurrentVersionId() == null
                ? null : datasetVersionMapper.selectById(dataset.getCurrentVersionId());
        return buildStatus(chart, dataset, saved, current, columnMap(dataset.getId()));
    }

    /** 批量装配用重载：版本与列已由调用方预取，避免逐项查询造成 N+1。 */
    public ChartViewStatusVO buildStatus(SavedChart chart, Dataset dataset,
                                         DatasetVersion saved, DatasetVersion current,
                                         Map<String, DatasetColumn> columns) {
        ChartViewStatusVO status = new ChartViewStatusVO();
        status.setSavedChartId(chart.getId());
        status.setSavedVersionId(chart.getDatasetVersionId());
        status.setCurrentVersionId(dataset.getCurrentVersionId());
        if (saved != null) {
            status.setSavedVersionNo(saved.getVersionNo());
        }
        if (current != null) {
            status.setCurrentVersionNo(current.getVersionNo());
        }
        List<String> missing = missingFields(chart, columns);
        status.setMissingFields(missing);
        boolean stale = !Objects.equals(chart.getDatasetVersionId(), dataset.getCurrentVersionId());
        status.setStale(stale);
        if (!missing.isEmpty()) {
            status.setMessage("字段已变更，需要重新配置");
        } else if (stale) {
            status.setMessage("数据已更新，需要刷新");
        }
        return status;
    }

    private LambdaQueryWrapper<SavedChart> visibleWrapper() {
        LambdaQueryWrapper<SavedChart> wrapper = new LambdaQueryWrapper<>();
        if (ownershipService.isPublic()) {
            wrapper.eq(SavedChart::getOwnerType, OwnershipService.OWNER_ANON)
                    .eq(SavedChart::getAnonSessionId, ownershipService.currentSessionId());
        } else {
            wrapper.eq(SavedChart::getOwnerType, OwnershipService.OWNER_PRIVATE);
        }
        return wrapper;
    }

    private SavedChartVO toVO(SavedChart chart, Dataset dataset) {
        SavedChartVO vo = new SavedChartVO();
        vo.setId(chart.getId());
        vo.setDatasetId(chart.getDatasetId());
        vo.setDatasetName(dataset.getName());
        vo.setTitle(chart.getTitle());
        vo.setChartType(chart.getChartType());
        vo.setChartConfig(readConfig(chart.getChartConfig()));
        vo.setSavedVersionId(chart.getDatasetVersionId());
        vo.setCurrentVersionId(dataset.getCurrentVersionId());
        if (chart.getDatasetVersionId() != null) {
            DatasetVersion saved = datasetVersionMapper.selectById(chart.getDatasetVersionId());
            if (saved != null) {
                vo.setSavedVersionNo(saved.getVersionNo());
            }
        }
        if (dataset.getCurrentVersionId() != null) {
            DatasetVersion current = datasetVersionMapper.selectById(dataset.getCurrentVersionId());
            if (current != null) {
                vo.setCurrentVersionNo(current.getVersionNo());
            }
        }
        List<String> missing = missingFields(chart, columnMap(dataset.getId()));
        vo.setMissingFields(missing);
        vo.setStale(!Objects.equals(chart.getDatasetVersionId(), dataset.getCurrentVersionId()));
        vo.setCreatedAt(chart.getCreatedAt());
        vo.setUpdatedAt(chart.getUpdatedAt());
        return vo;
    }

    /**
     * 只允许白名单键，且字段必须存在；避免把任意 JSON 持久化后再次注入 SQL。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeConfig(SavedChartDTO dto, Dataset dataset) {
        if (dto.getChartType() == null || dto.getChartType().isBlank()) {
            throw new BusinessException(400, "图表类型不能为空");
        }
        SqlGuard.requireChartType(dto.getChartType().trim().toLowerCase(Locale.ROOT));
        if (dto.getChartConfig() == null) {
            throw new BusinessException(400, "图表配置不能为空");
        }
        Map<String, Object> raw = dto.getChartConfig();
        for (String key : raw.keySet()) {
            if (!ALLOWED_CONFIG_KEYS.contains(key)) {
                throw new BusinessException(400, "图表配置包含非法字段: " + key);
            }
        }
        Map<String, DatasetColumn> columns = columnMap(dataset.getId());
        Object x = raw.get("xAxis");
        Object y = raw.get("yAxis");
        Object group = raw.get("groupBy");
        validateColumn(x, columns);
        validateColumn(y, columns);
        validateColumn(group, columns);
        Object filters = raw.get("filters");
        if (filters instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> filter) {
                    validateColumn(filter.get("column"), columns);
                    Object operator = filter.get("operator");
                    if (operator == null) {
                        throw new BusinessException(400, "过滤条件缺少操作符");
                    }
                    SqlGuard.requireFilterOperator(operator.toString());
                }
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(raw);
        normalized.put("chartType", dto.getChartType().trim().toLowerCase(Locale.ROOT));
        return normalized;
    }

    private void validateColumn(Object value, Map<String, DatasetColumn> columns) {
        if (value == null || value.toString().isBlank()) {
            return;
        }
        if (!columns.containsKey(value.toString())) {
            throw new BusinessException(400, "字段不存在: " + value);
        }
    }

    private List<String> missingFields(SavedChart chart, Map<String, DatasetColumn> columns) {
        List<String> missing = new ArrayList<>();
        Map<String, Object> config = readConfig(chart.getChartConfig());
        for (String key : List.of("xAxis", "yAxis", "groupBy")) {
            Object value = config.get(key);
            if (value != null && !value.toString().isBlank() && !columns.containsKey(value.toString())) {
                missing.add(value.toString());
            }
        }
        Object filters = config.get("filters");
        if (filters instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> filter) {
                    Object column = filter.get("column");
                    if (column != null && !columns.containsKey(column.toString())) {
                        missing.add(column.toString());
                    }
                }
            }
        }
        return missing.stream().distinct().toList();
    }

    private ChartRequestDTO readRequest(SavedChart chart, Dataset dataset) {
        Map<String, Object> config = readConfig(chart.getChartConfig());
        ChartRequestDTO request = objectMapper.convertValue(config, ChartRequestDTO.class);
        request.setDatasetId(dataset.getId());
        request.setChartType(chart.getChartType());
        return request;
    }

    private Map<String, DatasetColumn> columnMap(Long datasetId) {
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);
        Map<String, DatasetColumn> map = new LinkedHashMap<>();
        if (columns != null) {
            for (DatasetColumn column : columns) {
                map.put(column.getColumnAlias(), column);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfig(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "图表配置无法读取");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(400, "图表配置格式不正确");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
