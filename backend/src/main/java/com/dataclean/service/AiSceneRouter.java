package com.dataclean.service;

import com.dataclean.entity.AiConfig;
import com.dataclean.entity.AiModel;
import com.dataclean.entity.AiProvider;
import com.dataclean.entity.AiSceneRoute;
import com.dataclean.enums.AiScene;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.mapper.AiModelMapper;
import com.dataclean.mapper.AiProviderMapper;
import com.dataclean.mapper.AiSceneRouteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 场景路由解析：场景路由 > 模型默认 > 全局默认。
 *
 * <p>未配置 {@code ai_scene_route} 时回退到既有 {@code ai_config} 默认配置（legacy），
 * 保证存量部署在新增治理表为空时仍可正常调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSceneRouter {

    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_CONCURRENCY = 4;

    private final AiSceneRouteMapper routeMapper;
    private final AiModelMapper modelMapper;
    private final AiProviderMapper providerMapper;
    private final AiConfigMapper configMapper;

    public Route resolve(AiScene scene) {
        AiSceneRoute route = routeMapper.selectByScene(scene.name());
        Target primary = null;
        Target fallback = null;
        if (route != null && Integer.valueOf(1).equals(route.getStatus())) {
            primary = target(route.getPrimaryModelId());
            fallback = target(route.getFallbackModelId());
            primary = applyRouteOverrides(primary, route);
            fallback = applyRouteOverrides(fallback, route);
        }
        if (primary == null && fallback == null) {
            return legacy(scene);
        }
        return new Route(scene, primary, fallback, false);
    }

    /** 是否存在可用的治理路由配置（前端/健康检查可用）。 */
    public boolean hasConfiguredRoute(AiScene scene) {
        AiSceneRoute route = routeMapper.selectByScene(scene.name());
        return route != null && Integer.valueOf(1).equals(route.getStatus());
    }

    private Target target(Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModel model = modelMapper.selectById(modelId);
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())) {
            return null;
        }
        AiProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())) {
            return null;
        }
        Target target = new Target();
        target.modelId = model.getId();
        target.modelCode = model.getModelCode();
        target.providerId = provider.getId();
        target.providerName = provider.getName();
        target.apiUrl = provider.getApiUrl();
        target.apiKeyCipher = provider.getApiKeyCipher();
        target.temperature = model.getTemperature() == null
                ? DEFAULT_TEMPERATURE : model.getTemperature().doubleValue();
        target.maxTokens = model.getMaxOutputTokens() == null ? DEFAULT_MAX_TOKENS : model.getMaxOutputTokens();
        target.timeoutMs = model.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : model.getTimeoutMs();
        target.retryCount = model.getRetryCount() == null ? 1 : model.getRetryCount();
        target.concurrencyLimit = model.getConcurrencyLimit() == null
                ? DEFAULT_CONCURRENCY : model.getConcurrencyLimit();
        target.costInput = model.getCostPer1kInput();
        target.costOutput = model.getCostPer1kOutput();
        target.legacy = false;
        return target;
    }

    /** 路由级参数覆盖模型默认值。 */
    private Target applyRouteOverrides(Target target, AiSceneRoute route) {
        if (target == null) {
            return null;
        }
        if (route.getTemperature() != null) {
            target.temperature = route.getTemperature().doubleValue();
        }
        if (route.getMaxOutputTokens() != null) {
            target.maxTokens = route.getMaxOutputTokens();
        }
        if (route.getTimeoutMs() != null) {
            target.timeoutMs = route.getTimeoutMs();
        }
        if (route.getRetryCount() != null) {
            target.retryCount = route.getRetryCount();
        }
        if (route.getConcurrencyLimit() != null) {
            target.concurrencyLimit = route.getConcurrencyLimit();
        }
        return target;
    }

    private Route legacy(AiScene scene) {
        AiConfig config = configMapper.selectDefault();
        if (config == null) {
            return new Route(scene, null, null, true);
        }
        Target target = new Target();
        target.modelCode = config.getModelName();
        target.providerName = "legacy";
        target.apiUrl = config.getApiUrl();
        target.apiKeyCipher = config.getApiKeyCipher();
        target.apiKeyPlain = config.getApiKey();
        target.temperature = config.getTemperature() == null
                ? DEFAULT_TEMPERATURE : config.getTemperature().doubleValue();
        target.maxTokens = config.getMaxTokens() == null ? DEFAULT_MAX_TOKENS : config.getMaxTokens();
        target.timeoutMs = config.getTimeout() == null ? DEFAULT_TIMEOUT_MS : config.getTimeout();
        target.retryCount = 1;
        target.concurrencyLimit = DEFAULT_CONCURRENCY;
        target.costInput = BigDecimal.ZERO;
        target.costOutput = BigDecimal.ZERO;
        target.legacy = true;
        return new Route(scene, target, null, true);
    }

    /** 一次调用的有效路由。 */
    public record Route(AiScene scene, Target primary, Target fallback, boolean legacy) {

        public boolean available() {
            return primary != null || fallback != null;
        }
    }

    /** 已解析出的可调用目标（参数已按优先级合并）。 */
    public static class Target {
        public Long modelId;
        public String modelCode;
        public Long providerId;
        public String providerName;
        public String apiUrl;
        public String apiKeyCipher;
        public String apiKeyPlain;
        public double temperature;
        public int maxTokens;
        public int timeoutMs;
        public int retryCount;
        public int concurrencyLimit;
        public BigDecimal costInput;
        public BigDecimal costOutput;
        public boolean legacy;

        public String circuitKey() {
            if (modelId != null) {
                return "model:" + modelId;
            }
            return "provider:legacy";
        }

        public String providerCircuitKey() {
            if (providerId != null) {
                return "provider:" + providerId;
            }
            return "provider:legacy";
        }
    }
}
