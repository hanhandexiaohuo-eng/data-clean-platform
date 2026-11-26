package com.dataclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行监控与告警阈值配置（前缀 {@code app.monitoring}）。
 *
 * <p>阈值仅用于生成脱敏告警事件与指标，不参与业务请求判定；
 * 所有值均可通过环境变量覆盖。
 */
@Data
@ConfigurationProperties(prefix = "app.monitoring")
public class MonitoringProperties {

    /** 指标刷新与告警评估间隔（秒） */
    private int intervalSeconds = 60;

    /** 上传卷容量基线（MB），用于计算使用率 */
    private long uploadVolumeCapacityMb = 1024;

    /** 告警阈值 */
    private Alert alert = new Alert();

    @Data
    public static class Alert {
        /** 活跃匿名会话占全站上限比例 */
        private double sessionCapacityRatio = 0.8;
        /** 上传卷使用率 */
        private double uploadVolumeRatio = 0.85;
        /** 数据库连接池使用率 */
        private double dbPoolRatio = 0.9;
        /** AI 全站日 Token 预算使用率 */
        private double aiBudgetRatio = 0.9;
        /** 单轮清理失败数 */
        private long cleanupFailures = 3;
        /** 孤儿动态表上报数 */
        private long orphanTables = 10;
        /** 单个评估周期内上传扫描失败数 */
        private long scanFailuresPerInterval = 3;
    }
}
