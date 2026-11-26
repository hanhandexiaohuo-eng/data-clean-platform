package com.dataclean.util;

import com.dataclean.exception.BusinessException;

/**
 * 当前请求的公开模式上下文。
 *
 * <p>由 {@code AnonymousSessionFilter} 在请求开始时写入、结束时清理，业务层只读。
 */
public final class RequestContext {

    public static final String ATTR_SESSION_ID = "ANON_SESSION_ID";
    public static final String ATTR_IP_HASH = "ANON_IP_HASH";

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public record Ctx(Long sessionId, String ipHash, boolean publicMode) {
    }

    public static void set(Long sessionId, String ipHash, boolean publicMode) {
        HOLDER.set(new Ctx(sessionId, ipHash, publicMode));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isPublic() {
        Ctx ctx = HOLDER.get();
        return ctx != null && ctx.publicMode();
    }

    public static Long sessionId() {
        Ctx ctx = HOLDER.get();
        return ctx == null ? null : ctx.sessionId();
    }

    public static String ipHash() {
        Ctx ctx = HOLDER.get();
        return ctx == null ? null : ctx.ipHash();
    }

    public static Long requireSessionId() {
        Long id = sessionId();
        if (id == null) {
            throw new BusinessException(401, "匿名会话无效，请刷新页面");
        }
        return id;
    }
}
