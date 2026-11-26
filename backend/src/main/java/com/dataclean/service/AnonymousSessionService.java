package com.dataclean.service;

import com.dataclean.config.PublicModeProperties;
import com.dataclean.entity.AnonSession;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AnonSessionMapper;
import com.dataclean.mapper.AnonUsageMapper;
import com.dataclean.metrics.RuntimeMetrics;
import com.dataclean.util.ClientIpResolver;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 匿名会话服务。
 *
 * <p>安全要点：
 * <ul>
 *   <li>Cookie 只保存 32 字节高熵随机令牌，服务端仅存 SHA-256；</li>
 *   <li>Cookie 固定 __Host- 前缀 + HttpOnly + Secure + SameSite=Strict + Path=/ + 无 Domain；</li>
 *   <li>来源 IP 使用独立 pepper 的 HMAC-SHA256（域分隔 {@code ip:}），不复用 JWT 密钥；</li>
 *   <li>会话只能通过受控引导接口创建，普通业务 API 不会隐式建会话；</li>
 *   <li>签发前执行单 IP 活跃数、全站活跃数、单 IP 每自然日创建数三重限流。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymousSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final String IP_DOMAIN = "ip:";

    private final AnonSessionMapper anonSessionMapper;
    private final AnonUsageMapper anonUsageMapper;
    private final PublicModeProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final RuntimeMetrics metrics;

    /** 仅解析已有会话：不存在或失效返回 null，绝不隐式创建。 */
    public Long resolveExisting(HttpServletRequest request) {
        String token = readCookie(request);
        if (token == null) {
            return null;
        }
        AnonSession existing = anonSessionMapper.selectActive(hashToken(token), LocalDateTime.now());
        if (existing == null) {
            return null;
        }
        touchLastSeen(existing.getId());
        return existing.getId();
    }

    public boolean hasValidCookie(HttpServletRequest request) {
        return resolveExistingQuietly(request) != null;
    }

    private Long resolveExistingQuietly(HttpServletRequest request) {
        String token = readCookie(request);
        if (token == null) {
            return null;
        }
        AnonSession existing = anonSessionMapper.selectActive(hashToken(token), LocalDateTime.now());
        return existing == null ? null : existing.getId();
    }

    /** 当前 IP 当日创建次数是否已需要挑战 */
    public boolean requiresChallenge(HttpServletRequest request) {
        if (!properties.isSessionChallengeEnabled() || properties.getSessionChallengeThreshold() <= 0) {
            return false;
        }
        String ipHash = ipHash(clientIp(request));
        long created = createdToday(ipHash);
        return created >= properties.getSessionChallengeThreshold();
    }

    /**
     * 创建匿名会话。调用方必须先完成来源校验与（按需）挑战校验。
     * 任何限额不满足时抛 429，且不会写入半成品会话。
     */
    public Long createSession(HttpServletRequest request, HttpServletResponse response) {
        LocalDateTime now = LocalDateTime.now();
        String ipHash = ipHash(clientIp(request));

        if (anonSessionMapper.countActiveByIp(ipHash, now) >= properties.getMaxActiveSessionsPerIp()) {
            reject("ip_active");
        }
        if (anonSessionMapper.countActive(now) >= properties.getMaxActiveSessions()) {
            reject("global_active");
        }
        long createdToday = createdToday(ipHash);
        if (createdToday >= properties.getSessionCreatePerIpPerDay()) {
            reject("ip_daily");
        }

        String token = randomToken();
        AnonSession session = new AnonSession();
        session.setTokenHash(hashToken(token));
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plusHours(properties.getSessionTtlHours()));
        session.setIpHash(ipHash);
        session.setRevoked(0);
        session.setCleanupState("active");
        session.setCleanupAttempts(0);
        anonSessionMapper.insert(session);

        try {
            anonUsageMapper.increment("ip", ipHash, "session_create", LocalDate.now(), 1);
        } catch (Exception e) {
            log.debug("记录会话创建计数失败: {}", e.getClass().getSimpleName());
        }

        writeCookie(response, token);
        metrics.sessionCreated();
        log.info("签发匿名会话: id={}", session.getId());
        return session.getId();
    }

    private long createdToday(String ipHash) {
        Long value = anonUsageMapper.getCounter("ip", ipHash, "session_create", LocalDate.now());
        return value == null ? 0L : value;
    }

    private void reject(String reason) {
        // 只记录原因与聚合计数，不记录 IP、Cookie、Token
        log.warn("匿名会话创建被拒绝: reason={}", reason);
        metrics.sessionRejected(reason);
        try {
            anonUsageMapper.increment("global", "global", "session_reject_" + reason, LocalDate.now(), 1);
        } catch (Exception e) {
            log.debug("记录限流统计失败: {}", e.getClass().getSimpleName());
        }
        throw new BusinessException(429, "当前访问人数较多，请稍后再试");
    }

    private void writeCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), token)
                .httpOnly(properties.isCookieHttpOnly())
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path(properties.getCookiePath())
                .maxAge(properties.getSessionTtlHours() * 3600L)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** last_seen_at 节流：超过 5 分钟才更新一次 */
    private void touchLastSeen(Long sessionId) {
        try {
            anonSessionMapper.touchLastSeen(sessionId, LocalDateTime.now().minusMinutes(5));
        } catch (Exception e) {
            log.debug("更新会话活跃时间失败: {}", e.getClass().getSimpleName());
        }
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.getCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    /** 来源 IP 摘要：HMAC-SHA256(pepper, "ip:" + ip) */
    public String ipHash(String ip) {
        try {
            String pepper = properties.getIpHashPepper();
            if (pepper == null || pepper.length() < 32) {
                throw new IllegalStateException("PUBLIC_IP_HASH_PEPPER 未配置或长度不足");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal((IP_DOMAIN + ip).getBytes(StandardCharsets.UTF_8));
            return hex(result);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("IP 摘要计算失败");
        }
    }

    /** 取客户端 IP：仅当直连地址属于受信代理时才采信代理头（见 {@link ClientIpResolver}） */
    public String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
