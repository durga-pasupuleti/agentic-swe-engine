package com.swe.sdlc.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaModelServicePromptTest {
    @Test
    void brownfieldPromptUsesTheSuppliedDomainAndRepositoryStack() {
        String prompt = new PromptTemplateStore().render("implementation.prompt", java.util.Map.of(
            "executionMode", "DIRECT_CODE",
            "requirement", "Add OAuth login to the existing inventory service.",
            "plan", "Add an OAuth callback to the existing authentication module.",
            "repositoryContext", "Repository files use Kotlin and Spring Boot.",
            "previousFailure", "None"));

        assertTrue(prompt.contains("Add OAuth login to the existing inventory service."));
        assertTrue(prompt.contains("Follow the existing repository's language and framework"));
        assertFalse(prompt.contains("URL shortener"));
    }

    @Test
    void requirementAnalysisContractRequiresAmbiguitiesAndAssumptions() {
        String contract = new PromptTemplateStore().render("requirement-analysis.prompt", java.util.Map.of(
            "requirement", "Make links safer.",
            "repositoryContext", "Existing URL shortener."));

        assertTrue(contract.contains("acceptanceCriteria"));
        assertTrue(contract.contains("requirementCategory"));
        assertTrue(contract.contains("GREENFIELD, ENHANCEMENT, BROWNFIELD, AMBIGUOUS"));
        assertTrue(contract.contains("ambiguities"));
        assertTrue(contract.contains("assumptions"));
    }

    @Test
    void acceptsNoOpWorkstreamsButNotMalformedChangeSets() {
        assertTrue(OllamaModelService.validFileChangeSet(new OllamaModelService.FileChangeSet(List.of())));
        assertFalse(OllamaModelService.validFileChangeSet(null));
        assertFalse(OllamaModelService.validFileChangeSet(new OllamaModelService.FileChangeSet(null)));
    }
}
