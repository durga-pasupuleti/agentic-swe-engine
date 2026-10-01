package com.swe.sdlc.config;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.mcp.GitHubMcpManager;
import com.swe.sdlc.model.SdlcState;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdlcWorkflowVerificationTest {
    @Test
    void successfulResumeOpensHumanApprovalGateForTheVerifiedCommit() {
        GitHubMcpManager github = mock(GitHubMcpManager.class);
        when(github.verifyActions("owner", "repo", "agentic/job-1", "commit-1"))
                .thenReturn(new GitHubMcpManager.Verification(true, false, "Actions passed"));
        SdlcWorkflowConfig workflow = createWorkflow(github);
        SdlcState state = createPendingState(workflow);

        workflow.resumeVerification("job-1");

        assertEquals("AWAITING_APPROVAL", state.getTaskStatus());
        verify(github).verifyActions("owner", "repo", "agentic/job-1", "commit-1");
    }

    @Test
    void incompleteActionsRunRemainsResumable() {
        GitHubMcpManager github = mock(GitHubMcpManager.class);
        when(github.verifyActions("owner", "repo", "agentic/job-1", "commit-1"))
                .thenReturn(new GitHubMcpManager.Verification(false, true, "Still running"));
        SdlcWorkflowConfig workflow = createWorkflow(github);
        SdlcState state = createPendingState(workflow);

        workflow.resumeVerification("job-1");

        assertEquals("VERIFICATION_PENDING", state.getTaskStatus());
    }

    private static SdlcWorkflowConfig createWorkflow(GitHubMcpManager github) {
        TaskExecutor directExecutor = Runnable::run;
        return new SdlcWorkflowConfig(directExecutor, mock(OllamaModelCatalog.class),
                mock(OllamaModelService.class), github);
    }

    private static SdlcState createPendingState(SdlcWorkflowConfig workflow) {
        SdlcState state = new SdlcState("requirement", "owner/repo", "job-1", "user-1");
        state.setBranchName("agentic/job-1");
        state.setCommitSha("commit-1");
        state.setTaskStatus("VERIFICATION_PENDING");
        workflow.registerJob(state);
        return state;
    }
}
