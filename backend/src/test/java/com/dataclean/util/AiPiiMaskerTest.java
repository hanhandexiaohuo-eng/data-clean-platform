package com.dataclean.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiPiiMaskerTest {

    private final AiPiiMasker masker = new AiPiiMasker();

    @Test
    void masksPhoneNumber() {
        String result = masker.maskText("联系人电话 13812345678，请尽快回访");
        assertFalse(result.contains("13812345678"));
        assertTrue(result.contains("1**********"));
    }

    @Test
    void masksEmail() {
        String result = masker.maskText("邮箱 zhang.san@example.com");
        assertFalse(result.contains("zhang.san@example.com"));
        assertTrue(result.contains("[email]"));
    }

    @Test
    void masksIdCard() {
        String result = masker.maskText("身份证 110101199001011234");
        assertFalse(result.contains("110101199001011234"));
        assertTrue(result.contains("110101********1234"));
    }

    @Test
    void masksBankCard() {
        String result = masker.maskText("卡号 6222021234567890123");
        assertFalse(result.contains("6222021234567890123"));
        assertTrue(result.contains("****"));
    }

    @Test
    void masksTokenLikeSecrets() {
        String result = masker.maskText("Authorization: Bearer abcdefghijklmnop123456");
        assertFalse(result.contains("abcdefghijklmnop123456"));
        assertTrue(result.contains("[redacted]"));
    }

    @Test
    void keepsNormalTextUnchanged() {
        String text = "本月销售额为 12345 元，环比增长 12%";
        assertEquals(text, masker.maskText(text));
    }

    @Test
    void sensitiveColumnValueIsFullyRedacted() {
        assertEquals("[redacted]", masker.maskValue("手机号", "13812345678"));
        assertEquals("[redacted]", masker.maskValue("address", "北京市朝阳区某街道"));
        assertEquals("正常内容", masker.maskValue("city", "正常内容"));
    }
}
