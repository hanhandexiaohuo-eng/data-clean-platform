package com.dataclean.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PublicModePropertiesTest {

    @Test
    void allowedOriginSet_splitsCommas_andIgnoresBlank() {
        PublicModeProperties properties = new PublicModeProperties();
        properties.setAllowedOrigins(List.of("https://a.example.com, https://b.example.com", "", "  "));

        Set<String> origins = properties.allowedOriginSet();

        assertEquals(Set.of("https://a.example.com", "https://b.example.com"), origins);
    }

    @Test
    void allowedOriginSet_emptyWhenNotConfigured() {
        PublicModeProperties properties = new PublicModeProperties();
        assertTrue(properties.allowedOriginSet().isEmpty());
    }

    @Test
    void sizeHelpers_useMegabytes() {
        PublicModeProperties properties = new PublicModeProperties();
        properties.setMaxFileSizeMb(20);
        properties.setMaxTotalStorageMb(100);

        assertEquals(20L * 1024 * 1024, properties.maxFileSizeBytes());
        assertEquals(100L * 1024 * 1024, properties.maxTotalStorageBytes());
    }
}
