package com.dataclean.controller;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AnonUsageMapper;
import com.dataclean.service.HumanChallengeService;
import com.dataclean.service.PublicCleanupService;
import com.dataclean.util.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开模式只读配置与私有维护接口。
 *
 * <p>{@code /api/public/config} 不含任何密钥或内部地址；
 * {@code /api/admin/maintenance/**} 在公开模式下一律 404（同时被 Security 拒绝）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PublicController {

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final PublicCleanupService cleanupService;
    private final HumanChallengeService challengeService;
    private final AnonUsageMapper usageMapper;

    @GetMapping("/public/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", appMode.value());
        result.put("publicMode", appMode.isPublic());
        if (appMode.isPublic()) {
            result.put("sessionTtlHours", properties.getSessionTtlHours());
            result.put("retentionHours", properties.getSessionTtlHours());
            result.put("demoDataEnabled", properties.isDemoDataEnabled());
            result.put("aiEnabled", properties.isAiEnabled());
            result.put("maxFileSizeMb", properties.getMaxFileSizeMb());
            result.put("maxRows", properties.getMaxRows());
            result.put("maxColumns", properties.getMaxColumns());
            result.put("maxDatasets", properties.getMaxDatasets());
            result.put("maxCleanTasks", properties.getMaxCleanTasks());
            result.put("maxCharts", properties.getMaxCharts());
            result.put("maxExports", properties.getMaxExports());
            result.put("notice", "公开临时工作区，数据将在 " + properties.getSessionTtlHours()
                    + " 小时后自动清理，请勿上传个人隐私或保密数据");
        }
        return result;
    }

    @PostMapping("/public/ai/challenge")
    public Map<String, Object> issueAiChallenge() {
        if (!appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
        return challengeService.issue();
    }

    /**
     * 当前匿名会话的 AI 剩余额度（仅公开模式）。
     * 只返回本会话自身的用量计数，不含 IP、全局额度或供应商信息。
     */
    @GetMapping("/public/ai/quota")
    public Map<String, Object> aiQuota() {
        if (!appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        boolean enabled = properties.isAiEnabled();
        result.put("enabled", enabled);
        if (enabled) {
            int limit = properties.getAiMaxCallsPerDay();
            Long used = usageMapper.getCounter("session", String.valueOf(RequestContext.requireSessionId()),
                    "ai_call", LocalDate.now());
            long usedValue = used == null ? 0L : used;
            result.put("used", usedValue);
            result.put("limit", limit);
            result.put("remaining", Math.max(0, limit - usedValue));
        }
        return result;
    }

    @PostMapping("/admin/maintenance/cleanup")
    public Map<String, Object> runCleanup() {
        if (appMode.isPublic()) {
            throw new BusinessException(404, "接口不存在");
        }
        int handled = cleanupService.runOnce();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handledSessions", handled);
        return result;
    }
}
