package com.dataclean.service;

import com.dataclean.entity.AuditLog;
import com.dataclean.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作审计：只记录操作类型、资源标识、结果与脱敏摘要。
 *
 * <p>禁止写入原始数据、Cookie、Token、AI Key、完整 SQL 或数据库连接信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public void record(String action, String resourceType, Long resourceId,
                       Long datasetId, Long jobId, Long versionId,
                       String ownerType, Long anonSessionId, Long userId,
                       String result, String errorType, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setAction(action);
            entry.setResourceType(resourceType);
            entry.setResourceId(resourceId);
            entry.setDatasetId(datasetId);
            entry.setJobId(jobId);
            entry.setVersionId(versionId);
            entry.setOwnerType(ownerType);
            entry.setAnonSessionId(anonSessionId);
            entry.setUserId(userId);
            entry.setResult(result);
            entry.setErrorType(errorType);
            entry.setDetail(trim(detail));
            auditLogMapper.insert(entry);
        } catch (RuntimeException e) {
            // 审计失败不能阻断主流程，但必须留下类型日志
            log.error("审计写入失败: action={}, type={}", action, e.getClass().getSimpleName());
        }
    }

    private String trim(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > 1000 ? detail.substring(0, 1000) : detail;
    }
}
