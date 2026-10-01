package com.swe.sdlc.ai;

import com.swe.sdlc.config.OllamaModelCatalog;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class OllamaModelService {
    private static final int MAX_RESPONSE_LENGTH = 200_000;
    private static final int MAX_CHANGED_FILES = 12;

    private final ChatModel chatModel;
    private final OllamaModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;
    private final PromptTemplateStore promptTemplateStore;

    public OllamaModelService(ChatModel chatModel, OllamaModelCatalog modelCatalog, ObjectMapper objectMapper,
            PromptTemplateStore promptTemplateStore) {
        this.chatModel = chatModel;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
        this.promptTemplateStore = promptTemplateStore;
    }

    public String generateBrief(
            String stage,
            String requirement,
            String repositoryContext,
            String modelAlias,
            String priorContext) {
        String modelName = modelCatalog.resolve(modelAlias);
        String promptName = switch (stage) {
            case "ARCHITECTURE" -> "architecture.prompt";
            case "TASK_DECOMPOSITION" -> "task-decomposition.prompt";
            default -> throw new IllegalArgumentException("Unsupported planning stage");
        };
        String promptText = promptTemplateStore.render(promptName, Map.of(
                "requirement", requirement,
                "priorContext", priorContext,
                "repositoryContext", repositoryContext));
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

    public RequirementAnalysis analyzeRequirement(String requirement, String repositoryContext, String modelAlias) {
        String modelName = modelCatalog.resolve(modelAlias);
        String promptText = promptTemplateStore.render("requirement-analysis.prompt", Map.of(
            "requirement", requirement,
            "repositoryContext", repositoryContext));
        ChatResponse response = chatModel.call(new Prompt(promptText,
                OllamaChatOptions.builder().model(modelName).temperature(0.1).format("json").build()));
        String json = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (json == null || json.isBlank() || json.length() > 40_000) {
            throw new IllegalStateException("Model returned invalid structured requirement analysis");
        }
        try {
            RequirementAnalysis analysis = objectMapper.readValue(json, RequirementAnalysis.class);
            if (analysis == null || analysis.normalizedProblem() == null || analysis.normalizedProblem().isBlank()
                    || !validItems(analysis.acceptanceCriteria()) || !validItems(analysis.ambiguities())
                    || !validItems(analysis.assumptions()) || !validItems(analysis.risks())) {
                throw new IllegalStateException("Structured requirement analysis is incomplete or oversized");
            }
            return analysis;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Model returned invalid structured requirement analysis", exception);
        }
    }

    private static boolean validItems(List<String> items) {
        return items != null && items.size() <= 20
                && items.stream().allMatch(item -> item != null && !item.isBlank() && item.length() <= 2_000);
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
        String promptName = switch (workstream) {
            case "IMPLEMENTATION" -> "implementation.prompt";
            case "TESTS" -> "tests.prompt";
            case "DOCUMENTATION" -> "documentation.prompt";
            default -> throw new IllegalArgumentException("Unsupported generation workstream");
        };
        String previousFailureText = previousFailure == null || previousFailure.isBlank()
            ? "None" : previousFailure;
        String promptText = promptTemplateStore.render(promptName, Map.of(
            "executionMode", executionMode,
            "requirement", requirement,
            "plan", architecturePlan,
            "repositoryContext", repositoryContext,
            "previousFailure", previousFailureText));

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

    public record RequirementAnalysis(
            String normalizedProblem,
            List<String> acceptanceCriteria,
            List<String> ambiguities,
            List<String> assumptions,
            List<String> risks) {

        public String reviewText() {
            return "Normalized problem:\n" + normalizedProblem
                    + "\n\nAcceptance criteria:\n" + String.join("\n", acceptanceCriteria)
                    + "\n\nAmbiguities:\n" + (ambiguities.isEmpty() ? "None" : String.join("\n", ambiguities))
                    + "\n\nAssumptions:\n" + (assumptions.isEmpty() ? "None" : String.join("\n", assumptions))
                    + "\n\nRisks:\n" + (risks.isEmpty() ? "None" : String.join("\n", risks));
        }
    }
}