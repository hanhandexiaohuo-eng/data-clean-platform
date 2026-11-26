package com.dataclean.service;

import com.dataclean.entity.AiUsage;
import com.dataclean.enums.AiScene;
import com.dataclean.exception.BusinessException;
import com.dataclean.util.AiSecretCipher;
import com.dataclean.util.OkHttpUtil;
import com.dataclean.util.PrincipalHasher;
import com.dataclean.util.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * AI 调用治理：场景路由 + 配额 + 熔断 + 主备切换 + 审计。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只经后端代理调用供应商，浏览器绝不直接访问模型供应商；</li>
 *   <li>超限 429 且不调用供应商；主备均不可用 503，不继续放大故障；</li>
 *   <li>审计只写元数据，不写明文 Key / 完整 Prompt / 完整响应。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGovernanceService {

    /** 重试仅针对超时与 5xx，且上限 2 次。 */
    private static final int MAX_RETRIES = 2;

    private final AiSceneRouter router;
    private final AiCircuitBreakerService circuitBreaker;
    private final AiQuotaService quotaService;
    private final AiCallAuditService auditService;
    private final AiSecretCipher aiSecretCipher;
    private final PrincipalHasher principalHasher;

    @Value("${app.ai.max-prompt-chars:20000}")
    private int maxPromptChars;

    @Value("${app.ai.governance-enabled:true}")
    private boolean governanceEnabled;

    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return governanceEnabled;
    }

    public InvokeResult invoke(AiScene scene, String prompt, InvokeContext ctx) {
        if (!governanceEnabled) {
            throw new BusinessException(503, "AI 治理未启用");
        }
        AiScene effectiveScene = scene == null ? AiScene.DATASET_SUMMARY : scene;
        String safePrompt = truncate(prompt);
        String principalHash = principalHasher.hash(ctx.subject());
        List<ScopeKey> scopes = scopes(ctx, principalHash);
        String auditScope = ctx.publicMode() ? "public" : "private";

        AiSceneRouter.Route route = router.resolve(effectiveScene);
        if (!route.available()) {
            auditService.record(effectiveScene, null, principalHash, ctx.datasetId(), ctx.datasetVersionId(),
                    ctx.savedChartId(), LocalDateTime.now(), 0, 0, 0, BigDecimal.ZERO,
                    com.dataclean.entity.AiCallLog.RESULT_REJECTED, "NO_ROUTE", false, auditScope);
            throw new BusinessException(503, "AI 服务暂不可用，请稍后再试");
        }

        List<AiSceneRouter.Target> candidates = new ArrayList<>();
        if (route.primary() != null) {
            candidates.add(route.primary());
        }
        if (route.fallback() != null) {
            candidates.add(route.fallback());
        }

        for (int index = 0; index < candidates.size(); index++) {
            AiSceneRouter.Target target = candidates.get(index);
            boolean fallback = index > 0;
            if (skipByCircuit(target)) {
                continue;
            }
            if (!circuitBreaker.allow(target.circuitKey())) {
                continue;
            }
            if (!circuitBreaker.allow(target.providerCircuitKey())) {
                circuitBreaker.releaseProbe(target.circuitKey());
                continue;
            }

            for (ScopeKey scope : scopes) {
                AiQuotaService.QuotaCheck check = quotaService.check(
                        scope.type(), scope.key(), effectiveScene, target.modelCode);
                if (!check.allowed()) {
                    releaseProbes(target);
                    circuitBreaker.recordFailure(target.circuitKey(), "QUOTA", true);
                    auditService.record(effectiveScene, target, principalHash, ctx.datasetId(),
                            ctx.datasetVersionId(), ctx.savedChartId(), LocalDateTime.now(), 0, 0, 0,
                            BigDecimal.ZERO, com.dataclean.entity.AiCallLog.RESULT_REJECTED,
                            "QUOTA_EXCEEDED", fallback, auditScope);
                    throw new BusinessException(429, "AI 调用配额已用完，请稍后再试");
                }
            }

            Semaphore semaphore = semaphoreFor(target);
            boolean acquired = tryAcquire(semaphore);
            if (!acquired) {
                releaseProbes(target);
                throw new BusinessException(429, "AI 请求过于频繁，请稍后再试");
            }
            try {
                for (ScopeKey scope : scopes) {
                    quotaService.recordCall(scope.type(), scope.key(), effectiveScene, target.modelCode);
                }
                LocalDateTime requestAt = LocalDateTime.now();
                long started = System.currentTimeMillis();
                String apiKey = resolveApiKey(target);
                String response = callWithRetry(target, apiKey, safePrompt);
                int latency = (int) (System.currentTimeMillis() - started);
                int inputTokens = estimateTokens(safePrompt);
                int outputTokens = estimateTokens(response);
                BigDecimal cost = estimateCost(target, inputTokens, outputTokens);

                circuitBreaker.recordSuccess(target.circuitKey());
                circuitBreaker.recordSuccess(target.providerCircuitKey());
                for (ScopeKey scope : scopes) {
                    quotaService.recordUsage(scope.type(), scope.key(), effectiveScene, target.modelCode,
                            inputTokens, outputTokens, cost);
                }
                auditService.record(effectiveScene, target, principalHash, ctx.datasetId(),
                        ctx.datasetVersionId(), ctx.savedChartId(), requestAt, latency,
                        inputTokens, outputTokens, cost,
                        fallback
                                ? com.dataclean.entity.AiCallLog.RESULT_DEGRADED
                                : com.dataclean.entity.AiCallLog.RESULT_SUCCESS,
                        null, fallback, auditScope);
                return new InvokeResult(response, target.providerName, target.modelCode,
                        fallback, latency, inputTokens, outputTokens, cost);
            } catch (IOException e) {
                String errorType = classify(e);
                int latency = 0;
                circuitBreaker.recordFailure(target.circuitKey(), errorType, false);
                circuitBreaker.recordFailure(target.providerCircuitKey(), errorType, false);
                auditService.record(effectiveScene, target, principalHash, ctx.datasetId(),
                        ctx.datasetVersionId(), ctx.savedChartId(), LocalDateTime.now(), latency,
                        0, 0, BigDecimal.ZERO,
                        com.dataclean.entity.AiCallLog.RESULT_FAILED, errorType, fallback, auditScope);
                log.warn("AI 调用失败: scene={}, model={}, error={}", effectiveScene,
                        target.modelCode, errorType);
            } finally {
                semaphore.release();
            }
        }

        throw new BusinessException(503, "AI 服务暂不可用，请稍后再试");
    }

    private List<ScopeKey> scopes(InvokeContext ctx, String principalHash) {
        List<ScopeKey> scopes = new ArrayList<>();
        if (!ctx.publicMode()) {
            scopes.add(new ScopeKey(AiUsage.SCOPE_PRIVATE, principalHash));
            return scopes;
        }
        scopes.add(new ScopeKey(AiUsage.SCOPE_PUBLIC_SESSION, principalHash));
        String ipHash = RequestContext.ipHash();
        if (ipHash != null) {
            scopes.add(new ScopeKey(AiUsage.SCOPE_PUBLIC_IP, principalHasher.hash("ip:" + ipHash)));
        }
        scopes.add(new ScopeKey(AiUsage.SCOPE_PUBLIC_GLOBAL, principalHasher.hash("global")));
        return scopes;
    }

    private boolean skipByCircuit(AiSceneRouter.Target target) {
        return circuitBreaker.isOpen(target.circuitKey())
                || circuitBreaker.isOpen(target.providerCircuitKey());
    }

    private void releaseProbes(AiSceneRouter.Target target) {
        circuitBreaker.releaseProbe(target.circuitKey());
        circuitBreaker.releaseProbe(target.providerCircuitKey());
    }

    private Semaphore semaphoreFor(AiSceneRouter.Target target) {
        String key = target.modelCode == null ? "legacy" : target.modelCode;
        return semaphores.computeIfAbsent(key,
                k -> new Semaphore(Math.max(1, target.concurrencyLimit)));
    }

    private boolean tryAcquire(Semaphore semaphore) {
        try {
            return semaphore.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String resolveApiKey(AiSceneRouter.Target target) {
        if (target.apiKeyCipher != null && !target.apiKeyCipher.isBlank()) {
            return aiSecretCipher.decrypt(target.apiKeyCipher);
        }
        if (target.apiKeyPlain != null && !target.apiKeyPlain.isBlank()) {
            return target.apiKeyPlain;
        }
        throw new BusinessException(503, "AI 服务暂不可用");
    }

    private String callWithRetry(AiSceneRouter.Target target, String apiKey, String prompt) throws IOException {
        int attempts = Math.min(Math.max(target.retryCount, 0), MAX_RETRIES) + 1;
        IOException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                return OkHttpUtil.callAiApi(target.apiUrl, apiKey, target.modelCode,
                        target.temperature, target.maxTokens, target.timeoutMs, prompt);
            } catch (IOException e) {
                last = e;
                if (!isRetryable(e) || attempt == attempts - 1) {
                    throw e;
                }
                sleepQuietly(150L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("AI call failed") : last;
    }

    private boolean isRetryable(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("HTTP 429")) {
            return false;
        }
        if (message.contains("HTTP 4")) {
            return false;
        }
        return true;
    }

    private String classify(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("HTTP 429")) {
            return "429";
        }
        if (message.contains("HTTP 5")) {
            return "5XX";
        }
        String type = e.getClass().getSimpleName();
        if (type.toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
            return "TIMEOUT";
        }
        return "IO";
    }

    private BigDecimal estimateCost(AiSceneRouter.Target target, int inputTokens, int outputTokens) {
        BigDecimal input = target.costInput == null ? BigDecimal.ZERO : target.costInput;
        BigDecimal output = target.costOutput == null ? BigDecimal.ZERO : target.costOutput;
        return input.multiply(BigDecimal.valueOf(inputTokens)).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .add(output.multiply(BigDecimal.valueOf(outputTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    private String truncate(String prompt) {
        String value = prompt == null ? "" : prompt;
        return value.length() > maxPromptChars ? value.substring(0, maxPromptChars) : value;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 单次调用上下文。 */
    public record InvokeContext(Long datasetId, Long datasetVersionId, Long savedChartId,
                                String subject, boolean publicMode) {
    }

    /** 治理层返回（公开模式只取 text）。 */
    public record InvokeResult(String text, String providerName, String modelCode,
                               boolean degraded, int latencyMs, int inputTokens,
                               int outputTokens, BigDecimal cost) {
    }

    private record ScopeKey(String type, String key) {
    }
}
