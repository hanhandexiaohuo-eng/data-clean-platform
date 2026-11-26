package com.dataclean.service;

import com.dataclean.entity.AiCallLog;
import com.dataclean.enums.AiScene;
import com.dataclean.mapper.AiCallLogMapper;
import com.dataclean.util.PrincipalHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * AI 调用审计：只写元数据，主体以摘要形式落库，写入失败不影响主流程。
 */
@ExtendWith(MockitoExtension.class)
class AiCallAuditServiceTest {

    @Mock
    private AiCallLogMapper callLogMapper;

    private AiCallAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AiCallAuditService(callLogMapper, new PrincipalHasher("audit-test-pepper"));
    }

    private AiSceneRouter.Target target() {
        AiSceneRouter.Target target = new AiSceneRouter.Target();
        target.providerName = "local-provider";
        target.modelCode = "local-model";
        target.apiKeyPlain = "super-secret-key";
        target.apiUrl = "https://provider.example.com/v1/chat";
        return target;
    }

    @Test
    void recordsOnlyMetadataAndHashedPrincipal() {
        auditService.record(AiScene.DATASET_SUMMARY, target(), auditService.principalHash("user-raw-123"),
                1L, 2L, 3L, LocalDateTime.now(), 120, 30, 40,
                new BigDecimal("0.001"), AiCallLog.RESULT_SUCCESS, null, false, "private");

        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogMapper).insert(captor.capture());
        AiCallLog entry = captor.getValue();

        assertEquals(AiScene.DATASET_SUMMARY.name(), entry.getScene());
        assertEquals("local-provider", entry.getProviderName());
        assertEquals("local-model", entry.getModelCode());
        assertEquals(AiCallLog.RESULT_SUCCESS, entry.getResult());
        assertEquals(0, entry.getDegraded());
        assertEquals("private", entry.getQuotaScope());
        assertNotNull(entry.getPrincipalHash());
        assertEquals(64, entry.getPrincipalHash().length());
        assertFalse(entry.getPrincipalHash().contains("user-raw-123"));
        // 实体不含密钥/提示词/响应字段，确保无法落库敏感内容
        assertFalse(entry.toString().contains("super-secret-key"));
        assertFalse(entry.toString().contains("provider.example.com"));
    }

    @Test
    void insertFailureDoesNotBreakCaller() {
        when(callLogMapper.insert(any(AiCallLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> auditService.record(AiScene.CHART_INSIGHT, null, "hash",
                1L, null, null, LocalDateTime.now(), 10, 0, 0, BigDecimal.ZERO,
                AiCallLog.RESULT_FAILED, "IO", false, "public"));
    }

    @Test
    void principalHashIsStableForSameSubject() {
        assertEquals(auditService.principalHash("anon:abc"), auditService.principalHash("anon:abc"));
        assertNotEquals(auditService.principalHash("anon:abc"), auditService.principalHash("anon:xyz"));
    }
}
