package com.dataclean.metrics;

import com.dataclean.config.MonitoringProperties;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.config.UploadStorageConfig;
import com.dataclean.mapper.AnonSessionMapper;
import com.dataclean.mapper.AnonUsageMapper;
import com.dataclean.mapper.DatasetMapper;
import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 脱敏运行指标与阈值告警。
 *
 * <p>指标只包含聚合计数与容量/比例，不包含 Cookie、Token、IP、Prompt、样本内容。
 * 高频计数使用 Micrometer Counter；需要查库的容量类指标每 {@code intervalSeconds}
 * 刷新一次并缓存，避免抓取时打库。
 */
@Slf4j
@Component
public class RuntimeMetrics {

    private static final List<String> ALERT_RULES = List.of(
            "session_capacity", "upload_volume", "db_pool", "ai_budget",
            "cleanup_failures", "orphan_tables", "scan_failures");

    private final MeterRegistry registry;
    private final MonitoringProperties monitoring;
    private final PublicModeProperties publicProperties;
    private final AnonSessionMapper anonSessionMapper;
    private final AnonUsageMapper anonUsageMapper;
    private final DatasetMapper datasetMapper;
    private final UploadStorageConfig storageConfig;
    private final DataSource dataSource;

    private final Counter sessionCreated;
    private final Counter uploadSuccess;
    private final Counter uploadRejected;
    private final Counter uploadScanFailed;
    private final Counter aiCallsSuccess;
    private final Counter aiCallsFailure;
    private final Counter aiRejectedQuota;
    private final Counter aiRejectedBreaker;
    private final Counter aiTokens;
    private final Counter cleanupRuns;
    private final Counter cleanupFailures;
    private final Timer cleanupDuration;
    private final Counter http429;
    private final Counter http5xx;
    private final Counter httpOther4xx;

    private final Map<String, Counter> sessionRejected = new LinkedHashMap<>();

    private volatile double activeSessions;
    private volatile double dynamicTables;
    private volatile double uploadVolumeBytes;
    private volatile double uploadVolumeCapacityBytes;
    private volatile double dbPoolActive;
    private volatile double dbPoolMax;
    private volatile double aiBudgetUsed;
    private volatile double aiBudgetTotal;
    private volatile long lastCleanupFailures;
    private volatile long lastOrphanTables;
    private volatile long lastEvaluationTime = System.currentTimeMillis();

    private final AtomicLong scanFailureTotal = new AtomicLong();
    private long lastScanFailureTotal;
    private long scanFailuresInInterval;

    private final Map<String, Double> alertActive = new LinkedHashMap<>();

