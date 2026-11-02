package com.sa.agentic.orchestrator.engine;

import com.sa.agentic.orchestrator.OrchestratorProperties;
import com.sa.agentic.orchestrator.agent.AgentRegistry;
import com.sa.agentic.orchestrator.model.ReplanRequest;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.persistence.AuditLog;
import com.sa.agentic.orchestrator.policy.PolicyEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Applies plan changes under governance. Agents only <em>propose</em> ({@link ReplanRequest}); this class validates
 * (known agents, acyclic, bounded, policy-compliant, only touches work that has not started) and records the decision.
 * It also performs the other kind of re-plan: invalidating dependants when an upstream output changed.
 */
@Component
public class PlanMutator {

    public record Outcome(boolean applied, boolean budgetExceeded, String error) {
        static Outcome ok() {
            return new Outcome(true, false, null);
        }

        static Outcome rejected(String why) {
            return new Outcome(false, false, why);
        }

        static Outcome budget(String why) {
            return new Outcome(false, true, why);
        }
    }

    private final PolicyEngine policies;
    private final AgentRegistry agents;
    private final AuditLog audit;
    private final OrchestratorProperties props;

    public PlanMutator(PolicyEngine policies, AgentRegistry agents, AuditLog audit, OrchestratorProperties props) {
        this.policies = policies;
        this.agents = agents;
        this.audit = audit;
        this.props = props;
    }

    public Outcome applyReplan(WorkflowRun run, String requestedBy, ReplanRequest req) {
        List<TaskSpec> adds = new ArrayList<>();
        for (TaskSpec t : req.addTasks()) {
            if (!run.tasks.containsKey(t.id())) { // idempotent: a re-executed agent re-proposes the same tasks
                adds.add(t);
            }
        }
        // idempotent: only dependencies that are not already wired count as a change
        Map<String, List<String>> rewire = new LinkedHashMap<>();
        if (req.addDependencies() != null) {
            req.addDependencies().forEach((taskId, deps) -> {
                TaskSpec existing = run.plan.stream().filter(p -> p.id().equals(taskId)).findFirst().orElse(null);
                List<String> fresh = deps.stream()
                        .filter(d -> existing == null || !existing.dependsOn().contains(d)).toList();
                if (!fresh.isEmpty()) {
                    rewire.put(taskId, fresh);
                }
            });
        }
        List<String> removals = req.removeTaskIds() == null ? List.of() : req.removeTaskIds().stream()
                .filter(id -> run.tasks.containsKey(id) && run.tasks.get(id).status != TaskStatus.CANCELLED).toList();

        if (adds.isEmpty() && removals.isEmpty() && rewire.isEmpty()) {
            return Outcome.ok();
        }
        if (run.counters.planChanges + 1 > props.maxReplansPerRun()) {
            return Outcome.budget("re-plan budget exhausted (" + props.maxReplansPerRun() + ")");
        }
        if (run.plan.size() + adds.size() > props.maxTasksPerRun()) {
            return Outcome.budget("task budget exhausted (" + props.maxTasksPerRun() + ")");
        }

        List<TaskSpec> tentative = new ArrayList<>(run.plan);
        for (TaskSpec t : adds) {
            if (!agents.has(t.agent()) || (t.fallbackAgent() != null && !agents.has(t.fallbackAgent()))) {
                return Outcome.rejected("task " + t.id() + " references an unregistered agent");
            }
            List<Violation> v = policies.checkPlan(run, t);
            if (!v.isEmpty()) {
                v.forEach(x -> logViolation(run, requestedBy, t.id(), x));
                return Outcome.rejected(v.get(0).message());
            }
            tentative.add(t);
        }
        for (Map.Entry<String, List<String>> e : rewire.entrySet()) {
            int idx = indexOf(tentative, e.getKey());
            if (idx < 0) {
                return Outcome.rejected("cannot rewire unknown task " + e.getKey());
            }
            TaskState st = run.tasks.get(e.getKey());
            if (st != null && st.status != TaskStatus.PENDING) {
                return Outcome.rejected("cannot add dependencies to " + e.getKey() + " - already " + st.status);
            }
            tentative.set(idx, tentative.get(idx).withExtraDependencies(e.getValue()));
        }
        for (String id : removals) {
            TaskState st = run.tasks.get(id);
            if (st == null || st.status != TaskStatus.PENDING || st.attempts > 0) {
                return Outcome.rejected("cannot remove task " + id + " - unknown or already started");
            }
        }
        try {
            DagValidator.validate(tentative);
        } catch (IllegalArgumentException e) {
            return Outcome.rejected(e.getMessage());
        }

        run.plan.clear();
        run.plan.addAll(tentative);
        adds.forEach(t -> run.tasks.put(t.id(), new TaskState(t.id())));
        removals.forEach(id -> {
            run.tasks.get(id).status = TaskStatus.CANCELLED;
            run.tasks.get(id).staleReason = "pruned by re-plan from " + requestedBy;
        });
        run.planVersion++;
        run.counters.replans++;
        run.counters.planChanges++;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestedBy", requestedBy);
        detail.put("planVersion", run.planVersion);
        detail.put("added", adds.stream().map(TaskSpec::id).toList());
        detail.put("removed", removals);
        detail.put("rewired", rewire);
        detail.put("rationale", req.rationale());
        audit.append(run.id, "orchestrator", "REPLAN", requestedBy, detail);
        return Outcome.ok();
    }

    /**
     * Upstream output changed: every started dependant is reset to PENDING, its approvals are revoked and any
     * in-flight result is orphaned (generation bump). Work that has not started is left alone.
     */
    public List<String> invalidateDownstream(WorkflowRun run, String changedTaskId, String reason) {
        Set<String> downstream = DagValidator.downstreamOf(run.plan, changedTaskId);
        List<String> affected = new ArrayList<>();
        for (String id : downstream) {
            TaskState st = run.tasks.get(id);
            boolean untouched = st.status == TaskStatus.PENDING && st.attempts == 0 && st.staleReason == null;
            if (untouched || st.status == TaskStatus.CANCELLED || st.status == TaskStatus.FAILED
                    || st.status == TaskStatus.ROLLED_BACK) {
                continue;
            }
            st.generation++;
            st.status = TaskStatus.PENDING;
            st.attempts = 0;
            st.reworks = 0;
            st.onFallback = false;
            st.approval = null;
            st.notBefore = null;
            st.awaitingApprovalSince = null;
            st.finishedAt = null;
            st.firstFailureAt = null;
            st.lastError = null;
            st.staleReason = reason;
            affected.add(id);
        }
        if (!affected.isEmpty()) {
            run.counters.invalidations += affected.size();
            run.counters.replans++;
            audit.append(run.id, "orchestrator", "UPSTREAM_CHANGED", changedTaskId,
                    Map.of("reason", reason, "invalidated", affected,
                            "note", "approvals revoked; dependants re-execute with the new upstream output"));
        }
        return affected;
    }

    private void logViolation(WorkflowRun run, String actor, String nodeId, Violation v) {
        audit.append(run.id, "policy-engine", "POLICY_VIOLATION", nodeId,
                Map.of("policy", v.policyId(), "severity", v.severity().name(), "message", v.message(),
                        "phase", "plan", "requestedBy", actor));
    }

    private static int indexOf(List<TaskSpec> plan, String id) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
