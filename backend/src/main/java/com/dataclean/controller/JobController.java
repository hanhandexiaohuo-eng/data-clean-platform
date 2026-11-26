package com.dataclean.controller;

import com.dataclean.dto.Result;
import com.dataclean.entity.AsyncJob;
import com.dataclean.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 异步任务控制器：状态查询、取消与重试。
 *
 * <p>所有接口都经过 {@link JobService} 的归属校验：公开模式下跨会话一律 404。
 */
@RestController
@RequestMapping("/api/job")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /** 当前调用者可见的任务列表（公开模式仅本会话）。 */
    @GetMapping
    public Result<List<AsyncJob>> list() {
        return Result.success(jobService.listMine());
    }

    /** 指定数据集的任务列表。 */
    @GetMapping("/dataset/{datasetId}")
    public Result<List<AsyncJob>> listByDataset(@PathVariable Long datasetId) {
        return Result.success(jobService.listByDataset(datasetId));
    }

    @GetMapping("/{id}")
    public Result<AsyncJob> get(@PathVariable Long id) {
        return Result.success(jobService.requireJob(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<AsyncJob> cancel(@PathVariable Long id) {
        return Result.success(jobService.cancel(id));
    }

    @PostMapping("/{id}/retry")
    public Result<AsyncJob> retry(@PathVariable Long id) {
        return Result.success(jobService.retry(id));
    }
}
