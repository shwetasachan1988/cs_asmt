package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import java.util.List;

/**
 * A governance rule evaluated at three points: when a task is added to the plan, before an agent starts
 * (entry gate) and after it finishes (exit gate). Policies are code, not agent output - agents cannot waive them.
 */
public interface Policy {

    String id();

    default List<Violation> checkPlan(WorkflowRun run, TaskSpec spec) {
        return List.of();
    }

    default List<Violation> checkEntry(PolicyContext ctx) {
        return List.of();
    }

    default List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        return List.of();
    }
}
