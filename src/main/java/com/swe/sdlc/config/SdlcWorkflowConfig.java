package com.swe.sdlc.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(SdlcWorkflowConfig.class);

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
            List<String> contextFiles = discovery.originalFiles().keySet().stream().sorted().toList();
            state.setRepositoryContextFiles(contextFiles);
            state.recordEvent("REPOSITORY_DISCOVERY", "CONTEXT_SELECTED", String.join(", ", contextFiles));
            state.setBaseBranch(discovery.baseBranch());
            state.setEstimatedRepoSizeFiles(discovery.estimatedRepoSizeFiles());
            state.setSpecsAvailableInStorage(discovery.specsAvailableInStorage());

                WorkflowPlan plan = generateWorkflowPlan(state, discovery);
                saveWorkflowPlan(state, plan);
            state.completeStage("REPOSITORY_DISCOVERY", "branch=" + branchName
                    + "; tracked-files=" + discovery.estimatedRepoSizeFiles());
                state.setTaskStatus("PAUSED_AT_REQUIREMENT_REVIEW");
                state.recordDecision("REQUIREMENT_REVIEW_REQUIRED",
                    "Execution is blocked until the owner reviews the plan");
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
        String previousFailure = state.getRetryCount() == 0 ? null : state.getCompilerLogs();
        int firstAttempt = state.getRetryCount() + 1;
        state.recordEvent("WORKFLOW_GRAPH", "SCHEDULED", workflowGraph.executionLayers().toString());
        for (int attempt = firstAttempt; attempt <= MAX_PATCH_ATTEMPTS; attempt++) {
            state.setRetryCount(attempt - 1);
            state.setTaskStatus(attempt == 1 ? "GENERATING_PATCH" : "RETRYING");
            boolean changesPushed = false;
            List<String> changedPaths = List.of();
            try {
            if (attempt > 1) {
                String revisedArchitecture = runBriefStage(state, Stage.ARCHITECTURE,
                    state.getEffectiveRequirement(), repositoryContext.text(),
                    plan.analysis() + "\nPrior validation failure:\n" + previousFailure);
                String revisedTasks = runBriefStage(state, Stage.TASK_DECOMPOSITION,
                    state.getEffectiveRequirement(), repositoryContext.text(),
                    plan.analysis() + "\n\n" + revisedArchitecture + "\nPrior validation failure:\n"
                        + previousFailure);
                plan = new WorkflowPlan(plan.analysis(), revisedArchitecture, revisedTasks);
                workflowPlans.put(state.getJobAlias(), plan);
                state.setArchitecturePlan(revisedArchitecture);
                state.setTaskDecomposition(revisedTasks);
                state.recordDecision("DYNAMIC_REPLAN", "Architecture revised after failed verification");
            }

            Map<Stage, CompletableFuture<OllamaModelService.FileChangeSet>> parallelStages = new EnumMap<>(Stage.class);
            for (Stage stage : parallelWorkstreamStages()) {
                parallelStages.put(stage, generateWorkstreamAsync(
                    state, stage, repositoryContext.text(), plan, mode, previousFailure));
            }
            CompletableFuture.allOf(parallelStages.values().toArray(CompletableFuture[]::new)).join();
            state.beginStage(Stage.SYNCHRONIZATION.name());
            List<OllamaModelService.FileChange> generatedFiles = parallelStages.values().stream()
                .map(CompletableFuture::join)
                .flatMap(result -> result.files().stream())
                .toList();
            List<OllamaModelService.FileChange> mergedFiles = ChangeSetPolicy.merge(generatedFiles);
            ChangeSetPolicy.validateRepositoryPaths(mergedFiles, repositoryContext);
            List<Map<String, String>> files = mergedFiles.stream()
                .map(change -> Map.of("path", change.path(), "content", change.content()))
                .toList();
            changedPaths = mergedFiles.stream().map(OllamaModelService.FileChange::path).toList();
            state.setChangedPaths(changedPaths);
            state.completeStage(Stage.SYNCHRONIZATION.name(), "parallel outputs synchronized; files=" + files.size()
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
                state.recordRecovery();
                state.setRetryCount(attempt - 1);
                state.setCompilerLogs(verification.message() + " Awaiting owner approval to create a pull request.");
                state.setTaskStatus("AWAITING_APPROVAL");
                state.setEngineeringSummary(buildEngineeringSummary(state, "AWAITING_APPROVAL", verification.message()));
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
                        state.setChangedPaths(List.of());
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
        state.setEngineeringSummary(buildEngineeringSummary(state, "FAILED", previousFailure));
    }

    public void resumeVerification(String jobAlias) {
        SdlcState state = requireJobState(jobAlias);
        synchronized (state) {
            if (!"VERIFICATION_PENDING".equals(state.getTaskStatus())) {
                throw new IllegalStateException("Job is not waiting for Actions verification");
            }
            state.setTaskStatus("VERIFYING");
        }
        try {
            workflowExecutor.execute(() -> {
                try {
                    String[] repositoryParts = state.getTargetRepositoryId().split("/", 2);
                    GitHubMcpManager.Verification verification = githubMcpManager.verifyActions(
                            repositoryParts[0], repositoryParts[1], state.getBranchName(), state.getCommitSha());
                    if (verification.pending()) {
                        state.setCompilerLogs(verification.message());
                        state.setTaskStatus("VERIFICATION_PENDING");
                        return;
                    }
                    if (!verification.passed()) {
                        GitHubMcpManager.RepositoryContext repositoryContext = repositoryContexts.get(jobAlias);
                        if (repositoryContext == null) {
                            fail(state, "Repository context is unavailable for rollback");
                            return;
                        }
                        try {
                            githubMcpManager.rollback(repositoryParts[0], repositoryParts[1], state.getBranchName(),
                                repositoryContext.originalFiles(), state.getChangedPaths(),
                                "Restore files after failed Actions verification");
                        } catch (RuntimeException rollbackException) {
                            fail(state, "Actions failed and compensating restore failed: "
                                + safeMessage(rollbackException));
                            return;
                        }
                        state.recordRollback();
                        state.recordFailureAttempt(verification.message());
                        state.setCommitSha("");
                        state.setChangedPaths(List.of());
                        if (state.getRetryCount() >= MAX_PATCH_ATTEMPTS - 1) {
                            state.setRetryCount(MAX_PATCH_ATTEMPTS);
                            fail(state, verification.message());
                            return;
                        }
                        state.setRetryCount(state.getRetryCount() + 1);
                        state.setCompilerLogs(verification.message());
                        executePatchWorkflow(state, state.getExecutionMode());
                        return;
                    }
                    state.completeStage(Stage.VALIDATION.name(), verification.message());
                    state.recordRecovery();
                    state.setCompilerLogs(verification.message() + " Awaiting owner approval to create a pull request.");
                    state.setTaskStatus("AWAITING_APPROVAL");
                    state.setEngineeringSummary(buildEngineeringSummary(state, "AWAITING_APPROVAL", verification.message()));
                    state.beginStage(Stage.HUMAN_APPROVAL.name());
                    state.recordDecision("APPROVAL_REQUIRED", "PR creation requires explicit owner approval");
                } catch (RuntimeException exception) {
                    state.setCompilerLogs("Verification could not be resumed: " + safeMessage(exception));
                    state.setTaskStatus("VERIFICATION_PENDING");
                }
            });
        } catch (TaskRejectedException exception) {
            state.setCompilerLogs("Workflow executor is at capacity; verification remains pending.");
            state.setTaskStatus("VERIFICATION_PENDING");
        }
    }

    private String runBriefStage(SdlcState state, Stage stage, String requirement,
            String repositoryContext, String priorContext) {
        state.beginStage(stage.name());
        String brief = withModelFallback(state, stage.name(), alias -> ollamaModelService.generateBrief(
            stage.name(), requirement, repositoryContext, alias, priorContext));
        state.completeStage(stage.name(), "brief-sha256=" + hashText(brief));
        return brief;
    }

        private WorkflowPlan generateWorkflowPlan(SdlcState state,
            GitHubMcpManager.RepositoryContext repositoryContext) {
            Map<Stage, String> briefs = new EnumMap<>(Stage.class);
            for (List<Stage> layer : workflowGraph.executionLayers()) {
                if (layer.contains(Stage.IMPLEMENTATION)) {
                break;
                }
                for (Stage stage : layer) {
                    if (stage == Stage.REQUIREMENT_ANALYSIS) {
                        state.beginStage(stage.name());
                        OllamaModelService.RequirementAnalysis understanding = withModelFallback(state, stage.name(),
                            alias -> ollamaModelService.analyzeRequirement(
                                state.getEffectiveRequirement(), repositoryContext.text(), alias));
                        state.setRequirementCategory(understanding.requirementCategory().name());
                        state.setNormalizedRequirement(understanding.normalizedProblem());
                        state.setAcceptanceCriteria(understanding.acceptanceCriteria());
                        state.setAmbiguities(understanding.ambiguities());
                        state.setAssumptions(understanding.assumptions());
                        state.setIdentifiedRisks(understanding.risks());
                        briefs.put(stage, understanding.reviewText());
                        state.completeStage(stage.name(), "ambiguities=" + understanding.ambiguities().size()
                                + "; sha256=" + hashText(understanding.reviewText()));
                        continue;
                    }
                String priorContext = switch (stage) {
                    case ARCHITECTURE -> requireBrief(briefs, Stage.REQUIREMENT_ANALYSIS);
                    case TASK_DECOMPOSITION -> requireBrief(briefs, Stage.REQUIREMENT_ANALYSIS)
                        + "\n\n" + requireBrief(briefs, Stage.ARCHITECTURE);
                    default -> throw new IllegalStateException("Unexpected planning stage: " + stage);
                };
                briefs.put(stage, runBriefStage(state, stage, state.getEffectiveRequirement(),
                    repositoryContext.text(), priorContext));
                }
            }
            return new WorkflowPlan(requireBrief(briefs, Stage.REQUIREMENT_ANALYSIS),
                    requireBrief(briefs, Stage.ARCHITECTURE), requireBrief(briefs, Stage.TASK_DECOMPOSITION));
        }

        private static String requireBrief(Map<Stage, String> briefs, Stage stage) {
            String brief = briefs.get(stage);
            if (brief == null) {
                throw new IllegalStateException("Workflow graph did not produce the " + stage + " brief");
            }
            return brief;
        }

            private List<Stage> parallelWorkstreamStages() {
            return workflowGraph.executionLayers().stream()
                .filter(layer -> layer.contains(Stage.IMPLEMENTATION))
                .flatMap(List::stream)
                .filter(stage -> List.of(Stage.IMPLEMENTATION, Stage.TESTS, Stage.DOCUMENTATION).contains(stage))
                .toList();
            }

        private void saveWorkflowPlan(SdlcState state, WorkflowPlan plan) {
        workflowPlans.put(state.getJobAlias(), plan);
        state.setRequirementAnalysis(plan.analysis());
        state.setArchitecturePlan(plan.architecture());
        state.setTaskDecomposition(plan.decomposition());
        state.setCompilerLogs("Requirement analysis:\n" + plan.analysis()
            + "\n\nArchitecture:\n" + plan.architecture()
            + "\n\nTask decomposition:\n" + plan.decomposition());
        }

        public void reviewRequirement(String jobAlias, String decision, String clarifiedRequirement,
            boolean acceptAmbiguities) {
        SdlcState state = requireJobState(jobAlias);
        synchronized (state) {
            if (!"PAUSED_AT_REQUIREMENT_REVIEW".equals(state.getTaskStatus())) {
                throw new IllegalStateException("Job is not waiting for requirement review");
            }
            if ("APPROVE".equals(decision)) {
                if (!state.getAmbiguities().isEmpty() && !acceptAmbiguities) {
                    throw new IllegalStateException("Owner must explicitly acknowledge unresolved ambiguities");
                }
                String decisionType = state.getAmbiguities().isEmpty()
                        ? "REQUIREMENT_APPROVED" : "AMBIGUITIES_ACCEPTED";
                state.recordDecision(decisionType,
                        "Owner approved the plan; unresolved ambiguities=" + state.getAmbiguities().size());
                state.setTaskStatus("PAUSED_AT_ENTRY_GATE");
                return;
            }
            if (!"CLARIFY".equals(decision) || clarifiedRequirement == null
                    || clarifiedRequirement.isBlank()) {
                throw new IllegalArgumentException("Choose APPROVE or provide clarified requirement text");
            }
            state.setClarifiedRequirement(clarifiedRequirement.strip());
            state.recordDecision("REQUIREMENT_CLARIFIED", "Owner submitted revised requirement text");
            state.setTaskStatus("REANALYZING_REQUIREMENT");
        }

        try {
            workflowExecutor.execute(() -> {
                try {
                    GitHubMcpManager.RepositoryContext repositoryContext = repositoryContexts.get(jobAlias);
                    if (repositoryContext == null) {
                        throw new IllegalStateException("Repository context is unavailable for re-analysis");
                    }
                    WorkflowPlan revisedPlan = generateWorkflowPlan(state, repositoryContext);
                    saveWorkflowPlan(state, revisedPlan);
                    state.setTaskStatus("PAUSED_AT_REQUIREMENT_REVIEW");
                    state.recordDecision("REQUIREMENT_REVIEW_REQUIRED",
                            "Revised plan requires owner review before execution");
                } catch (RuntimeException exception) {
                    fail(state, safeMessage(exception));
                }
            });
        } catch (TaskRejectedException exception) {
            fail(state, "Workflow executor is at capacity; requirement re-analysis was not queued.");
        }
    }

    private CompletableFuture<OllamaModelService.FileChangeSet> generateWorkstreamAsync(
            SdlcState state, Stage stage, String repositoryContext, WorkflowPlan plan,
            String mode, String previousFailure) {
        return CompletableFuture.supplyAsync(() -> {
            state.beginStage(stage.name());
            try {
                OllamaModelService.FileChangeSet result = withModelFallback(state, stage.name(), alias ->
                    ollamaModelService.generateChanges(state.getEffectiveRequirement(), repositoryContext,
                        alias, mode, stage.name(), plan.analysis() + "\n\n" + plan.architecture() + "\n\n"
                            + plan.decomposition(), previousFailure));
                state.completeStage(stage.name(), "files=" + result.files().size()
                        + "; sha256=" + hashChanges(result.files()));
                return result;
            } catch (RuntimeException exception) {
                state.recordEvent(stage.name(), "FAILED", exception.getClass().getSimpleName());
                throw exception;
            }
        }, workflowExecutor);
    }

    private <T> T withModelFallback(SdlcState state, String stage, Function<String, T> operation) {
        String preferredAlias = state.getModelAlias();
        try {
            return operation.apply(preferredAlias);
        } catch (RuntimeException primaryFailure) {
            String fallbackAlias = modelCatalog.fallbackAlias(preferredAlias);
            if (fallbackAlias == null) {
                throw primaryFailure;
            }
            state.recordDecision("MODEL_FALLBACK", stage + " retried once with model alias " + fallbackAlias);
            try {
                return operation.apply(fallbackAlias);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
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
                state.completeStage(Stage.HUMAN_APPROVAL.name(), "Owner rejected PR creation");
                state.setTaskStatus("REJECTED");
                state.setCompilerLogs("Pull request creation was rejected by the job owner.");
                state.setEngineeringSummary(buildEngineeringSummary(state, "REJECTED", "No pull request was created."));
                return;
            }
            state.completeStage(Stage.HUMAN_APPROVAL.name(), "Owner approved PR creation");
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
                    state.beginStage(Stage.RELEASE_READY.name());
                    state.completeStage(Stage.RELEASE_READY.name(), "Pull request is ready for final review");
                    state.setTaskStatus("COMPLETED");
                        state.setEngineeringSummary(buildEngineeringSummary(state, "COMPLETED",
                            "Pull request: " + pullRequest.url()));
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
        String safeMessage = message
                .replaceAll("(?i)\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b", "[REDACTED]")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]");
        if (safeMessage.length() > MAX_FAILURE_LOG_LENGTH) {
            safeMessage = safeMessage.substring(safeMessage.length() - MAX_FAILURE_LOG_LENGTH);
        }
        state.setCompilerLogs(safeMessage);
        state.setTaskStatus("FAILED");
        state.setEngineeringSummary(buildEngineeringSummary(state, "FAILED", safeMessage));
        state.recordEvent("WORKFLOW", "FAILURE_DETAIL", safeMessage);
        LOGGER.error("SDLC job {} failed: {}", state.getJobAlias(), safeMessage);
    }

    private static String buildEngineeringSummary(SdlcState state, String outcome, String validation) {
        return "Outcome: " + outcome
                + "\n\nPlan and rationale:\nRequirement: " + state.getEffectiveRequirement()
                + "\nAnalysis: " + state.getRequirementAnalysis()
                + "\nArchitecture: " + state.getArchitecturePlan()
                + "\nTasks: " + state.getTaskDecomposition()
                + "\n\nArtifacts: " + String.join(", ", state.getChangedPaths())
                + "\n\nValidation: " + validation
                + "\n\nRisks and trade-offs: changes are restricted to context-reviewed files and new paths; "
                + "execution is limited to an isolated feature branch; verification depends on repository Actions."
                + "\nAssumptions: the GitHub App is installed on the target repository and CI runs for agentic branches."
                + "\nLimitations: repository context is bounded to 12 selected files and 80,000 characters; "
                + "job state is in memory and is lost when the engine restarts.";
    }

    private SdlcState requireJobState(String jobAlias) {
        SdlcState state = jobRegistry.get(jobAlias);
        if (state == null) {
            throw new IllegalArgumentException("Unknown job alias: " + jobAlias);
        }
        return state;
    }
}