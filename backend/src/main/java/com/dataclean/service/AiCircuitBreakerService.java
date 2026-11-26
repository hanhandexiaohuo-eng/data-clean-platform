package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.dto.AiCircuitVO;
import com.dataclean.entity.AiCircuitState;
import com.dataclean.mapper.AiCircuitStateMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型/供应商熔断。
 *
 * <p>连续失败 3 次、供应商 429 或预算耗尽 → OPEN，冷却 60 秒；
 * 冷却结束转 HALF_OPEN，仅允许 1 个探测请求；探测成功 → CLOSED，失败 → 重新 OPEN。
 * 内存状态与 {@code ai_circuit_state} 双写，重启后恢复 OPEN/HALF_OPEN。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCircuitBreakerService {

    private static final int FAILURE_THRESHOLD = 3;
    private static final int COOLDOWN_SECONDS = 60;

    private final AiCircuitStateMapper stateMapper;
    private final Map<String, LocalState> states = new ConcurrentHashMap<>();

    @PostConstruct
    public void restore() {
        try {
            List<AiCircuitState> rows = stateMapper.selectList(new LambdaQueryWrapper<AiCircuitState>()
                    .in(AiCircuitState::getState, AiCircuitState.STATE_OPEN, AiCircuitState.STATE_HALF_OPEN));
            for (AiCircuitState row : rows) {
                LocalState local = new LocalState();
                local.state = row.getState();
                local.failureCount = row.getFailureCount() == null ? 0 : row.getFailureCount();
                local.cooldownUntil = row.getCooldownUntil();
                local.openedAt = row.getOpenedAt();
                local.lastErrorType = row.getLastErrorType();
                states.put(row.getCircuitKey(), local);
            }
            if (!rows.isEmpty()) {
                log.info("已从数据库恢复 {} 条 AI 熔断状态", rows.size());
            }
        } catch (Exception e) {
            log.warn("恢复 AI 熔断状态失败: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 是否允许发起一次真实调用。
     *
     * <p>OPEN 且未到冷却时间 → 拒绝（调用方直接走备用）；
     * 冷却结束 → 转 HALF_OPEN 并占用唯一探测名额。
     */
    public boolean allow(String key) {
        LocalState state = states.get(key);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            if (AiCircuitState.STATE_OPEN.equals(state.state)) {
                if (state.cooldownUntil == null || LocalDateTime.now().isBefore(state.cooldownUntil)) {
                    return false;
                }
                state.state = AiCircuitState.STATE_HALF_OPEN;
                state.failureCount = 0;
                state.probeInFlight = false;
                persist(key, state);
            }
            if (AiCircuitState.STATE_HALF_OPEN.equals(state.state)) {
                if (state.probeInFlight) {
                    return false;
                }
                state.probeInFlight = true;
                return true;
            }
            return true;
        }
    }

    /** 当前是否处于打开状态（不占用探测名额）。 */
    public boolean isOpen(String key) {
        LocalState state = states.get(key);
        if (state == null || !AiCircuitState.STATE_OPEN.equals(state.state)) {
            return false;
        }
        return state.cooldownUntil != null && LocalDateTime.now().isBefore(state.cooldownUntil);
    }

    public void recordSuccess(String key) {
        LocalState state = states.remove(key);
        if (state == null) {
            return;
        }
        try {
            stateMapper.delete(new LambdaQueryWrapper<AiCircuitState>()
                    .eq(AiCircuitState::getCircuitKey, key));
        } catch (Exception e) {
            log.debug("清理熔断状态失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 非失败非成功的结束（如配额拒绝），释放探测名额但不改变状态。 */
    public void releaseProbe(String key) {
        LocalState state = states.get(key);
        if (state != null) {
            synchronized (state) {
                state.probeInFlight = false;
            }
        }
    }

    /**
     * 记录一次失败；budgetExhausted=true 或 errorType=429 时立即打开。
     */
    public void recordFailure(String key, String errorType, boolean budgetExhausted) {
        LocalState state = states.computeIfAbsent(key, k -> new LocalState());
        synchronized (state) {
            state.failureCount++;
            state.probeInFlight = false;
            boolean shouldOpen = budgetExhausted
                    || "429".equals(errorType)
                    || state.failureCount >= FAILURE_THRESHOLD;
            if (shouldOpen && !AiCircuitState.STATE_OPEN.equals(state.state)) {
                state.state = AiCircuitState.STATE_OPEN;
                state.openedAt = LocalDateTime.now();
                state.cooldownUntil = state.openedAt.plusSeconds(COOLDOWN_SECONDS);
                state.lastErrorType = errorType;
                log.warn("AI 熔断打开: key={}, error={}", key, errorType);
            }
            persist(key, state);
        }
    }

    public List<AiCircuitVO> list() {
        List<AiCircuitState> rows = stateMapper.selectList(new LambdaQueryWrapper<AiCircuitState>()
                .orderByDesc(AiCircuitState::getUpdatedAt));
        List<AiCircuitVO> result = new ArrayList<>();
        for (AiCircuitState row : rows) {
            AiCircuitVO vo = new AiCircuitVO();
            vo.setId(row.getId());
            vo.setCircuitKey(row.getCircuitKey());
            vo.setState(row.getState());
            vo.setFailureCount(row.getFailureCount());
            vo.setOpenedAt(row.getOpenedAt());
            vo.setCooldownUntil(row.getCooldownUntil());
            vo.setLastErrorType(row.getLastErrorType());
            vo.setUpdatedAt(row.getUpdatedAt());
            result.add(vo);
        }
        return result;
    }

    /** 受控重置：删除指定状态的持久化记录与内存状态。 */
    public void reset(Long id) {
        AiCircuitState row = stateMapper.selectById(id);
        if (row == null) {
            return;
        }
        states.remove(row.getCircuitKey());
        stateMapper.deleteById(id);
    }

    private void persist(String key, LocalState state) {
        try {
            AiCircuitState row = stateMapper.selectByKey(key);
            boolean insert = row == null;
            if (insert) {
                row = new AiCircuitState();
                row.setCircuitKey(key);
            }
            row.setState(state.state);
            row.setFailureCount(state.failureCount);
            row.setOpenedAt(state.openedAt);
            row.setCooldownUntil(state.cooldownUntil);
            row.setLastErrorType(state.lastErrorType);
            row.setUpdatedAt(LocalDateTime.now());
            if (insert) {
                stateMapper.insert(row);
            } else {
                stateMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("持久化熔断状态失败: key={}, error={}", key, e.getClass().getSimpleName());
        }
    }

    private static class LocalState {
        String state = AiCircuitState.STATE_CLOSED;
        int failureCount;
        LocalDateTime openedAt;
        LocalDateTime cooldownUntil;
        String lastErrorType;
        boolean probeInFlight;
    }
}
