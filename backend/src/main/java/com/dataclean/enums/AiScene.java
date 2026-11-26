package com.dataclean.enums;

import java.util.Locale;

/**
 * AI 调用场景。
 *
 * <p>场景用于模型路由、配额与审计；公开模式仅开放前 4 个只读分析场景。
 */
public enum AiScene {

    DATASET_SUMMARY("dataset_summary"),
    FIELD_RELATION("field_relation"),
    OUTLIER_ANALYSIS("outlier_analysis"),
    CHART_INSIGHT("chart_insight"),
    CLEAN_SUGGESTION("clean_suggestion");

    private final String templateKey;

    AiScene(String templateKey) {
        this.templateKey = templateKey;
    }

    public String templateKey() {
        return templateKey;
    }

    /** 公开模式是否允许该场景（清洗建议仅私有可用）。 */
    public boolean publicAllowed() {
        return this != CLEAN_SUGGESTION;
    }

    public static AiScene fromTemplateKey(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return null;
        }
        String key = templateKey.trim().toLowerCase(Locale.ROOT);
        for (AiScene scene : values()) {
            if (scene.templateKey.equals(key)) {
                return scene;
            }
        }
        return null;
    }

    public static AiScene fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
