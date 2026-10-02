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
        String analysisJson = responseText(response);
        if (analysisJson == null || analysisJson.isBlank() || analysisJson.length() > 40_000) {
            throw new IllegalStateException("Model returned invalid structured requirement analysis");
        }
        RequirementDetails details;
        try {
            details = objectMapper.readValue(analysisJson, RequirementDetails.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Model returned invalid structured requirement analysis");
        }
        if (details == null) {
            throw new IllegalStateException("Model returned an empty structured requirement analysis");
        }
        List<String> analysisWarnings = new java.util.ArrayList<>();
        String normalizedProblem = details.normalizedProblem();
        if (normalizedProblem == null || normalizedProblem.isBlank()) {
            normalizedProblem = requirement;
            analysisWarnings.add("The model omitted the normalized problem; using the submitted requirement verbatim.");
        }
        List<String> acceptanceCriteria = analysisItemsOrEmpty(
                details.acceptanceCriteria(), "acceptanceCriteria", analysisWarnings);
        List<String> ambiguities = new java.util.ArrayList<>(analysisItemsOrEmpty(
                details.ambiguities(), "ambiguities", analysisWarnings));
        List<String> assumptions = analysisItemsOrEmpty(details.assumptions(), "assumptions", analysisWarnings);
        List<String> risks = analysisItemsOrEmpty(details.risks(), "risks", analysisWarnings);
        ambiguities.addAll(analysisWarnings);

        RequirementClassification classification = classifyRequirement(requirement, repositoryContext, modelName);
        if (classification.changeClassification() == ChangeClassification.AMBIGUOUS && ambiguities.isEmpty()) {
            ambiguities = List.of("Clarify the requested change: " + classification.repositoryFitReason());
        }
        return new RequirementAnalysis(classification.changeClassification(), classification.repositoryFit(),
                classification.repositoryFitReason(), normalizedProblem, acceptanceCriteria,
                ambiguities, assumptions, risks);
    }

    private static List<String> analysisItemsOrEmpty(
            List<String> items, String fieldName, List<String> analysisWarnings) {
        if (items == null) {
            analysisWarnings.add("The model omitted " + fieldName + "; review this analysis for completeness.");
            return List.of();
        }
        if (!validItems(items)) {
            throw new IllegalStateException("Structured requirement analysis contains invalid or oversized "
                    + fieldName);
        }
        return items;
    }

    private RequirementClassification classifyRequirement(
            String requirement, String repositoryContext, String modelName) {
        int contextLimit = 16_000;
        String boundedContext = repositoryContext.length() > contextLimit
                ? repositoryContext.substring(0, contextLimit) : repositoryContext;
        String promptText = promptTemplateStore.render("requirement-classification.prompt", Map.of(
                "requirement", requirement,
                "repositoryContext", boundedContext));
        ChatResponse response = chatModel.call(new Prompt(promptText,
            OllamaChatOptions.builder().model(modelName).temperature(0.0).format("json").build()));
        String json = responseText(response);
        if (json == null || json.isBlank() || json.length() > 8_000) {
            throw new IllegalStateException("Model returned an invalid change classification response");
        }
        RequirementClassification classification;
        try {
            classification = objectMapper.readValue(json, RequirementClassification.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Model returned invalid JSON for change classification");
        }
        if (classification == null || classification.changeClassification() == null
                || classification.repositoryFit() == null || classification.repositoryFitReason() == null
                || classification.repositoryFitReason().isBlank()
                || classification.repositoryFitReason().length() > 2_000) {
            throw new IllegalStateException("Model classification must include changeClassification, repositoryFit, "
                    + "and a concise repositoryFitReason");
        }
        return classification;
    }

    private static String responseText(ChatResponse response) {
        return response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
    }

    private static boolean validItems(List<String> items) {
        return items != null && items.size() <= 20
                && items.stream().allMatch(item -> item != null && !item.isBlank() && item.length() <= 2_000);
    }

    static boolean validFileChangeSet(FileChangeSet changes) {
        return changes != null && changes.files() != null && changes.files().size() <= MAX_CHANGED_FILES;
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
                if (!validFileChangeSet(changes)) {
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

    public enum ChangeClassification {
        NEW_CHANGE,
        EXISTING_CHANGE,
        AMBIGUOUS
    }

    public enum RepositoryFit {
        MATCH,
        MISMATCH,
        INSUFFICIENT_CONTEXT
    }

    public record RequirementAnalysis(
            ChangeClassification changeClassification,
            RepositoryFit repositoryFit,
            String repositoryFitReason,
            String normalizedProblem,
            List<String> acceptanceCriteria,
            List<String> ambiguities,
            List<String> assumptions,
            List<String> risks) {

        public String reviewText() {
            return "Change classification: " + changeClassification
                + "\nRepository fit: " + repositoryFit
                + "\nRepository fit reason: " + repositoryFitReason
                    + "\n\nNormalized problem:\n" + normalizedProblem
                    + "\n\nAcceptance criteria:\n" + String.join("\n", acceptanceCriteria)
                    + "\n\nAmbiguities:\n" + (ambiguities.isEmpty() ? "None" : String.join("\n", ambiguities))
                    + "\n\nAssumptions:\n" + (assumptions.isEmpty() ? "None" : String.join("\n", assumptions))
                    + "\n\nRisks:\n" + (risks.isEmpty() ? "None" : String.join("\n", risks));
        }
    }

        public record RequirementDetails(
            String normalizedProblem,
            List<String> acceptanceCriteria,
            List<String> ambiguities,
            List<String> assumptions,
            List<String> risks) {
        }

        public record RequirementClassification(
            ChangeClassification changeClassification,
            RepositoryFit repositoryFit,
            String repositoryFitReason) {
        }
}