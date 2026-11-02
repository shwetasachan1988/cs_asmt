package com.sa.agentic.orchestrator.scenario;

import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.Risk;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Well-defined, new capability: the whole DAG is known up front, so it is declared statically. */
@Component
public class GreenfieldBulkScenario implements ScenarioDefinition {

    @Override
    public String id() {
        return "greenfield-bulk-links";
    }

    @Override
    public String title() {
        return "Greenfield - bulk link creation API";
    }

    @Override
    public Kind kind() {
        return Kind.GREENFIELD;
    }

    @Override
    public String description() {
        return "A new endpoint built from scratch with a precise requirement. Shows full SDLC decomposition, parallel "
                + "tracks (implementation || documentation; unit || contract || security || integration tests), a join at "
                + "release readiness, and two human checkpoints (design sign-off, release go/no-go).";
    }

    @Override
    public String defaultRequirement() {
        return "Add a bulk API to create up to 100 short links in one call. Each item must be validated independently "
                + "with a per-item result, and a single bad URL must not fail the whole batch. The existing rate limit "
                + "of 60 creates per minute per client must count every item. p95 latency for a 100-item batch must "
                + "stay under 500 ms.";
    }

    @Override
    public String planRationale() {
        return "Requirement is measurable (100 items, 60/min, 500 ms), so no clarification is needed. Documentation depends "
                + "only on the design, hence runs in parallel with implementation. Every test track fans out independently "
                + "and release readiness joins all evidence. HIGH-risk release packaging requires a prior human approval.";
    }

    @Override
    public List<TaskSpec> initialPlan(String requirement) {
        List<TaskSpec> plan = new ArrayList<>();
        plan.add(TaskSpec.of("intake", Stage.REQUIREMENTS, "requirements-analyst")
                .title("Understand and normalize the requirement").produces("requirements-spec").build());
        plan.add(TaskSpec.of("design", Stage.DESIGN, "architect").title("Design and API contract")
                .after("intake").risk(Risk.MEDIUM).approval(ApprovalMode.AFTER).param("feature", "bulk")
                .produces("design-doc", "api-spec").build());
        plan.add(TaskSpec.of("impl-core", Stage.IMPLEMENTATION, "codegen").title("Implement bulk planning logic")
                .after("design").param("module", "bulk").rollbackOnFailure()
                .produces("source", "dependency-manifest").build());
        plan.addAll(SdlcPlans.verificationAndRelease("bulk", "design", List.of("impl-core"), List.of()));
        return plan;
    }

    @Override
    public Map<String, Integer> transientFailures() {
        return Map.of("unit-tests", 1, "integration-tests", 1);
    }

    @Override
    public Map<String, Integer> permanentFailures() {
        return Map.of("integration-tests", -1);
    }
}
