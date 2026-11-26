package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.entity.AiChallenge;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AiChallengeMapper;
import com.dataclean.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 人机挑战（PoW）。
 *
 * <p>挑战绑定匿名会话 ID + 来源 IP 摘要 + 过期时间；验证成功后原子核销，
 * 重复使用或跨会话/跨 IP 使用一律 429。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanChallengeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final AiChallengeMapper challengeMapper;

    /** 签发 AI 挑战：绑定当前匿名会话 + IP + 过期时间。 */
    public Map<String, Object> issue() {
        requirePublicAndEnabled();
        Long sessionId = RequestContext.requireSessionId();
        return doIssue("ai", sessionId, RequestContext.ipHash());
    }

    /** 签发会话引导挑战：无会话，只绑定 IP + 过期时间。 */
    public Map<String, Object> issueForSession(String ipHash) {
        if (!appMode.isPublic() || !properties.isSessionChallengeEnabled()) {
            throw new BusinessException(404, "接口不存在");
        }
        return doIssue("session", null, ipHash);
    }

    private Map<String, Object> doIssue(String purpose, Long sessionId, String ipHash) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256(secret);

        AiChallenge challenge = new AiChallenge();
        challenge.setChallengeHash(hash);
        challenge.setAnonSessionId(sessionId);
        challenge.setIpHash(ipHash);
        challenge.setPurpose(purpose);
        challenge.setCreatedAt(LocalDateTime.now());
        challenge.setExpiresAt(LocalDateTime.now().plusSeconds(properties.getAiChallengeTtlSeconds()));
        challengeMapper.insert(challenge);

        // 顺带清理过期挑战（幂等）
        try {
            challengeMapper.deleteExpired(LocalDateTime.now().minusHours(1));
        } catch (Exception e) {
            log.debug("清理过期挑战失败: {}", e.getClass().getSimpleName());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("challenge", hash);
        result.put("difficulty", properties.getAiChallengeDifficulty());
        result.put("expiresInSeconds", properties.getAiChallengeTtlSeconds());
        // secret 只下发一次，服务端仅存摘要
        result.put("token", secret);
        return result;
    }

    /** 校验并核销 AI 挑战：绑定会话 + IP + 未过期 + 未使用。 */
    public void verify(String token) {
        requirePublicAndEnabled();
        Long sessionId = RequestContext.requireSessionId();
        consume(token, "ai", sessionId, RequestContext.ipHash());
    }

    /** 校验并核销会话引导挑战：绑定 IP + 未过期 + 未使用。 */
    public void verifySessionChallenge(String token, String ipHash) {
        if (!appMode.isPublic() || !properties.isSessionChallengeEnabled()) {
            throw new BusinessException(404, "接口不存在");
        }
        consume(token, "session", null, ipHash);
    }

    private void consume(String token, String purpose, Long sessionId, String ipHash) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(403, "缺少人机验证凭据");
        }

        AiChallenge challenge = challengeMapper.selectByHash(sha256(token.trim()));
        if (challenge == null || !purpose.equals(challenge.getPurpose())) {
            throw new BusinessException(403, "人机验证无效");
        }
        if (!ipHash.equals(challenge.getIpHash())) {
            throw new BusinessException(403, "人机验证无效");
        }
        if (sessionId != null && !sessionId.equals(challenge.getAnonSessionId())) {
            throw new BusinessException(403, "人机验证无效");
        }
        if (sessionId == null && challenge.getAnonSessionId() != null) {
            throw new BusinessException(403, "人机验证无效");
        }
        if (challenge.getUsedAt() != null || challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(429, "人机验证已失效，请重新获取");
        }
        // 原子核销：并发下只有一个请求能成功
        if (challengeMapper.consume(challenge.getId()) != 1) {
            throw new BusinessException(429, "人机验证已失效，请重新获取");
        }
    }

    private void requirePublicAndEnabled() {
        if (!appMode.isPublic() || !properties.isAiChallengeEnabled()) {
            throw new BusinessException(404, "接口不存在");
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
