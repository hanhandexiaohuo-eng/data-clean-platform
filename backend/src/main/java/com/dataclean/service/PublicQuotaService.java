package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.entity.ChartView;
import com.dataclean.entity.ExportRecord;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.ChartViewMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.ExportRecordMapper;
import com.dataclean.util.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 公开模式容量配额。
 *
 * <p>私有模式全部为无操作，保持第二阶段行为不变。超限统一 429 或 400。
 */
@Service
@RequiredArgsConstructor
public class PublicQuotaService {

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final DatasetMapper datasetMapper;
    private final CleanLogMapper cleanLogMapper;
    private final ChartViewMapper chartViewMapper;
    private final ExportRecordMapper exportRecordMapper;

    public boolean isPublic() {
        return appMode.isPublic();
    }

    /** 上传前：数据集数量、总存储、单文件大小。 */
    public void checkUpload(long fileSizeBytes) {
        if (!appMode.isPublic()) {
            return;
        }
        Long sessionId = RequestContext.requireSessionId();
        if (fileSizeBytes > properties.maxFileSizeBytes()) {
            throw new BusinessException(400, "文件大小超过公开模式上限（" + properties.getMaxFileSizeMb() + "MB）");
        }
        long count = datasetMapper.countAnonDatasets(sessionId);
        if (count >= properties.getMaxDatasets()) {
            throw new BusinessException(429, "数据集数量已达上限（" + properties.getMaxDatasets() + " 个）");
        }
        long storage = datasetMapper.sumAnonStorage(sessionId);
        if (storage + fileSizeBytes > properties.maxTotalStorageBytes()) {
            throw new BusinessException(429, "存储空间已达上限（" + properties.getMaxTotalStorageMb() + "MB）");
        }
    }

    /** 解析后：行数、列数。 */
    public void checkRowsAndColumns(int rows, int columns) {
        if (!appMode.isPublic()) {
            return;
        }
        if (rows > properties.getMaxRows()) {
            throw new BusinessException(400, "行数超过公开模式上限（" + properties.getMaxRows() + " 行）");
        }
        if (columns > properties.getMaxColumns()) {
            throw new BusinessException(400, "列数超过公开模式上限（" + properties.getMaxColumns() + " 列）");
        }
    }

    public void checkCleanTask() {
        if (!appMode.isPublic()) {
            return;
        }
        Long sessionId = RequestContext.requireSessionId();
        if (cleanLogMapper.countByAnonSession(sessionId) >= properties.getMaxCleanTasks()) {
            throw new BusinessException(429, "清洗任务已达上限（" + properties.getMaxCleanTasks() + " 次）");
        }
    }

    public void checkChart() {
        if (!appMode.isPublic()) {
            return;
        }
        Long sessionId = RequestContext.requireSessionId();
        if (chartViewMapper.countBySession(sessionId) >= properties.getMaxCharts()) {
            throw new BusinessException(429, "图表生成次数已达上限（" + properties.getMaxCharts() + " 次）");
        }
    }

    public void recordChart(Long datasetId, String chartType, String chartConfig) {
        if (!appMode.isPublic()) {
            return;
        }
        ChartView view = new ChartView();
        view.setAnonSessionId(RequestContext.requireSessionId());
        view.setDatasetId(datasetId);
        view.setChartType(chartType);
        view.setChartConfig(chartConfig);
        view.setCreatedAt(LocalDateTime.now());
        chartViewMapper.insert(view);
    }

    public void checkExport() {
        if (!appMode.isPublic()) {
            return;
        }
        Long sessionId = RequestContext.requireSessionId();
        if (exportRecordMapper.countBySession(sessionId) >= properties.getMaxExports()) {
            throw new BusinessException(429, "导出次数已达上限（" + properties.getMaxExports() + " 次）");
        }
    }

    public void recordExport(Long datasetId, int rowCount) {
        recordExport(datasetId, "csv", rowCount);
    }

    public void recordExport(Long datasetId, String exportType, int rowCount) {
        if (!appMode.isPublic()) {
            return;
        }
        ExportRecord record = new ExportRecord();
        record.setAnonSessionId(RequestContext.requireSessionId());
        record.setDatasetId(datasetId);
        record.setExportType(exportType == null || exportType.isBlank() ? "csv" : exportType);
        record.setRowCount(rowCount);
        record.setCreatedAt(LocalDateTime.now());
        exportRecordMapper.insert(record);
    }

    public int exportMaxRows() {
        return properties.getExportMaxRows();
    }
}
