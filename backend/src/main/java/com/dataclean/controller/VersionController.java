package com.dataclean.controller;

import com.dataclean.config.AppMode;
import com.dataclean.dto.Result;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.exception.BusinessException;
import com.dataclean.service.AuditService;
import com.dataclean.service.JobService;
import com.dataclean.service.OwnershipService;
import com.dataclean.service.VersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集版本控制器：版本列表、恢复与删除。
 *
 * <p>恢复与删除都是破坏性操作，必须携带 {@code confirm=true} 并写入审计。
 * 公开模式下跨会话统一 404，演示数据只读。
 */
@RestController
@RequestMapping("/api/dataset/{datasetId}/versions")
@RequiredArgsConstructor
public class VersionController {

    private final OwnershipService ownershipService;
    private final VersionService versionService;
    private final JobService jobService;
    private final AuditService auditService;
    private final AppMode appMode;

    @GetMapping
    public Result<Map<String, Object>> list(@PathVariable Long datasetId) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        List<DatasetVersion> versions = versionService.listVersions(datasetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentVersionId", dataset.getCurrentVersionId());
        result.put("versions", versions);
        return Result.success(result);
    }

    /** 切换到指定历史版本（创建 RESTORE 任务，不覆盖任何版本数据）。 */
    @PostMapping("/{versionId}/restore")
    public Result<AsyncJob> restore(@PathVariable Long datasetId,
                                    @PathVariable Long versionId,
                                    @RequestParam(defaultValue = "false") boolean confirm) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        ownershipService.requireWritable(dataset);
        if (!confirm) {
            throw new BusinessException(400, "恢复版本为破坏性操作，请确认后重试");
        }
        DatasetVersion version = versionService.requireVersion(datasetId, versionId);
        if (DatasetVersion.STATUS_BUILDING.equals(version.getStatus())) {
            throw new BusinessException(400, "目标版本尚未完成，无法恢复");
        }
        if (!versionService.tableExists(version.getTableName())) {
            throw new BusinessException(400, "目标版本物理表不存在，无法恢复");
        }
        AsyncJob job = jobService.create(JobService.TYPE_RESTORE, dataset, dataset.getCurrentVersionId(),
                versionId, Map.of("versionId", versionId));
        auditService.record("VERSION_RESTORE_REQUEST", "VERSION", versionId, datasetId, job.getId(), versionId,
                appMode.isPublic() ? OwnershipService.OWNER_ANON : OwnershipService.OWNER_PRIVATE,
                appMode.isPublic() ? ownershipService.currentSessionId() : null, null, "SUCCESS", null, null);
        return Result.success(job);
    }

    /** 删除历史版本（破坏性操作；当前版本与最后一个可恢复版本不可删除）。 */
    @DeleteMapping("/{versionId}")
    public Result<Void> delete(@PathVariable Long datasetId,
                               @PathVariable Long versionId,
                               @RequestParam(defaultValue = "false") boolean confirm) {
        Dataset dataset = ownershipService.requireDataset(datasetId);
        ownershipService.requireWritable(dataset);
        if (!confirm) {
            throw new BusinessException(400, "删除版本为破坏性操作，请确认后重试");
        }
        versionService.deleteVersion(dataset, versionId);
        auditService.record("VERSION_DELETE", "VERSION", versionId, datasetId, null, versionId,
                appMode.isPublic() ? OwnershipService.OWNER_ANON : OwnershipService.OWNER_PRIVATE,
                appMode.isPublic() ? ownershipService.currentSessionId() : null, null, "SUCCESS", null, null);
        return Result.success();
    }
}
