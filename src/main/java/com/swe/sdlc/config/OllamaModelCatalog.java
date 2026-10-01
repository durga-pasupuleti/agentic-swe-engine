package com.swe.sdlc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

@Component
@ConfigurationProperties(prefix = "sdlc.ollama")
public class OllamaModelCatalog {
    private static final Pattern MODEL_ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    private Map<String, String> models = Map.of();

    public Map<String, String> getModels() {
        return models;
    }

    public void setModels(Map<String, String> models) {
        this.models = models == null ? Map.of() : Map.copyOf(models);
    }

    public boolean containsAlias(String alias) {
        return alias != null && MODEL_ALIAS.matcher(alias).matches() && models.containsKey(alias);
    }

    public String resolve(String alias) {
        if (!containsAlias(alias)) {
            throw new IllegalArgumentException("Unsupported model alias");
        }

        String model = models.get(alias);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Configured model alias has no model name");
        }
        return model;
    }
}