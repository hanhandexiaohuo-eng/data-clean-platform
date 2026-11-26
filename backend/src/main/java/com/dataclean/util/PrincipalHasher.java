package com.dataclean.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 调用主体不可逆摘要。
 *
 * <p>HMAC-SHA256(subject, pepper)：审计只保存摘要，不保存会话 ID、用户 ID 原文。
 * pepper 未配置时使用应用内固定盐并记录 WARN（盐值本身不落库、不输出）。
 */
@Slf4j
@Component
public class PrincipalHasher {

    private static final String FALLBACK_PEPPER = "dc-phase6-principal-pepper";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    public PrincipalHasher(@Value("${app.ai.audit-pepper:}") String configuredPepper) {
        if (configuredPepper == null || configuredPepper.isBlank()) {
            log.warn("未配置 app.ai.audit-pepper，审计主体摘要将使用应用内固定盐");
            this.pepper = FALLBACK_PEPPER.getBytes(StandardCharsets.UTF_8);
        } else {
            this.pepper = configuredPepper.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 对主体标识做不可逆摘要；空主体统一归为 anonymous。 */
    public String hash(String subject) {
        String value = subject == null || subject.isBlank() ? "anonymous" : subject;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 摘要失败不能阻断调用，退化为固定标记（不泄露主体原文）
            return "unavailable";
        }
    }
}
