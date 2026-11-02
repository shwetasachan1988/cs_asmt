package com.sa.agentic.orchestrator.metrics;

import com.sa.agentic.orchestrator.model.RunStatus;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.persistence.RunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Reliability metrics derived from the persisted run state (the same data the audit trail explains), so the numbers
 * can always be traced back to events: success rate, retry/fallback/rollback frequency, MTTR and end-to-end latency.
 */
@Service
public class MetricsService {

    private final RunRepository runs;

    public MetricsService(RunRepository runs) {
        this.runs = runs;
    }

    public Map<String, Object> summary() {
        return summarize(runs.findAll());
    }

    public static Map<String, Object> summarize(List<WorkflowRun> all) {
        List<WorkflowRun> terminal = all.stream().filter(r -> r.status.isTerminal()).toList();
        long succeeded = terminal.stream().filter(r -> r.status == RunStatus.SUCCEEDED).count();
        int executions = all.stream().mapToInt(r -> r.counters.taskExecutions).sum();
        int failures = all.stream().mapToInt(r -> r.counters.taskFailures).sum();
        int retries = all.stream().mapToInt(r -> r.counters.retries).sum();
        int rollbacks = all.stream().mapToInt(r -> r.counters.rollbacks).sum();
        long runsWithRollback = all.stream().filter(r -> r.counters.rollbacks > 0).count();
        List<Long> mttr = all.stream().flatMap(r -> r.counters.recoveryMillis.stream()).toList();
        List<Long> latency = terminal.stream().map(MetricsService::latencyMillis).sorted().toList();
        List<Long> active = terminal.stream().map(r -> Math.max(0, latencyMillis(r) - r.approvalWaitMillis)).sorted().toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", Map.of("total", all.size(), "succeeded", succeeded, "failed", terminal.size() - succeeded,
                "inProgress", all.stream().filter(r -> r.status == RunStatus.RUNNING || r.status == RunStatus.AWAITING_APPROVAL).count(),
                "safeStopped", all.stream().filter(r -> r.status == RunStatus.SAFE_STOPPED).count()));
        out.put("runSuccessRate", ratio(succeeded, terminal.size()));
        out.put("taskSuccessRate", executions == 0 ? null : round(1.0 - (double) failures / executions));
        out.put("taskExecutions", executions);
        out.put("taskFailures", failures);
        out.put("retries", retries);
        out.put("retryRate", ratio(retries, executions));
        out.put("fallbacks", all.stream().mapToInt(r -> r.counters.fallbacks).sum());
        out.put("rollbacks", rollbacks);
        out.put("rollbackFrequency", ratio(runsWithRollback, all.size()));
        out.put("policyBlocks", all.stream().mapToInt(r -> r.counters.policyBlocks).sum());
        out.put("replans", all.stream().mapToInt(r -> r.counters.replans).sum());
        out.put("humanRejections", all.stream().mapToInt(r -> r.counters.rejections).sum());
        out.put("mttrMillis", mttr.isEmpty() ? null : Math.round(mttr.stream().mapToLong(Long::longValue).average().orElse(0)));
        out.put("mttrSamples", mttr.size());
        out.put("endToEndLatencyMillis", stats(latency));
        out.put("activeLatencyMillis", stats(active));
        out.put("byScenario", all.stream().collect(Collectors.groupingBy(r -> r.scenario, LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), MetricsService::scenarioRow))));
        return out;
    }

    /** Per-run view used by the dashboard and the final report. */
    public static Map<String, Object> forRun(WorkflowRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        long latency = latencyMillis(r);
        // include approvals still pending, so a run parked at a checkpoint does not look like slow agent work
        long pendingWait = r.tasks.values().stream().filter(t -> t.awaitingApprovalSince != null)
                .mapToLong(t -> Duration.between(t.awaitingApprovalSince, Instant.now()).toMillis()).sum();
        long humanWait = r.approvalWaitMillis + pendingWait;
        m.put("endToEndMillis", latency);
        m.put("humanWaitMillis", humanWait);
        m.put("activeMillis", Math.max(0, latency - humanWait));
        m.put("taskExecutions", r.counters.taskExecutions);
        m.put("taskFailures", r.counters.taskFailures);
        m.put("retries", r.counters.retries);
        m.put("fallbacks", r.counters.fallbacks);
        m.put("rollbacks", r.counters.rollbacks);
        m.put("policyBlocks", r.counters.policyBlocks);
        m.put("replans", r.counters.replans);
        m.put("invalidations", r.counters.invalidations);
        m.put("mttrMillis", r.counters.recoveryMillis.isEmpty() ? null
                : Math.round(r.counters.recoveryMillis.stream().mapToLong(Long::longValue).average().orElse(0)));
        return m;
    }

    private static Map<String, Object> scenarioRow(List<WorkflowRun> rs) {
        long ok = rs.stream().filter(r -> r.status == RunStatus.SUCCEEDED).count();
        long done = rs.stream().filter(r -> r.status.isTerminal()).count();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runs", rs.size());
        row.put("successRate", ratio(ok, done));
        return row;
    }

    private static long latencyMillis(WorkflowRun r) {
        Instant end = r.finishedAt != null ? r.finishedAt : Instant.now();
        return Duration.between(r.createdAt, end).toMillis();
    }

    private static Map<String, Object> stats(List<Long> sorted) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (sorted.isEmpty()) {
            m.put("count", 0);
            return m;
        }
        m.put("count", sorted.size());
        m.put("avg", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
        m.put("p50", percentile(sorted, 50));
        m.put("p95", percentile(sorted, 95));
        m.put("max", sorted.get(sorted.size() - 1));
        return m;
    }

    public static long percentile(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static Double ratio(long num, long den) {
        return den == 0 ? null : round((double) num / den);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
