package com.sa.agentic.orchestrator.model;

import java.util.List;
import java.util.Map;

/**
 * An agent proposes a change to the plan. The engine - not the agent - validates it (acyclic, bounded,
 * governed) and records the decision. Agents never mutate the DAG directly.
 */
public record ReplanRequest(List<TaskSpec> addTasks, List<String> removeTaskIds,
                            Map<String, List<String>> addDependencies, String rationale) {

    public static ReplanRequest add(String rationale, List<TaskSpec> tasks, Map<String, List<String>> rewire) {
        return new ReplanRequest(tasks, List.of(), rewire, rationale);
    }
}
