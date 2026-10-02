package com.swe.sdlc.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
/**
 * Represents the state of an SDLC (Software Development Life Cycle) job.
 * Contains information about the job's requirements, repository, execution status, and other metadata.
 */

public class SdlcState {
    private final String rawRequirement;
    private volatile String clarifiedRequirement;
    private final String targetRepositoryId;
    private final String jobAlias;
    private final String userIdentity;
    private final Instant createdAt = Instant.now();
    private final long createdAtNanos = System.nanoTime();
    private volatile long firstFailureAtNanos;
    private volatile long recoveredAtNanos;
    private volatile long finishedAtNanos;
    private final List<AuditEvent> auditTrail = new CopyOnWriteArrayList<>();
    private volatile Consumer<AuditEvent> auditSink = event -> { };
    private final Map<String, Long> stageStartedAt = new ConcurrentHashMap<>();
    private final Map<String, Long> stageDurationsMillis = new ConcurrentHashMap<>();

    private volatile String modelAlias = "code";
    private volatile String requirementCategory = "PENDING";
    private volatile String requirementAnalysis = "";
    private volatile String normalizedRequirement = "";
    private volatile List<String> acceptanceCriteria = List.of();
    private volatile List<String> ambiguities = List.of();
    private volatile List<String> assumptions = List.of();
    private volatile List<String> identifiedRisks = List.of();
    private volatile String architecturePlan = "";
    private volatile String taskDecomposition = "";
    private volatile String executionMode = "UNSET";
    private volatile String branchName = "";
    private volatile String baseBranch = "";
    private volatile String commitSha = "";
    private volatile String pullRequestUrl = "";
    private volatile long pullRequestNumber;
    private volatile String compilerLogs = "";
    private volatile String engineeringSummary = "";
    private volatile int retryCount;
    private volatile String generatedCodePatch = "";
    private volatile String taskStatus = "INITIALIZED";
    private volatile boolean specsAvailableInStorage;
    private volatile long estimatedRepoSizeFiles;
    private volatile int rollbackCount;
    private volatile List<String> changedPaths = List.of();
    private volatile List<String> repositoryContextFiles = List.of();

    public SdlcState(String requirement, String repoId, String alias, String user) {
        this.rawRequirement = requirement;
        this.targetRepositoryId = repoId;
        this.jobAlias = alias;
        this.userIdentity = user;
    }

    public String getRawRequirement() {
        return rawRequirement;
    }

    public String getEffectiveRequirement() {
        return clarifiedRequirement == null ? rawRequirement : clarifiedRequirement;
    }

    public void setClarifiedRequirement(String clarifiedRequirement) {
        this.clarifiedRequirement = clarifiedRequirement;
    }

    public String getTargetRepositoryId() {
        return targetRepositoryId;
    }

    public String getJobAlias() {
        return jobAlias;
    }

    public String getUserIdentity() {
        return userIdentity;
    }

    public String getModelAlias() {
        return modelAlias;
    }

    public void setModelAlias(String modelAlias) {
        this.modelAlias = modelAlias;
    }

    public String getRequirementAnalysis() {
        return requirementAnalysis;
    }

    public String getRequirementCategory() {
        return requirementCategory;
    }

    public void setRequirementCategory(String requirementCategory) {
        this.requirementCategory = requirementCategory;
    }

    public void setRequirementAnalysis(String requirementAnalysis) {
        this.requirementAnalysis = requirementAnalysis;
    }

    public String getNormalizedRequirement() {
        return normalizedRequirement;
    }

    public void setNormalizedRequirement(String normalizedRequirement) {
        this.normalizedRequirement = normalizedRequirement;
    }

    public List<String> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public void setAcceptanceCriteria(List<String> acceptanceCriteria) {
        this.acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }

    public List<String> getAmbiguities() {
        return ambiguities;
    }

    public void setAmbiguities(List<String> ambiguities) {
        this.ambiguities = List.copyOf(ambiguities);
    }

    public List<String> getAssumptions() {
        return assumptions;
    }

    public void setAssumptions(List<String> assumptions) {
        this.assumptions = List.copyOf(assumptions);
    }

    public List<String> getIdentifiedRisks() {
        return identifiedRisks;
    }

    public void setIdentifiedRisks(List<String> identifiedRisks) {
        this.identifiedRisks = List.copyOf(identifiedRisks);
    }

    public String getArchitecturePlan() {
        return architecturePlan;
    }

    public void setArchitecturePlan(String architecturePlan) {
        this.architecturePlan = architecturePlan;
    }

    public String getTaskDecomposition() {
        return taskDecomposition;
    }

