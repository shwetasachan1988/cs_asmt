package com.sa.agentic.orchestrator.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentResult(String summary, String rationale, List<ArtifactDraft> artifacts, List<Check> checks,
                          Map<String, Object> contextUpdates, List<ReplanRequest> replans) {

    public static Builder builder(String summary) {
        return new Builder(summary);
    }

    public static final class Builder {
        private final String summary;
        private String rationale = "";
        private final List<ArtifactDraft> artifacts = new ArrayList<>();
        private final List<Check> checks = new ArrayList<>();
        private final Map<String, Object> context = new LinkedHashMap<>();
        private final List<ReplanRequest> replans = new ArrayList<>();

        private Builder(String summary) {
            this.summary = summary;
        }

        public Builder rationale(String r) { this.rationale = r; return this; }

        public Builder artifact(String type, String name, String content) {
            artifacts.add(new ArtifactDraft(type, name, content));
            return this;
        }

        public Builder check(Check c) { checks.add(c); return this; }
        public Builder context(String k, Object v) { context.put(k, v); return this; }
        public Builder replan(ReplanRequest r) { replans.add(r); return this; }

        public AgentResult build() {
            return new AgentResult(summary, rationale, artifacts, checks, context, replans);
        }
    }
}
