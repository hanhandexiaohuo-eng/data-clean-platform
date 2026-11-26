package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataclean.config.AppMode;
import com.dataclean.dto.AiCallPageVO;
import com.dataclean.dto.AiProviderDTO;
import com.dataclean.dto.AiProviderVO;
import com.dataclean.dto.AiUsageSummaryVO;
import com.dataclean.entity.AiCallLog;
import com.dataclean.entity.AiModel;
import com.dataclean.entity.AiProvider;
import com.dataclean.entity.AiSceneRoute;
import com.dataclean.enums.AiScene;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AiCallLogMapper;
import com.dataclean.mapper.AiModelMapper;
import com.dataclean.mapper.AiProviderMapper;
import com.dataclean.mapper.AiSceneRouteMapper;
import com.dataclean.util.AiSecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 治理管理能力（仅私有模式）。
 *
 * <p>所有写操作都由控制器要求 {@code X-Confirm: yes} 并写审计。
 */
@Service
@RequiredArgsConstructor
public class AiGovernanceAdminService {

    private final AppMode appMode;
    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiSceneRouteMapper routeMapper;
    private final AiCallLogMapper callLogMapper;
    private final AiQuotaService quotaService;
    private final AiCircuitBreakerService circuitBreaker;
    private final AiSecretCipher aiSecretCipher;
    private final AuditService auditService;

