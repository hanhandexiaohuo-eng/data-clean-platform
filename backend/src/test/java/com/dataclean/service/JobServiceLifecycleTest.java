package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.JobProperties;
import com.dataclean.entity.AsyncJob;
import com.dataclean.exception.BusinessException;
import com.dataclean.job.JobCancelledException;
import com.dataclean.job.JobStatus;
import com.dataclean.mapper.AsyncJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 任务生命周期与跨会话可见性测试（不依赖数据库）。
 */
@ExtendWith(MockitoExtension.class)
class JobServiceLifecycleTest {

    @Mock
    private AsyncJobMapper jobMapper;
    @Mock
    private OwnershipService ownershipService;
    @Mock
    private AuditService auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobProperties properties;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        properties = new JobProperties();
    }

    private JobService publicService() {
        return new JobService(jobMapper, properties, new AppMode("public"),
                ownershipService, auditService, objectMapper);
    }

    private JobService privateService() {
        return new JobService(jobMapper, properties, new AppMode("private"),
                ownershipService, auditService, objectMapper);
    }

    private AsyncJob job(Long sessionId, String status) {
        AsyncJob job = new AsyncJob();
        job.setId(10L);
        job.setJobType("CLEAN");
        job.setDatasetId(3L);
        job.setStatus(status);
        job.setAnonSessionId(sessionId);
        job.setOwnerType(sessionId == null ? OwnershipService.OWNER_PRIVATE : OwnershipService.OWNER_ANON);
        job.setRetryCount(0);
        job.setMaxRetries(2);
        return job;
    }

    @Test
    void requireJob_crossSession_returns404() {
        JobService service = publicService();
        when(ownershipService.currentSessionId()).thenReturn(1L);
        when(jobMapper.selectById(10L)).thenReturn(job(2L, JobStatus.SUCCEEDED));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireJob(10L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void requireJob_sameSession_ok() {
        JobService service = publicService();
        when(ownershipService.currentSessionId()).thenReturn(1L);
        when(jobMapper.selectById(10L)).thenReturn(job(1L, JobStatus.PENDING));

        assertEquals(10L, service.requireJob(10L).getId());
    }

    @Test
    void requireJob_missing_returns404() {
        JobService service = privateService();
        when(jobMapper.selectById(anyLong())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireJob(99L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void cancel_terminalJob_rejected() {
        JobService service = privateService();
        when(jobMapper.selectById(10L)).thenReturn(job(null, JobStatus.SUCCEEDED));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(10L));
        assertEquals(400, ex.getCode());
        verify(jobMapper, never()).requestCancel(anyLong());
    }

    @Test
    void retry_whenSucceeded_rejected() {
        JobService service = privateService();
        when(jobMapper.selectById(10L)).thenReturn(job(null, JobStatus.SUCCEEDED));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(10L));
        assertEquals(400, ex.getCode());
    }

    @Test
    void retry_whenAttemptsExhausted_rejected() {
        JobService service = privateService();
        AsyncJob failed = job(null, JobStatus.FAILED);
        failed.setRetryCount(2);
        when(jobMapper.selectById(10L)).thenReturn(failed);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(10L));
        assertEquals(429, ex.getCode());
    }

    @Test
    void retry_movesFailedJobToPending() {
        JobService service = privateService();
        AsyncJob failed = job(null, JobStatus.FAILED);
        AsyncJob pending = job(null, JobStatus.PENDING);
        when(jobMapper.selectById(10L)).thenReturn(failed, pending);
        when(jobMapper.retry(10L, JobStatus.FAILED, JobStatus.PENDING)).thenReturn(1);

        AsyncJob result = service.retry(10L);

        assertEquals(JobStatus.PENDING, result.getStatus());
        verify(auditService).record(eq("CLEAN_RETRY"), anyString(), eq(10L), any(), eq(10L), any(),
                any(), any(), any(), eq("SUCCESS"), any(), any());
    }

    @Test
    void checkpoint_cancelRequested_throwsCancelled() {
        JobService service = privateService();
        AsyncJob running = job(null, JobStatus.RUNNING);
        when(jobMapper.selectById(10L)).thenReturn(job(null, JobStatus.CANCEL_REQUESTED));

        assertThrows(JobCancelledException.class, () -> service.checkpoint(running));
    }

    @Test
    void checkpoint_runtimeExceeded_throws408() {
        JobService service = privateService();
        properties.setMaxRuntimeSeconds(60);
        AsyncJob running = job(null, JobStatus.RUNNING);
        running.setStartedAt(LocalDateTime.now().minusSeconds(120));
        when(jobMapper.selectById(10L)).thenReturn(running);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkpoint(running));
        assertEquals(408, ex.getCode());
    }

    @Test
    void checkpoint_missingJob_throwsCancelled() {
        JobService service = privateService();
        when(jobMapper.selectById(10L)).thenReturn(null);
        assertThrows(JobCancelledException.class, () -> service.checkpoint(job(null, JobStatus.RUNNING)));
    }
}
