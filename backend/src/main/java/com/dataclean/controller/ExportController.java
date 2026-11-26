package com.dataclean.controller;

import com.dataclean.exception.BusinessException;
import com.dataclean.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据集导出（需二次确认）。
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @PostMapping("/dataset/{id}")
    public ResponseEntity<byte[]> exportDataset(
            @PathVariable Long id,
            @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new BusinessException(400, "请确认后再执行导出");
        }
        ExportService.ExportResult result = exportService.exportCsv(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dataset-" + id + ".csv\"")
                .header("X-Export-Truncated", String.valueOf(result.truncated()))
                .header("X-Export-Rows", String.valueOf(result.rows()))
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(result.content());
    }

    @PostMapping("/chart/{chartViewId}/png")
    public ResponseEntity<byte[]> exportChartPng(
            @PathVariable Long chartViewId,
            @RequestParam(defaultValue = "1200") int width,
            @RequestParam(defaultValue = "720") int height,
            @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new BusinessException(400, "请确认后再执行导出");
        }
        ExportService.ExportResult result = exportService.exportChartPng(chartViewId, width, height);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"chart-" + chartViewId + ".png\"")
                .header("X-Export-Truncated", String.valueOf(result.truncated()))
                .header("X-Export-Rows", String.valueOf(result.rows()))
                .contentType(MediaType.IMAGE_PNG)
                .body(result.content());
    }

    @PostMapping("/chart/{chartViewId}")
    public ResponseEntity<byte[]> exportChart(
            @PathVariable Long chartViewId,
            @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new BusinessException(400, "请确认后再执行导出");
        }
        ExportService.ExportResult result = exportService.exportChartCsv(chartViewId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"chart-" + chartViewId + ".csv\"")
                .header("X-Export-Truncated", String.valueOf(result.truncated()))
                .header("X-Export-Rows", String.valueOf(result.rows()))
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(result.content());
    }
}
