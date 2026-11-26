package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.config.AppMode;
import com.dataclean.config.JobProperties;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.Dataset;
import com.dataclean.exception.BusinessException;
import com.dataclean.job.JobCancelledException;
import com.dataclean.job.JobStatus;
import com.dataclean.mapper.AsyncJobMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 异步任务生命周期管理：创建、配额、取消、重试、恢复与可见性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    public static final String TYPE_IMPORT = "IMPORT";
    public static final String TYPE_CLEAN = "CLEAN";
    public static final String TYPE_RESTORE = "RESTORE";
    /** 真实质量统计（同步执行，仍写入任务中心）。 */
    public static final String TYPE_QUALITY_STATS = "QUALITY_STATS";
    public static final String TYPE_CHART = "CHART";
    public static final String TYPE_EXPORT = "EXPORT";
    public static final String TYPE_AI = "AI";

    /** 只有这三类任务具备后台 Worker 与安全重试语义。 */
    private static final java.util.Set<String> WORKER_TYPES =
            java.util.Set.of(TYPE_IMPORT, TYPE_CLEAN, TYPE_RESTORE);

    private final AsyncJobMapper jobMapper;
    private final JobProperties jobProperties;
    private final AppMode appMode;
    private final OwnershipService ownershipService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AsyncJob create(String type, Dataset dataset, Long inputVersionId, Long outputVersionId,
                           Map<String, Object> payload) {
        Long sessionId = ownershipService.currentSessionId();
        checkQuota(sessionId);

        AsyncJob job = new AsyncJob();
        job.setJobType(type);
        job.setDatasetId(dataset == null ? null : dataset.getId());
        job.setInputVersionId(inputVersionId);
        job.setOutputVersionId(outputVersionId);
        job.setStatus(JobStatus.PENDING);
        job.setPhase("");
        job.setProgress(0);
        job.setProcessedRows(0L);
        job.setTotalRows(0L);
        job.setRetryCount(0);
        job.setMaxRetries(appMode.isPublic() ? jobProperties.getPublicMaxRetries()
                : jobProperties.getPrivateMaxRetries());
        job.setOwnerType(appMode.isPublic() ? OwnershipService.OWNER_ANON : OwnershipService.OWNER_PRIVATE);
        job.setAnonSessionId(sessionId);
        job.setPayload(writePayload(payload));
        jobMapper.insert(job);

        auditService.record(type + "_CREATE", "JOB", job.getId(),
                job.getDatasetId(), job.getId(), outputVersionId,
                job.getOwnerType(), sessionId, null, "SUCCESS", null, null);
        return job;
    }

    private void checkQuota(Long sessionId) {
        if (!appMode.isPublic()) {
            long active = jobMapper.selectCount(new LambdaQueryWrapper<AsyncJob>()
                    .in(AsyncJob::getStatus, JobStatus.PENDING, JobStatus.RUNNING, JobStatus.CANCEL_REQUESTED));
            if (active >= jobProperties.getPrivateMaxActive()) {
                throw new BusinessException(429, "后台任务过多，请稍后再试");
            }
            return;
        }
        if (sessionId == null) {
            throw new BusinessException(401, "会话已失效");
        }
        long active = jobMapper.countActiveBySession(sessionId);
        if (active >= jobProperties.getPublicMaxActivePerSession()) {
            throw new BusinessException(429, "当前会话已有进行中的任务，请等待完成或取消");
        }
        long today = jobMapper.countCreatedSince(sessionId, LocalDate.now().atStartOfDay());
        if (today >= jobProperties.getPublicMaxPerDay()) {
            throw new BusinessException(429, "今日任务数已达上限");
        }
    }

    /** 指定数据集上指定类型的进行中任务数，用于防止重复创建同类任务。 */
    public long countActiveByDataset(Long datasetId, String type) {
        if (datasetId == null) {
            return 0;
        }
        return jobMapper.selectCount(new LambdaQueryWrapper<AsyncJob>()
                .eq(AsyncJob::getDatasetId, datasetId)
                .eq(AsyncJob::getJobType, type)
                .in(AsyncJob::getStatus, JobStatus.PENDING, JobStatus.RUNNING, JobStatus.CANCEL_REQUESTED));
    }

    public List<AsyncJob> listByDataset(Long datasetId) {
        ownershipService.requireDataset(datasetId);
        LambdaQueryWrapper<AsyncJob> wrapper = new LambdaQueryWrapper<AsyncJob>()
                .eq(AsyncJob::getDatasetId, datasetId)
                .orderByDesc(AsyncJob::getId);
        if (appMode.isPublic()) {
            wrapper.eq(AsyncJob::getAnonSessionId, ownershipService.currentSessionId());
        }
        return jobMapper.selectList(wrapper);
    }

    /** 当前调用者可见的任务列表：公开模式只看本会话，私有模式看全部。 */
    public List<AsyncJob> listMine() {
        LambdaQueryWrapper<AsyncJob> wrapper = new LambdaQueryWrapper<AsyncJob>()
                .orderByDesc(AsyncJob::getId)
                .last("LIMIT 100");
        if (appMode.isPublic()) {
            wrapper.eq(AsyncJob::getAnonSessionId, ownershipService.currentSessionId());
        }
        return jobMapper.selectList(wrapper);
    }

    /** 任务可见性：跨会话统一 404。 */
    public AsyncJob requireJob(Long jobId) {
        AsyncJob job = jobId == null ? null : jobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (appMode.isPublic() && !ownershipService.currentSessionId().equals(job.getAnonSessionId())) {
            throw new BusinessException(404, "任务不存在");
        }
        return job;
    }

    public AsyncJob cancel(Long jobId) {
        AsyncJob job = requireJob(jobId);
        if (JobStatus.isTerminal(job.getStatus())) {
            throw new BusinessException(400, "任务已结束，无法取消");
        }
        if (JobStatus.CANCEL_REQUESTED.equals(job.getStatus())) {
            return jobMapper.selectById(jobId);
        }
        int pending = jobMapper.cancelPending(jobId);
        if (pending == 0) {
            jobMapper.requestCancel(jobId);
        }
        AsyncJob updated = jobMapper.selectById(jobId);
        auditService.record(job.getJobType() + "_CANCEL", "JOB", jobId, job.getDatasetId(), jobId,
                job.getOutputVersionId(), job.getOwnerType(), job.getAnonSessionId(), job.getUserId(),
                "SUCCESS", null, null);
        return updated;
    }

    public AsyncJob retry(Long jobId) {
        AsyncJob job = requireJob(jobId);
        if (!WORKER_TYPES.contains(job.getJobType())) {
            // 同步任务没有可重放的 Worker，禁止把状态改成 PENDING 造成“假排队”。
            throw new BusinessException(400, "该任务类型不支持重试，请在原页面重新发起");
        }
        if (!(JobStatus.FAILED.equals(job.getStatus())
                || JobStatus.RECOVERABLE.equals(job.getStatus())
                || JobStatus.CANCELLED.equals(job.getStatus()))) {
            throw new BusinessException(400, "当前状态不支持重试");
        }
        int maxRetries = job.getMaxRetries() == null ? 0 : job.getMaxRetries();
        if (job.getRetryCount() != null && job.getRetryCount() >= maxRetries) {
            throw new BusinessException(429, "重试次数已达上限");
        }
        if (jobMapper.retry(jobId, job.getStatus(), JobStatus.PENDING) == 0) {
            throw new BusinessException(409, "任务状态已变化，请刷新后重试");
        }
        AsyncJob updated = jobMapper.selectById(jobId);
        auditService.record(job.getJobType() + "_RETRY", "JOB", jobId, job.getDatasetId(), jobId,
                job.getOutputVersionId(), job.getOwnerType(), job.getAnonSessionId(), job.getUserId(),
                "SUCCESS", null, null);
        return updated;
    }

    /** 同步任务开始执行：PENDING → RUNNING（失败时不阻断业务异常）。 */
    public AsyncJob begin(AsyncJob job) {
        jobMapper.markRunning(job.getId());
        AsyncJob running = jobMapper.selectById(job.getId());
        return running == null ? job : running;
    }

    /** 安全失败摘要：只保留可展示文案，绝不包含 SQL/堆栈/路径。 */
    public void markFailedSafely(AsyncJob job, String errorCode, Throwable error) {
        String summary = "任务执行失败，请稍后重试";
        if (error instanceof BusinessException businessException
                && businessException.getCode() < 500
                && businessException.getMessage() != null) {
            summary = businessException.getMessage();
        }
        markFailed(job, errorCode, summary);
    }

    public void updateProgress(Long jobId, String phase, int progress, long processedRows, long totalRows) {
        jobMapper.updateProgress(jobId, phase, Math.max(0, Math.min(100, progress)), processedRows, totalRows);
    }

    /** 安全检查点：检测取消请求与运行超时。 */
    public void checkpoint(AsyncJob job) {
        AsyncJob latest = jobMapper.selectById(job.getId());
        if (latest == null) {
            throw new JobCancelledException();
        }
        if (JobStatus.CANCEL_REQUESTED.equals(latest.getStatus())
                || JobStatus.CANCELLED.equals(latest.getStatus())) {
            throw new JobCancelledException();
        }
        if (latest.getStartedAt() != null
                && latest.getStartedAt().plusSeconds(jobProperties.getMaxRuntimeSeconds()).isBefore(LocalDateTime.now())) {
            throw new BusinessException(408, "任务运行超时，已停止");
        }
    }

    public void markSucceeded(AsyncJob job, Long outputVersionId, long rows, String resultSummary) {
        jobMapper.finish(job.getId(), JobStatus.SUCCEEDED, "DONE", 100, rows, rows,
                outputVersionId, null, null, resultSummary);
        auditService.record(job.getJobType() + "_SUCCEEDED", "JOB", job.getId(), job.getDatasetId(), job.getId(),
                outputVersionId, job.getOwnerType(), job.getAnonSessionId(), job.getUserId(), "SUCCESS", null, null);
    }

    public void markFailed(AsyncJob job, String errorCode, String safeSummary) {
        jobMapper.finish(job.getId(), JobStatus.FAILED, "FAILED", 0, 0L, 0L,
                null, errorCode, safeSummary, null);
        auditService.record(job.getJobType() + "_FAILED", "JOB", job.getId(), job.getDatasetId(), job.getId(),
                job.getOutputVersionId(), job.getOwnerType(), job.getAnonSessionId(), job.getUserId(),
                "FAILED", errorCode, null);
    }

    public void markCancelled(AsyncJob job) {
        jobMapper.finish(job.getId(), JobStatus.CANCELLED, "CANCELLED", 0, 0L, 0L,
                null, null, null, "已取消");
    }

    /** 启动时把残留 RUNNING 任务降级为 RECOVERABLE。 */
    public int recoverOnStartup() {
        int count = jobMapper.recoverRunning();
        if (count > 0) {
            log.warn("检测到 {} 个未完成的异步任务，已标记为 RECOVERABLE", count);
        }
        return count;
    }

    public Map<String, Object> readPayload(AsyncJob job) {
        if (job.getPayload() == null || job.getPayload().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(job.getPayload(), Map.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "任务参数损坏，请重新创建任务");
        }
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return payload == null ? null : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "任务参数序列化失败");
        }
    }
}
