package com.swe.sdlc.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptTemplateStore {
    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z][a-z0-9-]*\\.prompt");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}");

    private final PromptTemplateRepository repository;

    public PromptTemplateStore() {
        this.repository = new ClasspathPromptTemplateRepository();
    }

    @Autowired
    public PromptTemplateStore(
            @Value("${sdlc.prompts.storage:classpath}") String storage,
            @Value("${sdlc.prompts.database.url:jdbc:postgresql://localhost:5432/agentic_prompts}") String url,
            @Value("${sdlc.prompts.database.username:sdlc_prompts}") String username,
            @Value("${sdlc.prompts.database.password:local-development-only}") String password) {
        this.repository = switch (storage) {
            case "classpath" -> new ClasspathPromptTemplateRepository();
            case "postgres" -> new PostgresPromptTemplateRepository(url, username, password);
            default -> throw new IllegalArgumentException("Unsupported prompt storage backend: " + storage);
        };
    }

    public String render(String templateName, Map<String, ?> values) {
        if (templateName == null || !TEMPLATE_NAME.matcher(templateName).matches()) {
            throw new IllegalArgumentException("Invalid prompt template name");
        }
        String template = repository.load(templateName);

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Missing prompt value: " + key);
            }
            Object value = values.get(key);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}