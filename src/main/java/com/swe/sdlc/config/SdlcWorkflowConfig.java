package com.swe.sdlc.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.mcp.GitHubMcpManager;
import com.swe.sdlc.model.SdlcState;
import com.swe.sdlc.workflow.SdlcWorkflowGraph;
import com.swe.sdlc.workflow.SdlcWorkflowGraph.Stage;

/**
 * Configuration class for managing the SDLC workflow.
 * what we are addressing here is the management of job states and the execution flow of the SDLC pipeline.
 * 
 */
@Configuration
public class SdlcWorkflowConfig {
    private static final int MAX_PATCH_ATTEMPTS = 3;
    private static final int MAX_FAILURE_LOG_LENGTH = 12_000;

    private final Map<String, SdlcState> jobRegistry = new ConcurrentHashMap<>();
    private final Map<String, GitHubMcpManager.RepositoryContext> repositoryContexts = new ConcurrentHashMap<>();
    private final Map<String, WorkflowPlan> workflowPlans = new ConcurrentHashMap<>();
    private final TaskExecutor workflowExecutor;
    private final OllamaModelCatalog modelCatalog;
    private final OllamaModelService ollamaModelService;
    private final GitHubMcpManager githubMcpManager;
    private final SdlcWorkflowGraph workflowGraph = new SdlcWorkflowGraph();

    public SdlcWorkflowConfig(
            @Qualifier("workflowExecutor") TaskExecutor workflowExecutor,
            OllamaModelCatalog modelCatalog,
            OllamaModelService ollamaModelService,
            GitHubMcpManager githubMcpManager) {
        this.workflowExecutor = workflowExecutor;
        this.modelCatalog = modelCatalog;
        this.ollamaModelService = ollamaModelService;
        this.githubMcpManager = githubMcpManager;
    }

    public void registerJob(SdlcState state) {
        if (state == null || state.getJobAlias() == null || state.getJobAlias().isBlank()) {
            throw new IllegalArgumentException("A job state with a non-empty alias is required");
        }
        if (jobRegistry.putIfAbsent(state.getJobAlias(), state) != null) {
            throw new IllegalStateException("A job with this alias is already registered");
        }
    }

    public SdlcState getJobState(String jobAlias) {
        return jobRegistry.get(jobAlias);
    }

    public boolean isModelAliasAllowed(String modelAlias) {
        return modelCatalog.containsAlias(modelAlias);
    }

        public Map<String, Object> getReliabilityMetrics() {
        List<SdlcState> states = List.copyOf(jobRegistry.values());
        long completed = states.stream().filter(state -> "COMPLETED".equals(state.getTaskStatus())).count();
        long failed = states.stream().filter(state -> "FAILED".equals(state.getTaskStatus())).count();
        long rejected = states.stream().filter(state -> "REJECTED".equals(state.getTaskStatus())).count();
        long terminal = completed + failed + rejected;
        long retries = states.stream().mapToLong(SdlcState::getRetryCount).sum();
        long rollbacks = states.stream().mapToLong(SdlcState::getRollbackCount).sum();
        double successRate = terminal == 0 ? 0.0 : (double) completed / terminal;
        double averageLatency = terminal == 0 ? 0.0 : states.stream()
            .filter(state -> Set.of("COMPLETED", "FAILED", "REJECTED").contains(state.getTaskStatus()))
            .mapToLong(SdlcState::getEndToEndLatencyMillis).average().orElse(0.0);
        double meanTimeToRecovery = states.stream()
            .filter(state -> state.getRollbackCount() > 0 && "COMPLETED".equals(state.getTaskStatus()))
            .mapToLong(SdlcState::getTimeToRecoveryMillis).average().orElse(0.0);
        return Map.of(
            "totalJobs", states.size(),
            "completedJobs", completed,
            "failedJobs", failed,
            "rejectedJobs", rejected,
            "successRate", successRate,
            "retryCount", retries,
            "rollbackCount", rollbacks,
            "meanTimeToRecoveryMillis", meanTimeToRecovery,
            "meanEndToEndLatencyMillis", averageLatency);
        }

    public void runDiscoveryPipelineAsync(String jobAlias) {
        SdlcState state = requireJobState(jobAlias);
        state.setTaskStatus("DISCOVERING");
        try {
            workflowExecutor.execute(() -> discoverRepository(state));
        } catch (TaskRejectedException exception) {
            fail(state, "Workflow executor is at capacity; retry the job later.");
        }
    }