    public void requirePrivate() {
        if (appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
    }

    // ========== 供应商 ==========

    public List<AiProviderVO> listProviders() {
        List<AiProvider> providers = providerMapper.selectList(
                new LambdaQueryWrapper<AiProvider>().orderByAsc(AiProvider::getId));
        List<AiProviderVO> result = new ArrayList<>();
        for (AiProvider provider : providers) {
            AiProviderVO vo = new AiProviderVO();
            vo.setId(provider.getId());
            vo.setName(provider.getName());
            vo.setApiUrl(provider.getApiUrl());
            vo.setApiKeyMask(provider.getApiKeyMask());
            vo.setKeyConfigured(provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isBlank());
            vo.setStatus(provider.getStatus());
            vo.setModelCount(providerMapper.countModels(provider.getId()));
            vo.setCreatedAt(provider.getCreatedAt());
            vo.setUpdatedAt(provider.getUpdatedAt());
            result.add(vo);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long saveProvider(AiProviderDTO dto) {
        if (dto == null) {
            throw new BusinessException(400, "供应商信息不能为空");
        }
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BusinessException(400, "供应商名称不能为空");
        }
        String apiUrl = dto.getApiUrl() == null ? "" : dto.getApiUrl().trim();
        if (!apiUrl.startsWith("https://") && !apiUrl.startsWith("http://")) {
            throw new BusinessException(400, "API 地址必须为 http(s) 协议");
        }
        boolean keyProvided = dto.getApiKey() != null && !dto.getApiKey().isBlank();
        AiProvider provider;
        boolean keyChanged = false;
        if (dto.getId() != null) {
            provider = providerMapper.selectById(dto.getId());
            if (provider == null) {
                throw new BusinessException(404, "供应商不存在");
            }
        } else {
            provider = new AiProvider();
            provider.setCreatedAt(LocalDateTime.now());
            if (!keyProvided) {
                throw new BusinessException(400, "API Key 不能为空");
            }
        }
        provider.setName(dto.getName().trim());
        provider.setApiUrl(apiUrl);
        provider.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        if (keyProvided) {
            String trimmedKey = dto.getApiKey().trim();
            provider.setApiKeyCipher(aiSecretCipher.encrypt(trimmedKey));
            provider.setApiKeyMask(AiSecretCipher.mask(trimmedKey));
            keyChanged = true;
        }
        provider.setUpdatedAt(LocalDateTime.now());
        if (dto.getId() == null) {
            providerMapper.insert(provider);
        } else {
            providerMapper.updateById(provider);
        }
        // 审计只记录 ID/名称/是否更换密钥，绝不记录密钥内容
        auditService.record("AI_PROVIDER_SAVE", "ai_provider", provider.getId(), null, null, null,
                "private", null, null, "SUCCESS", null,
                "name=" + provider.getName() + ",keyChanged=" + keyChanged);
        return provider.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProvider(Long id) {
        AiProvider provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new BusinessException(404, "供应商不存在");
        }
        if (providerMapper.countModels(id) > 0) {
            throw new BusinessException(400, "该供应商下仍有模型，无法删除");
        }
        providerMapper.deleteById(id);
        auditService.record("AI_PROVIDER_DELETE", "ai_provider", id, null, null, null,
                "private", null, null, "SUCCESS", null, null);
    }

    // ========== 模型 ==========

    public List<AiModel> listModels() {
        return modelMapper.selectList(new LambdaQueryWrapper<AiModel>().orderByAsc(AiModel::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long saveModel(AiModel dto) {
        if (dto == null || dto.getProviderId() == null) {
            throw new BusinessException(400, "供应商不能为空");
        }
        if (providerMapper.selectById(dto.getProviderId()) == null) {
            throw new BusinessException(400, "供应商不存在");
        }
        if (dto.getModelCode() == null || dto.getModelCode().isBlank()) {
            throw new BusinessException(400, "模型标识不能为空");
        }
        dto.setModelCode(dto.getModelCode().trim());
        if (dto.getTemperature() != null
                && (dto.getTemperature().compareTo(BigDecimal.ZERO) < 0
                || dto.getTemperature().compareTo(BigDecimal.valueOf(2)) > 0)) {
            throw new BusinessException(400, "温度必须在 0–2 之间");
        }
        if (dto.getTimeoutMs() != null && (dto.getTimeoutMs() < 1000 || dto.getTimeoutMs() > 120000)) {
            throw new BusinessException(400, "超时时间必须在 1000–120000ms 之间");
        }
        if (dto.getRetryCount() != null && (dto.getRetryCount() < 0 || dto.getRetryCount() > 2)) {
            throw new BusinessException(400, "重试次数必须在 0–2 之间");
        }
        if (dto.getConcurrencyLimit() != null
                && (dto.getConcurrencyLimit() < 1 || dto.getConcurrencyLimit() > 50)) {
            throw new BusinessException(400, "并发上限必须在 1–50 之间");
        }
        dto.setUpdatedAt(LocalDateTime.now());
        if (dto.getId() == null) {
            dto.setCreatedAt(LocalDateTime.now());
            modelMapper.insert(dto);
        } else {
            modelMapper.updateById(dto);
        }
        auditService.record("AI_MODEL_SAVE", "ai_model", dto.getId(), null, null, null,
                "private", null, null, "SUCCESS", null, "modelCode=" + dto.getModelCode());
        return dto.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long id) {
        if (modelMapper.selectById(id) == null) {
            throw new BusinessException(404, "模型不存在");
        }
        if (modelMapper.countRoutes(id) > 0) {
            throw new BusinessException(400, "该模型仍被场景路由引用，无法删除");
        }
        modelMapper.deleteById(id);
        auditService.record("AI_MODEL_DELETE", "ai_model", id, null, null, null,
                "private", null, null, "SUCCESS", null, null);
    }

    // ========== 场景路由 ==========

    public List<AiSceneRoute> listRoutes() {
        return routeMapper.selectList(new LambdaQueryWrapper<AiSceneRoute>().orderByAsc(AiSceneRoute::getScene));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long saveRoute(AiSceneRoute dto) {
        if (dto == null || AiScene.fromCode(dto.getScene()) == null) {
            throw new BusinessException(400, "场景不合法");
        }
        if (dto.getPrimaryModelId() == null && dto.getFallbackModelId() == null) {
            throw new BusinessException(400, "主模型与备用模型不能同时为空");
        }
        if (dto.getPrimaryModelId() != null && modelMapper.selectById(dto.getPrimaryModelId()) == null) {
            throw new BusinessException(400, "主模型不存在");
        }
        if (dto.getFallbackModelId() != null && modelMapper.selectById(dto.getFallbackModelId()) == null) {
            throw new BusinessException(400, "备用模型不存在");
        }
        dto.setScene(AiScene.fromCode(dto.getScene()).name());
        dto.setUpdatedAt(LocalDateTime.now());
        if (dto.getId() == null) {
            dto.setCreatedAt(LocalDateTime.now());
            routeMapper.insert(dto);
        } else {
            routeMapper.updateById(dto);
        }
        auditService.record("AI_SCENE_ROUTE_SAVE", "ai_scene_route", dto.getId(), null, null, null,
                "private", null, null, "SUCCESS", null, "scene=" + dto.getScene());
        return dto.getId();
    }

    // ========== 审计与用量 ==========

    public AiCallPageVO listCalls(LocalDate from, LocalDate to, String scene, String modelCode,
                                  String result, String errorType, Integer page, Integer size) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null ? 20 : Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<AiCallLog> wrapper = new LambdaQueryWrapper<>();
        if (from != null) {
            wrapper.ge(AiCallLog::getRequestAt, from.atStartOfDay());
        }
        if (to != null) {
            wrapper.le(AiCallLog::getRequestAt, LocalDateTime.of(to, LocalTime.MAX));
        }
        if (scene != null && !scene.isBlank()) {
            wrapper.eq(AiCallLog::getScene, scene.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (modelCode != null && !modelCode.isBlank()) {
            wrapper.eq(AiCallLog::getModelCode, modelCode.trim());
        }
        if (result != null && !result.isBlank()) {
            wrapper.eq(AiCallLog::getResult, result.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (errorType != null && !errorType.isBlank()) {
            wrapper.eq(AiCallLog::getErrorType, errorType.trim());
        }
        wrapper.orderByDesc(AiCallLog::getRequestAt);
        Page<AiCallLog> pageResult = callLogMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        AiCallPageVO vo = new AiCallPageVO();
        vo.setTotal(pageResult.getTotal());
        vo.setItems(pageResult.getRecords());
        return vo;
    }

    public AiUsageSummaryVO usage(LocalDate date) {
        return quotaService.summary(date);
    }

    public List<com.dataclean.dto.AiCircuitVO> circuits() {
        return circuitBreaker.list();
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetCircuit(Long id) {
        circuitBreaker.reset(id);
        auditService.record("AI_CIRCUIT_RESET", "ai_circuit_state", id, null, null, null,
                "private", null, null, "SUCCESS", null, null);
    }
}
