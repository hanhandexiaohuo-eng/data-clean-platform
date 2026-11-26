package com.dataclean.controller;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.exception.BusinessException;
import com.dataclean.service.AnonymousSessionService;
import com.dataclean.service.HumanChallengeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开模式受控会话引导。
 *
 * <p>这是唯一允许创建匿名会话的入口：
 * <ul>
 *   <li>已有有效会话时幂等返回，不重复创建；</li>
 *   <li>单 IP 当日创建次数达到阈值后必须携带一次性挑战令牌（428）；</li>
 *   <li>任意限额不满足返回 429，且不会留下半成品会话。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/public/session")
@RequiredArgsConstructor
public class PublicSessionController {

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final AnonymousSessionService sessionService;
    private final HumanChallengeService challengeService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request, HttpServletResponse response) {
        requirePublic();

        Long existing = sessionService.resolveExisting(request);
        if (existing != null) {
            return ResponseEntity.ok(body(false, existing));
        }

        if (sessionService.requiresChallenge(request)) {
            String token = request.getHeader("X-Session-Challenge");
            if (token == null || token.isBlank()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", 428);
                body.put("message", "需要完成人机验证");
                return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                        .header("X-Session-Challenge-Required", "1")
                        .body(body);
            }
            String ipHash = sessionService.ipHash(sessionService.clientIp(request));
            challengeService.verifySessionChallenge(token, ipHash);
        }

        Long sessionId = sessionService.createSession(request, response);
        return ResponseEntity.ok(body(true, sessionId));
    }

    @PostMapping("/challenge")
    public Map<String, Object> challenge(HttpServletRequest request) {
        requirePublic();
        if (!properties.isSessionChallengeEnabled()) {
            throw new BusinessException(404, "接口不存在");
        }
        String ipHash = sessionService.ipHash(sessionService.clientIp(request));
        return challengeService.issueForSession(ipHash);
    }

    private void requirePublic() {
        if (!appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
    }

    private Map<String, Object> body(boolean created, Long sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("ttlHours", properties.getSessionTtlHours());
        result.put("cookieName", properties.getCookieName());
        // 不返回会话 ID 的明文，只回显是否存在，避免内部 ID 暴露
        result.put("active", sessionId != null);
        return result;
    }
}
