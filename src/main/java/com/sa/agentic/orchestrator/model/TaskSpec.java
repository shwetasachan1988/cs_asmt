package com.sa.agentic.orchestrator.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable definition of one node in the workflow DAG, including its governance envelope:
 * dependencies (entry), required artifacts (exit), approval mode, retry budget, fallback agent and rollback behaviour.
 */
public record TaskSpec(
        String id,
        String title,
        Stage stage,
        String agent,
        List<String> dependsOn,
        Risk risk,
        ApprovalMode approval,
        List<String> requiredArtifacts,
        int maxRetries,
        String fallbackAgent,
        boolean rollbackOnFailure,
        Map<String, String> params) {

    public TaskSpec {
        dependsOn = List.copyOf(dependsOn);
        requiredArtifacts = List.copyOf(requiredArtifacts);
        params = Map.copyOf(params);
    }

    public static Builder of(String id, Stage stage, String agent) {
        return new Builder(id, stage, agent);
    }

    public TaskSpec withExtraDependencies(List<String> extra) {
        List<String> merged = new ArrayList<>(dependsOn);
        extra.stream().filter(d -> !merged.contains(d)).forEach(merged::add);
        return new TaskSpec(id, title, stage, agent, merged, risk, approval, requiredArtifacts, maxRetries,
                fallbackAgent, rollbackOnFailure, params);
    }

    public static final class Builder {
        private final String id;
        private final Stage stage;
        private final String agent;
        private String title;
        private final List<String> dependsOn = new ArrayList<>();
        private Risk risk = Risk.LOW;
        private ApprovalMode approval = ApprovalMode.NONE;
        private final List<String> required = new ArrayList<>();
        private int maxRetries = 2;
        private String fallback;
        private boolean rollback;
        private final Map<String, String> params = new LinkedHashMap<>();

        private Builder(String id, Stage stage, String agent) {
            this.id = id;
            this.stage = stage;
            this.agent = agent;
            this.title = id;
        }

        public Builder title(String t) { this.title = t; return this; }
        public Builder after(String... ids) { dependsOn.addAll(List.of(ids)); return this; }
        public Builder risk(Risk r) { this.risk = r; return this; }
        public Builder approval(ApprovalMode a) { this.approval = a; return this; }
        public Builder produces(String... types) { required.addAll(List.of(types)); return this; }
        public Builder retries(int n) { this.maxRetries = n; return this; }
        public Builder fallback(String agentName) { this.fallback = agentName; return this; }
        public Builder rollbackOnFailure() { this.rollback = true; return this; }
        public Builder param(String k, String v) { params.put(k, v); return this; }

        public TaskSpec build() {
            return new TaskSpec(id, title, stage, agent, dependsOn, risk, approval, required, maxRetries, fallback,
                    rollback, params);
        }
    }
}