    public RuntimeMetrics(MeterRegistry registry,
                          MonitoringProperties monitoring,
                          PublicModeProperties publicProperties,
                          AnonSessionMapper anonSessionMapper,
                          AnonUsageMapper anonUsageMapper,
                          DatasetMapper datasetMapper,
                          UploadStorageConfig storageConfig,
                          DataSource dataSource) {
        this.registry = registry;
        this.monitoring = monitoring;
        this.publicProperties = publicProperties;
        this.anonSessionMapper = anonSessionMapper;
        this.anonUsageMapper = anonUsageMapper;
        this.datasetMapper = datasetMapper;
        this.storageConfig = storageConfig;
        this.dataSource = dataSource;

        this.sessionCreated = Counter.builder("dc.session.created").register(registry);
        this.uploadSuccess = Counter.builder("dc.upload.total").tag("result", "success").register(registry);
        this.uploadRejected = Counter.builder("dc.upload.total").tag("result", "rejected").register(registry);
        this.uploadScanFailed = Counter.builder("dc.upload.total").tag("result", "scan_failed").register(registry);
        this.aiCallsSuccess = Counter.builder("dc.ai.calls").tag("result", "success").register(registry);
        this.aiCallsFailure = Counter.builder("dc.ai.calls").tag("result", "failure").register(registry);
        this.aiRejectedQuota = Counter.builder("dc.ai.calls").tag("result", "quota").register(registry);
        this.aiRejectedBreaker = Counter.builder("dc.ai.calls").tag("result", "breaker").register(registry);
        this.aiTokens = Counter.builder("dc.ai.tokens.estimated").register(registry);
        this.cleanupRuns = Counter.builder("dc.cleanup.runs").register(registry);
        this.cleanupFailures = Counter.builder("dc.cleanup.failures").register(registry);
        this.cleanupDuration = Timer.builder("dc.cleanup.duration").register(registry);
        this.http429 = Counter.builder("dc.http.responses").tag("status", "429").register(registry);
        this.http5xx = Counter.builder("dc.http.responses").tag("status", "5xx").register(registry);
        this.httpOther4xx = Counter.builder("dc.http.responses").tag("status", "4xx").register(registry);

        Gauge.builder("dc.sessions.active", this, RuntimeMetrics::getActiveSessions).register(registry);
        Gauge.builder("dc.dynamic.tables", this, RuntimeMetrics::getDynamicTables).register(registry);
        Gauge.builder("dc.upload.volume.bytes", this, RuntimeMetrics::getUploadVolumeBytes).register(registry);
        Gauge.builder("dc.upload.volume.capacity.bytes", this, RuntimeMetrics::getUploadVolumeCapacityBytes).register(registry);
        Gauge.builder("dc.db.pool.active", this, RuntimeMetrics::getDbPoolActive).register(registry);
        Gauge.builder("dc.db.pool.max", this, RuntimeMetrics::getDbPoolMax).register(registry);
        Gauge.builder("dc.ai.budget.used", this, RuntimeMetrics::getAiBudgetUsed).register(registry);
        Gauge.builder("dc.ai.budget.total", this, RuntimeMetrics::getAiBudgetTotal).register(registry);
        Gauge.builder("dc.cleanup.last.failures", this, RuntimeMetrics::getLastCleanupFailures).register(registry);
        Gauge.builder("dc.orphan.tables", this, RuntimeMetrics::getLastOrphanTables).register(registry);
        Gauge.builder("dc.scan.failures.interval", this, RuntimeMetrics::getScanFailuresInInterval).register(registry);

        for (String reason : List.of("ip_active", "global_active", "ip_daily")) {
            sessionRejected.put(reason, Counter.builder("dc.session.rejected").tag("reason", reason).register(registry));
        }
        for (String rule : ALERT_RULES) {
            alertActive.put(rule, 0.0);
            Gauge.builder("dc.alert.active", this, m -> m.alertActive.getOrDefault(rule, 0.0))
                    .tag("rule", rule)
                    .register(registry);
        }
    }

    // ---------- 业务计数 ----------

    public void sessionCreated() {
        sessionCreated.increment();
    }

