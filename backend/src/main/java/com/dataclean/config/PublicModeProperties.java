package com.dataclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 公开演示模式配置（前缀 {@code app.public}）。
 *
 * <p>所有数值均有安全默认值；敏感值（源站白名单、IP 哈希 pepper）必须由环境变量注入。
 */
@Data
@ConfigurationProperties(prefix = "app.public")
public class PublicModeProperties {

    /** 固定公开源站白名单，逗号分隔；public 模式必填，禁止 * */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 独立 HMAC pepper，用于来源 IP 摘要；public 模式必填，>= 32 字符 */
    private String ipHashPepper;

    /** 匿名会话 TTL（小时），1–168 */
    private int sessionTtlHours = 24;

    /** 匿名会话 Cookie 名称；__Host- 前缀强制 Secure + Path=/ + 无 Domain */
    private String cookieName = "__Host-dc_anon";

    /** 生产固定 true：Cookie 必须 Secure；本地仅能通过 local Profile 覆盖 */
    private boolean cookieSecure = true;

    /** 仅本地开发允许的非 Secure Cookie 覆盖开关 */
    private boolean localInsecureCookie = false;

    /** Cookie 属性；生产启动校验要求 HttpOnly=true、SameSite=Strict、Path=/、Domain 为空 */
    private boolean cookieHttpOnly = true;
    private String cookieSameSite = "Strict";
    private String cookiePath = "/";
    private String cookieDomain = "";

    private int maxActiveSessions = 5000;
    private int maxActiveSessionsPerIp = 10;
    /** 单 IP 每自然日新增会话上限（与实现一致） */
    private int sessionCreatePerIpPerDay = 20;
    /** 单 IP 当日创建次数达到阈值后必须携带会话挑战令牌 */
    private int sessionChallengeThreshold = 3;
    private boolean sessionChallengeEnabled = true;

    private int maxDatasets = 5;
    private int maxFileSizeMb = 20;
    private int maxTotalStorageMb = 100;
    private int maxRows = 100000;
    private int maxColumns = 200;
    private int maxCleanTasks = 50;
    private int maxCharts = 100;
    private int maxExports = 20;
    private int exportMaxRows = 10000;

    private boolean aiEnabled = false;
    private int aiMaxCallsPerDay = 20;
    private int aiMaxCallsPerIpPerDay = 60;
    private int aiGlobalDailyCalls = 500;
    private long aiGlobalDailyTokenBudget = 200000;
    private int aiMaxInputChars = 4000;
    private int aiMaxOutputTokens = 1024;
    private int aiMaxConcurrency = 1;
    private int aiTimeoutMs = 30000;
    private boolean aiBreakerEnabled = true;
    private boolean aiChallengeEnabled = true;
    private int aiChallengeDifficulty = 18;
    private int aiChallengeTtlSeconds = 120;

    private boolean demoDataEnabled = true;
    private boolean cleanupEnabled = true;
    private int cleanupIntervalMinutes = 60;
    private int cleanupGraceMinutes = 60;

    /**
     * 规范化后的源站集合：拆分逗号、去空白、去重。
     */
    public Set<String> allowedOriginSet() {
        Set<String> result = new LinkedHashSet<>();
        if (allowedOrigins == null) {
            return result;
        }
        for (String origin : allowedOrigins) {
            if (origin == null) {
                continue;
            }
            for (String part : origin.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    // 源站比较不区分大小写（scheme/host 大小写不敏感）
                    result.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    public long maxFileSizeBytes() {
        return (long) maxFileSizeMb * 1024 * 1024;
    }

    public long maxTotalStorageBytes() {
        return (long) maxTotalStorageMb * 1024 * 1024;
    }
}
