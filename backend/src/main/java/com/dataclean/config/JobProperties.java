package com.dataclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步任务、版本保留与大数据阈值配置。
 */
@Data
@ConfigurationProperties(prefix = "app.jobs")
public class JobProperties {

    /** 是否启用后台任务轮询。 */
    private boolean enabled = true;
    /** 单实例并发执行任务数。 */
    private int workerThreads = 2;
    private long pollIntervalMs = 2000;
    /** 单任务最长运行时间（秒），超过则标记失败并释放资源。 */
    private long maxRuntimeSeconds = 1800;
    /** 超过该行数的导入自动转为异步。 */
    private int asyncImportRows = 20000;
    /** 超过该字节数的导入自动转为异步。 */
    private long asyncImportBytes = 5L * 1024 * 1024;
    /** 批量写入行数。 */
    private int chunkSize = 1000;

    /** 公开模式：单会话同时运行任务数。 */
    private int publicMaxActivePerSession = 1;
    /** 公开模式：单会话每日任务数。 */
    private int publicMaxPerDay = 20;
    /** 公开模式：失败重试次数。 */
    private int publicMaxRetries = 2;

    /** 私有模式：同时运行任务数。 */
    private int privateMaxActive = 4;
    private int privateMaxPerDay = 500;
    private int privateMaxRetries = 3;

    /** 每个数据集保留的历史版本数量（含当前版本）。 */
    private int versionKeep = 5;
    /** 历史版本最长保留天数。 */
    private int versionMaxAgeDays = 30;
}
