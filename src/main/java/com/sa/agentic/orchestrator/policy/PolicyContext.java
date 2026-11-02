package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.WorkflowRun;

public record PolicyContext(WorkflowRun run, TaskSpec spec, TaskState state) {
}
