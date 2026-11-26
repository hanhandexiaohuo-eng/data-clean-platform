package com.dataclean.controller;

import com.dataclean.dto.ChartViewStatusVO;
import com.dataclean.dto.Result;
import com.dataclean.dto.SavedChartDTO;
import com.dataclean.dto.SavedChartVO;
import com.dataclean.service.SavedChartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/chart-view")
@RequiredArgsConstructor
public class ChartViewController {

    private final SavedChartService savedChartService;

    @GetMapping
    public Result<List<SavedChartVO>> list(@RequestParam Long datasetId) {
        return Result.success(savedChartService.list(datasetId));
    }

    @GetMapping("/{id}")
    public Result<SavedChartVO> detail(@PathVariable Long id) {
        return Result.success(savedChartService.detail(id));
    }

    @GetMapping("/{id}/status")
    public Result<ChartViewStatusVO> status(@PathVariable Long id) {
        return Result.success(savedChartService.status(id));
    }

    @PostMapping
    public Result<SavedChartVO> create(@RequestBody SavedChartDTO dto) {
        return Result.success(savedChartService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<SavedChartVO> update(@PathVariable Long id, @RequestBody SavedChartDTO dto) {
        return Result.success(savedChartService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        requireConfirm(confirm);
        savedChartService.delete(id);
        return Result.success();
    }

    /**
     * 按保存配置生成数据。
     *
     * <p>数据版本变化时必须由前端显式携带当前版本确认，否则返回 409 并附带版本信息，
     * 绝不静默把旧配置应用到新数据上。
     */
    @PostMapping("/{id}/generate")
    public ResponseEntity<Result<?>> generate(
            @PathVariable Long id,
            @RequestHeader(value = "X-Confirm-Version", required = false) Long confirmVersion) {
        ChartViewStatusVO status = savedChartService.status(id);
        boolean hasMissingFields = status.getMissingFields() != null && !status.getMissingFields().isEmpty();
        if (hasMissingFields) {
            return conflict("字段已变更，需要重新配置", status);
        }
        if (Boolean.TRUE.equals(status.getStale())) {
            if (confirmVersion == null) {
                return conflict("数据已更新，需要确认是否使用最新版本", status);
            }
            if (!Objects.equals(confirmVersion, status.getCurrentVersionId())) {
                return conflict("数据版本已再次变化，请重新确认", status);
            }
        }
        return ResponseEntity.ok(Result.success(savedChartService.generate(id, confirmVersion)));
    }

    private ResponseEntity<Result<?>> conflict(String message, ChartViewStatusVO status) {
        Result<Object> payload = Result.fail(409, message);
        payload.setData(status);
        return ResponseEntity.status(409).body(payload);
    }

    private void requireConfirm(String confirm) {
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new com.dataclean.exception.BusinessException(400, "请确认后再执行该操作");
        }
    }
}
