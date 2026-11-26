package com.dataclean.service;

import com.dataclean.entity.AiCircuitState;
import com.dataclean.mapper.AiCircuitStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 熔断器：连续失败阈值、429/预算立即打开、冷却探测名额、成功恢复与受控重置。
 */
@ExtendWith(MockitoExtension.class)
class AiCircuitBreakerServiceTest {

    @Mock
    private AiCircuitStateMapper stateMapper;

    private AiCircuitBreakerService breaker;

    @BeforeEach
    void setUp() {
        breaker = new AiCircuitBreakerService(stateMapper);
    }

    @Test
    void unknownKeyIsAllowed() {
        assertTrue(breaker.allow("model:1"));
        assertFalse(breaker.isOpen("model:1"));
    }

    @Test
    void opensAfterThreeConsecutiveFailures() {
        breaker.recordFailure("model:1", "500", false);
        breaker.recordFailure("model:1", "500", false);
        assertFalse(breaker.isOpen("model:1"));
        assertTrue(breaker.allow("model:1"));

        breaker.recordFailure("model:1", "500", false);
        assertTrue(breaker.isOpen("model:1"));
        assertFalse(breaker.allow("model:1"));
    }

    @Test
    void rateLimitOpensImmediately() {
        breaker.recordFailure("model:2", "429", false);
        assertTrue(breaker.isOpen("model:2"));
        assertFalse(breaker.allow("model:2"));
    }

    @Test
    void budgetExhaustedOpensImmediately() {
        breaker.recordFailure("model:3", "BUDGET", true);
        assertTrue(breaker.isOpen("model:3"));
    }

    @Test
    void successClosesAndClearsState() {
        breaker.recordFailure("model:4", "429", false);
        assertTrue(breaker.isOpen("model:4"));

        breaker.recordSuccess("model:4");
        assertFalse(breaker.isOpen("model:4"));
        assertTrue(breaker.allow("model:4"));
        verify(stateMapper).delete(any());
    }

    @Test
    void halfOpenAllowsOnlyOneProbe() {
        AiCircuitState row = new AiCircuitState();
        row.setCircuitKey("model:5");
        row.setState(AiCircuitState.STATE_HALF_OPEN);
        row.setFailureCount(0);
        when(stateMapper.selectList(any())).thenReturn(List.of(row));
        breaker.restore();

        assertTrue(breaker.allow("model:5"));
        assertFalse(breaker.allow("model:5"));
        breaker.releaseProbe("model:5");
        assertTrue(breaker.allow("model:5"));
    }

    @Test
    void resetDeletesPersistedRow() {
        AiCircuitState row = new AiCircuitState();
        row.setId(9L);
        row.setCircuitKey("model:6");
        row.setState(AiCircuitState.STATE_OPEN);
        row.setCooldownUntil(LocalDateTime.now().plusSeconds(30));
        when(stateMapper.selectById(9L)).thenReturn(row);

        breaker.reset(9L);
        verify(stateMapper).deleteById(9L);
    }
}