    public synchronized void resumeExecutionTrack(String jobAlias, String mode) {
        if (!"SPEC_DRIVEN".equals(mode) && !"DIRECT_CODE".equals(mode)) {
            throw new IllegalArgumentException("Unsupported execution mode: " + mode);
        }

        SdlcState state = requireJobState(jobAlias);
        if (!"PAUSED_AT_ENTRY_GATE".equals(state.getTaskStatus())) {
            throw new IllegalStateException("Job is not waiting at the execution mode gate");
        }

        state.setExecutionMode(mode);
        state.setTaskStatus("RUNNING");
        try {
            workflowExecutor.execute(() -> executePatchWorkflow(state, mode));
        } catch (TaskRejectedException exception) {
            fail(state, "Workflow executor is at capacity; retry the job later.");
        }
    }

    private void discoverRepository(SdlcState state) {
        state.recordEvent("WORKFLOW_GRAPH", "SCHEDULED", workflowGraph.executionLayers().toString());
        try {
            state.beginStage("REPOSITORY_DISCOVERY");
            String[] repositoryParts = state.getTargetRepositoryId().split("/", 2);
            String branchName = "agentic/" + state.getJobAlias() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            githubMcpManager.createBranch(repositoryParts[0], repositoryParts[1], branchName);
            state.setBranchName(branchName);
            GitHubMcpManager.RepositoryContext discovery = githubMcpManager.loadContext(
                repositoryParts[0], repositoryParts[1], branchName, state.getRawRequirement());
            repositoryContexts.put(state.getJobAlias(), discovery);
            state.setBaseBranch(discovery.baseBranch());
            state.setEstimatedRepoSizeFiles(discovery.estimatedRepoSizeFiles());
            state.setSpecsAvailableInStorage(discovery.specsAvailableInStorage());

            String analysis = runBriefStage(state, Stage.REQUIREMENT_ANALYSIS,
                    state.getRawRequirement(), discovery.text(), "");
            String architecture = runBriefStage(state, Stage.ARCHITECTURE,
                    state.getRawRequirement(), discovery.text(), analysis);
                String decomposition = runBriefStage(state, Stage.TASK_DECOMPOSITION,
                    state.getRawRequirement(), discovery.text(), analysis + "\n\n" + architecture);
                workflowPlans.put(state.getJobAlias(), new WorkflowPlan(analysis, architecture, decomposition));
                state.setRequirementAnalysis(analysis);
                state.setArchitecturePlan(architecture);
                state.setTaskDecomposition(decomposition);
                state.setCompilerLogs(analysis + "\n\n" + architecture + "\n\n" + decomposition);
            state.completeStage("REPOSITORY_DISCOVERY", "branch=" + branchName
                    + "; tracked-files=" + discovery.estimatedRepoSizeFiles());
            state.setTaskStatus("PAUSED_AT_ENTRY_GATE");
        } catch (RuntimeException exception) {
            fail(state, safeMessage(exception));
        }
    }

