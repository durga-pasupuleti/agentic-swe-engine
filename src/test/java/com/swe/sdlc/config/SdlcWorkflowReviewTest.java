package com.swe.sdlc.config;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.mcp.GitHubMcpManager;
import com.swe.sdlc.model.SdlcState;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SdlcWorkflowReviewTest {
    @Test
    void approvingReviewedPlanOpensExecutionModeGate() {
        SdlcWorkflowConfig workflow = createWorkflow(mock(TaskExecutor.class));
        SdlcState state = createReviewState(workflow);

        workflow.reviewRequirement("job-1", "APPROVE", null, false);

        assertEquals("PAUSED_AT_ENTRY_GATE", state.getTaskStatus());
        assertTrue(state.getAuditTrail().stream()
            .anyMatch(event -> "REQUIREMENT_APPROVED".equals(event.event())));
    }

    @Test
    void clarificationIsRetainedAndReanalysisIsQueued() {
        TaskExecutor executor = mock(TaskExecutor.class);
        SdlcWorkflowConfig workflow = createWorkflow(executor);
        SdlcState state = createReviewState(workflow);

        workflow.reviewRequirement("job-1", "CLARIFY", "Add expiry and return 410 for expired links.", false);

        assertEquals("REANALYZING_REQUIREMENT", state.getTaskStatus());
        assertEquals("Add expiry and return 410 for expired links.", state.getEffectiveRequirement());
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void unresolvedAmbiguitiesRequireExplicitAcknowledgment() {
        SdlcWorkflowConfig workflow = createWorkflow(mock(TaskExecutor.class));
        SdlcState state = createReviewState(workflow);
        state.setAmbiguities(java.util.List.of("What does safer mean?"));

        assertThrows(IllegalStateException.class,
                () -> workflow.reviewRequirement("job-1", "APPROVE", null, false));
        assertEquals("PAUSED_AT_REQUIREMENT_REVIEW", state.getTaskStatus());

        workflow.reviewRequirement("job-1", "APPROVE", null, true);
        assertEquals("PAUSED_AT_ENTRY_GATE", state.getTaskStatus());
    }

    private static SdlcWorkflowConfig createWorkflow(TaskExecutor executor) {
        return new SdlcWorkflowConfig(executor, mock(OllamaModelCatalog.class),
                mock(OllamaModelService.class), mock(GitHubMcpManager.class));
    }

    private static SdlcState createReviewState(SdlcWorkflowConfig workflow) {
        SdlcState state = new SdlcState("Make links safer", "owner/repo", "job-1", "user-1");
        state.setTaskStatus("PAUSED_AT_REQUIREMENT_REVIEW");
        state.setRepositoryFit("MATCH");
        workflow.registerJob(state);
        return state;
    }
}
