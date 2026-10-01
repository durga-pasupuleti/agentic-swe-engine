package com.swe.sdlc.controller;

import com.swe.sdlc.config.SdlcWorkflowConfig;
import com.swe.sdlc.model.AuditTrailStore;
import com.swe.sdlc.model.SdlcState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v3/sdlc")
public class HybridOrchestratorController {
    private static final Pattern REPOSITORY_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern JOB_ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_REQUIREMENT_LENGTH = 100_000;

    private final SdlcWorkflowConfig workflowConfig;
    private final AuditTrailStore auditTrailStore;

    public HybridOrchestratorController(SdlcWorkflowConfig workflowConfig, AuditTrailStore auditTrailStore) {
        this.workflowConfig = workflowConfig;
        this.auditTrailStore = auditTrailStore;
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> triggerSdlcJob(
            @RequestHeader("X-User-Identity") String userIdentity,
            @RequestBody(required = false) Map<String, String> payload) {

        if (!isValidIdentity(userIdentity) || payload == null) {
            return error(HttpStatus.BAD_REQUEST, "Missing or invalid user identity or request body.");
        }

        String requirement = payload.get("requirement");
        String repositoryId = payload.get("repositoryId");
        String jobAlias = payload.get("jobAlias");
        String modelAlias = payload.getOrDefault("modelAlias", "code");

        if (requirement == null || requirement.isBlank() || requirement.length() > MAX_REQUIREMENT_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "Requirement must be non-empty and within the allowed length.");
        }
        if (!isValidRepositoryId(repositoryId)) {
            return error(HttpStatus.BAD_REQUEST, "Repository ID must use the owner/repository format.");
        }
        if (!workflowConfig.isModelAliasAllowed(modelAlias)) {
            return error(HttpStatus.BAD_REQUEST, "Unsupported model alias.");
        }
        if (jobAlias == null || jobAlias.isBlank()) {
            jobAlias = "job-" + UUID.randomUUID();
        } else if (!JOB_ALIAS.matcher(jobAlias).matches()) {
            return error(HttpStatus.BAD_REQUEST, "Job alias contains unsupported characters or is too long.");
        }

        String auditJobAlias = jobAlias;
        SdlcState newState = new SdlcState(requirement, repositoryId, jobAlias, userIdentity);
        newState.setAuditSink(event -> auditTrailStore.append(auditJobAlias, event));
        newState.setModelAlias(modelAlias);
        try {
            workflowConfig.registerJob(newState);
        } catch (IllegalStateException exception) {
            return error(HttpStatus.CONFLICT, "Job alias is already in use.");
        }
        workflowConfig.runDiscoveryPipelineAsync(jobAlias);

        return ResponseEntity.accepted().body(Map.of(
                "jobAlias", jobAlias,
                "modelAlias", modelAlias,
                "status", "QUEUED",
                "checkStatusUrl", "/api/v3/sdlc/jobs/" + jobAlias + "/status"
        ));
    }