    public void setTaskDecomposition(String taskDecomposition) {
        this.taskDecomposition = taskDecomposition;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getPullRequestUrl() {
        return pullRequestUrl;
    }

    public void setPullRequestUrl(String pullRequestUrl) {
        this.pullRequestUrl = pullRequestUrl;
    }

    public long getPullRequestNumber() {
        return pullRequestNumber;
    }

    public void setPullRequestNumber(long pullRequestNumber) {
        this.pullRequestNumber = pullRequestNumber;
    }

    public String getCompilerLogs() {
        return compilerLogs;
    }

    public String getEngineeringSummary() {
        return engineeringSummary;
    }

    public void setEngineeringSummary(String engineeringSummary) {
        this.engineeringSummary = engineeringSummary;
    }

    public void setCompilerLogs(String compilerLogs) {
        this.compilerLogs = compilerLogs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getRollbackCount() {
        return rollbackCount;
    }

    public void recordRollback() {
        rollbackCount++;
        recordEvent("ROLLBACK", "COMPLETED", "Compensating restore completed");
    }

    public void recordFailureAttempt(String summary) {
        if (firstFailureAtNanos == 0) {
            firstFailureAtNanos = System.nanoTime();
        }
        recordEvent("VALIDATION", "ATTEMPT_FAILED", summary);
    }

    public void recordRecovery() {
        if (firstFailureAtNanos != 0 && recoveredAtNanos == 0) {
            recoveredAtNanos = System.nanoTime();
        }
    }

    public List<String> getChangedPaths() {
        return changedPaths;
    }

    public void setChangedPaths(List<String> changedPaths) {
        this.changedPaths = List.copyOf(changedPaths);
    }

    public List<String> getRepositoryContextFiles() {
        return repositoryContextFiles;
    }

    public void setRepositoryContextFiles(List<String> repositoryContextFiles) {
        this.repositoryContextFiles = List.copyOf(repositoryContextFiles);
    }

    public void beginStage(String stage) {
        stageStartedAt.put(stage, System.nanoTime());
        recordEvent(stage, "STARTED", "Stage started");
    }

    public void completeStage(String stage, String summary) {
        Long startedAt = stageStartedAt.remove(stage);
        if (startedAt != null) {
            stageDurationsMillis.merge(stage, (System.nanoTime() - startedAt) / 1_000_000L, Long::sum);
        }
        recordEvent(stage, "COMPLETED", summary);
    }

    public void recordDecision(String decision, String summary) {
        appendAuditEvent(new AuditEvent(Instant.now(), userIdentity, "GOVERNANCE", decision, summary));
    }

    public void recordEvent(String stage, String event, String summary) {
        appendAuditEvent(new AuditEvent(Instant.now(), "system", stage, event, summary));
    }

    public void setAuditSink(Consumer<AuditEvent> auditSink) {
        this.auditSink = auditSink == null ? event -> { } : auditSink;
    }

    private void appendAuditEvent(AuditEvent event) {
        auditSink.accept(event);
        auditTrail.add(event);
    }

    public List<AuditEvent> getAuditTrail() {
        return List.copyOf(auditTrail);
    }

    public Map<String, Object> getReliabilityMetrics() {
        long endNanos = finishedAtNanos == 0 ? System.nanoTime() : finishedAtNanos;
        long recoveryMillis = firstFailureAtNanos == 0 || recoveredAtNanos == 0
            || recoveredAtNanos < firstFailureAtNanos
            ? 0 : (recoveredAtNanos - firstFailureAtNanos) / 1_000_000L;
        return Map.of(
                "elapsedMillis", (System.nanoTime() - createdAtNanos) / 1_000_000L,
                "endToEndLatencyMillis", (endNanos - createdAtNanos) / 1_000_000L,
                "timeToRecoveryMillis", recoveryMillis,
                "retryCount", retryCount,
                "rollbackCount", rollbackCount,
                "stageDurationsMillis", Map.copyOf(stageDurationsMillis),
                "completed", "COMPLETED".equals(taskStatus));
    }

    public long getEndToEndLatencyMillis() {
        long endNanos = finishedAtNanos == 0 ? System.nanoTime() : finishedAtNanos;
        return (endNanos - createdAtNanos) / 1_000_000L;
    }

    public long getTimeToRecoveryMillis() {
        if (firstFailureAtNanos == 0 || recoveredAtNanos == 0 || recoveredAtNanos < firstFailureAtNanos) {
            return 0;
        }
        return (recoveredAtNanos - firstFailureAtNanos) / 1_000_000L;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getGeneratedCodePatch() {
        return generatedCodePatch;
    }

    public void setGeneratedCodePatch(String generatedCodePatch) {
        this.generatedCodePatch = generatedCodePatch;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        String previous = this.taskStatus;
        this.taskStatus = taskStatus;
        if ("FAILED".equals(taskStatus) && firstFailureAtNanos == 0) {
            firstFailureAtNanos = System.nanoTime();
        }
        if ("COMPLETED".equals(taskStatus) || "FAILED".equals(taskStatus) || "REJECTED".equals(taskStatus)) {
            if (finishedAtNanos == 0) {
                finishedAtNanos = System.nanoTime();
            }
        }
        if (!Objects.equals(previous, taskStatus)) {
            recordEvent("WORKFLOW", taskStatus, previous + " -> " + taskStatus);
        }
    }

    public boolean isSpecsAvailableInStorage() {
        return specsAvailableInStorage;
    }

    public void setSpecsAvailableInStorage(boolean specsAvailableInStorage) {
        this.specsAvailableInStorage = specsAvailableInStorage;
    }

    public long getEstimatedRepoSizeFiles() {
        return estimatedRepoSizeFiles;
    }

    public void setEstimatedRepoSizeFiles(long estimatedRepoSizeFiles) {
        this.estimatedRepoSizeFiles = estimatedRepoSizeFiles;
    }

    public record AuditEvent(Instant timestamp, String actor, String stage, String event, String summary) {
    }
}