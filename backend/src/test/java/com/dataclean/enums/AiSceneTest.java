package com.dataclean.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 场景枚举：模板键映射、公开可用性与非法值兜底。
 */
class AiSceneTest {

    @Test
    void templateKeyRoundTrip() {
        for (AiScene scene : AiScene.values()) {
            assertEquals(scene, AiScene.fromTemplateKey(scene.templateKey()));
            assertEquals(scene, AiScene.fromTemplateKey(scene.templateKey().toUpperCase()));
            assertEquals(scene, AiScene.fromCode(scene.name()));
        }
    }

    @Test
    void cleanSuggestionIsPrivateOnly() {
        assertFalse(AiScene.CLEAN_SUGGESTION.publicAllowed());
        assertTrue(AiScene.DATASET_SUMMARY.publicAllowed());
        assertTrue(AiScene.FIELD_RELATION.publicAllowed());
        assertTrue(AiScene.OUTLIER_ANALYSIS.publicAllowed());
        assertTrue(AiScene.CHART_INSIGHT.publicAllowed());
    }

    @Test
    void unknownValueReturnsNull() {
        assertNull(AiScene.fromTemplateKey(null));
        assertNull(AiScene.fromTemplateKey("  "));
        assertNull(AiScene.fromTemplateKey("unknown_scene"));
        assertNull(AiScene.fromCode("NOT_A_SCENE"));
    }

    @Test
    void sceneSetCoversRequiredScenes() {
        assertEquals(5, AiScene.values().length);
    }
}
