package com.sa.agentic.orchestrator.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable runtime state of a task. Persistence-shaped (public fields, JSON snapshot) and mutated
 * only by the engine while it holds the per-run lock.
 */
public class TaskState {
    public String id;
    public TaskStatus status = TaskStatus.PENDING;
    public int attempts;
    public int reworks;
    public boolean onFallback;
    public String agentUsed;
    /** Bumped whenever in-flight work becomes obsolete; a result carrying an old generation is discarded. */
    public long generation;
    public Instant startedAt;
    public Instant finishedAt;
    public Instant firstFailureAt;
    public Instant awaitingApprovalSince;
    public Instant notBefore;
    public long agentMillis;
    public String lastError;
    public String staleReason;
    public String inputHash;
    public String outputHash;
    /** Hash of what downstream work may rely on (artifacts + human input); a change re-plans dependants. */
    public String committedHash;
    public List<String> artifactIds = new ArrayList<>();
    /** Lineage: ids of upstream artifacts this task consumed on its latest execution. */
    public List<String> consumedArtifactIds = new ArrayList<>();
    public ApprovalRecord approval;
    public String summary;
    public String rationale;
    /** Validations the agent performed on its latest successful execution (gate evidence). */
    public List<Check> checks = new ArrayList<>();
    public List<String> lastViolations = new ArrayList<>();

    public TaskState() {
    }

    public TaskState(String id) {
        this.id = id;
    }
}
