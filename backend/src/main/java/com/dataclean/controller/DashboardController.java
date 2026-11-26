package com.dataclean.controller;

import com.dataclean.dto.DashboardDTO;
import com.dataclean.dto.DashboardItemDTO;
import com.dataclean.dto.DashboardItemVO;
import com.dataclean.dto.DashboardVO;
import com.dataclean.dto.Result;
import com.dataclean.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public Result<List<DashboardVO>> list() {
        return Result.success(dashboardService.list());
    }

    @GetMapping("/{id}")
    public Result<DashboardVO> detail(@PathVariable Long id) {
        return Result.success(dashboardService.detail(id));
    }

    @PostMapping
    public Result<DashboardVO> create(@RequestBody DashboardDTO dto) {
        return Result.success(dashboardService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<DashboardVO> rename(@PathVariable Long id, @RequestBody DashboardDTO dto) {
        return Result.success(dashboardService.rename(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        requireConfirm(confirm);
        dashboardService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/items")
    public Result<DashboardItemVO> addItem(@PathVariable Long id, @RequestBody DashboardItemDTO dto) {
        return Result.success(dashboardService.addItem(id, dto));
    }

    @PutMapping("/{id}/items/{itemId}")
    public Result<DashboardItemVO> updateItem(@PathVariable Long id, @PathVariable Long itemId,
                                              @RequestBody DashboardItemDTO dto) {
        return Result.success(dashboardService.updateItem(id, itemId, dto));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public Result<Void> removeItem(@PathVariable Long id, @PathVariable Long itemId,
                                   @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        requireConfirm(confirm);
        dashboardService.removeItem(id, itemId);
        return Result.success();
    }

    @PutMapping("/{id}/order")
    public Result<DashboardVO> reorder(@PathVariable Long id, @RequestBody List<Long> itemIds) {
        return Result.success(dashboardService.reorder(id, itemIds));
    }

    private void requireConfirm(String confirm) {
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new com.dataclean.exception.BusinessException(400, "请确认后再执行该操作");
        }
    }
}
