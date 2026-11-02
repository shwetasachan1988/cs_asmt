package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Runs every registered {@link Policy}; the engine decides what each severity means. */
@Component
public class PolicyEngine {

    private final List<Policy> policies;

    public PolicyEngine(List<Policy> policies) {
        this.policies = List.copyOf(policies);
    }

    public List<String> policyIds() {
        return policies.stream().map(Policy::id).toList();
    }

    public List<Violation> checkPlan(WorkflowRun run, TaskSpec spec) {
        List<Violation> out = new ArrayList<>();
        policies.forEach(p -> out.addAll(p.checkPlan(run, spec)));
        return out;
    }

    public List<Violation> checkEntry(PolicyContext ctx) {
        List<Violation> out = new ArrayList<>();
        policies.forEach(p -> out.addAll(p.checkEntry(ctx)));
        return out;
    }

    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        List<Violation> out = new ArrayList<>();
        policies.forEach(p -> out.addAll(p.checkExit(ctx, result)));
        return out;
    }

    public static boolean hasSeverity(List<Violation> violations, Violation.Severity s) {
        return violations.stream().anyMatch(v -> v.severity() == s);
    }
}
