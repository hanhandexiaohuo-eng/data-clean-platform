package com.dataclean.util;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 送入 AI 前的敏感信息脱敏。
 *
 * <p>只做「最小化 + 默认脱敏」：手机号、邮箱、身份证、银行卡、常见 Token/Key 一律打码；
 * 不改变业务库中的原始数据，仅作用于发往模型的文本。
 */
@Component
public class AiPiiMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<!\\d)(\\d{6})(\\d{8})(\\d{3}[\\dXx])(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile(
            "(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");
    private static final Pattern SECRET_TOKEN = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|"
                    + "Bearer\\s+[A-Za-z0-9._-]{8,})");

    /** 列名命中这些关键字时，整列样本值直接打码（用于有限的样本行）。 */
    private static final List<String> SENSITIVE_COLUMN_KEYWORDS = List.of(
            "phone", "mobile", "tel", "email", "mail", "id_card", "idcard", "identity",
            "bank", "card", "account", "address", "addr",
            "手机", "电话", "邮箱", "邮件", "身份证", "证件", "银行卡", "卡号", "账号", "地址", "住址");

    /**
     * 对任意文本做脱敏；null 原样返回。
     */
    public String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = EMAIL.matcher(text).replaceAll("[email]");
        result = PHONE.matcher(result).replaceAll("1**********");
        result = ID_CARD.matcher(result).replaceAll("$1********$3");
        result = BANK_CARD.matcher(result).replaceAll("$1 **** **** $2");
        result = SECRET_TOKEN.matcher(result).replaceAll("[redacted]");
        return result;
    }

    /**
     * 按列名判断是否敏感；敏感列的值整体替换为占位符。
     */
    public String maskValue(String columnName, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (isSensitiveColumn(columnName)) {
            return "[redacted]";
        }
        return maskText(value);
    }

    public boolean isSensitiveColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        String lower = columnName.toLowerCase();
        return SENSITIVE_COLUMN_KEYWORDS.stream().anyMatch(lower::contains);
    }
}