    @GetMapping("/jobs/{jobAlias}/status")
    public ResponseEntity<Map<String, Object>> getJobStatus(
            @PathVariable String jobAlias,
            @RequestHeader("X-User-Identity") String userIdentity) {

        SdlcState state = workflowConfig.getJobState(jobAlias);
        if (!isOwnedBy(state, userIdentity)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> status = new HashMap<>();
        status.put("jobAlias", state.getJobAlias());
        status.put("repositoryId", state.getTargetRepositoryId());
        status.put("modelAlias", state.getModelAlias());
        status.put("branch", state.getBranchName());
        status.put("baseBranch", state.getBaseBranch());
        status.put("commitSha", state.getCommitSha());
        status.put("pullRequestNumber", state.getPullRequestNumber());
        status.put("pullRequestUrl", state.getPullRequestUrl());
        status.put("specsDetectedInStorage", state.isSpecsAvailableInStorage());
        status.put("repositoryFileCount", state.getEstimatedRepoSizeFiles());
        status.put("repositoryContextFiles", state.getRepositoryContextFiles());
        status.put("executionMode", Objects.toString(state.getExecutionMode(), "UNSET"));
        status.put("status", Objects.toString(state.getTaskStatus(), "UNKNOWN"));
        status.put("requirement", state.getRawRequirement());
        status.put("effectiveRequirement", state.getEffectiveRequirement());
        status.put("requirementAnalysis", state.getRequirementAnalysis());
        status.put("normalizedRequirement", state.getNormalizedRequirement());
        status.put("acceptanceCriteria", state.getAcceptanceCriteria());
        status.put("ambiguities", state.getAmbiguities());
        status.put("assumptions", state.getAssumptions());
        status.put("identifiedRisks", state.getIdentifiedRisks());
        status.put("architecturePlan", state.getArchitecturePlan());
        status.put("taskDecomposition", state.getTaskDecomposition());
        status.put("compilerLogs", Objects.toString(state.getCompilerLogs(), ""));
        status.put("engineeringSummary", state.getEngineeringSummary());
        status.put("retryCount", state.getRetryCount());
        status.put("patchContent", Objects.toString(state.getGeneratedCodePatch(), ""));
        status.put("reliabilityMetrics", state.getReliabilityMetrics());
        status.put("auditTrail", state.getAuditTrail());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/jobs/{jobAlias}/requirement-review")
    public ResponseEntity<Map<String, Object>> reviewRequirement(
            @PathVariable String jobAlias,
            @RequestHeader("X-User-Identity") String userIdentity,
            @RequestBody(required = false) Map<String, Object> payload) {

        SdlcState state = workflowConfig.getJobState(jobAlias);
        if (!isOwnedBy(state, userIdentity)) {
            return ResponseEntity.notFound().build();
        }
        if (payload == null) {
            return error(HttpStatus.BAD_REQUEST, "Requirement review decision is required.");
        }
        Object decisionValue = payload.get("decision");
        Object requirementValue = payload.get("requirement");
        String decision = decisionValue instanceof String value ? value : null;
        String clarifiedRequirement = requirementValue instanceof String value ? value : null;
        boolean acceptAmbiguities = Boolean.TRUE.equals(payload.get("acceptAmbiguities"));
        if (!"APPROVE".equals(decision) && !"CLARIFY".equals(decision)) {
            return error(HttpStatus.BAD_REQUEST, "Decision must be APPROVE or CLARIFY.");
        }
        if (clarifiedRequirement != null && clarifiedRequirement.length() > MAX_REQUIREMENT_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "Clarified requirement exceeds the allowed length.");
        }
        if ("CLARIFY".equals(decision)
                && (clarifiedRequirement == null || clarifiedRequirement.isBlank())) {
            return error(HttpStatus.BAD_REQUEST, "Clarified requirement text is required.");
        }
        if (!"PAUSED_AT_REQUIREMENT_REVIEW".equals(state.getTaskStatus())) {
            return error(HttpStatus.CONFLICT, "Job is not waiting for requirement review.");
        }

        if ("APPROVE".equals(decision) && !state.getAmbiguities().isEmpty() && !acceptAmbiguities) {
            return error(HttpStatus.CONFLICT, "Explicitly acknowledge the listed ambiguities before approval.");
        }
        workflowConfig.reviewRequirement(jobAlias, decision, clarifiedRequirement, acceptAmbiguities);
        return ResponseEntity.accepted().body(Map.of(
                "jobAlias", jobAlias,
                "status", "APPROVE".equals(decision) ? "PAUSED_AT_ENTRY_GATE" : "REANALYZING_REQUIREMENT"));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getReliabilityMetrics(
            @RequestHeader("X-User-Identity") String userIdentity) {
        if (!isValidIdentity(userIdentity)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflowConfig.getReliabilityMetrics());
    }

    @PostMapping("/jobs/{jobAlias}/select-mode")
    public ResponseEntity<Map<String, Object>> submitModeSelection(
            @PathVariable String jobAlias,
            @RequestHeader("X-User-Identity") String userIdentity,
            @RequestBody(required = false) Map<String, String> payload) {

        SdlcState state = workflowConfig.getJobState(jobAlias);
        if (!isOwnedBy(state, userIdentity)) {
            return ResponseEntity.notFound().build();
        }
        if (payload == null) {
            return error(HttpStatus.BAD_REQUEST, "A mode selection body is required.");
        }

        String mode = payload.get("mode");
        if (!"SPEC_DRIVEN".equals(mode) && !"DIRECT_CODE".equals(mode)) {
            return error(HttpStatus.BAD_REQUEST, "Mode must be SPEC_DRIVEN or DIRECT_CODE.");
        }
        if (!"PAUSED_AT_ENTRY_GATE".equals(state.getTaskStatus())) {
            return error(HttpStatus.CONFLICT, "Job is not waiting for mode selection.");
        }

        workflowConfig.resumeExecutionTrack(jobAlias, mode);
        return ResponseEntity.accepted().body(Map.of(
                "jobAlias", jobAlias,
                "status", "RUNNING",
                "modelAlias", state.getModelAlias()
        ));
    }

    @PostMapping("/jobs/{jobAlias}/verification")
    public ResponseEntity<Map<String, Object>> resumeVerification(
            @PathVariable String jobAlias,
            @RequestHeader("X-User-Identity") String userIdentity) {

        SdlcState state = workflowConfig.getJobState(jobAlias);
        if (!isOwnedBy(state, userIdentity)) {
            return ResponseEntity.notFound().build();
        }
        if (!"VERIFICATION_PENDING".equals(state.getTaskStatus())) {
            return error(HttpStatus.CONFLICT, "Job is not waiting for Actions verification.");
        }
        workflowConfig.resumeVerification(jobAlias);
        return ResponseEntity.accepted().body(Map.of(
                "jobAlias", jobAlias,
                "status", "VERIFYING"));
    }

    @PostMapping("/jobs/{jobAlias}/pull-request")
    public ResponseEntity<Map<String, Object>> decidePullRequest(
            @PathVariable String jobAlias,
            @RequestHeader("X-User-Identity") String userIdentity,
            @RequestBody(required = false) Map<String, Boolean> payload) {

        SdlcState state = workflowConfig.getJobState(jobAlias);
        if (!isOwnedBy(state, userIdentity)) {
            return ResponseEntity.notFound().build();
        }
        if (payload == null || payload.get("approved") == null) {
            return error(HttpStatus.BAD_REQUEST, "Approval decision is required.");
        }
        if (!"AWAITING_APPROVAL".equals(state.getTaskStatus())) {
            return error(HttpStatus.CONFLICT, "Job is not waiting for pull request approval.");
        }

        boolean approved = payload.get("approved");
        workflowConfig.decidePullRequest(jobAlias, approved);
        if (!approved) {
            return ResponseEntity.ok(Map.of("jobAlias", jobAlias, "status", "REJECTED"));
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobAlias", jobAlias,
                "status", "CREATING_PULL_REQUEST"
        ));
    }

    private static boolean isValidIdentity(String userIdentity) {
        return userIdentity != null
                && !userIdentity.isBlank()
                && userIdentity.length() <= 254
                && userIdentity.chars().noneMatch(Character::isISOControl);
    }

    private static boolean isValidRepositoryId(String repositoryId) {
        if (repositoryId == null || !REPOSITORY_ID.matcher(repositoryId).matches()) {
            return false;
        }
        String[] segments = repositoryId.split("/", -1);
        return !".".equals(segments[0]) && !"..".equals(segments[0])
                && !".".equals(segments[1]) && !"..".equals(segments[1]);
    }

    private static boolean isOwnedBy(SdlcState state, String userIdentity) {
        return state != null && isValidIdentity(userIdentity) && state.getUserIdentity().equals(userIdentity);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}