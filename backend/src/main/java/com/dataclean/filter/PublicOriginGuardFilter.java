package com.dataclean.filter;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * 公开模式写操作来源校验。
 *
 * <p>规则：
 * <ul>
 *   <li>所有非 GET/HEAD/OPTIONS 请求必须携带 {@code Origin}；</li>
 *   <li>{@code Origin} 必须与固定白名单精确匹配（scheme+host+port）；</li>
 *   <li>不使用请求 Host 作为可信基准，避免 Host Header 伪造；</li>
 *   <li>{@code X-Confirm: yes} 仅用于破坏性操作的二次确认标记，不作为安全控制。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicOriginGuardFilter extends OncePerRequestFilter {

    private final AppMode appMode;
    private final PublicModeProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!appMode.isPublic()) {
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin.trim())) {
            writeJson(response, 403, "缺少来源信息，请求已拒绝");
            return;
        }

        if (!isAllowedOrigin(origin)) {
            writeJson(response, 403, "来源不被允许，请求已拒绝");
            return;
        }

        // 破坏性操作二次确认（仅前端提示用途，安全仍依赖 Origin + SameSite + HttpOnly）
        if (requiresConfirm(request)) {
            String confirm = request.getHeader("X-Confirm");
            if (confirm == null || !"yes".equalsIgnoreCase(confirm.trim())) {
                writeJson(response, 400, "请确认后再执行该操作");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresConfirm(HttpServletRequest request) {
        if ("DELETE".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith("/api/clean/execute")
                || path.startsWith("/api/clean/rollback")
                || path.startsWith("/api/export/");
    }

    private String normalize(String origin) {
        String value = origin.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\""
                + message.replace("\"", "\\\"") + "\"}");
    }

    /** 供其他地方复用的白名单判定 */
    public boolean isAllowedOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        String normalized = normalize(origin).toLowerCase(Locale.ROOT);
        return properties.allowedOriginSet().stream()
                .anyMatch(item -> item.toLowerCase(Locale.ROOT).equals(normalized));
    }
}
