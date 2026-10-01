package com.swe.sdlc.ai;

import com.swe.sdlc.config.OllamaModelCatalog;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class OllamaModelService {
    private static final int MAX_RESPONSE_LENGTH = 200_000;
    private static final int MAX_CHANGED_FILES = 12;

    private final ChatModel chatModel;
    private final OllamaModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;

    public OllamaModelService(ChatModel chatModel, OllamaModelCatalog modelCatalog, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
    }

    public String generateBrief(
            String stage,
            String requirement,
            String repositoryContext,
            String modelAlias,
            String priorContext) {
        String modelName = modelCatalog.resolve(modelAlias);
        String promptText = "You are performing the " + stage + " stage of an SDLC workflow. "
                + "Return concise, actionable plain text. Identify ambiguity, assumptions, dependencies, and risks explicitly.\n"
                + "Requirement:\n" + requirement + "\n\n"
                + "Prior stage context:\n" + priorContext + "\n\n"
                + "Repository context (empty means greenfield):\n" + repositoryContext;
        ChatResponse response = chatModel.call(new Prompt(promptText,
                OllamaChatOptions.builder().model(modelName).temperature(0.1).build()));
        String text = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank() || text.length() > 40_000) {
            throw new IllegalStateException("Model returned an invalid " + stage + " brief");
        }
        return text;
    }

    public FileChangeSet generateChanges(
            String requirement,
            String repositoryContext,
            String modelAlias,
            String executionMode,
            String workstream,
            String architecturePlan,
            String previousFailure) {
        String modelName = modelCatalog.resolve(modelAlias);
        String workstreamScope = switch (workstream) {
            case "IMPLEMENTATION" -> "Create or update production source and configuration files only.";
            case "TESTS" -> "Create or update unit and integration test files only.";
            case "DOCUMENTATION" -> "Create or update README, API, and runbook documentation only.";
            default -> throw new IllegalArgumentException("Unsupported generation workstream");
        };
        String promptText = "For the " + workstream + " workstream, " + workstreamScope + " Return one JSON object "
                + "with a 'files' array. Each item has only 'path' and complete file 'content'. You may replace supplied "
                + "files or create necessary new files for greenfield work. Never delete files. Do not modify secrets, "
                + "credentials, or .github/workflows. Preserve unrelated content. Use production-quality Java 21 and "
                + "Spring Boot for the URL shortener.\nExecution mode: " + executionMode + "\nRequirement:\n"
                + requirement + "\n\nRequirement analysis and architecture:\n" + architecturePlan
                + "\n\nRepository files:\n" + repositoryContext;
        if (previousFailure != null && !previousFailure.isBlank()) {
            promptText += "\n\nThe prior change failed verification. Correct it using this output:\n"
                + previousFailure;
        }

        Prompt prompt = new Prompt(promptText, OllamaChatOptions.builder().model(modelName).format("json").build());
        ChatResponse response = chatModel.call(prompt);

        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("Ollama returned an empty response");
        }
        String json = response.getResult().getOutput().getText();
        if (json.length() > MAX_RESPONSE_LENGTH) {
            throw new IllegalStateException("Ollama response exceeds the file-change size limit");
        }
        try {
            FileChangeSet changes = objectMapper.readValue(json, FileChangeSet.class);
            if (changes == null || changes.files() == null || changes.files().isEmpty()
                    || changes.files().size() > MAX_CHANGED_FILES) {
                throw new IllegalStateException("Ollama returned an invalid file-change set");
            }
            return changes;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Ollama returned invalid JSON file changes", exception);
        }
    }

    public record FileChange(String path, String content) {
    }

    public record FileChangeSet(List<FileChange> files) {
    }
}