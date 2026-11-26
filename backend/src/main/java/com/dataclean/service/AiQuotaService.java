package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.dto.AiUsageSummaryVO;
import com.dataclean.entity.AiQuota;
import com.dataclean.entity.AiUsage;
import com.dataclean.enums.AiScene;
import com.dataclean.mapper.AiQuotaMapper;
import com.dataclean.mapper.AiUsageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 配额与用量：
 *
 * <ul>
 *   <li>私有模式默认每场景每日 200 次 / 200000 tokens / 20 成本单位；</li>
 *   <li>{@code ai_quota} 可按 scope + scene + model 覆盖；</li>
 *   <li>公开模式的会话/IP/全站总量仍由 {@code PublicModeProperties} 控制，
 *       {@code ai_quota} 只是可选的追加约束；</li>
 *   <li>调用前先计入次数，防止并发绕过；返回后再累加 token/成本。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private static final String PERIOD_DAILY = "daily";
    private static final long PRIVATE_DEFAULT_CALLS = 200;
    private static final long PRIVATE_DEFAULT_TOKENS = 200_000L;
    private static final BigDecimal PRIVATE_DEFAULT_COST = BigDecimal.valueOf(20);

    private final AiQuotaMapper quotaMapper;
    private final AiUsageMapper usageMapper;

    public QuotaCheck check(String scopeType, String scopeKeyHash, AiScene scene, String modelCode) {
        String periodKey = LocalDate.now().toString();
        AiQuota quota = quotaMapper.findApplicable(scopeType, scene.name(), modelCode, PERIOD_DAILY);

        boolean privateScope = AiUsage.SCOPE_PRIVATE.equals(scopeType);
        if (quota == null && !privateScope) {
            // 公开模式未追加配额时，由 PublicAiService 的会话/IP/全站限制负责
            return QuotaCheck.unlimited(scopeType);
        }

        long maxCalls = quota != null && quota.getMaxCalls() != null
                ? quota.getMaxCalls() : PRIVATE_DEFAULT_CALLS;
        long maxTokens = quota != null && quota.getMaxTokens() != null
                ? quota.getMaxTokens() : PRIVATE_DEFAULT_TOKENS;
        BigDecimal maxCost = quota != null && quota.getMaxCost() != null
                ? quota.getMaxCost() : PRIVATE_DEFAULT_COST;

        long usedCalls = usageMapper.sumCalls(scopeType, scopeKeyHash, scene.name(), modelCode, periodKey);
        if (usedCalls >= maxCalls) {
            return QuotaCheck.denied(scopeType, "调用次数已达上限");
        }
        long usedTokens = usageMapper.sumTokens(scopeType, scopeKeyHash, scene.name(), modelCode, periodKey);
        if (usedTokens >= maxTokens) {
            return QuotaCheck.denied(scopeType, "Token 配额已用完");
        }
        BigDecimal usedCost = usageMapper.sumCost(scopeType, scopeKeyHash, scene.name(), modelCode, periodKey);
        if (usedCost != null && usedCost.compareTo(maxCost) >= 0) {
            return QuotaCheck.denied(scopeType, "预算已用完");
        }
        return QuotaCheck.allowed(scopeType);
    }

    /** 调用前计入 1 次，防止并发绕过配额。 */
    public void recordCall(String scopeType, String scopeKeyHash, AiScene scene, String modelCode) {
        usageMapper.accumulate(scopeType, scopeKeyHash, scene.name(), modelCode,
                LocalDate.now().toString(), 1, 0, 0, BigDecimal.ZERO);
    }

    /** 调用后累加 token 与成本。 */
    public void recordUsage(String scopeType, String scopeKeyHash, AiScene scene, String modelCode,
                            long inputTokens, long outputTokens, BigDecimal cost) {
        usageMapper.accumulate(scopeType, scopeKeyHash, scene.name(), modelCode,
                LocalDate.now().toString(), 0, inputTokens, outputTokens,
                cost == null ? BigDecimal.ZERO : cost);
    }

    public AiUsageSummaryVO summary(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        List<AiUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<AiUsage>()
                .eq(AiUsage::getPeriodKey, target.toString())
                .orderByAsc(AiUsage::getScopeType)
                .orderByAsc(AiUsage::getScene));
        AiUsageSummaryVO summary = new AiUsageSummaryVO();
        summary.setDate(target);
        List<AiUsageSummaryVO.Item> items = new ArrayList<>();
        for (AiUsage row : rows) {
            AiUsageSummaryVO.Item item = new AiUsageSummaryVO.Item();
            item.setScopeType(row.getScopeType());
            item.setScene(row.getScene());
            item.setModelCode(row.getModelCode());
            item.setCalls(row.getCalls() == null ? 0 : row.getCalls());
            item.setTokens((row.getInputTokens() == null ? 0 : row.getInputTokens())
                    + (row.getOutputTokens() == null ? 0 : row.getOutputTokens()));
            item.setCost(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
            items.add(item);
        }
        summary.setItems(items);
        return summary;
    }

    /** 配额判定结果。 */
    public record QuotaCheck(boolean allowed, String scopeType, String reason, boolean budgetExhausted) {

        static QuotaCheck allowed(String scopeType) {
            return new QuotaCheck(true, scopeType, null, false);
        }

        static QuotaCheck denied(String scopeType, String reason) {
            return new QuotaCheck(false, scopeType, reason, true);
        }

        static QuotaCheck unlimited(String scopeType) {
            return new QuotaCheck(true, scopeType, null, false);
        }
    }
}
