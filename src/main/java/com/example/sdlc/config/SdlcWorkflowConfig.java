package com.example.sdlc.config;

import com.example.sdlc.model.SdlcState;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class SdlcWorkflowConfig {
    private final Map<String, SdlcState> jobRegistry = new ConcurrentHashMap<>();

    public void registerJob(SdlcState state) {
        jobRegistry.put(state.getJobAlias(), state);
    }

    public SdlcState getJobState(String jobAlias) {
        return jobRegistry.get(jobAlias);
    }

    public void runDiscoveryPipelineAsync(String jobAlias) {
        throw new UnsupportedOperationException("Discovery pipeline is not implemented yet");
    }

    public void resumeExecutionTrack(String jobAlias, String mode) {
        throw new UnsupportedOperationException("Workflow execution is not implemented yet");
    }
}