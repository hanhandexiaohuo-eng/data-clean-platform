package com.dataclean.controller;

import com.dataclean.dto.*;
import com.dataclean.entity.AiConfig;
import com.dataclean.entity.AiGenerationLog;
import com.dataclean.entity.AiPromptTemplate;
import com.dataclean.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 模块控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    // ========== AI 生成接口 ==========

    /**
     * 同步生成
     */
    @PostMapping("/generate")
    public Result<String> generate(@RequestBody AiGenerateDTO dto) {
        return Result.success(aiService.generate(dto));
    }

    /**
     * 流式生成(SSE)
     */
    @GetMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(
            @RequestParam Long datasetId,
            @RequestParam String templateKey,
            @RequestParam String contextType,
            @RequestParam(required = false) String customPrompt,
            @RequestParam(required = false) Long configId) {

        AiGenerateDTO dto = new AiGenerateDTO();
        dto.setDatasetId(datasetId);
        dto.setTemplateKey(templateKey);
        dto.setContextType(contextType);
        dto.setCustomPrompt(customPrompt);
        dto.setConfigId(configId);

        return aiService.generateStream(dto);
    }

    /**
     * 流式生成(Flux)
     */
    @PostMapping(value = "/generate/flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateFlux(@RequestBody AiGenerateDTO dto) {
        return aiService.generateFlux(dto);
    }

    // ========== AI 配置管理 ==========

    /**
     * 获取所有配置
     */
    @GetMapping("/config")
    public Result<List<AiConfig>> listConfigs() {
        return Result.success(aiService.listConfigs());
    }

    /**
     * 获取默认配置
     */
    @GetMapping("/config/default")
    public Result<AiConfig> getDefaultConfig() {
        return Result.success(aiService.getDefaultConfig());
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
