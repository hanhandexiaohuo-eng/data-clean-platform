package com.dataclean.controller;

import com.dataclean.dto.AiCallPageVO;
import com.dataclean.dto.AiCircuitVO;
import com.dataclean.dto.AiProviderDTO;
import com.dataclean.dto.AiProviderVO;
import com.dataclean.dto.AiUsageSummaryVO;
import com.dataclean.dto.Result;
import com.dataclean.entity.AiModel;
import com.dataclean.entity.AiSceneRoute;
import com.dataclean.exception.BusinessException;
import com.dataclean.service.AiGovernanceAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

/**
 * AI 治理管理接口（仅私有模式；公开模式一律 404）。
 */
@RestController
@RequestMapping("/api/ai/governance")
@RequiredArgsConstructor
public class AiGovernanceController {

    private final AiGovernanceAdminService adminService;

    // ========== 供应商 ==========

    @GetMapping("/providers")
    public Result<List<AiProviderVO>> listProviders() {
        adminService.requirePrivate();
        return Result.success(adminService.listProviders());
    }

    @PostMapping("/providers")
    public Result<Long> createProvider(@RequestBody AiProviderDTO dto,
                                       @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        adminService.requirePrivate();
        requireConfirm(confirm);
        dto.setId(null);
        return Result.success(adminService.saveProvider(dto));
    }

    @PutMapping("/providers")
    public Result<Long> updateProvider(@RequestBody AiProviderDTO dto,
                                       @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        adminService.requirePrivate();
        requireConfirm(confirm);
        if (dto.getId() == null) {
            throw new BusinessException(400, "供应商 ID 不能为空");
        }
        return Result.success(adminService.saveProvider(dto));
    }

    @DeleteMapping("/providers/{id}")
    public Result<Void> deleteProvider(@PathVariable Long id,
                                       @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        adminService.requirePrivate();
        requireConfirm(confirm);
        adminService.deleteProvider(id);
        return Result.success();
    }

    // ========== 模型 ==========

    @GetMapping("/models")
    public Result<List<AiModel>> listModels() {
        adminService.requirePrivate();
        return Result.success(adminService.listModels());
    }

    @PostMapping("/models")
    public Result<Long> createModel(@RequestBody AiModel dto) {
        adminService.requirePrivate();
        dto.setId(null);
        return Result.success(adminService.saveModel(dto));
    }

    @PutMapping("/models")
    public Result<Long> updateModel(@RequestBody AiModel dto) {
        adminService.requirePrivate();
        if (dto.getId() == null) {
            throw new BusinessException(400, "模型 ID 不能为空");
        }
        return Result.success(adminService.saveModel(dto));
    }

    @DeleteMapping("/models/{id}")
    public Result<Void> deleteModel(@PathVariable Long id) {
        adminService.requirePrivate();
        adminService.deleteModel(id);
        return Result.success();
    }

    // ========== 场景路由 ==========

    @GetMapping("/scenes")
    public Result<List<AiSceneRoute>> listRoutes() {
        adminService.requirePrivate();
        return Result.success(adminService.listRoutes());
    }

    @PostMapping("/scenes")
    public Result<Long> createRoute(@RequestBody AiSceneRoute dto) {
        adminService.requirePrivate();
        dto.setId(null);
        return Result.success(adminService.saveRoute(dto));
    }

    @PutMapping("/scenes")
    public Result<Long> updateRoute(@RequestBody AiSceneRoute dto) {
        adminService.requirePrivate();
        if (dto.getId() == null) {
            throw new BusinessException(400, "路由 ID 不能为空");
        }
        return Result.success(adminService.saveRoute(dto));
    }

    // ========== 审计 / 用量 / 熔断 ==========

    @GetMapping("/calls")
    public Result<AiCallPageVO> listCalls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        adminService.requirePrivate();
        return Result.success(adminService.listCalls(from, to, scene, modelCode, result, errorType, page, size));
    }

    @GetMapping("/usage")
    public Result<AiUsageSummaryVO> usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        adminService.requirePrivate();
        return Result.success(adminService.usage(date));
    }

    @GetMapping("/circuits")
    public Result<List<AiCircuitVO>> circuits() {
        adminService.requirePrivate();
        return Result.success(adminService.circuits());
    }

    @PostMapping("/circuits/{id}/reset")
    public Result<Void> resetCircuit(@PathVariable Long id,
                                     @RequestHeader(value = "X-Confirm", required = false) String confirm) {
        adminService.requirePrivate();
        requireConfirm(confirm);
        adminService.resetCircuit(id);
        return Result.success();
    }

    private void requireConfirm(String confirm) {
        if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
            throw new BusinessException(400, "请确认后再执行该操作");
        }
    }
}
