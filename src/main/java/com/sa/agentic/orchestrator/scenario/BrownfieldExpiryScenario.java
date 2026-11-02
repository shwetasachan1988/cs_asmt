package com.sa.agentic.orchestrator.scenario;

import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.Risk;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Enhancement of the existing service. The codebase-analysis task scans the real source tree and the plan grows
 * from its evidence (cache TTL work, analytics regression); the schema change is HIGH risk and therefore gated.
 */
@Component
public class BrownfieldExpiryScenario implements ScenarioDefinition {

    @Override
    public String id() {
        return "brownfield-link-expiry";
    }

    @Override
    public String title() {
        return "Brownfield - link expiration (TTL) on the existing service";
    }

    @Override
    public Kind kind() {
        return Kind.BROWNFIELD;
    }

    @Override
    public String description() {
        return "Modify a live system: scan the real code for impacted modules and data flows, let the evidence extend the "
                + "plan, change the schema behind an approval with a verified rollback script, then regress the live API.";
    }

    @Override
    public String defaultRequirement() {
        return "Short links must support an optional expiry timestamp. After expiry the redirect must return 410 Gone, "
                + "cached entries must not outlive the expiry, and existing click history must be preserved. Existing "
                + "links without an expiry must keep working unchanged.";
    }

    @Override
    public String planRationale() {
        return "Brownfield work starts with evidence, not design: codebase-analysis runs first and its findings (cache and "
                + "analytics coupling) add tasks to the plan. The schema change is HIGH risk, so it needs human review and a "
                + "rollback script that is executed on a scratch schema before it is trusted. Migration and code tracks run "
                + "in parallel and join at release readiness.";
    }

    @Override
    public List<TaskSpec> initialPlan(String requirement) {
        List<TaskSpec> plan = new ArrayList<>();
        plan.add(TaskSpec.of("intake", Stage.REQUIREMENTS, "requirements-analyst")
                .title("Understand the requirement").produces("requirements-spec").build());
        plan.add(TaskSpec.of("codebase-analysis", Stage.REQUIREMENTS, "codebase-analyst")
                .title("Scan the code: impacted modules, APIs and data flows").after("intake")
                .produces("impact-report").build());
        plan.add(TaskSpec.of("design", Stage.DESIGN, "architect").title("Delta design grounded in the impact report")
                .after("codebase-analysis").risk(Risk.MEDIUM).approval(ApprovalMode.AFTER).param("feature", "expiry")
                .produces("design-doc", "api-spec").build());
        plan.add(TaskSpec.of("migration", Stage.IMPLEMENTATION, "migration-author")
                .title("Schema migration V3 + rollback script").after("design").risk(Risk.HIGH)
                .approval(ApprovalMode.AFTER).param("feature", "expiry").param("schemaChange", "true")
                .rollbackOnFailure().produces("sql", "rollback-script").build());
        plan.add(TaskSpec.of("migration-validate", Stage.TESTING, "migration-validator")
                .title("Execute migration and rollback on a scratch schema").after("migration")
                .param("expectColumn", "short_link.expires_at").rollbackOnFailure().retries(2)
                .produces("test-report").build());
        plan.add(TaskSpec.of("impl-expiry", Stage.IMPLEMENTATION, "codegen").title("Implement expiry rules")
                .after("design").param("module", "expiry").rollbackOnFailure()
                .produces("source", "dependency-manifest").build());
        plan.addAll(SdlcPlans.verificationAndRelease("expiry", "design", List.of("impl-expiry"),
                List.of("migration-validate")));
        return plan;
    }

    @Override
    public Map<String, Integer> transientFailures() {
        return Map.of("migration-validate", 1, "unit-tests", 1);
    }

    @Override
    public Map<String, Integer> permanentFailures() {
        return Map.of("integration-tests", -1);
    }
}
