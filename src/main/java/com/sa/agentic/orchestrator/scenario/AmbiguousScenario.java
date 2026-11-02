package com.sa.agentic.orchestrator.scenario;

import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Vague requirement: only the first step is known. The plan is discovered at run time - clarification first
 * (human answers), then a graph shaped by the answers - so the DAG genuinely changes during execution.
 */
@Component
public class AmbiguousScenario implements ScenarioDefinition {

    @Override
    public String id() {
        return "ambiguous-safer-faster-tracking";
    }

    @Override
    public String title() {
        return "Ambiguous - 'safer, faster, and see who clicks'";
    }

    @Override
    public Kind kind() {
        return Kind.AMBIGUOUS;
    }

    @Override
    public String description() {
        return "A one-line wish with no measurable meaning. The system detects the ambiguity, asks the stakeholder, "
                + "records documented assumptions, then builds a plan for exactly the interpretation chosen - while policies "
                + "stop an unapproved SDK and raw-IP storage that the first implementation attempt proposes.";
    }

    @Override
    public String defaultRequirement() {
        return "Make our short links safer and faster, and give marketing a way to see who is clicking them.";
    }

    @Override
    public String planRationale() {
        return "Planning on an ambiguous requirement would bake guesses into the DAG. Only 'intake' is planned; its result "
                + "decides whether a clarification checkpoint is inserted, and the answers decide which implementation tracks "
                + "exist. Changing an answer later invalidates dependants and revokes their approvals.";
    }

    @Override
    public List<TaskSpec> initialPlan(String requirement) {
        return List.of(TaskSpec.of("intake", Stage.REQUIREMENTS, "requirements-analyst")
                .title("Analyze requirement and detect ambiguity").param("adaptivePlanning", "true")
                .produces("requirements-spec").build());
    }

    @Override
    public Map<String, Integer> transientFailures() {
        return Map.of("unit-tests", 1);
    }

    @Override
    public Map<String, Integer> permanentFailures() {
        return Map.of("integration-tests", -1);
    }
}
