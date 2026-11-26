package com.dataclean.service;

import com.dataclean.config.PublicModeProperties;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.AnonSessionMapper;
import com.dataclean.mapper.AnonUsageMapper;
import com.dataclean.metrics.RuntimeMetrics;
import com.dataclean.util.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AnonymousSessionServiceHashTest {

    @Mock
    private AnonSessionMapper anonSessionMapper;
    @Mock
    private AnonUsageMapper anonUsageMapper;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private RuntimeMetrics metrics;

    private PublicModeProperties properties;
    private AnonymousSessionService service;

    @BeforeEach
    void setUp() {
        properties = new PublicModeProperties();
        properties.setIpHashPepper("0123456789abcdef0123456789abcdef");
        service = new AnonymousSessionService(anonSessionMapper, anonUsageMapper, properties,
                clientIpResolver, metrics);
    }

    @Test
    void tokenHash_isSha256Hex_andNotReversible() {
        String hash = service.hashToken("token-value");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertNotEquals("token-value", hash);
        assertEquals(hash, service.hashToken("token-value"));
    }

    @Test
    void ipHash_isStable_andDomainSeparatedByInput() {
        String first = service.ipHash("203.0.113.9");
        String second = service.ipHash("203.0.113.9");
        String other = service.ipHash("203.0.113.10");

        assertEquals(first, second);
        assertNotEquals(first, other);
        assertEquals(64, first.length());
    }

    @Test
    void missingPepper_failsClosed() {
        properties.setIpHashPepper("");
        assertThrows(IllegalStateException.class, () -> service.ipHash("203.0.113.9"));
    }

    @Test
    void businessException_carriesHttpCode() {
        BusinessException exception = new BusinessException(429, "too many");
        assertEquals(429, exception.getCode());
    }
}
