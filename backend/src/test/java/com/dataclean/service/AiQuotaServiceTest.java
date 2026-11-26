package com.dataclean.service;

import com.dataclean.entity.AiQuota;
import com.dataclean.entity.AiUsage;
import com.dataclean.enums.AiScene;
import com.dataclean.mapper.AiQuotaMapper;
import com.dataclean.mapper.AiUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 配额：私有默认阈值、次数/Token/预算耗尽拒绝、公开未配置时放行、调用前先计数。
 */
@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock
    private AiQuotaMapper quotaMapper;
    @Mock
    private AiUsageMapper usageMapper;

    private AiQuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new AiQuotaService(quotaMapper, usageMapper);
    }

    private AiQuota quota(Long maxCalls, Long maxTokens, BigDecimal maxCost) {
        AiQuota quota = new AiQuota();
        quota.setMaxCalls(maxCalls);
        quota.setMaxTokens(maxTokens);
        quota.setMaxCost(maxCost);
        return quota;
    }

    @Test
    void privateScopeUsesDefaultsWhenNoQuotaRow() {
        when(quotaMapper.findApplicable(anyString(), anyString(), any(), anyString())).thenReturn(null);
        when(usageMapper.sumCalls(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(0L);
        when(usageMapper.sumTokens(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(0L);
        when(usageMapper.sumCost(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(BigDecimal.ZERO);

        AiQuotaService.QuotaCheck check = quotaService.check(
                AiUsage.SCOPE_PRIVATE, "hash", AiScene.DATASET_SUMMARY, "gpt-4o-mini");
        assertTrue(check.allowed());
        assertEquals(AiUsage.SCOPE_PRIVATE, check.scopeType());
    }

    @Test
    void privateScopeDeniedWhenCallsReachLimit() {
        when(quotaMapper.findApplicable(anyString(), anyString(), any(), anyString()))
                .thenReturn(quota(5L, 1000L, BigDecimal.TEN));
        when(usageMapper.sumCalls(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(5L);

        AiQuotaService.QuotaCheck check = quotaService.check(
                AiUsage.SCOPE_PRIVATE, "hash", AiScene.DATASET_SUMMARY, "gpt-4o-mini");
        assertFalse(check.allowed());
        assertEquals("调用次数已达上限", check.reason());
        assertTrue(check.budgetExhausted());
    }

    @Test
    void privateScopeDeniedWhenBudgetExhausted() {
        when(quotaMapper.findApplicable(anyString(), anyString(), any(), anyString()))
                .thenReturn(quota(100L, 1000L, BigDecimal.ONE));
        when(usageMapper.sumCalls(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(1L);
        when(usageMapper.sumTokens(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(10L);
        when(usageMapper.sumCost(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(BigDecimal.ONE);

        AiQuotaService.QuotaCheck check = quotaService.check(
                AiUsage.SCOPE_PRIVATE, "hash", AiScene.CHART_INSIGHT, "gpt-4o-mini");
        assertFalse(check.allowed());
        assertEquals("预算已用完", check.reason());
    }

    @Test
    void publicScopeWithoutQuotaRowIsUnlimited() {
        when(quotaMapper.findApplicable(anyString(), anyString(), any(), anyString())).thenReturn(null);

        AiQuotaService.QuotaCheck check = quotaService.check(
                AiUsage.SCOPE_PUBLIC_SESSION, "hash", AiScene.DATASET_SUMMARY, "*");
        assertTrue(check.allowed());
        assertEquals(AiUsage.SCOPE_PUBLIC_SESSION, check.scopeType());
    }

    @Test
    void recordCallWritesOnceWithTodayPeriod() {
        quotaService.recordCall(AiUsage.SCOPE_PRIVATE, "hash", AiScene.OUTLIER_ANALYSIS, "gpt-4o-mini");

        verify(usageMapper).accumulate(eq(AiUsage.SCOPE_PRIVATE), eq("hash"),
                eq(AiScene.OUTLIER_ANALYSIS.name()), eq("gpt-4o-mini"),
                eq(LocalDate.now().toString()), eq(1L), eq(0L), eq(0L), eq(BigDecimal.ZERO));
    }

    @Test
    void recordUsageAccumulatesTokensAndCost() {
        quotaService.recordUsage(AiUsage.SCOPE_PRIVATE, "hash", AiScene.FIELD_RELATION, "gpt-4o-mini",
                120L, 80L, new BigDecimal("0.002"));

        verify(usageMapper).accumulate(eq(AiUsage.SCOPE_PRIVATE), eq("hash"),
                eq(AiScene.FIELD_RELATION.name()), eq("gpt-4o-mini"),
                eq(LocalDate.now().toString()), eq(0L), eq(120L), eq(80L), eq(new BigDecimal("0.002")));
    }
}