    public void sessionRejected(String reason) {
        Counter counter = sessionRejected.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    public void uploadSuccess() {
        uploadSuccess.increment();
    }

    public void uploadRejected() {
        uploadRejected.increment();
    }

    public void uploadScanFailed() {
        uploadScanFailed.increment();
        scanFailureTotal.incrementAndGet();
    }

    public void aiSuccess(long tokens) {
        aiCallsSuccess.increment();
        if (tokens > 0) {
            aiTokens.increment(tokens);
        }
    }

    public void aiFailure() {
        aiCallsFailure.increment();
    }

    public void aiQuotaRejected() {
        aiRejectedQuota.increment();
    }

    public void aiBreakerRejected() {
        aiRejectedBreaker.increment();
    }

    public void cleanupFinished(long durationMs, int failures, int orphans) {
        cleanupDuration.record(Duration.ofMillis(Math.max(0, durationMs)));
        cleanupRuns.increment();
        if (failures > 0) {
            cleanupFailures.increment(failures);
        }
        lastCleanupFailures = failures;
        lastOrphanTables = orphans;
    }

    public void httpStatus(int status) {
        if (status == 429) {
            http429.increment();
        } else if (status >= 500) {
            http5xx.increment();
        } else if (status >= 400) {
            httpOther4xx.increment();
        }
    }

    // ---------- 周期刷新与告警 ----------

    @Scheduled(fixedDelayString = "#{1000 * ${app.monitoring.interval-seconds:60}}",
            initialDelay = 30000)
    public void refreshAndEvaluate() {
        try {
            refreshGauges();
        } catch (Exception e) {
            log.debug("刷新运行指标失败: {}", e.getClass().getSimpleName());
        }
        evaluateAlerts();
    }

    private void refreshGauges() {
        LocalDateTime now = LocalDateTime.now();
        activeSessions = anonSessionMapper.countActive(now);
        dynamicTables = datasetMapper.countDynamicTables();
        uploadVolumeBytes = directorySize(storageConfig.root());
        uploadVolumeCapacityBytes = Math.max(1, monitoring.getUploadVolumeCapacityMb()) * 1024.0 * 1024.0;
        if (dataSource instanceof DruidDataSource druid) {
            dbPoolActive = druid.getActiveCount();
            dbPoolMax = druid.getMaxActive();
        }
        if (publicProperties.isAiEnabled()) {
            aiBudgetTotal = Math.max(1, publicProperties.getAiGlobalDailyTokenBudget());
            Long used = anonUsageMapper.sumGlobal("ai_token", LocalDate.now());
            aiBudgetUsed = used == null ? 0 : used;
        } else {
            aiBudgetTotal = 0;
            aiBudgetUsed = 0;
        }
    }

    private void evaluateAlerts() {
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(1, now - lastEvaluationTime);
        lastEvaluationTime = now;

        long total = scanFailureTotal.get();
        long delta = Math.max(0, total - lastScanFailureTotal);
        scanFailuresInInterval = delta;
        lastScanFailureTotal = total;

        MonitoringProperties.Alert alert = monitoring.getAlert();
        setAlert("session_capacity", ratio(activeSessions, publicProperties.getMaxActiveSessions()) >= alert.getSessionCapacityRatio());
        setAlert("upload_volume", ratio(uploadVolumeBytes, uploadVolumeCapacityBytes) >= alert.getUploadVolumeRatio());
        setAlert("db_pool", ratio(dbPoolActive, dbPoolMax) >= alert.getDbPoolRatio());
        setAlert("ai_budget", aiBudgetTotal > 0 && ratio(aiBudgetUsed, aiBudgetTotal) >= alert.getAiBudgetRatio());
        setAlert("cleanup_failures", lastCleanupFailures >= alert.getCleanupFailures());
        setAlert("orphan_tables", lastOrphanTables >= alert.getOrphanTables());
        setAlert("scan_failures", delta >= alert.getScanFailuresPerInterval());

        if (elapsedMs > 0) {
            // 只输出规则名与阈值判断结果，不输出 IP、Cookie、Token、文件内容
            alertActive.forEach((rule, active) -> {
                if (active > 0) {
                    log.warn("运行告警触发: rule={}", rule);
                }
            });
        }
    }

    private void setAlert(String rule, boolean active) {
        alertActive.put(rule, active ? 1.0 : 0.0);
    }

    private static double ratio(double used, double total) {
        return total <= 0 ? 0 : used / total;
    }

    private double directorySize(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------- Gauge 读取器 ----------

    private double getActiveSessions() {
        return activeSessions;
    }

    private double getDynamicTables() {
        return dynamicTables;
    }

    private double getUploadVolumeBytes() {
        return uploadVolumeBytes;
    }

    private double getUploadVolumeCapacityBytes() {
        return uploadVolumeCapacityBytes;
    }

    private double getDbPoolActive() {
        return dbPoolActive;
    }

    private double getDbPoolMax() {
        return dbPoolMax;
    }

    private double getAiBudgetUsed() {
        return aiBudgetUsed;
    }

    private double getAiBudgetTotal() {
        return aiBudgetTotal;
    }

    private long getLastCleanupFailures() {
        return lastCleanupFailures;
    }

    private long getLastOrphanTables() {
        return lastOrphanTables;
    }

    private long getScanFailuresInInterval() {
        return scanFailuresInInterval;
    }
}
