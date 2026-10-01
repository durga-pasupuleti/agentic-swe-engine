package com.swe.sdlc.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OllamaModelCatalogTest {
    @Test
    void prefersFastAliasAsFallbackForCodeModel() {
        OllamaModelCatalog catalog = new OllamaModelCatalog();
        catalog.setModels(Map.of("code", "qwen2.5-coder:7b", "fast", "llama3.2"));

        assertEquals("fast", catalog.fallbackAlias("code"));
        assertEquals("code", catalog.fallbackAlias("fast"));
    }

    @Test
    void returnsNoFallbackWhenOnlyPreferredAliasIsConfigured() {
        OllamaModelCatalog catalog = new OllamaModelCatalog();
        catalog.setModels(Map.of("code", "qwen2.5-coder:7b"));

        assertNull(catalog.fallbackAlias("code"));
    }
}