    private void executePatchWorkflow(SdlcState state, String mode) {
        GitHubMcpManager.RepositoryContext repositoryContext = repositoryContexts.get(state.getJobAlias());
        WorkflowPlan plan = workflowPlans.get(state.getJobAlias());
        if (repositoryContext == null || plan == null) {
            fail(state, "Repository context or design plan is unavailable");
            return;
        }
        String[] repositoryParts = state.getTargetRepositoryId().split("/", 2);
        String previousFailure = null;
        state.recordEvent("WORKFLOW_GRAPH", "SCHEDULED", workflowGraph.executionLayers().toString());
        for (int attempt = 1; attempt <= MAX_PATCH_ATTEMPTS; attempt++) {
            state.setRetryCount(attempt - 1);
            state.setTaskStatus(attempt == 1 ? "GENERATING_PATCH" : "RETRYING");
            boolean changesPushed = false;
            List<String> changedPaths = List.of();
            try {
            if (attempt > 1) {
                String revisedArchitecture = runBriefStage(state, Stage.ARCHITECTURE,
                    state.getRawRequirement(), repositoryContext.text(),
                    plan.analysis() + "\nPrior validation failure:\n" + previousFailure);
                String revisedTasks = runBriefStage(state, Stage.TASK_DECOMPOSITION,
                    state.getRawRequirement(), repositoryContext.text(),
                    plan.analysis() + "\n\n" + revisedArchitecture + "\nPrior validation failure:\n"
                        + previousFailure);
                plan = new WorkflowPlan(plan.analysis(), revisedArchitecture, revisedTasks);
                workflowPlans.put(state.getJobAlias(), plan);
                state.setArchitecturePlan(revisedArchitecture);
                state.setTaskDecomposition(revisedTasks);
                state.recordDecision("DYNAMIC_REPLAN", "Architecture revised after failed verification");
            }

            Map<Stage, CompletableFuture<OllamaModelService.FileChangeSet>> parallelStages = new EnumMap<>(Stage.class);
            for (Stage stage : List.of(Stage.IMPLEMENTATION, Stage.TESTS, Stage.DOCUMENTATION)) {
                parallelStages.put(stage, generateWorkstreamAsync(
                    state, stage, repositoryContext.text(), plan, mode, previousFailure));
            }
            CompletableFuture.allOf(parallelStages.values().toArray(CompletableFuture[]::new)).join();
            List<OllamaModelService.FileChange> generatedFiles = parallelStages.values().stream()
                .map(CompletableFuture::join)
                .flatMap(result -> result.files().stream())
                .toList();
            List<OllamaModelService.FileChange> mergedFiles = mergeChanges(generatedFiles);
            List<Map<String, String>> files = mergedFiles.stream()
                .map(change -> Map.of("path", change.path(), "content", change.content()))
                .toList();
            changedPaths = mergedFiles.stream().map(OllamaModelService.FileChange::path).toList();
            state.completeStage(Stage.VALIDATION.name(), "parallel outputs synchronized; files=" + files.size()
                + "; sha256=" + hashChanges(mergedFiles));

                String commitSha = githubMcpManager.pushChanges(repositoryParts[0], repositoryParts[1],
                        state.getBranchName(), files, "Apply agent changes for " + state.getJobAlias());
                changesPushed = true;
                state.setCommitSha(commitSha);
            state.setGeneratedCodePatch(files.size() + " file(s) committed to " + state.getBranchName());
                state.setTaskStatus("VERIFYING");
            state.beginStage(Stage.VALIDATION.name());
                GitHubMcpManager.Verification verification = githubMcpManager.verifyActions(
                    repositoryParts[0], repositoryParts[1], state.getBranchName(), commitSha);
                if (verification.pending()) {
                    state.setCompilerLogs(verification.message());
                    state.setTaskStatus("VERIFICATION_PENDING");
                    return;
                }
                if (!verification.passed()) {
                    throw new IllegalStateException(verification.message());
                }
                state.completeStage(Stage.VALIDATION.name(), verification.message());
                state.setRetryCount(attempt - 1);
                state.setCompilerLogs(verification.message() + " Awaiting owner approval to create a pull request.");
                state.setTaskStatus("AWAITING_APPROVAL");
                state.beginStage(Stage.HUMAN_APPROVAL.name());
                state.recordDecision("APPROVAL_REQUIRED", "PR creation requires explicit owner approval");
                return;
            } catch (RuntimeException exception) {
                previousFailure = safeMessage(exception);
                state.recordFailureAttempt(previousFailure);
                state.setCompilerLogs(previousFailure);
                if (changesPushed) {
                    try {
                        githubMcpManager.rollback(repositoryParts[0], repositoryParts[1], state.getBranchName(),
                                repositoryContext.originalFiles(), changedPaths,
                                "Restore original files after failed verification");
                        state.setCommitSha("");
                        state.recordRollback();
                    } catch (RuntimeException rollbackException) {
                        fail(state, "Attempt failed and restore commit failed: " + safeMessage(rollbackException));
                        return;
                    }
                }
            }
        }
        state.setRetryCount(MAX_PATCH_ATTEMPTS);
        state.setTaskStatus("FAILED");
    }

    private String runBriefStage(SdlcState state, Stage stage, String requirement,
            String repositoryContext, String priorContext) {
        state.beginStage(stage.name());
        String brief = ollamaModelService.generateBrief(stage.name(), requirement, repositoryContext,
                state.getModelAlias(), priorContext);
        state.completeStage(stage.name(), "brief-sha256=" + hashText(brief));
        return brief;
    }

