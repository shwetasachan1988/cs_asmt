package com.sa.agentic.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

/** Raw reliability signals recorded during a run; aggregated by MetricsService. */
public class RunCounters {
    public int taskExecutions;
    public int taskFailures;
    public int retries;
    public int fallbacks;
    public int rollbacks;
    public int rejections;
    public int policyBlocks;
    /** Every re-planning event: agent-proposed plan changes, human re-opens and upstream-change invalidations. */
    public int replans;
    /** Agent-proposed plan changes only - this is what the autonomy budget bounds. */
    public int planChanges;
    public int invalidations;
    /** Time from a task's first failure to its eventual success (MTTR samples). */
    public List<Long> recoveryMillis = new ArrayList<>();

    public RunCounters() {
    }
}
