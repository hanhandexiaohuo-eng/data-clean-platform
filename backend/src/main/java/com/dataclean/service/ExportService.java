package com.dataclean.service;

import com.dataclean.dto.ChartDataVO;
import com.dataclean.dto.DatasetPreviewVO;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.SavedChart;
import com.dataclean.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据集 CSV 导出。
 *
 * <p>安全要点：演示数据禁止导出；公开模式计入导出配额；公式注入统一转义；
 * 导出上限内截断并显式告知调用方。
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final int PAGE_SIZE = 200;

    private final DatasetService datasetService;
    private final OwnershipService ownershipService;
    private final PublicQuotaService quotaService;
    private final SavedChartService savedChartService;
    private final JobService jobService;
    private final ChartPngRenderer chartPngRenderer;

    public ExportResult exportCsv(Long datasetId) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        ownershipService.requireWritable(dataset);
        AsyncJob job = jobService.create(JobService.TYPE_EXPORT, dataset, dataset.getCurrentVersionId(), null,
                Map.of("format", "csv", "scope", "dataset"));
        jobService.begin(job);
        try {
            ExportResult result = doExportCsv(dataset);
            jobService.markSucceeded(job, null, result.rows(),
                    "CSV 导出行数=" + result.rows() + (result.truncated() ? "（已截断）" : ""));
            return result;
        } catch (BusinessException e) {
            jobService.markFailedSafely(job,
                    e.getCode() == 429 ? "QUOTA_EXCEEDED" : "EXPORT_FAILED", e);
            throw e;
        } catch (RuntimeException e) {
            BusinessException safe = new BusinessException(500, "导出失败，请稍后重试");
            jobService.markFailedSafely(job, "EXPORT_FAILED", safe);
            throw safe;
        }
    }

    private ExportResult doExportCsv(Dataset dataset) {
        Long datasetId = dataset.getId();
        quotaService.checkExport();

        int maxRows = quotaService.isPublic() ? quotaService.exportMaxRows() : 100_000;

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF'); // BOM，便于 Excel 正确识别 UTF-8

        List<DatasetPreviewVO.ColumnInfoVO> columns = null;
        int exported = 0;
        boolean truncated = false;
        int page = 1;

        while (exported < maxRows) {
            DatasetPreviewVO preview = datasetService.previewData(datasetId, page, PAGE_SIZE);
            if (columns == null) {
                columns = preview.getColumns();
                csv.append(headerLine(columnNames(columns)));
            }
            List<Map<String, Object>> rows = preview.getRows();
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                if (exported >= maxRows) {
                    truncated = true;
                    break;
                }
                csv.append(rowLine(columnNames(columns), row));
                exported++;
            }
            long total = preview.getTotal() == null ? 0 : preview.getTotal();
            if (exported >= total || rows.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }

        quotaService.recordExport(datasetId, exported);
        return new ExportResult(csv.toString().getBytes(StandardCharsets.UTF_8), truncated, exported);
    }

    /**
     * 导出保存图表当前配置的聚合结果，而不是全量原始数据。
     *
     * <p>版本失效不做静默套用：由 {@link SavedChartService#generate(Long)} 抛出 409；
     * 公开模式仍受导出次数与行数限制。
     */
    public ExportResult exportChartCsv(Long chartViewId) {
        SavedChart chart = savedChartService.requireVisible(chartViewId);
        Dataset dataset = ownershipService.requireDataset(chart.getDatasetId());
        AsyncJob job = jobService.create(JobService.TYPE_EXPORT, dataset, chart.getDatasetVersionId(), null,
                Map.of("format", "csv", "scope", "chart", "chartViewId", chartViewId));
        jobService.begin(job);
        try {
            ExportResult result = doExportChartCsv(chart);
            jobService.markSucceeded(job, null, result.rows(),
                    "图表 CSV 导出行数=" + result.rows() + (result.truncated() ? "（已截断）" : ""));
            return result;
        } catch (BusinessException e) {
            jobService.markFailedSafely(job,
                    e.getCode() == 429 ? "QUOTA_EXCEEDED" : "EXPORT_FAILED", e);
            throw e;
        } catch (RuntimeException e) {
            BusinessException safe = new BusinessException(500, "导出失败，请稍后重试");
            jobService.markFailedSafely(job, "EXPORT_FAILED", safe);
            throw safe;
        }
    }

    /** 受控 PNG 导出：渲染已聚合结果，不读取全量明细。 */
    public ExportResult exportChartPng(Long chartViewId, int width, int height) {
        SavedChart chart = savedChartService.requireVisible(chartViewId);
        Dataset dataset = ownershipService.requireDataset(chart.getDatasetId());
        quotaService.checkExport();
        AsyncJob job = jobService.create(JobService.TYPE_EXPORT, dataset, chart.getDatasetVersionId(), null,
                Map.of("format", "png", "scope", "chart", "chartViewId", chartViewId));
        jobService.begin(job);
        try {
            ChartDataVO data = savedChartService.generateForExport(chartViewId);
            byte[] png = chartPngRenderer.render(chart.getTitle(), data, width, height);
            int points = data.getRawData() == null ? 0 : data.getRawData().size();
            quotaService.recordExport(chart.getDatasetId(), "png", points);
            jobService.markSucceeded(job, null, points, "PNG 图表导出，数据点=" + points);
            return new ExportResult(png, Boolean.TRUE.equals(data.getTruncated()), points);
        } catch (BusinessException e) {
            jobService.markFailedSafely(job,
                    e.getCode() == 429 ? "QUOTA_EXCEEDED" : "EXPORT_FAILED", e);
            throw e;
        } catch (RuntimeException e) {
            BusinessException safe = new BusinessException(500, "PNG 导出失败，请稍后重试");
            jobService.markFailedSafely(job, "EXPORT_FAILED", safe);
            throw safe;
        }
    }

    private ExportResult doExportChartCsv(SavedChart chart) {
        quotaService.checkExport();
        ChartDataVO data = savedChartService.generateForExport(chart.getId());

        int maxRows = quotaService.isPublic() ? quotaService.exportMaxRows() : 100_000;
        List<Map<String, Object>> rows = data.getRawData() == null ? List.of() : data.getRawData();

        List<String> names = new ArrayList<>();
        Set<String> ordered = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            ordered.addAll(row.keySet());
        }
        names.addAll(ordered);

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        if (!names.isEmpty()) {
            csv.append(headerLine(names));
        }
        int exported = 0;
        boolean truncated = false;
        for (Map<String, Object> row : rows) {
            if (exported >= maxRows) {
                truncated = true;
                break;
            }
            csv.append(rowLine(names, row));
            exported++;
        }

        quotaService.recordExport(chart.getDatasetId(), "chart", exported);
        return new ExportResult(csv.toString().getBytes(StandardCharsets.UTF_8), truncated, exported);
    }

    private List<String> columnNames(List<DatasetPreviewVO.ColumnInfoVO> columns) {
        List<String> names = new ArrayList<>();
        for (DatasetPreviewVO.ColumnInfoVO column : columns) {
            names.add(column.getName());
        }
        return names;
    }

    private String headerLine(List<String> names) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(csvCell(names.get(i)));
        }
        return line.append("\r\n").toString();
    }

    private String rowLine(List<String> names, Map<String, Object> row) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            Object value = row.get(names.get(i));
            line.append(csvCell(value == null ? "" : String.valueOf(value)));
        }
        return line.append("\r\n").toString();
    }

    /** RFC4180 转义 + 公式注入防护 */
    private String csvCell(String raw) {
        String value = raw;
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public record ExportResult(byte[] content, boolean truncated, int rows) {
    }
}
