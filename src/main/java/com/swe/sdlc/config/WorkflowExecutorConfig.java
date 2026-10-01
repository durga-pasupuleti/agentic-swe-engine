package com.swe.sdlc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
public class WorkflowExecutorConfig {
    @Bean("workflowExecutor")
    public SimpleAsyncTaskExecutor workflowExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("sdlc-workflow-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(10);
        return executor;
    }
}