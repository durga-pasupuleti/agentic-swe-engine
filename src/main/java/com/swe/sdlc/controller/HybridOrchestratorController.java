package com.swe.sdlc.controller;

import com.swe.sdlc.config.SdlcWorkflowConfig;
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

    public HybridOrchestratorController(SdlcWorkflowConfig workflowConfig) {
        this.workflowConfig = workflowConfig;
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> triggerSdlcJob(
            @RequestHeader("X-User-Identity") String userIdentity,
            @RequestHeader("X-VCS-Token") String vcsToken,
            @RequestBody(required = false) Map<String, String> payload) {

        if (!isValidIdentity(userIdentity) || !isValidToken(vcsToken) || payload == null) {
            return error(HttpStatus.BAD_REQUEST, "Missing or invalid request credentials or body.");
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

        SdlcState newState = new SdlcState(requirement, repositoryId, jobAlias, userIdentity, vcsToken);
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
        status.put("specsDetectedInStorage", state.isSpecsAvailableInStorage());
        status.put("localRepositoryFileCount", state.getEstimatedRepoSizeFiles());
        status.put("executionMode", Objects.toString(state.getExecutionMode(), "UNSET"));
        status.put("status", Objects.toString(state.getTaskStatus(), "UNKNOWN"));
        status.put("compilerLogs", Objects.toString(state.getCompilerLogs(), ""));
        status.put("retryCount", state.getRetryCount());
        status.put("patchContent", Objects.toString(state.getGeneratedCodePatch(), ""));
        return ResponseEntity.ok(status);
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

    private static boolean isValidIdentity(String userIdentity) {
        return userIdentity != null
                && !userIdentity.isBlank()
                && userIdentity.length() <= 254
                && userIdentity.chars().noneMatch(Character::isISOControl);
    }

    private static boolean isValidToken(String token) {
        return token != null
                && !token.isBlank()
                && token.length() <= 8192
                && token.chars().noneMatch(Character::isWhitespace);
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