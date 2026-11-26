package com.dataclean.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 公开模式启动校验：任一项不满足直接终止启动，避免带着不安全配置上线。
 *
 * <p>日志只输出模式、TTL 与布尔开关，不打印 pepper、origin 之外的值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicModeStartupValidator implements InitializingBean {

    /** 生产公开模式固定 Cookie 名称 */
    static final String COOKIE_NAME_PROD = "__Host-dc_anon";

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (!appMode.isPublic()) {
            log.info("运行模式: private（公开演示关闭）");
            return;
        }

        boolean localProfile = isLocalProfile();
        Set<String> origins = properties.allowedOriginSet();

        if (origins.isEmpty()) {
            throw new IllegalStateException("公开模式必须配置 PUBLIC_ALLOWED_ORIGIN（固定源站白名单）");
        }
        for (String origin : origins) {
            if ("*".equals(origin)) {
                throw new IllegalStateException("PUBLIC_ALLOWED_ORIGIN 不允许为 *");
            }
            if (!localProfile && !origin.startsWith("https://")) {
                throw new IllegalStateException("生产公开模式的 PUBLIC_ALLOWED_ORIGIN 必须使用 https");
            }
        }

        String pepper = properties.getIpHashPepper();
        if (pepper == null || pepper.trim().length() < 32) {
            throw new IllegalStateException("公开模式必须配置 PUBLIC_IP_HASH_PEPPER（>=32 字符的独立密钥）");
        }

        validateCookie(localProfile);
        validateUploadScan();

        if (properties.getSessionTtlHours() < 1 || properties.getSessionTtlHours() > 168) {
            throw new IllegalStateException("PUBLIC_SESSION_TTL_HOURS 必须在 1–168 之间");
        }

        log.info("运行模式: public，Cookie={}，会话 TTL={}h，AI={}，演示数据={}，清理={}",
                properties.getCookieName(),
                properties.getSessionTtlHours(),
                properties.isAiEnabled() ? "on" : "off",
                properties.isDemoDataEnabled() ? "on" : "off",
                properties.isCleanupEnabled() ? "on" : "off");
    }

    /**
     * Cookie 安全属性强校验：生产必须 __Host-dc_anon、HttpOnly、Secure、SameSite=Strict、
     * Path=/、无 Domain；任一项不满足直接拒绝启动。
     *
     * <p>本地明文 HTTP 调试只能通过 local Profile 显式覆盖：此时必须换成非 __Host- 名称
     * 并关闭 Secure（浏览器会拒收非 Secure 的 __Host- Cookie）。
     */
    private void validateCookie(boolean localProfile) {
        String name = properties.getCookieName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("公开模式必须配置匿名会话 Cookie 名称");
        }
        if (!properties.isCookieHttpOnly()) {
            throw new IllegalStateException("公开模式 Cookie 必须 HttpOnly");
        }
        if (!"Strict".equalsIgnoreCase(properties.getCookieSameSite())) {
            throw new IllegalStateException("公开模式 Cookie 必须 SameSite=Strict");
        }
        if (!"/".equals(properties.getCookiePath())) {
            throw new IllegalStateException("公开模式 Cookie 必须 Path=/");
        }
        if (properties.getCookieDomain() != null && !properties.getCookieDomain().isBlank()) {
            throw new IllegalStateException("__Host- Cookie 不允许设置 Domain");
        }

        boolean localInsecure = localProfile && !properties.isCookieSecure();
        if (localInsecure) {
            if (!properties.isLocalInsecureCookie()) {
                throw new IllegalStateException(
                        "本地关闭 Cookie Secure 必须同时显式开启 local-insecure-cookie（仅 local Profile）");
            }
            if (name.startsWith("__Host-")) {
                throw new IllegalStateException(
                        "本地非 Secure 调试不能使用 __Host- 前缀（浏览器会拒收），请在 local Profile 中改用独立名称");
            }
            log.warn("公开模式 Cookie 已切换为本地调试模式（非 Secure、非 __Host-），禁止用于生产");
            return;
        }

        if (!COOKIE_NAME_PROD.equals(name)) {
            throw new IllegalStateException("公开模式 Cookie 名称必须为 " + COOKIE_NAME_PROD);
        }
        if (!properties.isCookieSecure()) {
            throw new IllegalStateException(
                    "公开模式 Cookie 必须 Secure；本地 HTTP 调试请使用 local Profile 覆盖");
        }
    }

    /**
     * 上传扫描强校验：公开模式默认要求接入可运行的 HTTP 扫描服务，
     * 缺失实现或地址时拒绝启动，避免无扫描直达生产。
     */
    private void validateUploadScan() {
        boolean required = Boolean.parseBoolean(
                environment.getProperty("upload.scan.required", "true"));
        String provider = environment.getProperty("upload.scan-provider", "none");
        String url = environment.getProperty("upload.scan.url", "");
        if (required && !"http".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "公开模式必须接入恶意文件扫描服务（UPLOAD_SCAN_PROVIDER=http）");
        }
        if ("http".equalsIgnoreCase(provider) && (url == null || url.isBlank())) {
            throw new IllegalStateException("扫描服务地址 UPLOAD_SCAN_URL 不能为空");
        }
        log.info("上传扫描: provider={}，required={}", provider, required);
    }

    private boolean isLocalProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
