package com.dataclean.filter;

import com.dataclean.config.AppMode;
import com.dataclean.service.AnonymousSessionService;
import com.dataclean.util.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 公开模式匿名会话过滤器。
 *
 * <p>只解析已有会话，不在业务请求中隐式创建；未建立会话时仅允许最小公开入口
 * （健康检查、公开配置、会话引导），其余业务 API 返回 401 + X-Session-Required。
 * 私有模式直接放行，不影响既有行为。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousSessionFilter extends OncePerRequestFilter {

    private final AppMode appMode;
    private final AnonymousSessionService sessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!appMode.isPublic()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isPublicEntry(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Long sessionId = (Long) request.getAttribute(RequestContext.ATTR_SESSION_ID);
        if (sessionId == null) {
            sessionId = sessionService.resolveExisting(request);
            if (sessionId == null) {
                response.setHeader("X-Session-Required", "1");
                writeJson(response, 401, "匿名会话未建立，请刷新页面后重试");
                return;
            }
            request.setAttribute(RequestContext.ATTR_SESSION_ID, sessionId);
        }

        String ipHash = sessionService.ipHash(sessionService.clientIp(request));
        request.setAttribute(RequestContext.ATTR_IP_HASH, ipHash);

        RequestContext.set(sessionId, ipHash, true);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /** 未建立会话时允许访问的最小公开入口 */
    private boolean isPublicEntry(String path) {
        return path == null
                || path.equals("/api/health")
                || path.equals("/api/public/config")
                || path.equals("/api/public/session")
                || path.equals("/api/public/session/challenge")
                || path.equals("/error")
                || path.equals("/nginx-health")
                // 运维诊断接口仅绑定内网管理端口（不在公网入口暴露），此处放行给 Actuator
                || path.equals("/actuator")
                || path.startsWith("/actuator/")
                || isBlockedPrivatePath(path)
                || path.startsWith("/assets/")
                || path.equals("/")
                || path.equals("/index.html")
                || path.equals("/favicon.ico")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".map");
    }

    /** 私有能力：放行到 Security 层统一返回 404，不在此处暴露会话要求 */
    private boolean isBlockedPrivatePath(String path) {
        return path.startsWith("/api/admin/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/audit/")
                || path.startsWith("/api/history/")
                || path.equals("/api/ai/config")
                || path.startsWith("/api/ai/config/")
                || path.equals("/api/ai/template")
                || path.startsWith("/api/ai/template/")
                || path.equals("/api/ai/history")
                || path.startsWith("/api/ai/history/");
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + escape(message) + "\"}");
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
