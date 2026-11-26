package com.dataclean.controller;

import com.dataclean.dto.Result;
import com.dataclean.service.DataQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据质量中心真实统计接口。
 *
 * <p>归属校验统一由 DataQualityService → OwnershipService 完成：
 * 跨会话/跨私有工作区一律 404，不泄露资源是否存在。
 */
@RestController
@RequestMapping("/api/quality")
@RequiredArgsConstructor
public class QualityController {

    private final DataQualityService dataQualityService;

    /** 读取已采集结果；没有记录时返回 status=NOT_COLLECTED。 */
    @GetMapping("/{datasetId}")
    public Result<Map<String, Object>> report(@PathVariable Long datasetId,
                                              @RequestParam(required = false) Long versionId) {
        return Result.success(dataQualityService.getReport(datasetId, versionId));
    }

    /** 触发一次真实统计（数据库聚合），并写入任务中心。 */
    @PostMapping("/{datasetId}/compute")
    public Result<Map<String, Object>> compute(@PathVariable Long datasetId,
                                               @RequestParam(required = false) Long versionId,
                                               @RequestParam(required = false) String columns,
                                               @RequestParam(required = false) Integer sampleLimit,
                                               @RequestParam(defaultValue = "false") boolean refresh) {
        return Result.success(dataQualityService.compute(datasetId, versionId, columns, sampleLimit, refresh));
    }
}
