package com.sa.agentic.orchestrator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate root of one orchestrated execution: the live plan (DAG), per-task state and the shared context. */
public class WorkflowRun {
    public String id;
    public String scenario;
    public String requirement;
    public RunStatus status = RunStatus.RUNNING;
    public long version;
    public int planVersion = 1;
    public List<TaskSpec> plan = new ArrayList<>();
    public Map<String, TaskState> tasks = new LinkedHashMap<>();
    /** Cross-stage blackboard: facts agents publish for downstream stages (never secrets). */
    public Map<String, Object> context = new LinkedHashMap<>();
    /** Failure injection: taskId -> number of primary-agent attempts to fail (-1 = always). */
    public Map<String, Integer> chaos = new LinkedHashMap<>();
    public RunCounters counters = new RunCounters();
    public long approvalWaitMillis;
    public String safeStopReason;
    public Instant createdAt;
    public Instant finishedAt;

    public WorkflowRun() {
    }

    public TaskSpec spec(String taskId) {
        return plan.stream().filter(t -> t.id().equals(taskId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown task " + taskId));
    }
}
