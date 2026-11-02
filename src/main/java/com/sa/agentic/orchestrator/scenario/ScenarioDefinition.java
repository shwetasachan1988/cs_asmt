package com.sa.agentic.orchestrator.scenario;

import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.List;
import java.util.Map;

/** A named requirement archetype: how it is decomposed into an initial DAG and which failures the demo can inject. */
public interface ScenarioDefinition {

    enum Kind { GREENFIELD, BROWNFIELD, AMBIGUOUS }

    String id();

    String title();

    Kind kind();

    String description();

    /** Requirement text used when the caller does not supply one. */
    String defaultRequirement();

    /** Why the DAG has the shape it has; recorded as the first decision in the lineage. */
    String planRationale();

    /** The DAG known up-front. Dynamic scenarios return a skeleton and let agents propose the rest. */
    List<TaskSpec> initialPlan(String requirement);

    /** Failures the retry budget absorbs: taskId -> primary-agent attempts to fail. */
    Map<String, Integer> transientFailures();

    /** A failure no retry fixes (-1 = always): drives fallback/rollback/safe-stop behaviour. */
    Map<String, Integer> permanentFailures();
}
