package com.dataclean.controller;

import com.dataclean.dto.CleanExecuteDTO;
import com.dataclean.dto.CleanResultVO;
import com.dataclean.dto.CleanRuleDTO;
import com.dataclean.dto.Result;
import com.dataclean.entity.CleanLog;
import com.dataclean.entity.CleanRule;
import com.dataclean.service.CleanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据清洗控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/clean")
@RequiredArgsConstructor
public class CleanController {

    private final CleanService cleanService;

    /**
     * 查询数据集的清洗规则
     */
    @GetMapping("/rule")
    public Result<List<CleanRule>> listRules(@RequestParam Long datasetId) {
        return Result.success(cleanService.listRulesByDataset(datasetId));
    }

    /**
     * 创建清洗规则
     */
    @PostMapping("/rule")
    public Result<CleanRule> createRule(@RequestBody CleanRuleDTO dto) {
        return Result.success(cleanService.createRule(dto));
    }

    /**
     * 更新清洗规则
     */
    @PutMapping("/rule/{id}")
    public Result<CleanRule> updateRule(@PathVariable Long id, @RequestBody CleanRuleDTO dto) {
        dto.setId(id);
        return Result.success(cleanService.updateRule(dto));
    }

    /**
     * 删除清洗规则
     */
    @DeleteMapping("/rule/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        cleanService.deleteRule(id);
        return Result.success();
    }

    /**
     * 执行清洗
     */
    @PostMapping("/execute")
    public Result<CleanResultVO> executeClean(@RequestBody CleanExecuteDTO dto) {
        return Result.success(cleanService.executeClean(dto));
    }

    /**
     * 预览清洗效果
     */
    @PostMapping("/preview")
    public Result<CleanResultVO> previewClean(@RequestBody CleanExecuteDTO dto) {
        dto.setPreviewMode(true);
        return Result.success(cleanService.executeClean(dto));
    }

    /**
     * 回滚清洗
     */
    @PostMapping("/rollback/{logId}")
    public Result<Void> rollbackClean(@PathVariable Long logId) {
        cleanService.rollbackClean(logId);
        return Result.success();
    }

    /**
     * 查询清洗日志
     */
    @GetMapping("/log")
    public Result<List<CleanLog>> listLogs(@RequestParam Long datasetId) {
        return Result.success(cleanService.listCleanLogs(datasetId));
    }
}
