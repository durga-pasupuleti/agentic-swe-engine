package com.swe.sdlc.config;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.ai.OllamaModelService.ChangeClassification;
import com.swe.sdlc.ai.OllamaModelService.RepositoryFit;
import com.swe.sdlc.mcp.GitHubMcpManager;
import com.swe.sdlc.model.SdlcState;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SdlcWorkflowRepositoryFitTest {
    @Test
    void repositoryMismatchFailsBeforeCreatingBranch() {
        GitHubMcpManager github = mock(GitHubMcpManager.class);
        OllamaModelService ollama = mock(OllamaModelService.class);
        doReturn(repositoryContext()).when(github).loadContext(anyString(), anyString(), anyString());
        doReturn(analysis(RepositoryFit.MISMATCH, ChangeClassification.NEW_CHANGE, List.of()))
                .when(ollama).analyzeRequirement(anyString(), anyString(), anyString());
        doReturn("plan").when(ollama).generateBrief(
                anyString(), anyString(), anyString(), anyString(), anyString());

        SdlcState state = runDiscovery(workflow(github, ollama));

        assertEquals("FAILED", state.getTaskStatus());
        assertEquals("MISMATCH", state.getRepositoryFit());
        assertEquals("", state.getBranchName());
        verify(github, never()).createBranch(anyString(), anyString(), anyString());
    }

    @Test
    void insufficientContextRequiresClarificationBeforeApproval() {
        GitHubMcpManager github = mock(GitHubMcpManager.class);
        OllamaModelService ollama = mock(OllamaModelService.class);
        doReturn(repositoryContext()).when(github).loadContext(anyString(), anyString(), anyString());
        doReturn(analysis(RepositoryFit.INSUFFICIENT_CONTEXT, ChangeClassification.AMBIGUOUS,
                List.of("Identify the module that should receive this behavior.")))
                .when(ollama).analyzeRequirement(anyString(), anyString(), anyString());
        doReturn("plan").when(ollama).generateBrief(
                anyString(), anyString(), anyString(), anyString(), anyString());

        SdlcWorkflowConfig workflow = workflow(github, ollama);
        SdlcState state = runDiscovery(workflow);

        assertEquals("PAUSED_AT_REQUIREMENT_REVIEW", state.getTaskStatus());
        assertEquals("", state.getBranchName());
        assertThrows(IllegalStateException.class,
                () -> workflow.reviewRequirement("job-1", "APPROVE", null, false));
        verify(github, never()).createBranch(anyString(), anyString(), anyString());
    }

        private static SdlcState runDiscovery(SdlcWorkflowConfig workflow) {
        SdlcState state = new SdlcState("Add this capability", "owner/repo", "job-1", "user-1");
        workflow.registerJob(state);
        workflow.runDiscoveryPipelineAsync("job-1");
        return state;
    }

    private static SdlcWorkflowConfig workflow(GitHubMcpManager github, OllamaModelService ollama) {
        TaskExecutor directExecutor = Runnable::run;
        return new SdlcWorkflowConfig(directExecutor, mock(OllamaModelCatalog.class), ollama, github);
    }

    private static GitHubMcpManager.RepositoryContext repositoryContext() {
        return new GitHubMcpManager.RepositoryContext("main", 1, false,
                "--- README.md ---\nExisting repository context",
                Map.of("README.md", "Existing repository context"), Set.of("README.md"));
    }

    private static OllamaModelService.RequirementAnalysis analysis(
            RepositoryFit fit, ChangeClassification classification, List<String> ambiguities) {
        return new OllamaModelService.RequirementAnalysis(classification, fit, "Repository evidence",
                "Normalized requirement", List.of("Acceptance criterion"), ambiguities, List.of(), List.of());
    }
}