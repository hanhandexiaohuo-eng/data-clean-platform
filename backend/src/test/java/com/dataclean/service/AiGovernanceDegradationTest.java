package com.dataclean.service;

import com.dataclean.enums.AiScene;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AiCircuitStateMapper;
import com.dataclean.util.AiSecretCipher;
import com.dataclean.util.PrincipalHasher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 治理降级链路：主模型失败切备用（DEGRADED）、全部失败 503、配额拒绝 429 且不发起真实调用。
 *
 * <p>使用本地 {@link HttpServer} 模拟供应商，验证的是真实 HTTP 调用与降级判定，
 * 不依赖外部网络与真实密钥。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiGovernanceDegradationTest {

    @Mock
    private AiSceneRouter router;
    @Mock
    private AiQuotaService quotaService;
    @Mock
    private AiCallAuditService auditService;
    @Mock
    private AiSecretCipher aiSecretCipher;

    private AiCircuitBreakerService circuitBreaker;
    private AiGovernanceService governanceService;
    private HttpServer server;
    private final AtomicInteger failHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        circuitBreaker = new AiCircuitBreakerService(mock(AiCircuitStateMapper.class));
        governanceService = new AiGovernanceService(router, circuitBreaker, quotaService,
                auditService, aiSecretCipher, new PrincipalHasher("degradation-pepper"));
        setField("governanceEnabled", true);
        setField("maxPromptChars", 20000);

        when(quotaService.check(anyString(), anyString(), any(), anyString()))
                .thenReturn(new AiQuotaService.QuotaCheck(true, "private", null, false));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fail", exchange -> {
            failHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"fallback-ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = AiGovernanceService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(governanceService, value);
    }

    private AiSceneRouter.Target target(String path, String modelCode, Long modelId) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AiSceneRouter.Target target = new AiSceneRouter.Target();
        target.modelId = modelId;
        target.modelCode = modelCode;
        target.providerId = modelId;
        target.providerName = "local-" + modelCode;
        target.apiUrl = base + path;
        target.apiKeyPlain = "test-key";
        target.temperature = 0.3;
        target.maxTokens = 256;
        target.timeoutMs = 3000;
        target.retryCount = 0;
        target.concurrencyLimit = 2;
        target.costInput = BigDecimal.ZERO;
        target.costOutput = BigDecimal.ZERO;
        return target;
    }

    private AiGovernanceService.InvokeContext ctx() {
        return new AiGovernanceService.InvokeContext(1L, 2L, null, "user:1", false);
    }

    @Test
    void primaryFailureFallsBackAndMarksDegraded() {
        AiSceneRouter.Target primary = target("/fail", "primary-model", 11L);
        AiSceneRouter.Target fallback = target("/ok", "fallback-model", 12L);
        when(router.resolve(AiScene.DATASET_SUMMARY))
                .thenReturn(new AiSceneRouter.Route(AiScene.DATASET_SUMMARY, primary, fallback, false));

        AiGovernanceService.InvokeResult result = governanceService.invoke(
                AiScene.DATASET_SUMMARY, "请总结这份数据", ctx());

        assertEquals("fallback-ok", result.text());
        assertTrue(result.degraded());
        assertEquals("fallback-model", result.modelCode());
        verify(quotaService, atLeastOnce()).recordUsage(eq("private"), anyString(),
                eq(AiScene.DATASET_SUMMARY), eq("fallback-model"), anyLong(), anyLong(), any());
        verify(auditService, atLeastOnce()).record(eq(AiScene.DATASET_SUMMARY), eq(fallback),
                anyString(), eq(1L), eq(2L), isNull(), any(), anyInt(), anyInt(), anyInt(),
                any(), eq(com.dataclean.entity.AiCallLog.RESULT_DEGRADED), isNull(), eq(true), eq("private"));
    }

    @Test
    void allTargetsFailingReturns503() {
        AiSceneRouter.Target primary = target("/fail", "primary-model", 21L);
        AiSceneRouter.Target fallback = target("/fail", "fallback-model", 22L);
        when(router.resolve(AiScene.CHART_INSIGHT))
                .thenReturn(new AiSceneRouter.Route(AiScene.CHART_INSIGHT, primary, fallback, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.invoke(AiScene.CHART_INSIGHT, "解释图表", ctx()));
        assertEquals(503, ex.getCode());
        assertTrue(failHits.get() >= 2);
        verify(auditService, atLeastOnce()).record(eq(AiScene.CHART_INSIGHT), any(),
                anyString(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(),
                eq(com.dataclean.entity.AiCallLog.RESULT_FAILED), anyString(), anyBoolean(), anyString());
    }

    @Test
    void quotaRejectionReturns429WithoutCallingProvider() {
        AiSceneRouter.Target primary = target("/fail", "primary-model", 31L);
        when(router.resolve(AiScene.OUTLIER_ANALYSIS))
                .thenReturn(new AiSceneRouter.Route(AiScene.OUTLIER_ANALYSIS, primary, null, false));
        when(quotaService.check(anyString(), anyString(), any(), anyString()))
                .thenReturn(new AiQuotaService.QuotaCheck(false, "private", "调用次数已达上限", true));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.invoke(AiScene.OUTLIER_ANALYSIS, "分析异常值", ctx()));
        assertEquals(429, ex.getCode());
        assertEquals(0, failHits.get());
    }

    @Test
    void noRouteReturns503AndIsAudited() {
        when(router.resolve(AiScene.FIELD_RELATION))
                .thenReturn(new AiSceneRouter.Route(AiScene.FIELD_RELATION, null, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.invoke(AiScene.FIELD_RELATION, "字段关系", ctx()));
        assertEquals(503, ex.getCode());
        verify(auditService).record(eq(AiScene.FIELD_RELATION), isNull(), anyString(), any(), any(),
                any(), any(), anyInt(), anyInt(), anyInt(), any(),
                eq(com.dataclean.entity.AiCallLog.RESULT_REJECTED), eq("NO_ROUTE"), eq(false), eq("private"));
    }

    @Test
    void governanceDisabledRejectsInvocation() throws Exception {
        setField("governanceEnabled", false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.invoke(AiScene.DATASET_SUMMARY, "x", ctx()));
        assertEquals(503, ex.getCode());
    }
}
