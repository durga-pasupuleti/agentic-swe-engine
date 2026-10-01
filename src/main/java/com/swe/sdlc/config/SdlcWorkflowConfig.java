package com.swe.sdlc.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.context.annotation.Configuration;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.model.SdlcState;

/**
 * Configuration class for managing the SDLC workflow.
 * what we are addressing here is the management of job states and the execution flow of the SDLC pipeline.
 * 
 */
@Configuration
public class SdlcWorkflowConfig {
    private final Map<String, SdlcState> jobRegistry = new ConcurrentHashMap<>();
    private final TaskExecutor workflowExecutor;
    private final OllamaModelCatalog modelCatalog;
    private final OllamaModelService ollamaModelService;

    public SdlcWorkflowConfig(
            @Qualifier("workflowExecutor") TaskExecutor workflowExecutor,
            OllamaModelCatalog modelCatalog,
            OllamaModelService ollamaModelService) {
        this.workflowExecutor = workflowExecutor;
        this.modelCatalog = modelCatalog;
        this.ollamaModelService = ollamaModelService;
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

    public void runDiscoveryPipelineAsync(String jobAlias) {
        SdlcState state = requireJobState(jobAlias);
        workflowExecutor.execute(() -> state.setTaskStatus("PAUSED_AT_ENTRY_GATE"));
    }

    public void resumeExecutionTrack(String jobAlias, String mode) {
        if (!"SPEC_DRIVEN".equals(mode) && !"DIRECT_CODE".equals(mode)) {
            throw new IllegalArgumentException("Unsupported execution mode: " + mode);
        }

        SdlcState state = requireJobState(jobAlias);
        if (!"PAUSED_AT_ENTRY_GATE".equals(state.getTaskStatus())) {
            throw new IllegalStateException("Job is not waiting at the execution mode gate");
        }

        state.setExecutionMode(mode);
        state.setTaskStatus("RUNNING");
        workflowExecutor.execute(() -> {
            if (!"DIRECT_CODE".equals(mode)) {
                state.setCompilerLogs("Spec-driven execution is not implemented yet.");
                state.setTaskStatus("NOT_IMPLEMENTED");
                return;
            }

            try {
                String patch = ollamaModelService.generatePatch(state.getRawRequirement(), state.getModelAlias());
                state.setGeneratedCodePatch(patch);
                state.setCompilerLogs("Model response ready; repository changes and build validation are not implemented.");
                state.setTaskStatus("MODEL_RESPONSE_READY");
            } catch (RuntimeException exception) {
                state.setCompilerLogs("Model generation failed (" + exception.getClass().getSimpleName() + ").");
                state.setTaskStatus("FAILED");
            }
        });
    }

    private SdlcState requireJobState(String jobAlias) {
        SdlcState state = jobRegistry.get(jobAlias);
        if (state == null) {
            throw new IllegalArgumentException("Unknown job alias: " + jobAlias);
        }
        return state;
    }
}