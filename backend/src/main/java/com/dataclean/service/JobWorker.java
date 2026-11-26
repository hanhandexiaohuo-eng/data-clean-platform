package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.config.JobProperties;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.CleanLog;
import com.dataclean.entity.CleanRule;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.entity.DatasetVersionRule;
import com.dataclean.exception.BusinessException;
import com.dataclean.job.JobCancelledException;
import com.dataclean.job.JobStatus;
import com.dataclean.mapper.AsyncJobMapper;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.DatasetVersionRuleMapper;
import com.dataclean.util.SqlGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久化任务执行器。
 *
 * <p>任务状态全部落库；启动时把残留 RUNNING 任务标记为 RECOVERABLE。
 * 每个任务在规则边界、批次边界检查取消请求；取消/失败都会删除刚创建的版本表，
 * 不会留下半成品版本或临时文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorker {

    private final AsyncJobMapper jobMapper;
    private final JobService jobService;
    private final JobProperties jobProperties;
    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DatasetVersionMapper versionMapper;
    private final DatasetVersionRuleMapper versionRuleMapper;
    private final CleanLogMapper cleanLogMapper;
    private final VersionService versionService;
    private final CleanService cleanService;
    private final StreamingImportService importService;
    private final ObjectMapper objectMapper;

    private final AtomicInteger running = new AtomicInteger();
    private ExecutorService executor;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        jobService.recoverOnStartup();
        executor = Executors.newFixedThreadPool(Math.max(1, jobProperties.getWorkerThreads()), r -> {
            Thread t = new Thread(r, "dc-job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:2000}", initialDelay = 5000)
    public void pump() {
        if (!jobProperties.isEnabled() || executor == null) {
            return;
        }
        int free = jobProperties.getWorkerThreads() - running.get();
        while (free-- > 0) {
            AsyncJob job = claimNext();
            if (job == null) {
                return;
            }
            running.incrementAndGet();
            executor.submit(() -> {
                try {
                    execute(job);
                } finally {
                    running.decrementAndGet();
                }
            });
        }
    }

    private AsyncJob claimNext() {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<AsyncJob> pending = jobMapper.selectPending(1);
            if (pending.isEmpty()) {
                return null;
            }
            AsyncJob job = pending.get(0);
            if (jobMapper.markRunning(job.getId()) == 1) {
                return jobMapper.selectById(job.getId());
            }
        }
        return null;
    }

    private void execute(AsyncJob job) {
        Long buildingVersionId = null;
        String buildingTable = null;
        boolean importType = JobService.TYPE_IMPORT.equals(job.getJobType());
        try {
            jobService.updateProgress(job.getId(), "STARTING", 1, 0, 0);
            switch (job.getJobType()) {
                case JobService.TYPE_IMPORT -> {
                    ImportOutcome outcome = runImport(job);
                    buildingVersionId = outcome.versionId;
                    buildingTable = outcome.table;
                    jobService.markSucceeded(job, outcome.versionId, outcome.rows,
                            "导入完成，版本 " + outcome.versionNo);
                    deleteTempFile(job);
                }
                case JobService.TYPE_CLEAN -> {
                    CleanOutcome outcome = runClean(job);
                    buildingVersionId = outcome.versionId;
                    buildingTable = outcome.table;
                    jobService.markSucceeded(job, outcome.versionId, outcome.rows,
                            "清洗完成，版本 " + outcome.versionNo);
                }
                case JobService.TYPE_RESTORE -> {
                    runRestore(job);
                    jobService.markSucceeded(job, job.getOutputVersionId(), 0L, "已切换到指定版本");
                }
                default -> throw new BusinessException(400, "不支持的任务类型");
            }
        } catch (JobCancelledException e) {
            cleanupPartial(buildingTable, buildingVersionId, job);
            if (importType) {
                // 取消同样不允许留下空表与永久"处理中"的占位数据集
                dropImportTable(job);
            }
            jobService.markCancelled(job);
            deleteTempFile(job);
            log.info("任务已取消: id={}", job.getId());
        } catch (BusinessException e) {
            cleanupPartial(buildingTable, buildingVersionId, job);
            if (importType) {
                dropImportTable(job);
            }
            jobService.markFailed(job, String.valueOf(e.getCode()), safeMessage(e));
            log.warn("任务失败: id={}, code={}", job.getId(), e.getCode());
        } catch (Exception e) {
            cleanupPartial(buildingTable, buildingVersionId, job);
            if (importType) {
                dropImportTable(job);
            }
            jobService.markFailed(job, "INTERNAL", "任务执行失败，请稍后重试");
            log.error("任务执行异常: id={}, type={}", job.getId(), e.getClass().getSimpleName(), e);
        }
    }

    // ----------------------------------------------------------------- 导入

    private ImportOutcome runImport(AsyncJob job) throws Exception {
        Map<String, Object> payload = jobService.readPayload(job);
        Long datasetId = toLong(payload.get("datasetId"));
        Dataset dataset = datasetId == null ? null : datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BusinessException(404, "导入目标不存在");
        }
        File file = new File(String.valueOf(payload.get("tempPath")));
        if (!file.isFile()) {
            throw new BusinessException(400, "导入文件已失效，请重新上传");
        }
        jobService.checkpoint(job);
        jobService.updateProgress(job.getId(), "PARSING", 3, 0, 0);

        String fileType = String.valueOf(payload.getOrDefault("fileType", "csv"));
        boolean hasHeader = !"false".equals(String.valueOf(payload.getOrDefault("hasHeader", "true")));
        String encoding = String.valueOf(payload.getOrDefault("encoding", "UTF-8"));
        long rows = importService.importFile(file, fileType, hasHeader, encoding, dataset, job.getId());

        jobService.checkpoint(job);
        jobService.updateProgress(job.getId(), "VERIFYING", 96, rows, rows);

        String checksum = versionService.checksum(dataset.getTableName());
        DatasetVersion version = new DatasetVersion();
        version.setDatasetId(dataset.getId());
        version.setVersionNo(versionMapper.maxVersionNo(dataset.getId()) + 1);
        version.setTableName(dataset.getTableName());
        version.setRowCount(rows);
        version.setColumnCount(dataset.getColumnCount() == null ? 0 : dataset.getColumnCount());
        version.setChecksum(checksum);
        version.setStatus(DatasetVersion.STATUS_ACTIVE);
        version.setSource(DatasetVersion.SOURCE_IMPORT);
        version.setJobId(job.getId());
        version.setOwnerType(dataset.getOwnerType());
        version.setAnonSessionId(dataset.getAnonSessionId());
        version.setActivatedAt(LocalDateTime.now());
        versionMapper.insert(version);

        dataset.setRowCount((int) rows);
        dataset.setCurrentVersionId(version.getId());
        dataset.setStatus(1);
        datasetMapper.updateById(dataset);
        return new ImportOutcome(rows, version.getId(), dataset.getTableName(), version.getVersionNo());
    }

    // ----------------------------------------------------------------- 清洗

    private CleanOutcome runClean(AsyncJob job) throws Exception {
        Map<String, Object> payload = jobService.readPayload(job);
        Dataset dataset = datasetMapper.selectById(job.getDatasetId());
        if (dataset == null) {
            throw new BusinessException(404, "数据集不存在");
        }
        versionService.ensureInitialVersion(dataset);
        List<Long> ruleIds = new ArrayList<>();
        Object raw = payload.get("ruleIds");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                ruleIds.add(toLong(item));
            }
        }
        List<CleanRule> rules = cleanService.loadRulesForJob(dataset.getId(), ruleIds);
        if (rules.isEmpty()) {
            throw new BusinessException(400, "没有有效的清洗规则");
        }

        jobService.checkpoint(job);
        DatasetVersion version = versionService.createBuildingVersion(dataset, DatasetVersion.SOURCE_CLEAN, job.getId());
        String newTable = version.getTableName();
        String sourceTable = SqlGuard.requireTableName(dataset.getTableName());

        long beforeCount = safeRowCount(sourceTable);
        jobService.updateProgress(job.getId(), "BUILDING", 8, 0, beforeCount);

        datasetMapper.executeDynamicSql("CREATE TABLE `" + newTable + "` LIKE `" + sourceTable + "`");
        jobService.checkpoint(job);
        datasetMapper.executeDynamicSql("INSERT INTO `" + newTable + "` SELECT * FROM `" + sourceTable + "`");
        jobService.updateProgress(job.getId(), "BUILDING", 22, beforeCount, beforeCount);

        snapshotRules(version.getId(), rules);

        int index = 0;
        for (CleanRule rule : rules) {
            jobService.checkpoint(job);
            cleanService.applyRule(newTable, rule);
            index++;
            int progress = 22 + (int) (60.0 * index / rules.size());
            jobService.updateProgress(job.getId(), "CLEANING", progress, beforeCount, beforeCount);
        }

        jobService.checkpoint(job);
        long afterCount = safeRowCount(newTable);
        String checksum = versionService.checksum(newTable);
        version.setRowCount(afterCount);
        version.setChecksum(checksum);
        version.setColumnCount(columnCount(newTable));
        versionMapper.updateById(version);

        jobService.updateProgress(job.getId(), "SWITCHING", 92, afterCount, beforeCount);
        jobService.checkpoint(job);

        int beforeCountInt = (int) beforeCount;
        versionService.activate(dataset, version);

        CleanLog cleanLog = new CleanLog();
        cleanLog.setDatasetId(dataset.getId());
        cleanLog.setRuleIds(ruleIds.toString());
        cleanLog.setRuleSnapshot(objectMapper.writeValueAsString(rules));
        cleanLog.setBeforeCount(beforeCountInt);
        cleanLog.setAfterCount((int) afterCount);
        cleanLog.setAffectedRows(beforeCountInt - (int) afterCount);
        // 版本化清洗不再生成备份表，backup_table 列保持非空约束，用空串表示"无备份表"
        cleanLog.setBackupTable("");
        cleanLog.setStatus(1);
        cleanLog.setExecuteTimeMs((int) Math.max(0, job.getStartedAt() == null ? 0
                : java.time.Duration.between(job.getStartedAt(), LocalDateTime.now()).toMillis()));
        cleanLog.setAnonSessionId(dataset.getAnonSessionId());
        try {
            cleanLogMapper.insert(cleanLog);
            versionService.pruneVersions(dataset);
        } catch (Exception e) {
            // 版本已激活，日志/保留策略失败不能回滚已提交的数据切换；明确记录，避免任务与版本状态不一致
            log.warn("清洗后置处理失败（版本已激活）: jobId={}, versionId={}", job.getId(), version.getId(), e);
        }
        return new CleanOutcome(afterCount, version.getId(), newTable, version.getVersionNo());
    }

    private void snapshotRules(Long versionId, List<CleanRule> rules) throws Exception {
        int order = 0;
        for (CleanRule rule : rules) {
            DatasetVersionRule snapshot = new DatasetVersionRule();
            snapshot.setVersionId(versionId);
            snapshot.setRuleId(rule.getId());
            snapshot.setRuleType(rule.getRuleType());
            snapshot.setRuleConfig(rule.getRuleConfig());
            snapshot.setSortOrder(rule.getSortOrder() == null ? order : rule.getSortOrder());
            snapshot.setSnapshotHash(sha256(rule.getRuleType() + "|" + rule.getRuleConfig()));
            versionRuleMapper.insert(snapshot);
            order++;
        }
    }

    // ----------------------------------------------------------------- 恢复

    private void runRestore(AsyncJob job) throws Exception {
        Dataset dataset = datasetMapper.selectById(job.getDatasetId());
        if (dataset == null) {
            throw new BusinessException(404, "数据集不存在");
        }
        DatasetVersion target = versionService.requireVersion(dataset.getId(), job.getOutputVersionId());
        if (DatasetVersion.STATUS_BUILDING.equals(target.getStatus())) {
            throw new BusinessException(400, "目标版本尚未完成，无法恢复");
        }
        if (!versionService.tableExists(target.getTableName())) {
            throw new BusinessException(400, "目标版本物理表不存在，无法恢复");
        }
        jobService.checkpoint(job);
        jobService.updateProgress(job.getId(), "VERIFYING", 30, 0, 0);

        String actual = versionService.checksum(target.getTableName());
        if (target.getChecksum() != null && !target.getChecksum().isBlank()
                && !target.getChecksum().equals(actual)) {
            throw new BusinessException(400, "目标版本校验失败，已终止恢复");
        }
        if (target.getChecksum() == null || target.getChecksum().isBlank()) {
            target.setChecksum(actual);
            versionMapper.updateById(target);
        }
        jobService.checkpoint(job);
        jobService.updateProgress(job.getId(), "SWITCHING", 85, 0, 0);
        versionService.activate(dataset, target);
    }

    // ------------------------------------------------------------- 清理辅助

    private void cleanupPartial(String table, Long versionId, AsyncJob job) {
        if (table != null && versionId != null) {
            try {
                versionService.dropVersionTable(table);
            } catch (RuntimeException e) {
                log.warn("删除半成品版本表失败: {}", e.getClass().getSimpleName());
            }
        }
        if (versionId != null) {
            try {
                versionService.deleteVersionRules(versionId);
                versionMapper.deleteById(versionId);
            } catch (RuntimeException e) {
                log.warn("删除半成品版本记录失败: {}", e.getClass().getSimpleName());
            }
        }
        // 兜底：按 job_id 清理所有仍处于 BUILDING 的版本，避免异常发生在中途时留下半成品
        if (job != null && job.getId() != null) {
            try {
                List<DatasetVersion> building = versionMapper.selectList(new LambdaQueryWrapper<DatasetVersion>()
                        .eq(DatasetVersion::getJobId, job.getId())
                        .eq(DatasetVersion::getStatus, DatasetVersion.STATUS_BUILDING));
                for (DatasetVersion version : building) {
                    if (!version.getId().equals(versionId)) {
                        versionService.dropVersionTable(version.getTableName());
                        versionService.deleteVersionRules(version.getId());
                        versionMapper.deleteById(version.getId());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("按任务清理半成品版本失败: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void dropImportTable(AsyncJob job) {
        try {
            Dataset dataset = job.getDatasetId() == null ? null : datasetMapper.selectById(job.getDatasetId());
            if (dataset == null) {
                return;
            }
            datasetMapper.executeDynamicSql("DROP TABLE IF EXISTS `" + dataset.getTableName() + "`");
            datasetColumnMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.dataclean.entity.DatasetColumn>()
                    .eq(com.dataclean.entity.DatasetColumn::getDatasetId, dataset.getId()));
            dataset.setStatus(2);
            datasetMapper.updateById(dataset);
        } catch (RuntimeException e) {
            log.warn("清理导入半成品失败: {}", e.getClass().getSimpleName());
        }
    }

    private void deleteTempFile(AsyncJob job) {
        try {
            Object path = jobService.readPayload(job).get("tempPath");
            if (path != null) {
                File file = new File(String.valueOf(path));
                if (file.isFile() && file.getCanonicalPath().contains(File.separator + "tmp" + File.separator)) {
                    if (!file.delete()) {
                        log.warn("临时导入文件删除失败");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("临时导入文件清理异常: {}", e.getClass().getSimpleName());
        }
    }

    private long safeRowCount(String table) {
        Long count = datasetMapper.selectDynamicDataCount(table);
        return count == null ? 0L : count;
    }

    private int columnCount(String table) {
        List<Map<String, Object>> rows = datasetMapper.selectDynamicSqlWithParams(
                "SELECT COUNT(*) AS c FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = #{params[0]} AND COLUMN_NAME != '_id'",
                List.of(table));
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("c")).intValue();
    }

    private String safeMessage(BusinessException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "任务执行失败";
        }
        String lower = message.toLowerCase();
        if (lower.contains("select ") || lower.contains("insert ") || lower.contains("`")
                || lower.contains("jdbc") || message.length() > 200) {
            return "任务执行失败，请稍后重试";
        }
        return message;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record ImportOutcome(long rows, Long versionId, String table, int versionNo) {
    }

    private record CleanOutcome(long rows, Long versionId, String table, int versionNo) {
    }
}
