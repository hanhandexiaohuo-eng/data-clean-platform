package com.dataclean.service;

import com.dataclean.dto.AiConfigDTO;
import com.dataclean.dto.AiConfigVO;
import com.dataclean.dto.AiGenerateDTO;
import com.dataclean.dto.AiPromptTemplateDTO;
import com.dataclean.entity.AiConfig;
import com.dataclean.entity.AiGenerationLog;
import com.dataclean.entity.AiPromptTemplate;
import com.dataclean.enums.AiScene;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.mapper.AiGenerationLogMapper;
import com.dataclean.mapper.AiPromptTemplateMapper;
import com.dataclean.util.AiPiiMasker;
import com.dataclean.util.AiSecretCipher;
import com.dataclean.util.OkHttpUtil;
import com.dataclean.util.SqlGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * AI 业务服务层
 * 处理大模型调用、Prompt模板管理和生成历史
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private static final int LOG_TEXT_LIMIT = 2000;

    private final AiConfigMapper aiConfigMapper;
    private final AiPromptTemplateMapper aiPromptTemplateMapper;
    private final AiGenerationLogMapper aiGenerationLogMapper;
    private final DatasetService datasetService;
    private final ObjectMapper objectMapper;
    private final AiSecretCipher aiSecretCipher;
    private final AiPiiMasker aiPiiMasker;
    private final AiGovernanceService aiGovernanceService;

    @Value("${app.ai.max-prompt-chars:20000}")
    private int maxPromptChars;

    // ========== AI 配置管理 ==========

    /**
     * 获取所有AI配置（对外视图，不含任何明文密钥）
     */
    public List<AiConfigVO> listConfigs() {
        return aiConfigMapper.selectList(null).stream()
                .map(AiConfigVO::from)
                .toList();
    }

    /**
     * 获取默认配置的对外视图
     */
    public AiConfigVO getDefaultConfigVO() {
        return AiConfigVO.from(getDefaultConfig());
    }

    /**
     * 根据ID获取配置
     */
    public AiConfig getConfigById(Long id) {
        return aiConfigMapper.selectById(id);
    }

    /**
     * 获取默认配置
     */
    public AiConfig getDefaultConfig() {
        AiConfig config = aiConfigMapper.selectDefault();
        if (config == null) {
            throw new BusinessException("未找到有效的AI配置，请先在配置页面设置");
        }
        return config;
    }

    /**
     * 保存配置
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(AiConfigDTO dto) {
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new BusinessException(400, "配置名称不能为空");
        }
        if (dto.getApiUrl() == null || dto.getApiUrl().trim().isEmpty()) {
            throw new BusinessException(400, "API地址不能为空");
        }
        String apiUrl = dto.getApiUrl().trim();
        if (!apiUrl.startsWith("https://") && !apiUrl.startsWith("http://")) {
            throw new BusinessException(400, "API 地址必须为 http(s) 协议");
        }

        AiConfig config = new AiConfig();
        config.setId(dto.getId());
        config.setName(dto.getName().trim());
        config.setApiUrl(apiUrl);
        config.setModelName(dto.getModelName());
        config.setTemperature(dto.getTemperature());
        config.setMaxTokens(dto.getMaxTokens());
        config.setTimeout(dto.getTimeout());
        config.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : 0);
        config.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);

        // API Key 只写不读：新增时必须提供；编辑时留空表示保持不变
        String rawKey = dto.getApiKey();
        boolean keyProvided = rawKey != null && !rawKey.isBlank();
        if (dto.getId() == null && !keyProvided) {
            throw new BusinessException(400, "API Key不能为空");
        }
        if (keyProvided) {
            String trimmedKey = rawKey.trim();
            config.setApiKeyCipher(aiSecretCipher.encrypt(trimmedKey));
            config.setApiKeyMask(AiSecretCipher.mask(trimmedKey));
        }

        if (dto.getId() != null) {
            aiConfigMapper.updateById(config);
        } else {
            aiConfigMapper.insert(config);
        }

        // 如果设置为默认，重置其他默认配置
        if (Integer.valueOf(1).equals(config.getIsDefault()) && config.getId() != null) {
            aiConfigMapper.resetDefault(config.getId());
        }
    }

    /**
     * 仅在服务端调用链中解密使用，禁止把返回值写入日志或响应。
     */
    private String resolveApiKey(AiConfig config) {
        if (config.getApiKeyCipher() != null && !config.getApiKeyCipher().isBlank()) {
            return aiSecretCipher.decrypt(config.getApiKeyCipher());
        }
        // 兼容尚未迁移的存量明文（迁移任务会尽快将其转为密文并清空）
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey();
        }
        throw new BusinessException(400, "该 AI 配置尚未设置 API Key");
    }

    /**
     * 删除配置（不允许删除默认配置）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        AiConfig config = aiConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("配置不存在");
        }
        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            throw new BusinessException("不能删除默认配置，请先将其他配置设为默认");
        }
        aiConfigMapper.deleteById(id);
    }

    // ========== Prompt 模板管理 ==========

    /**
     * 获取所有模板
     */
    public List<AiPromptTemplate> listTemplates() {
        return aiPromptTemplateMapper.selectList(null);
    }

    /**
     * 根据标识获取模板
     */
    public AiPromptTemplate getTemplateByKey(String templateKey) {
        return aiPromptTemplateMapper.selectByKey(templateKey);
    }

    /**
     * 保存模板
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveTemplate(AiPromptTemplateDTO dto) {
        if (dto.getTemplateKey() == null || dto.getTemplateKey().trim().isEmpty()) {
            throw new BusinessException("模板标识不能为空");
        }
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new BusinessException("模板名称不能为空");
        }

        AiPromptTemplate template = new AiPromptTemplate();
        template.setId(dto.getId());
        template.setTemplateKey(dto.getTemplateKey());
        template.setName(dto.getName());
        template.setDescription(dto.getDescription());
        template.setContent(dto.getContent());
        template.setVariables(dto.getVariables());
        template.setIsBuiltin(dto.getIsBuiltin() != null ? dto.getIsBuiltin() : 0);
        template.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);

        try {
            if (dto.getId() != null) {
                aiPromptTemplateMapper.updateById(template);
            } else {
                aiPromptTemplateMapper.insert(template);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("模板标识 '" + dto.getTemplateKey() + "' 已存在，请使用其他标识");
        }
    }

    /**
     * 删除模板（不允许删除内置模板）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        AiPromptTemplate template = aiPromptTemplateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException("模板不存在");
        }
        if (Integer.valueOf(1).equals(template.getIsBuiltin())) {
            throw new BusinessException("内置模板不可删除");
        }
        aiPromptTemplateMapper.deleteById(id);
    }

    // ========== AI 生成 ==========

    /**
     * 同步生成AI内容。
     *
     * <p>治理开启时统一走 {@link AiGovernanceService}（路由/配额/熔断/审计）；
     * 关闭时回退到既有 ai_config 直连路径（回滚开关 AI_GOVERNANCE_ENABLED）。
     */
    public String generate(AiGenerateDTO dto) {
        String prompt = null;
        try {
            // 1. 构建Prompt（已脱敏并截断）
            prompt = buildPrompt(dto);

            if (aiGovernanceService.isEnabled()) {
                AiGovernanceService.InvokeResult result = aiGovernanceService.invoke(
                        resolveScene(dto), prompt, privateContext(dto));
                saveGenerationLog(dto, prompt, result.text(), null, 1);
                return result.text();
            }

            // 2. 获取配置
            AiConfig config = dto.getConfigId() != null
                    ? getConfigById(dto.getConfigId())
                    : getDefaultConfig();

            // 3. 调用AI接口
            int timeout = config.getTimeout() != null ? config.getTimeout() : 30000;
            String response = OkHttpUtil.callAiApi(
                    config.getApiUrl(),
                    resolveApiKey(config),
                    config.getModelName(),
                    config.getTemperature().doubleValue(),
                    config.getMaxTokens(),
                    timeout,
                    prompt
            );

            // 4. 记录历史
            saveGenerationLog(dto, prompt, response, null, 1);

            return response;

        } catch (BusinessException e) {
            saveGenerationLog(dto, prompt == null ? "" : prompt, "", safeAiErrorMessage(e), 0);
            throw e;
        } catch (IOException e) {
            log.error("AI生成失败: {}", e.getClass().getSimpleName());
            saveGenerationLog(dto, prompt == null ? "" : prompt, "", safeAiErrorMessage(e), 0);
            throw new BusinessException("AI生成失败，请检查配置与网络");
        }
    }

    /**
     * 流式生成(SSE)
     */
    public SseEmitter generateStream(AiGenerateDTO dto) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                final String prompt = buildPrompt(dto);

                // 发送开始标记
                emitter.send(SseEmitter.event()
                        .name("start")
                        .data("{\"status\":\"started\"}"));

                if (aiGovernanceService.isEnabled()) {
                    AiGovernanceService.InvokeResult result = aiGovernanceService.invoke(
                            resolveScene(dto), prompt, privateContext(dto));
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data("{\"content\":\"" + escapeJson(result.text()) + "\"}"));
                    emitter.send(SseEmitter.event()
                            .name("complete")
                            .data("{\"status\":\"completed\"}"));
                    emitter.complete();
                    saveGenerationLog(dto, prompt, result.text(), null, 1);
                    return;
                }

                AiConfig config = dto.getConfigId() != null
                        ? getConfigById(dto.getConfigId())
                        : getDefaultConfig();
                StringBuilder fullResponse = new StringBuilder();

                OkHttpUtil.streamAiApi(
                        config.getApiUrl(),
                        resolveApiKey(config),
                        config.getModelName(),
                        config.getTemperature().doubleValue(),
                        config.getMaxTokens(),
                        config.getTimeout() != null ? config.getTimeout() : 120000,
                        prompt,
                        content -> {
                            try {
                                fullResponse.append(content);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data("{\"content\":\"" + escapeJson(content) + "\"}"));
                            } catch (IOException e) {
                                log.error("发送SSE数据失败", e);
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("{\"error\":\"" + escapeJson(safeAiErrorMessage(error)) + "\"}"));
                                emitter.completeWithError(error);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data("{\"status\":\"completed\"}"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.complete();
                            }
                            // 保存历史
                            saveGenerationLog(dto, prompt, fullResponse.toString(), null, 1);
                        }
                );

            } catch (Exception e) {
                log.error("流式生成失败: {}", e.getClass().getSimpleName());
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\":\"" + escapeJson(safeAiErrorMessage(e)) + "\"}"));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.complete();
                }
                saveGenerationLog(dto, "", "", safeAiErrorMessage(e), 0);
            }
        });

        return emitter;
    }

    /**
     * 流式生成(Flux - 用于WebFlux响应)
     */
    public Flux<String> generateFlux(AiGenerateDTO dto) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        CompletableFuture.runAsync(() -> {
            try {
                final String prompt = buildPrompt(dto);
                sink.tryEmitNext("{\"type\":\"start\"}");

                if (aiGovernanceService.isEnabled()) {
                    AiGovernanceService.InvokeResult result = aiGovernanceService.invoke(
                            resolveScene(dto), prompt, privateContext(dto));
                    sink.tryEmitNext("{\"type\":\"data\",\"content\":\"" + escapeJson(result.text()) + "\"}");
                    sink.tryEmitNext("{\"type\":\"complete\"}");
                    sink.tryEmitComplete();
                    saveGenerationLog(dto, prompt, result.text(), null, 1);
                    return;
                }

                AiConfig config = dto.getConfigId() != null
                        ? getConfigById(dto.getConfigId())
                        : getDefaultConfig();
                StringBuilder fullResponse = new StringBuilder();

                OkHttpUtil.streamAiApi(
                        config.getApiUrl(),
                        resolveApiKey(config),
                        config.getModelName(),
                        config.getTemperature().doubleValue(),
                        config.getMaxTokens(),
                        config.getTimeout() != null ? config.getTimeout() : 120000,
                        prompt,
                        content -> {
                            fullResponse.append(content);
                            sink.tryEmitNext("{\"type\":\"data\",\"content\":\"" + escapeJson(content) + "\"}");
                        },
                        error -> {
                            sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + escapeJson(safeAiErrorMessage(error)) + "\"}");
                            sink.tryEmitComplete();
                        },
                        () -> {
                            sink.tryEmitNext("{\"type\":\"complete\"}");
                            sink.tryEmitComplete();
                            saveGenerationLog(dto, prompt, fullResponse.toString(), null, 1);
                        }
                );

            } catch (Exception e) {
                sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + escapeJson(safeAiErrorMessage(e)) + "\"}");
                sink.tryEmitComplete();
                saveGenerationLog(dto, "", "", safeAiErrorMessage(e), 0);
            }
        });

        return sink.asFlux()
                .delayElements(Duration.ofMillis(10))
                .onErrorResume(e -> Flux.just("{\"type\":\"error\",\"message\":\"流式传输失败\"}"));
    }

    /**
     * 查询生成历史
     */
    public List<AiGenerationLog> listHistory(Long datasetId, String contextType, Integer limit) {
        int safeLimit = SqlGuard.clampLimit(limit, 20, 100);
        if (datasetId != null) {
            return aiGenerationLogMapper.selectByDatasetId(datasetId, safeLimit);
        }
        return aiGenerationLogMapper.selectList(null);
    }

    /**
     * 获取最新的生成记录
     */
    public AiGenerationLog getLatestGeneration(Long datasetId, String contextType) {
        return aiGenerationLogMapper.selectLatestByContext(datasetId, contextType);
    }

    // ========== 内部方法 ==========

    /** 场景解析：显式 scene > templateKey 映射 > 默认 DATASET_SUMMARY。 */
    private AiScene resolveScene(AiGenerateDTO dto) {
        AiScene scene = AiScene.fromCode(dto.getScene());
        if (scene == null) {
            scene = AiScene.fromTemplateKey(dto.getTemplateKey());
        }
        return scene == null ? AiScene.DATASET_SUMMARY : scene;
    }

    /** 私有模式调用上下文；主体只用于审计摘要。 */
    private AiGovernanceService.InvokeContext privateContext(AiGenerateDTO dto) {
        return new AiGovernanceService.InvokeContext(dto.getDatasetId(), null, null, currentSubject(), false);
    }

    private String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal == null ? "user:anonymous" : "user:" + principal;
    }

    /**
     * 构建Prompt
     */
    private String buildPrompt(AiGenerateDTO dto) {
        String prompt;

        // 优先使用自定义Prompt
        if (dto.getCustomPrompt() != null && !dto.getCustomPrompt().isEmpty()) {
            prompt = dto.getCustomPrompt();
        } else if (dto.getTemplateKey() != null) {
            // 使用模板
            AiPromptTemplate template = getTemplateByKey(dto.getTemplateKey());
            if (template == null) {
                throw new BusinessException("模板不存在: " + dto.getTemplateKey());
            }
            prompt = template.getContent();
        } else {
            throw new BusinessException("请提供自定义Prompt或选择模板");
        }

        // 替换变量
        Map<String, String> variables = dto.getVariables();
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                prompt = prompt.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }

        // 如果有关联数据集，填充数据集相关变量
        if (dto.getDatasetId() != null) {
            try {
                Map<String, Object> datasetStats = datasetService.getDatasetStatistics(dto.getDatasetId());
                prompt = prompt.replace("{{datasetName}}", String.valueOf(datasetStats.getOrDefault("datasetName", "")));
                prompt = prompt.replace("{{rowCount}}", String.valueOf(datasetStats.getOrDefault("rowCount", "")));
                prompt = prompt.replace("{{columnCount}}", String.valueOf(datasetStats.getOrDefault("columnCount", "")));
                prompt = prompt.replace("{{fileType}}", String.valueOf(datasetStats.getOrDefault("fileType", "")));

                // 列统计信息
                if (prompt.contains("{{columnStats}}")) {
                    String columnStats = objectMapper.writeValueAsString(datasetStats.getOrDefault("columns", ""));
                    prompt = prompt.replace("{{columnStats}}", columnStats);
                }
            } catch (Exception e) {
                log.warn("填充数据集变量失败: {}", e.getClass().getSimpleName());
            }
        }

        // 处理额外上下文
        if (dto.getExtraContext() != null) {
            Map<String, Object> extra = dto.getExtraContext();
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                String value = entry.getValue() instanceof String
                        ? (String) entry.getValue()
                        : String.valueOf(entry.getValue());
                prompt = prompt.replace("{{" + entry.getKey() + "}}", value);
            }
        }

        // 统一出口：先脱敏，再限制长度，保证任何路径都不会把敏感原文送入模型
        String masked = aiPiiMasker.maskText(prompt);
        if (masked.length() > maxPromptChars) {
            masked = masked.substring(0, maxPromptChars);
        }
        return masked;
    }

    /**
     * 对外/落库的错误信息：只透出可安全展示的业务提示，其余统一为通用文案，
     * 避免供应商响应、SQL、堆栈等细节泄露。
     */
    private String safeAiErrorMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            String message = be.getMessage();
            if (message != null && message.length() <= 200) {
                return message;
            }
        }
        return "AI 服务调用失败，请检查配置与网络";
    }

    /**
     * 保存生成历史
     */
    private void saveGenerationLog(AiGenerateDTO dto, String prompt, String response, String errorMsg, Integer status) {
        try {
            AiGenerationLog log = new AiGenerationLog();
            log.setDatasetId(dto.getDatasetId());
            log.setTemplateKey(dto.getTemplateKey());
            log.setContextType(dto.getContextType());

            // 构建输入摘要(不包含原始数据)
            Map<String, Object> inputSummary = new java.util.LinkedHashMap<>();
            inputSummary.put("templateKey", dto.getTemplateKey());
            inputSummary.put("variables", maskMap(dto.getVariables()));
            inputSummary.put("extraContext", maskMap(dto.getExtraContext()));
            log.setInputSummary(truncate(aiPiiMasker.maskText(objectMapper.writeValueAsString(inputSummary))));

            // 调用历史只保存脱敏且截断后的文本，不保存完整敏感数据
            log.setPrompt(truncate(aiPiiMasker.maskText(prompt)));
            log.setResponse(truncate(aiPiiMasker.maskText(response)));
            log.setStatus(status);
            log.setErrorMsg(truncate(aiPiiMasker.maskText(errorMsg)));

            aiGenerationLogMapper.insert(log);
        } catch (Exception e) {
            log.error("保存生成历史失败: {}", e.getMessage());
        }
    }

    /**
     * 对 Map 中的字符串值做脱敏，用于调用历史的输入摘要。
     */
    private Map<String, Object> maskMap(Map<String, ?> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> masked = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            masked.put(entry.getKey(), value instanceof String ? aiPiiMasker.maskText((String) value) : value);
        }
        return masked;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > LOG_TEXT_LIMIT ? text.substring(0, LOG_TEXT_LIMIT) + "...(truncated)" : text;
    }

    /**
     * JSON 字符串转义（处理所有控制字符）
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
