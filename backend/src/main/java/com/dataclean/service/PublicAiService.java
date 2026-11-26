package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.dto.AiGenerateDTO;
import com.dataclean.entity.AiConfig;
import com.dataclean.entity.AiGenerationLog;
import com.dataclean.entity.AiPromptTemplate;
import com.dataclean.entity.Dataset;
import com.dataclean.enums.AiScene;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.mapper.AiGenerationLogMapper;
import com.dataclean.mapper.AiPromptTemplateMapper;
import com.dataclean.mapper.AnonUsageMapper;
import com.dataclean.metrics.RuntimeMetrics;
import com.dataclean.util.AiPiiMasker;
import com.dataclean.util.AiSecretCipher;
import com.dataclean.util.OkHttpUtil;
import com.dataclean.util.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 公开模式 AI 调用。
 *
 * <p>安全边界：
 * <ul>
 *   <li>默认关闭；仅开放 4 个预置场景，拒绝自定义 Prompt、禁止指定配置/模型；</li>
 *   <li>Prompt 只含字段名、类型、聚合统计与脱敏摘要，不含原始全量数据；</li>
 *   <li>会话日配额 + IP 日配额 + 全站日调用 + 全站日 Token 预算四重限制；</li>
 *   <li>并发信号量 + 超时 + 熔断；超限 429，依赖不可用 503。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicAiService {

    /** 公开允许的模板（仅输出型分析，不包含清洗执行类模板） */
    private static final Set<String> ALLOWED_TEMPLATES = Set.of(
            "dataset_summary", "field_relation", "outlier_analysis", "chart_insight");
    private static final Set<String> ALLOWED_EXTRA_KEYS = Set.of(
            "chartType", "xAxis", "yAxis", "aggregation", "chartDataSummary");
    private static final int LOG_TEXT_LIMIT = 2000;

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final AiConfigMapper aiConfigMapper;
    private final AiPromptTemplateMapper templateMapper;
    private final AiGenerationLogMapper generationLogMapper;
    private final AnonUsageMapper usageMapper;
    private final OwnershipService ownershipService;
    private final HumanChallengeService challengeService;
    private final AiPiiMasker aiPiiMasker;
    private final AiSecretCipher aiSecretCipher;
    private final ObjectMapper objectMapper;
    private final RuntimeMetrics metrics;
    private final AiGovernanceService aiGovernanceService;
    private final AiPromptContextBuilder aiPromptContextBuilder;

    private final Semaphore concurrency = new Semaphore(8);

    private volatile LocalDate breakerDate;
    private volatile boolean breakerOpen;

    public String generate(AiGenerateDTO dto) {
        if (!appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
        if (!properties.isAiEnabled()) {
            throw new BusinessException(403, "公开演示环境暂未开放 AI 分析");
        }
        validateRequest(dto);

        Dataset dataset = ownershipService.requireDataset(dto.getDatasetId());
        if (properties.isAiChallengeEnabled()) {
            challengeService.verify(dto.getChallengeToken());
        }
        checkQuota();

        String prompt = buildPrompt(dto, dataset);

        if (!acquirePermit()) {
            metrics.aiQuotaRejected();
            throw new BusinessException(429, "AI 请求过于频繁，请稍后再试");
        }
        try {
            // 先计入调用次数，避免并发绕过
            recordCalls();

            if (aiGovernanceService.isEnabled()) {
                AiScene scene = AiScene.fromTemplateKey(dto.getTemplateKey());
                AiGovernanceService.InvokeResult result = aiGovernanceService.invoke(
                        scene, prompt,
                        new AiGovernanceService.InvokeContext(dataset.getId(), null, null,
                                "anon:" + RequestContext.requireSessionId(), true));
                long tokens = estimateTokens(prompt.length()
                        + (result.text() == null ? 0 : result.text().length()));
                recordTokens(tokens);
                metrics.aiSuccess(tokens);
                saveLog(dto, prompt, result.text(), null, 1);
                return result.text();
            }

            AiConfig config = aiConfigMapper.selectDefault();
            if (config == null) {
                throw new BusinessException(503, "AI 服务暂不可用");
            }
            int maxTokens = Math.min(
                    config.getMaxTokens() != null ? config.getMaxTokens() : properties.getAiMaxOutputTokens(),
                    properties.getAiMaxOutputTokens());
            int timeout = Math.min(
                    config.getTimeout() != null ? config.getTimeout() : properties.getAiTimeoutMs(),
                    properties.getAiTimeoutMs());

            String response = OkHttpUtil.callAiApi(
                    config.getApiUrl(),
                    resolveApiKey(config),
                    config.getModelName(),
                    config.getTemperature() != null ? config.getTemperature().doubleValue() : 0.7,
                    maxTokens,
                    timeout,
                    prompt);

            long tokens = estimateTokens(prompt.length() + (response == null ? 0 : response.length()));
            recordTokens(tokens);
            metrics.aiSuccess(tokens);
            saveLog(dto, prompt, response, null, 1);
            return response;
        } catch (BusinessException e) {
            if (e.getCode() == 429) {
                metrics.aiQuotaRejected();
            } else {
                metrics.aiFailure();
            }
            saveLog(dto, prompt, "", safeAiMessage(e), 0);
            throw e;
        } catch (IOException e) {
            metrics.aiFailure();
            log.warn("公开 AI 调用失败: {}", e.getClass().getSimpleName());
            saveLog(dto, prompt, "", "AI 服务调用失败", 0);
            throw new BusinessException(503, "AI 服务暂不可用，请稍后再试");
        } finally {
            concurrency.release();
        }
    }

    private void validateRequest(AiGenerateDTO dto) {
        if (dto == null || dto.getDatasetId() == null) {
            throw new BusinessException(400, "datasetId 不能为空");
        }
        if (dto.getConfigId() != null) {
            throw new BusinessException(403, "公开模式不支持指定 AI 配置");
        }
        if (dto.getCustomPrompt() != null && !dto.getCustomPrompt().isBlank()) {
            throw new BusinessException(403, "公开模式不支持自定义提示词");
        }
        String templateKey = dto.getTemplateKey();
        if (templateKey == null || !ALLOWED_TEMPLATES.contains(templateKey)) {
            throw new BusinessException(403, "该分析场景未开放");
        }
        if (dto.getVariables() != null) {
            if (dto.getVariables().size() > 8) {
                throw new BusinessException(400, "模板变量过多");
            }
            for (Map.Entry<String, String> entry : dto.getVariables().entrySet()) {
                if (entry.getValue() != null && entry.getValue().length() > 200) {
                    throw new BusinessException(400, "模板变量超出长度限制");
                }
            }
        }
        if (dto.getExtraContext() != null) {
            for (Map.Entry<String, Object> entry : dto.getExtraContext().entrySet()) {
                if (!ALLOWED_EXTRA_KEYS.contains(entry.getKey())) {
                    throw new BusinessException(403, "不支持额外上下文: " + entry.getKey());
                }
                String value = String.valueOf(entry.getValue());
                if (value.length() > 500) {
                    throw new BusinessException(400, "图表上下文超出长度限制");
                }
            }
        }
    }

    private String buildPrompt(AiGenerateDTO dto, Dataset dataset) {
        AiPromptTemplate template = templateMapper.selectByKey(dto.getTemplateKey());
        if (template == null || template.getContent() == null) {
            throw new BusinessException(503, "分析模板暂不可用");
        }
        String prompt = template.getContent();

        // 仅填充受信数据集元信息与统计摘要
        prompt = prompt.replace("{{datasetName}}", safe(dataset.getName()));
        prompt = prompt.replace("{{rowCount}}", String.valueOf(dataset.getRowCount()));
        prompt = prompt.replace("{{columnCount}}", String.valueOf(dataset.getColumnCount()));
        prompt = prompt.replace("{{fileType}}", safe(dataset.getFileType()));
        // 公开模式：只发送字段统计摘要，绝不发送样本行
        String columnContext = aiPromptContextBuilder.build(dataset.getId(), true);
        if (prompt.contains("{{columnStats}}")) {
            prompt = prompt.replace("{{columnStats}}", columnContext);
        }
        prompt = prompt.replace("{{numericStats}}", columnContext);

        if (dto.getVariables() != null) {
            for (Map.Entry<String, String> entry : dto.getVariables().entrySet()) {
                prompt = prompt.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
            }
        }
        if (dto.getExtraContext() != null) {
            for (Map.Entry<String, Object> entry : dto.getExtraContext().entrySet()) {
                prompt = prompt.replace("{{" + entry.getKey() + "}}", safe(String.valueOf(entry.getValue())));
            }
        }

        // 统一脱敏 + 长度上限
        String masked = aiPiiMasker.maskText(prompt);
        if (masked.length() > properties.getAiMaxInputChars()) {
            masked = masked.substring(0, properties.getAiMaxInputChars());
        }
        return masked;
    }

    private void checkQuota() {
        LocalDate today = LocalDate.now();
        Long sessionId = RequestContext.requireSessionId();
        String ipHash = RequestContext.ipHash();

        if (isBreakerOpen()) {
            metrics.aiBreakerRejected();
            throw new BusinessException(503, "今日 AI 服务额度已用完，请明天再试");
        }
        long sessionCalls = nullToZero(usageMapper.getCounter("session", String.valueOf(sessionId), "ai_call", today));
        if (sessionCalls >= properties.getAiMaxCallsPerDay()) {
            metrics.aiQuotaRejected();
            throw new BusinessException(429, "当前会话今日 AI 调用次数已达上限");
        }
        long ipCalls = nullToZero(usageMapper.getCounter("ip", ipHash, "ai_call", today));
        if (ipCalls >= properties.getAiMaxCallsPerIpPerDay()) {
            metrics.aiQuotaRejected();
            throw new BusinessException(429, "当前网络今日 AI 调用次数已达上限");
        }
        long globalCalls = usageMapper.sumGlobal("ai_call", today);
        if (globalCalls >= properties.getAiGlobalDailyCalls()) {
            openBreaker();
            metrics.aiBreakerRejected();
            throw new BusinessException(503, "今日 AI 服务额度已用完，请明天再试");
        }
        long globalTokens = usageMapper.sumGlobal("ai_token", today);
        if (globalTokens >= properties.getAiGlobalDailyTokenBudget()) {
            openBreaker();
            metrics.aiBreakerRejected();
            throw new BusinessException(503, "今日 AI 服务额度已用完，请明天再试");
        }
    }

    private void recordCalls() {
        LocalDate today = LocalDate.now();
        usageMapper.increment("session", String.valueOf(RequestContext.requireSessionId()), "ai_call", today, 1);
        usageMapper.increment("ip", RequestContext.ipHash(), "ai_call", today, 1);
        usageMapper.increment("global", "global", "ai_call", today, 1);
    }

    private void recordTokens(long tokens) {
        LocalDate today = LocalDate.now();
        usageMapper.increment("session", String.valueOf(RequestContext.requireSessionId()), "ai_token", today, tokens);
        usageMapper.increment("ip", RequestContext.ipHash(), "ai_token", today, tokens);
        usageMapper.increment("global", "global", "ai_token", today, tokens);
    }

    private boolean acquirePermit() {
        int max = Math.max(1, properties.getAiMaxConcurrency());
        try {
            return concurrency.tryAcquire(2, TimeUnit.SECONDS) && drainageGuard(max);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 信号量以 8 初始化，这里按配置上限做二次约束 */
    private boolean drainageGuard(int max) {
        int inUse = 8 - concurrency.availablePermits();
        if (inUse <= max) {
            return true;
        }
        concurrency.release();
        return false;
    }

    private boolean isBreakerOpen() {
        if (!properties.isAiBreakerEnabled()) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (breakerDate != null && !breakerDate.equals(today)) {
            breakerDate = today;
            breakerOpen = false;
        }
        return breakerOpen;
    }

    private void openBreaker() {
        breakerDate = LocalDate.now();
        breakerOpen = true;
        log.warn("公开 AI 全站额度已用完，熔断至次日");
    }

    private String resolveApiKey(AiConfig config) {
        if (config.getApiKeyCipher() != null && !config.getApiKeyCipher().isBlank()) {
            return aiSecretCipher.decrypt(config.getApiKeyCipher());
        }
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey();
        }
        throw new BusinessException(503, "AI 服务暂不可用");
    }

    private void saveLog(AiGenerateDTO dto, String prompt, String response, String error, int status) {
        try {
            AiGenerationLog generationLog = new AiGenerationLog();
            generationLog.setDatasetId(dto.getDatasetId());
            generationLog.setTemplateKey(dto.getTemplateKey());
            generationLog.setContextType(dto.getContextType());
            generationLog.setInputSummary(truncate(aiPiiMasker.maskText(
                    toJson(Map.of("templateKey", dto.getTemplateKey())))));
            generationLog.setPrompt(truncate(aiPiiMasker.maskText(prompt)));
            generationLog.setResponse(truncate(aiPiiMasker.maskText(response)));
            generationLog.setStatus(status);
            generationLog.setErrorMsg(truncate(error));
            generationLog.setAnonSessionId(RequestContext.requireSessionId());
            generationLogMapper.insert(generationLog);
        } catch (Exception e) {
            log.debug("保存公开 AI 日志失败: {}", e.getClass().getSimpleName());
        }
    }

    private long estimateTokens(int chars) {
        return Math.max(1, chars / 4L);
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private String safeAiMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            String message = be.getMessage();
            if (message != null && message.length() <= 200) {
                return message;
            }
        }
        return "AI 服务调用失败，请检查配置与网络";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > LOG_TEXT_LIMIT ? text.substring(0, LOG_TEXT_LIMIT) + "...(truncated)" : text;
    }
}
