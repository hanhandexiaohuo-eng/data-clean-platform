package com.dataclean.service;

import com.dataclean.dto.AiConfigDTO;
import com.dataclean.dto.AiGenerateDTO;
import com.dataclean.dto.AiPromptTemplateDTO;
import com.dataclean.entity.AiConfig;
import com.dataclean.entity.AiGenerationLog;
import com.dataclean.entity.AiPromptTemplate;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.mapper.AiGenerationLogMapper;
import com.dataclean.mapper.AiPromptTemplateMapper;
import com.dataclean.util.OkHttpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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

    private final AiConfigMapper aiConfigMapper;
    private final AiPromptTemplateMapper aiPromptTemplateMapper;
    private final AiGenerationLogMapper aiGenerationLogMapper;
    private final DatasetService datasetService;
    private final ObjectMapper objectMapper;

    // ========== AI 配置管理 ==========

    /**
     * 获取所有AI配置
     */
    public List<AiConfig> listConfigs() {
        return aiConfigMapper.selectList(null);
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
            throw new BusinessException("配置名称不能为空");
        }
        if (dto.getApiUrl() == null || dto.getApiUrl().trim().isEmpty()) {
            throw new BusinessException("API地址不能为空");
        }
        if (dto.getApiKey() == null || dto.getApiKey().trim().isEmpty()) {
            throw new BusinessException("API Key不能为空");
        }

        AiConfig config = new AiConfig();
        config.setId(dto.getId());
        config.setName(dto.getName());
        config.setApiUrl(dto.getApiUrl());
        config.setApiKey(dto.getApiKey());
        config.setModelName(dto.getModelName());
        config.setTemperature(dto.getTemperature());
        config.setMaxTokens(dto.getMaxTokens());
        config.setTimeout(dto.getTimeout());
        config.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : 0);
        config.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);

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
     * 同步生成AI内容
     */
    public String generate(AiGenerateDTO dto) {
        try {
            // 1. 获取配置
            AiConfig config = dto.getConfigId() != null
                    ? getConfigById(dto.getConfigId())
                    : getDefaultConfig();

            // 2. 构建Prompt
            String prompt = buildPrompt(dto);

            // 3. 调用AI接口
            int timeout = config.getTimeout() != null ? config.getTimeout() : 30000;
            String response = OkHttpUtil.callAiApi(
                    config.getApiUrl(),
                    config.getApiKey(),
                    config.getModelName(),
                    config.getTemperature().doubleValue(),
                    config.getMaxTokens(),
                    timeout,
                    prompt
            );

            // 4. 记录历史
            saveGenerationLog(dto, prompt, response, null, 1);

            return response;

        } catch (IOException e) {
            log.error("AI生成失败", e);
            saveGenerationLog(dto, "", "", e.getMessage(), 0);
            throw new BusinessException("AI生成失败: " + e.getMessage());
        }
    }

    /**
     * 流式生成(SSE)
     */
    public SseEmitter generateStream(AiGenerateDTO dto) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                AiConfig config = dto.getConfigId() != null
                        ? getConfigById(dto.getConfigId())
                        : getDefaultConfig();

                String prompt = buildPrompt(dto);
                StringBuilder fullResponse = new StringBuilder();

                // 发送开始标记
                emitter.send(SseEmitter.event()
                        .name("start")
                        .data("{\"status\":\"started\"}"));

                OkHttpUtil.streamAiApi(
                        config.getApiUrl(),
                        config.getApiKey(),
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
                                        .data("{\"error\":\"" + escapeJson(error.getMessage()) + "\"}"));
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
                log.error("流式生成失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.complete();
                }
                saveGenerationLog(dto, "", "", e.getMessage(), 0);
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
                AiConfig config = dto.getConfigId() != null
                        ? getConfigById(dto.getConfigId())
                        : getDefaultConfig();

                String prompt = buildPrompt(dto);
                StringBuilder fullResponse = new StringBuilder();

                sink.tryEmitNext("{\"type\":\"start\"}");

                OkHttpUtil.streamAiApi(
                        config.getApiUrl(),
                        config.getApiKey(),
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
                            sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + escapeJson(error.getMessage()) + "\"}");
                            sink.tryEmitComplete();
                        },
                        () -> {
                            sink.tryEmitNext("{\"type\":\"complete\"}");
                            sink.tryEmitComplete();
                            saveGenerationLog(dto, prompt, fullResponse.toString(), null, 1);
                        }
                );

            } catch (Exception e) {
                sink.tryEmitNext("{\"type\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
                sink.tryEmitComplete();
                saveGenerationLog(dto, "", "", e.getMessage(), 0);
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
        if (limit == null) limit = 20;
        if (datasetId != null) {
            return aiGenerationLogMapper.selectByDatasetId(datasetId, limit);
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
                log.warn("填充数据集变量失败: {}", e.getMessage());
            }
        }

        // 处理额外上下文
        if (dto.getExtraContext() != null) {
            Map<String, Object> extra = dto.getExtraContext();
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                String value = entry.getValue() instanceof String
                        ? (String) entry.getValue()
                        : entry.getValue().toString();
                prompt = prompt.replace("{{" + entry.getKey() + "}}", value);
            }
        }

        return prompt;
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
            inputSummary.put("variables", dto.getVariables());
            inputSummary.put("extraContext", dto.getExtraContext());
            log.setInputSummary(objectMapper.writeValueAsString(inputSummary));

            log.setPrompt(prompt);
            log.setResponse(response);
            log.setStatus(status);
            log.setErrorMsg(errorMsg);

            aiGenerationLogMapper.insert(log);
        } catch (Exception e) {
            log.error("保存生成历史失败: {}", e.getMessage());
        }
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
