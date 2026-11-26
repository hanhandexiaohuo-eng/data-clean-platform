package com.dataclean.util;

import com.dataclean.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 供应商密钥的加解密工具。
 *
 * <p>使用 AES-256-GCM（带认证）：
 * <ul>
 *   <li>主密钥只来自环境变量 AI_SECRET_KEY（Base64 编码的 32 字节）；</li>
 *   <li>每次加密生成 12 字节随机 IV，密文格式为 {@code v1:Base64(iv||ciphertext)}；</li>
 *   <li>任何异常都只返回通用错误信息，不包含密钥或密文片段。</li>
 * </ul>
 */
@Component
public class AiSecretCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey key;

    public AiSecretCipher(@Value("${app.ai.secret-key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    /**
     * 主密钥是否已正确配置。
     */
    public boolean isConfigured() {
        return key != null;
    }

    /**
     * 加密明文密钥。空值直接抛业务异常，避免把空串写入密文字段。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new BusinessException(400, "API Key 不能为空");
        }
        if (key == null) {
            throw new BusinessException(500, "未配置 AI 密钥加密主密钥（AI_SECRET_KEY），无法保存 API Key");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new BusinessException(500, "API Key 加密失败");
        }
    }

    /**
     * 解密。密文被篡改或格式不正确时返回通用异常，不泄露细节。
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return null;
        }
        if (!ciphertext.startsWith(VERSION_PREFIX)) {
            throw new BusinessException(500, "API Key 密文格式不正确");
        }
        if (key == null) {
            throw new BusinessException(500, "未配置 AI 密钥加密主密钥（AI_SECRET_KEY），无法解密 API Key");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(VERSION_PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("payload too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(500, "API Key 解密失败");
        }
    }

    /**
     * 生成用于界面展示的掩码，例如 {@code sk-****abcd}。
     * 长度不足 8 位时只返回固定掩码，避免泄露过短密钥的过多字符。
     */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        if (plaintext.length() < 8) {
            return "****";
        }
        String prefix = plaintext.substring(0, Math.min(3, plaintext.length() - 4));
        String suffix = plaintext.substring(plaintext.length() - 4);
        return prefix + "****" + suffix;
    }

    private SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalArgumentException("key length must be 32 bytes");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (Exception e) {
            // 不输出密钥内容，只提示格式问题
            throw new IllegalStateException("AI_SECRET_KEY 格式不正确，必须为 Base64 编码的 32 字节随机值");
        }
    }
}
