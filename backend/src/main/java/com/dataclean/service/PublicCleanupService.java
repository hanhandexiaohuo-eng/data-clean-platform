package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.entity.AnonSession;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.AuditLog;
import com.dataclean.entity.BackupTableRecord;
import com.dataclean.entity.CleanLog;
import com.dataclean.entity.CleanupItem;
import com.dataclean.entity.CleanupLog;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.entity.StatsCacheEntry;
import com.dataclean.mapper.AiChallengeMapper;
import com.dataclean.mapper.AiGenerationLogMapper;
import com.dataclean.mapper.AnonSessionMapper;
import com.dataclean.mapper.AnonUsageMapper;
import com.dataclean.mapper.AsyncJobMapper;
import com.dataclean.mapper.AuditLogMapper;
import com.dataclean.mapper.BackupTableRecordMapper;
import com.dataclean.mapper.ChartViewMapper;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.CleanupItemMapper;
import com.dataclean.mapper.CleanupLogMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.DashboardItemMapper;
import com.dataclean.mapper.DashboardMapper;
import com.dataclean.mapper.DataQualityReportMapper;
import com.dataclean.mapper.DatasetVersionRuleMapper;
import com.dataclean.mapper.ExportRecordMapper;
import com.dataclean.mapper.SavedChartMapper;
import com.dataclean.mapper.StatsCacheMapper;
import com.dataclean.metrics.RuntimeMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 公开模式临时数据清理。
 *
 * <p>状态机：{@code active → purging → purged}，失败进入 {@code failed} 并在下一轮幂等重试。
 * 关键规则：
 * <ul>
 *   <li>只删除来自数据库元数据（{@code dataset.table_name} / {@code clean_log.backup_table}）
 *       且通过严格命名校验的精确表名，禁止按前缀正则扫描删除；</li>
 *   <li>先落库清理明细（cleanup_item），再逐项执行，进程中断可续跑；</li>
 *   <li>资源全部清理完成并确认无元数据残留后，才删除会话记录；</li>
 *   <li>孤儿表只统计上报，不自动删除。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicCleanupService {

    /**
     * 物理表名只做“标识符合法性”校验，具体归属由数据库元数据（dataset / clean_log）精确证明，
     * 不使用任何前缀白名单扫描。
     */
    private static final Pattern TABLE_IDENTIFIER =
            Pattern.compile("^[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]{0,63}$");
    private static final int BATCH_LIMIT = 50;
    private static final int STALE_PURGE_MINUTES = 10;

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final AnonSessionMapper anonSessionMapper;
    private final AnonUsageMapper anonUsageMapper;
    private final CleanupItemMapper cleanupItemMapper;
    private final CleanupLogMapper cleanupLogMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final CleanRuleMapper cleanRuleMapper;
    private final CleanLogMapper cleanLogMapper;
    private final ChartViewMapper chartViewMapper;
    private final ExportRecordMapper exportRecordMapper;
    private final AiGenerationLogMapper aiGenerationLogMapper;
    private final AiChallengeMapper aiChallengeMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetVersionRuleMapper datasetVersionRuleMapper;
    private final DataQualityReportMapper dataQualityReportMapper;
    private final AsyncJobMapper asyncJobMapper;
    private final BackupTableRecordMapper backupTableRecordMapper;
    private final StatsCacheMapper statsCacheMapper;
    private final SavedChartMapper savedChartMapper;
    private final DashboardMapper dashboardMapper;
    private final DashboardItemMapper dashboardItemMapper;
    private final AuditLogMapper auditLogMapper;
    private final DynamicTableService dynamicTableService;
    private final RuntimeMetrics metrics;

    @Scheduled(fixedDelayString = "#{60000 * ${app.public.cleanup-interval-minutes:60}}",
            initialDelay = 60000)
    public void scheduledCleanup() {
        if (!appMode.isPublic() || !properties.isCleanupEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.error("公开数据清理任务异常: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 执行一轮清理，返回本轮处理的会话数。可被管理接口手动触发。
     */
    public int runOnce() {
        if (!appMode.isPublic()) {
            return 0;
        }
        long startedMs = System.currentTimeMillis();

        // 用量计数只保留 7 天，避免计数表无限增长（只含会话/匿名摘要，无原始数据）
        try {
            anonUsageMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.AnonUsageDaily>()
                    .lt(com.dataclean.entity.AnonUsageDaily::getUsageDate, java.time.LocalDate.now().minusDays(7)));
        } catch (Exception e) {
            log.warn("清理过期用量计数失败: {}", e.getClass().getSimpleName());
        }

        long orphanTables = datasetMapper.countOrphanTables();
        if (orphanTables > 0) {
            log.warn("检测到 {} 张疑似孤儿动态表（仅报告，不自动删除）", orphanTables);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(properties.getCleanupGraceMinutes());
        List<AnonSession> candidates = anonSessionMapper.selectPurgeCandidates(cutoff, BATCH_LIMIT);
        List<AnonSession> stale = anonSessionMapper.selectStalePurging(now.minusMinutes(STALE_PURGE_MINUTES), BATCH_LIMIT);
        candidates.addAll(stale);

        int handled = 0;
        CleanupLog summary = new CleanupLog();
        summary.setStartedAt(now);
        summary.setSessionsCleaned(0);
        summary.setDatasetsRemoved(0);
        summary.setTablesDropped(0);
        summary.setRowsRemoved(0);
        summary.setFailures(0);
        summary.setOrphanTablesReported((int) orphanTables);

        for (AnonSession session : candidates) {
            if (anonSessionMapper.claimForPurge(session.getId()) == 0) {
                continue;
            }
            handled++;
            try {
                PurgeResult result = purgeSession(session.getId());
                summary.setSessionsCleaned(summary.getSessionsCleaned() + 1);
                summary.setDatasetsRemoved(summary.getDatasetsRemoved() + result.datasets);
                summary.setTablesDropped(summary.getTablesDropped() + result.tables);
            } catch (Exception e) {
                summary.setFailures(summary.getFailures() + 1);
                String errorType = e.getClass().getSimpleName();
                anonSessionMapper.markFailed(session.getId(), truncate(errorType));
                log.warn("会话清理失败，将下轮重试: sessionId={}, error={}", session.getId(), errorType);
            }
        }

        summary.setFinishedAt(LocalDateTime.now());
        summary.setDetail(truncate("handled=" + handled + ",orphan=" + orphanTables));
        try {
            cleanupLogMapper.insert(summary);
        } catch (Exception e) {
            log.debug("写入清理日志失败: {}", e.getClass().getSimpleName());
        }
        if (handled > 0) {
            log.info("公开数据清理完成: 会话 {} 个，失败 {} 项", handled, summary.getFailures());
        }
        metrics.cleanupFinished(System.currentTimeMillis() - startedMs,
                summary.getFailures(), (int) orphanTables);
        return handled;
    }

    private PurgeResult purgeSession(Long sessionId) {
        PurgeResult result = new PurgeResult();
        materializeItems(sessionId, result);

        // 逐项执行；失败项保持 pending/failed 供下轮重试
        List<CleanupItem> items = cleanupItemMapper.selectBySession(sessionId);
        boolean allDone = true;
        for (CleanupItem item : items) {
            if ("done".equals(item.getState())) {
                continue;
            }
            try {
                boolean changed = executeItem(sessionId, item);
                cleanupItemMapper.updateState(item.getId(), "done", null);
                if (changed && "dataset_table".equals(item.getResourceType())) {
                    result.tables++;
                }
            } catch (Exception e) {
                allDone = false;
                cleanupItemMapper.updateState(item.getId(), "failed", truncate(e.getClass().getSimpleName()));
                log.warn("清理明细失败: sessionId={}, type={}, error={}",
                        sessionId, item.getResourceType(), e.getClass().getSimpleName());
            }
        }
        if (!allDone) {
            throw new IllegalStateException("cleanup_items_failed");
        }

        // 确认无数据集元数据残留
        if (datasetMapper.countAnonDatasets(sessionId) > 0) {
            throw new IllegalStateException("dataset_metadata_remaining");
        }

        // 最后一步：删除会话级残留记录与会话本身（全部为幂等删除）
        deleteSessionRecords(sessionId);
        cleanupItemMapper.deleteBySession(sessionId);
        anonSessionMapper.deleteById(sessionId);
        log.info("匿名会话数据已清理: sessionId={}", sessionId);
        return result;
    }

    private void materializeItems(Long sessionId, PurgeResult result) {
        List<Dataset> datasets = datasetMapper.selectList(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getOwnerType, OwnershipService.OWNER_ANON)
                .eq(Dataset::getAnonSessionId, sessionId));
        result.datasets = datasets.size();

        for (Dataset dataset : datasets) {
            cleanupItemMapper.insertIgnore(sessionId, "dataset_table", dataset.getTableName());
            cleanupItemMapper.insertIgnore(sessionId, "dataset_columns", String.valueOf(dataset.getId()));
            cleanupItemMapper.insertIgnore(sessionId, "dataset_meta", String.valueOf(dataset.getId()));

            // 版本表与备份表必须由元数据精确证明归属后才允许删除
            List<DatasetVersion> versions = datasetVersionMapper.selectList(
                    new LambdaQueryWrapper<DatasetVersion>().eq(DatasetVersion::getDatasetId, dataset.getId()));
            for (DatasetVersion version : versions) {
                if (version.getTableName() != null && !version.getTableName().equals(dataset.getTableName())) {
                    cleanupItemMapper.insertIgnore(sessionId, "version_table", version.getTableName());
                }
                cleanupItemMapper.insertIgnore(sessionId, "version_rule", String.valueOf(version.getId()));
                cleanupItemMapper.insertIgnore(sessionId, "version_meta", String.valueOf(dataset.getId()));
            }
            cleanupItemMapper.insertIgnore(sessionId, "data_quality_report", String.valueOf(dataset.getId()));
            cleanupItemMapper.insertIgnore(sessionId, "stats_cache", String.valueOf(dataset.getId()));
            cleanupItemMapper.insertIgnore(sessionId, "async_job_dataset", String.valueOf(dataset.getId()));

            List<CleanLog> logs = cleanLogMapper.selectByDatasetId(dataset.getId());
            for (CleanLog cleanLog : logs) {
                String backup = cleanLog.getBackupTable();
                if (backup != null && !backup.isBlank()) {
                    cleanupItemMapper.insertIgnore(sessionId, "backup_table", backup);
                }
            }
        }

        // 版本表兜底：按会话归属的版本记录（可能所属数据集元数据已删但版本记录残留）
        List<DatasetVersion> sessionVersions = datasetVersionMapper.selectList(new LambdaQueryWrapper<DatasetVersion>()
                .eq(DatasetVersion::getAnonSessionId, sessionId));
        for (DatasetVersion version : sessionVersions) {
            cleanupItemMapper.insertIgnore(sessionId, "version_table", version.getTableName());
            cleanupItemMapper.insertIgnore(sessionId, "version_rule", String.valueOf(version.getId()));
        }

        cleanupItemMapper.insertIgnore(sessionId, "clean_rule", "session");
        cleanupItemMapper.insertIgnore(sessionId, "clean_log", "session");
        cleanupItemMapper.insertIgnore(sessionId, "chart_view", "session");
        cleanupItemMapper.insertIgnore(sessionId, "saved_chart", "session");
        cleanupItemMapper.insertIgnore(sessionId, "dashboard_item", "session");
        cleanupItemMapper.insertIgnore(sessionId, "dashboard", "session");
        cleanupItemMapper.insertIgnore(sessionId, "data_quality_report", "session");
        cleanupItemMapper.insertIgnore(sessionId, "export_record", "session");
        cleanupItemMapper.insertIgnore(sessionId, "ai_log", "session");
        cleanupItemMapper.insertIgnore(sessionId, "ai_challenge", "session");
        cleanupItemMapper.insertIgnore(sessionId, "usage", "session");
        cleanupItemMapper.insertIgnore(sessionId, "async_job_session", "session");
        cleanupItemMapper.insertIgnore(sessionId, "audit_log", "session");
        cleanupItemMapper.insertIgnore(sessionId, "backup_record", "session");
    }

    /** @return 是否确实执行了删除动作（用于统计表删除数） */
    private boolean executeItem(Long sessionId, CleanupItem item) {
        String type = item.getResourceType();
        String key = item.getResourceKey();
        switch (type) {
            case "dataset_table", "backup_table" -> {
                if (!isPhysicalTableName(key)) {
                    // 元数据异常时只报告，不执行 DROP
                    throw new IllegalStateException("unsafe_table_name");
                }
                if (!hasKnownProvenance(sessionId, type, key)) {
                    // 元数据已不存在：无法证明归属，绝不猜测删除，交给孤儿表统计
                    log.warn("清理明细缺少元数据来源，跳过并计入孤儿表: sessionId={}, type={}", sessionId, type);
                    return false;
                }
                dynamicTableService.dropTable(key);
                return true;
            }
            case "version_table" -> {
                if (!isPhysicalTableName(key)) {
                    throw new IllegalStateException("unsafe_table_name");
                }
                long owned = datasetVersionMapper.selectCount(new LambdaQueryWrapper<DatasetVersion>()
                        .eq(DatasetVersion::getTableName, key)
                        .inSql(DatasetVersion::getDatasetId, anonDatasetIds(sessionId)));
                if (owned == 0) {
                    // 版本记录已不存在：无法证明归属，转交孤儿表统计，不猜测删除
                    log.warn("版本表缺少归属证明，跳过并计入孤儿表: sessionId={}", sessionId);
                    return false;
                }
                dynamicTableService.dropTable(key);
                return true;
            }
            case "version_meta" -> datasetVersionMapper.delete(
                    new LambdaQueryWrapper<DatasetVersion>().eq(DatasetVersion::getDatasetId, parseId(key)));
            case "version_rule" -> datasetVersionRuleMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.DatasetVersionRule>()
                            .eq(com.dataclean.entity.DatasetVersionRule::getVersionId, parseId(key)));
            case "data_quality_report" -> {
                if ("session".equals(key)) {
                    dataQualityReportMapper.delete(
                            new LambdaQueryWrapper<com.dataclean.entity.DataQualityReport>()
                                    .eq(com.dataclean.entity.DataQualityReport::getAnonSessionId, sessionId));
                } else {
                    dataQualityReportMapper.delete(
                            new LambdaQueryWrapper<com.dataclean.entity.DataQualityReport>()
                                    .eq(com.dataclean.entity.DataQualityReport::getDatasetId, parseId(key)));
                }
            }
            case "stats_cache" -> statsCacheMapper.delete(
                    new LambdaQueryWrapper<StatsCacheEntry>().eq(StatsCacheEntry::getDatasetId, parseId(key)));
            case "async_job_dataset" -> asyncJobMapper.delete(
                    new LambdaQueryWrapper<AsyncJob>().eq(AsyncJob::getDatasetId, parseId(key)));
            case "async_job_session" -> asyncJobMapper.delete(
                    new LambdaQueryWrapper<AsyncJob>().eq(AsyncJob::getAnonSessionId, sessionId));
            case "audit_log" -> auditLogMapper.delete(
                    new LambdaQueryWrapper<AuditLog>().eq(AuditLog::getAnonSessionId, sessionId));
            case "backup_record" -> backupTableRecordMapper.delete(
                    new LambdaQueryWrapper<BackupTableRecord>().eq(BackupTableRecord::getAnonSessionId, sessionId));
            case "dataset_columns" -> datasetColumnMapper.delete(
                    new LambdaQueryWrapper<DatasetColumn>().eq(DatasetColumn::getDatasetId, parseId(key)));
            case "dataset_meta" -> datasetMapper.delete(new LambdaQueryWrapper<Dataset>()
                    .eq(Dataset::getId, parseId(key))
                    .eq(Dataset::getOwnerType, OwnershipService.OWNER_ANON)
                    .eq(Dataset::getAnonSessionId, sessionId));
            case "clean_rule" -> cleanRuleMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.CleanRule>()
                            .eq(com.dataclean.entity.CleanRule::getAnonSessionId, sessionId));
            case "clean_log" -> cleanLogMapper.delete(
                    new LambdaQueryWrapper<CleanLog>().eq(CleanLog::getAnonSessionId, sessionId));
            case "chart_view" -> chartViewMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.ChartView>()
                            .eq(com.dataclean.entity.ChartView::getAnonSessionId, sessionId));
            case "saved_chart" -> savedChartMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.SavedChart>()
                            .eq(com.dataclean.entity.SavedChart::getOwnerType, OwnershipService.OWNER_ANON)
                            .eq(com.dataclean.entity.SavedChart::getAnonSessionId, sessionId));
            case "dashboard_item" -> dashboardItemMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.DashboardItem>()
                            .inSql(com.dataclean.entity.DashboardItem::getDashboardId, anonDashboardIds(sessionId)));
            case "dashboard" -> dashboardMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.Dashboard>()
                            .eq(com.dataclean.entity.Dashboard::getOwnerType, OwnershipService.OWNER_ANON)
                            .eq(com.dataclean.entity.Dashboard::getAnonSessionId, sessionId));
            case "export_record" -> exportRecordMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.ExportRecord>()
                            .eq(com.dataclean.entity.ExportRecord::getAnonSessionId, sessionId));
            case "ai_log" -> aiGenerationLogMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.AiGenerationLog>()
                            .eq(com.dataclean.entity.AiGenerationLog::getAnonSessionId, sessionId));
            case "ai_challenge" -> aiChallengeMapper.delete(
                    new LambdaQueryWrapper<com.dataclean.entity.AiChallenge>()
                            .eq(com.dataclean.entity.AiChallenge::getAnonSessionId, sessionId));
            case "usage" -> anonUsageMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.AnonUsageDaily>()
                    .eq(com.dataclean.entity.AnonUsageDaily::getScopeType, "session")
                    .eq(com.dataclean.entity.AnonUsageDaily::getScopeKey, String.valueOf(sessionId)));
            default -> throw new IllegalStateException("unknown_resource_type");
        }
        return false;
    }

    private void deleteSessionRecords(Long sessionId) {
        // 二次兜底：按会话精确删除（不会触碰私有数据 owner_type=private）
        List<Dataset> remaining = datasetMapper.selectList(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getAnonSessionId, sessionId)
                .eq(Dataset::getOwnerType, OwnershipService.OWNER_ANON));
        for (Dataset dataset : remaining) {
            // 版本表：仅删除由版本元数据精确证明归属的表，随后再删除版本记录
            List<DatasetVersion> versions = datasetVersionMapper.selectList(
                    new LambdaQueryWrapper<DatasetVersion>().eq(DatasetVersion::getDatasetId, dataset.getId()));
            for (DatasetVersion version : versions) {
                if (version.getTableName() != null && !version.getTableName().equals(dataset.getTableName())
                        && isPhysicalTableName(version.getTableName())) {
                    dynamicTableService.dropTable(version.getTableName());
                }
            }
            if (!versions.isEmpty()) {
                // 版本规则快照随版本一并清理，避免失去归属的孤儿快照
                datasetVersionRuleMapper.delete(
                        new LambdaQueryWrapper<com.dataclean.entity.DatasetVersionRule>()
                                .in(com.dataclean.entity.DatasetVersionRule::getVersionId,
                                        versions.stream().map(DatasetVersion::getId).toList()));
            }
            datasetVersionMapper.delete(new LambdaQueryWrapper<DatasetVersion>()
                    .eq(DatasetVersion::getDatasetId, dataset.getId()));
            statsCacheMapper.delete(new LambdaQueryWrapper<StatsCacheEntry>()
                    .eq(StatsCacheEntry::getDatasetId, dataset.getId()));
            asyncJobMapper.delete(new LambdaQueryWrapper<AsyncJob>()
                    .eq(AsyncJob::getDatasetId, dataset.getId()));
            if (isPhysicalTableName(dataset.getTableName())) {
                dynamicTableService.dropTable(dataset.getTableName());
            }
            datasetColumnMapper.delete(new LambdaQueryWrapper<DatasetColumn>()
                    .eq(DatasetColumn::getDatasetId, dataset.getId()));
            datasetMapper.deleteById(dataset.getId());
        }
        asyncJobMapper.delete(new LambdaQueryWrapper<AsyncJob>()
                .eq(AsyncJob::getAnonSessionId, sessionId));
        dataQualityReportMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.DataQualityReport>()
                .eq(com.dataclean.entity.DataQualityReport::getAnonSessionId, sessionId));
        auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getAnonSessionId, sessionId));
        backupTableRecordMapper.delete(new LambdaQueryWrapper<BackupTableRecord>()
                .eq(BackupTableRecord::getAnonSessionId, sessionId));
        cleanRuleMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.CleanRule>()
                .eq(com.dataclean.entity.CleanRule::getAnonSessionId, sessionId));
        cleanLogMapper.delete(new LambdaQueryWrapper<CleanLog>()
                .eq(CleanLog::getAnonSessionId, sessionId));
        chartViewMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.ChartView>()
                .eq(com.dataclean.entity.ChartView::getAnonSessionId, sessionId));
        savedChartMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.SavedChart>()
                .eq(com.dataclean.entity.SavedChart::getOwnerType, OwnershipService.OWNER_ANON)
                .eq(com.dataclean.entity.SavedChart::getAnonSessionId, sessionId));
        dashboardItemMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.DashboardItem>()
                .inSql(com.dataclean.entity.DashboardItem::getDashboardId, anonDashboardIds(sessionId)));
        dashboardMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.Dashboard>()
                .eq(com.dataclean.entity.Dashboard::getOwnerType, OwnershipService.OWNER_ANON)
                .eq(com.dataclean.entity.Dashboard::getAnonSessionId, sessionId));
        exportRecordMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.ExportRecord>()
                .eq(com.dataclean.entity.ExportRecord::getAnonSessionId, sessionId));
        aiGenerationLogMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.AiGenerationLog>()
                .eq(com.dataclean.entity.AiGenerationLog::getAnonSessionId, sessionId));
        aiChallengeMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.AiChallenge>()
                .eq(com.dataclean.entity.AiChallenge::getAnonSessionId, sessionId));
        anonUsageMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.AnonUsageDaily>()
                .eq(com.dataclean.entity.AnonUsageDaily::getScopeType, "session")
                .eq(com.dataclean.entity.AnonUsageDaily::getScopeKey, String.valueOf(sessionId)));
    }

    /** 合法物理表名：仅允许常规标识符，且禁止系统库前缀 */
    private boolean isPhysicalTableName(String name) {
        if (name == null || !TABLE_IDENTIFIER.matcher(name).matches()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !lower.startsWith("information_schema")
                && !lower.startsWith("mysql")
                && !lower.startsWith("performance_schema")
                && !lower.startsWith("sys");
    }

    /** 精确归属证明：表名必须来自本会话的 dataset.table_name、dataset_version.table_name 或备份记录 */
    private boolean hasKnownProvenance(Long sessionId, String resourceType, String tableName) {
        if ("dataset_table".equals(resourceType)) {
            return datasetMapper.selectCount(new LambdaQueryWrapper<Dataset>()
                    .eq(Dataset::getOwnerType, OwnershipService.OWNER_ANON)
                    .eq(Dataset::getAnonSessionId, sessionId)
                    .eq(Dataset::getTableName, tableName)) > 0;
        }
        long byCleanLog = cleanLogMapper.selectCount(new LambdaQueryWrapper<CleanLog>()
                .eq(CleanLog::getAnonSessionId, sessionId)
                .eq(CleanLog::getBackupTable, tableName));
        if (byCleanLog > 0) {
            return true;
        }
        return backupTableRecordMapper.selectCount(new LambdaQueryWrapper<BackupTableRecord>()
                .eq(BackupTableRecord::getAnonSessionId, sessionId)
                .eq(BackupTableRecord::getTableName, tableName)) > 0;
    }

    /** 本会话匿名数据集的 id 子查询（仅服务端使用，sessionId 为 Long，无注入风险）。 */
    private String anonDatasetIds(Long sessionId) {
        return "SELECT id FROM dataset WHERE owner_type='anon' AND anon_session_id=" + sessionId;
    }

    /** 本会话匿名看板的 id 子查询（仅服务端使用，sessionId 为 Long，无注入风险）。 */
    private String anonDashboardIds(Long sessionId) {
        return "SELECT id FROM dashboard WHERE owner_type='anon' AND anon_session_id=" + sessionId;
    }

    private long parseId(String key) {
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid_resource_key");
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static class PurgeResult {
        int datasets;
        int tables;
    }
}