    private CompletableFuture<OllamaModelService.FileChangeSet> generateWorkstreamAsync(
            SdlcState state, Stage stage, String repositoryContext, WorkflowPlan plan,
            String mode, String previousFailure) {
        return CompletableFuture.supplyAsync(() -> {
            state.beginStage(stage.name());
            try {
                OllamaModelService.FileChangeSet result = ollamaModelService.generateChanges(
                        state.getRawRequirement(), repositoryContext, state.getModelAlias(), mode,
                        stage.name(), plan.analysis() + "\n\n" + plan.architecture() + "\n\n"
                            + plan.decomposition(), previousFailure);
                state.completeStage(stage.name(), "files=" + result.files().size()
                        + "; sha256=" + hashChanges(result.files()));
                return result;
            } catch (RuntimeException exception) {
                state.recordEvent(stage.name(), "FAILED", exception.getClass().getSimpleName());
                throw exception;
            }
        }, workflowExecutor);
    }

    private static List<OllamaModelService.FileChange> mergeChanges(List<OllamaModelService.FileChange> changes) {
        Map<String, OllamaModelService.FileChange> merged = new LinkedHashMap<>();
        for (OllamaModelService.FileChange change : changes) {
            OllamaModelService.FileChange existing = merged.putIfAbsent(change.path(), change);
            if (existing != null && !existing.content().equals(change.content())) {
                throw new IllegalStateException("Parallel stages produced conflicting changes for " + change.path());
            }
        }
        if (merged.isEmpty() || merged.size() > 12) {
            throw new IllegalStateException("Merged workflow output is empty or exceeds the file limit");
        }
        return List.copyOf(merged.values());
    }

    private static String hashText(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hashChanges(List<OllamaModelService.FileChange> changes) {
        String value = changes.stream()
                .sorted(java.util.Comparator.comparing(OllamaModelService.FileChange::path))
                .map(change -> change.path() + "\n" + change.content())
                .reduce("", (left, right) -> left + right + "\n");
        return hashText(value);
    }

    private record WorkflowPlan(String analysis, String architecture, String decomposition) {
    }

    public void decidePullRequest(String jobAlias, boolean approved) {
        SdlcState state = requireJobState(jobAlias);
        synchronized (state) {
            if (!"AWAITING_APPROVAL".equals(state.getTaskStatus())) {
                throw new IllegalStateException("Job is not waiting for pull request approval");
            }
            if (!approved) {
                state.setTaskStatus("REJECTED");
                state.setCompilerLogs("Pull request creation was rejected by the job owner.");
                return;
            }
            state.setTaskStatus("CREATING_PULL_REQUEST");
        }

        String[] repositoryParts = state.getTargetRepositoryId().split("/", 2);
        try {
            workflowExecutor.execute(() -> {
                try {
                    GitHubMcpManager.PullRequest pullRequest = githubMcpManager.createPullRequest(
                            repositoryParts[0],
                            repositoryParts[1],
                            state.getBranchName(),
                            state.getBaseBranch(),
                            "Agent changes: " + state.getJobAlias(),
                                """
                                    Automated changes generated for this approved SDLC job.

                                    Requirement: %s
                                    """.formatted(state.getRawRequirement()));
                    state.setPullRequestNumber(pullRequest.number());
                    state.setPullRequestUrl(pullRequest.url());
                    state.setCompilerLogs("Pull request created after owner approval.");
                    state.setTaskStatus("COMPLETED");
                } catch (RuntimeException exception) {
                    fail(state, safeMessage(exception));
                }
            });
        } catch (TaskRejectedException exception) {
            fail(state, "Workflow executor is at capacity; pull request creation was not queued.");
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > MAX_FAILURE_LOG_LENGTH
                ? message.substring(message.length() - MAX_FAILURE_LOG_LENGTH)
                : message;
    }

    private static void fail(SdlcState state, String message) {
        state.setCompilerLogs(message);
        state.setTaskStatus("FAILED");
    }

    private SdlcState requireJobState(String jobAlias) {
        SdlcState state = jobRegistry.get(jobAlias);
        if (state == null) {
            throw new IllegalArgumentException("Unknown job alias: " + jobAlias);
        }
        return state;
    }
}