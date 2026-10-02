package com.swe.sdlc.config;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.mcp.GitHubMcpManager;
import com.swe.sdlc.model.SdlcState;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

class SdlcWorkflowFailureTest {
    @Test
    void discoveryFailureIsAvailableInJobStatusAndAuditTrail() {
        GitHubMcpManager github = mock(GitHubMcpManager.class);
        doThrow(new GitHubMcpManager.GitHubMcpException("GitHub MCP client is unavailable"))
                .when(github).createBranch(anyString(), anyString(), anyString());
        TaskExecutor directExecutor = Runnable::run;
        SdlcWorkflowConfig workflow = new SdlcWorkflowConfig(directExecutor,
                mock(OllamaModelCatalog.class), mock(OllamaModelService.class), github);
        SdlcState state = new SdlcState("Update the service", "owner/repo", "job-1", "user-1");
        workflow.registerJob(state);

        workflow.runDiscoveryPipelineAsync("job-1");

        assertEquals("FAILED", state.getTaskStatus());
        assertEquals("GitHub MCP client is unavailable", state.getCompilerLogs());
        assertTrue(state.getAuditTrail().stream()
                .anyMatch(event -> "FAILURE_DETAIL".equals(event.event())
                        && event.summary().contains("GitHub MCP client is unavailable")));
    }
}