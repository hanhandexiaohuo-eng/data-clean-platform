package com.dataclean.service;

import com.dataclean.entity.AiCallLog;
import com.dataclean.enums.AiScene;
import com.dataclean.mapper.AiCallLogMapper;
import com.dataclean.util.PrincipalHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用审计。
 *
 * <p>只记录允许的元数据；严禁写入明文 Key、完整 Prompt、完整模型响应、Cookie、Token、
 * 原始敏感数据与供应商完整 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallAuditService {

    private final AiCallLogMapper callLogMapper;
    private final PrincipalHasher principalHasher;

    public String principalHash(String subject) {
        return principalHasher.hash(subject);
    }

    public void record(AiScene scene,
                       AiSceneRouter.Target target,
                       String principalHash,
                       Long datasetId,
                       Long datasetVersionId,
                       Long savedChartId,
                       LocalDateTime requestAt,
                       int latencyMs,
                       int inputTokens,
                       int outputTokens,
                       BigDecimal cost,
                       String result,
                       String errorType,
                       boolean degraded,
                       String quotaScope) {
        try {
            AiCallLog entry = new AiCallLog();
            entry.setScene(scene.name());
            entry.setProviderName(target == null ? null : target.providerName);
            entry.setModelCode(target == null ? null : target.modelCode);
            entry.setPrincipalHash(principalHash);
            entry.setDatasetId(datasetId);
            entry.setDatasetVersionId(datasetVersionId);
            entry.setSavedChartId(savedChartId);
            entry.setRequestAt(requestAt == null ? LocalDateTime.now() : requestAt);
            entry.setLatencyMs(latencyMs);
            entry.setInputTokens(inputTokens);
            entry.setOutputTokens(outputTokens);
            entry.setCost(cost == null ? BigDecimal.ZERO : cost);
            entry.setResult(result);
            entry.setErrorType(errorType);
            entry.setDegraded(degraded ? 1 : 0);
            entry.setQuotaScope(quotaScope);
            entry.setCreatedAt(LocalDateTime.now());
            callLogMapper.insert(entry);
        } catch (Exception e) {
            log.error("AI 调用审计写入失败: scene={}, type={}", scene, e.getClass().getSimpleName());
        }
    }
}
