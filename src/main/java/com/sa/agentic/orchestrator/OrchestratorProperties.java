package com.sa.agentic.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        String codebaseRoot,
        String workspaceRoot,
        int maxParallelTasks,
        int maxReplansPerRun,
        int maxTasksPerRun,
        long retryBackoffMillis,
        long agentThinkMillis,
        long taskTimeoutSeconds,
        String smokeBaseUrl,
        String apiKey) {

    public OrchestratorProperties {
        if (maxParallelTasks <= 0) {
            maxParallelTasks = 4;
        }
        if (taskTimeoutSeconds <= 0) {
            taskTimeoutSeconds = 60;
        }
    }
}
