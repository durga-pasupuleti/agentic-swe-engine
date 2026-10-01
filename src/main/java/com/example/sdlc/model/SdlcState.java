package com.example.sdlc.model;
/**
 * Represents the state of an SDLC (Software Development Life Cycle) job.
 * Contains information about the job's requirements, repository, execution status, and other metadata.
 */

public class SdlcState {
    private final String rawRequirement;
    private final String targetRepositoryId;
    private final String jobAlias;
    private final String userIdentity;
    private final String vcsToken;

    private String executionMode = "UNSET";
    private String localWorkspacePath = "";
    private String compilerLogs = "";
    private int retryCount;
    private String generatedCodePatch = "";
    private String taskStatus = "INITIALIZED";
    private boolean specsAvailableInStorage;
    private long estimatedRepoSizeFiles;

    public SdlcState(String requirement, String repoId, String alias, String user, String token) {
        this.rawRequirement = requirement;
        this.targetRepositoryId = repoId;
        this.jobAlias = alias;
        this.userIdentity = user;
        this.vcsToken = token;
    }

    public String getRawRequirement() {
        return rawRequirement;
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

    public String getVcsToken() {
        return vcsToken;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getLocalWorkspacePath() {
        return localWorkspacePath;
    }

    public void setLocalWorkspacePath(String localWorkspacePath) {
        this.localWorkspacePath = localWorkspacePath;
    }

    public String getCompilerLogs() {
        return compilerLogs;
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
        this.taskStatus = taskStatus;
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
}