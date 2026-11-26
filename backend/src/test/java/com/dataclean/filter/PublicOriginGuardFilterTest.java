package com.dataclean.filter;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicOriginGuardFilterTest {

    private PublicOriginGuardFilter filter;

    @BeforeEach
    void setUp() {
        PublicModeProperties properties = new PublicModeProperties();
        properties.setAllowedOrigins(List.of("https://demo.example.com"));
        filter = new PublicOriginGuardFilter(new AppMode("public"), properties);
    }

    @Test
    void exactOrigin_allowed() {
        assertTrue(filter.isAllowedOrigin("https://demo.example.com"));
    }

    @Test
    void trailingSlash_isNormalized() {
        assertTrue(filter.isAllowedOrigin("https://demo.example.com/"));
    }

    @Test
    void differentScheme_port_orHost_rejected() {
        assertFalse(filter.isAllowedOrigin("http://demo.example.com"));
        assertFalse(filter.isAllowedOrigin("https://demo.example.com:8443"));
        assertFalse(filter.isAllowedOrigin("https://evil.example.com"));
        assertFalse(filter.isAllowedOrigin("null"));
        assertFalse(filter.isAllowedOrigin(null));
    }
}
