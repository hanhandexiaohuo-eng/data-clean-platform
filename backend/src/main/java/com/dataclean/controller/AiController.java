package com.dataclean.controller;

import com.dataclean.config.AppMode;
import com.dataclean.dto.*;
import com.dataclean.entity.AiGenerationLog;
import com.dataclean.entity.AiPromptTemplate;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.Dataset;
import com.dataclean.exception.BusinessException;
import com.dataclean.service.AiService;
import com.dataclean.service.JobService;
import com.dataclean.service.OwnershipService;
import com.dataclean.service.PublicAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模块控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final PublicAiService publicAiService;
    private final AppMode appMode;
    private final OwnershipService ownershipService;
    private final JobService jobService;

    // ========== AI 生成接口 ==========

    /**
     * 同步生成
     */
    @PostMapping("/generate")
    public Result<String> generate(@RequestBody AiGenerateDTO dto,
                                   @RequestHeader(value = "X-AI-Challenge", required = false) String challengeToken) {
        Dataset dataset = dto.getDatasetId() == null ? null
                : ownershipService.requireDataset(dto.getDatasetId());
        // 任务只记录场景与模式，不记录 Prompt、密钥或完整响应
        Map<String, Object> payload = new HashMap<>();
        payload.put("scene", dto.getTemplateKey());
        payload.put("mode", appMode.isPublic() ? "public" : "private");
        AsyncJob job = jobService.create(JobService.TYPE_AI, dataset,
                dataset == null ? null : dataset.getCurrentVersionId(), null, payload);
        jobService.begin(job);
        try {
            String answer;
            if (appMode.isPublic()) {
                if (challengeToken != null && !challengeToken.isBlank()) {
                    dto.setChallengeToken(challengeToken);
                }
                answer = publicAiService.generate(dto);
            } else {
                answer = aiService.generate(dto);
            }
            jobService.markSucceeded(job, null, 0L, "AI 调用完成");
            return Result.success(answer);
        } catch (BusinessException e) {
            jobService.markFailedSafely(job, aiErrorCode(e.getCode()), e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("AI 调用失败: scene={}, error={}", dto.getTemplateKey(), e.getClass().getSimpleName());
            BusinessException safe = new BusinessException(503, "AI 服务暂时不可用，请稍后重试");
            jobService.markFailedSafely(job, "AI_UPSTREAM_UNAVAILABLE", safe);
            throw safe;
        }
    }

    private String aiErrorCode(int code) {
        return switch (code) {
            case 429 -> "QUOTA_EXCEEDED";
            case 503 -> "AI_CIRCUIT_OPEN";
            case 408 -> "AI_TIMEOUT";
            default -> "AI_CALL_FAILED";
        };
    }

    /**
     * 流式生成(SSE)：公开模式不提供流式，统一 404
     */
    @GetMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(
            @RequestParam Long datasetId,
            @RequestParam String templateKey,
            @RequestParam String contextType,
            @RequestParam(required = false) String customPrompt,
            @RequestParam(required = false) Long configId) {

        requirePrivateAi();
        AiGenerateDTO dto = new AiGenerateDTO();
        dto.setDatasetId(datasetId);
        dto.setTemplateKey(templateKey);
        dto.setContextType(contextType);
        dto.setCustomPrompt(customPrompt);
        dto.setConfigId(configId);

        return aiService.generateStream(dto);
    }

    /**
     * 流式生成(Flux)：公开模式不提供流式，统一 404
     */
    @PostMapping(value = "/generate/flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateFlux(@RequestBody AiGenerateDTO dto) {
        requirePrivateAi();
        return aiService.generateFlux(dto);
    }

    private void requirePrivateAi() {
        if (appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
    }

    // ========== AI 配置管理 ==========

    /**
     * 获取所有配置
     */
    @GetMapping("/config")
    public Result<List<AiConfigVO>> listConfigs() {
        return Result.success(aiService.listConfigs());
    }

    /**
     * 获取默认配置（只返回掩码与是否已配置）
     */
    @GetMapping("/config/default")
    public Result<AiConfigVO> getDefaultConfig() {
        return Result.success(aiService.getDefaultConfigVO());
    }

    /**
     * 保存配置
     */
    @PutMapping("/config")
    public Result<Void> saveConfig(@RequestBody AiConfigDTO dto) {
        aiService.saveConfig(dto);
        return Result.success();
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/config/{id}")
    public Result<Void> deleteConfig(@PathVariable Long id) {
        aiService.deleteConfig(id);
        return Result.success();
    }

    // ========== Prompt 模板管理 ==========

    /**
     * 获取所有模板
     */
    @GetMapping("/template")
    public Result<List<AiPromptTemplate>> listTemplates() {
        return Result.success(aiService.listTemplates());
    }

    /**
     * 根据标识获取模板
     */
    @GetMapping("/template/{key}")
    public Result<AiPromptTemplate> getTemplateByKey(@PathVariable String key) {
        return Result.success(aiService.getTemplateByKey(key));
    }

    /**
     * 保存模板
     */
    @PutMapping("/template")
    public Result<Void> saveTemplate(@RequestBody AiPromptTemplateDTO dto) {
        aiService.saveTemplate(dto);
        return Result.success();
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/template/{id}")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        aiService.deleteTemplate(id);
        return Result.success();
    }

    // ========== 生成历史 ==========

    /**
     * 查询历史
     */
    @GetMapping("/history")
    public Result<List<AiGenerationLog>> listHistory(
            @RequestParam(required = false) Long datasetId,
            @RequestParam(required = false) String contextType,
            @RequestParam(required = false) Integer limit) {
        return Result.success(aiService.listHistory(datasetId, contextType, limit));
    }

    /**
     * 获取最新生成
     */
    @GetMapping("/history/latest")
    public Result<AiGenerationLog> getLatestGeneration(
            @RequestParam Long datasetId,
            @RequestParam String contextType) {
        return Result.success(aiService.getLatestGeneration(datasetId, contextType));
    }
}
