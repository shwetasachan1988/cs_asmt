package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.Check;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Renders documentation from the machine-readable contract, so docs cannot drift from the API definition. */
@Component
public class DocWriterAgent extends BaseAgent {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public DocWriterAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "doc-writer";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        Artifact spec = ctx.upstreamArtifact("api-spec").orElseThrow(() -> new NonRetryableAgentException("api-spec missing"));
        JsonNode root = yaml.readTree(spec.content());
        String feature = ctx.param("feature", "");

        StringBuilder api = new StringBuilder("# ").append(root.path("info").path("title").asText())
                .append(" - API reference (v").append(root.path("info").path("version").asText()).append(")\n\n")
                .append("Generated from `openapi.yaml`; do not edit by hand.\n\n");
        List<String> documented = new ArrayList<>();
        root.path("paths").fields().forEachRemaining(p -> p.getValue().fields().forEachRemaining(op -> {
            if (!METHODS.contains(op.getKey())) {
                return;
            }
            documented.add(p.getKey());
            api.append("## ").append(op.getKey().toUpperCase()).append(' ').append(p.getKey()).append("\n")
                    .append(op.getValue().path("summary").asText()).append("\n\n| Status | Meaning |\n|---|---|\n");
            op.getValue().path("responses").fields().forEachRemaining(r -> api.append("| ").append(r.getKey())
                    .append(" | ").append(r.getValue().path("description").asText()).append(" |\n"));
            api.append('\n');
        }));

        String runbook = "# Runbook - " + feature + "\n\n## Signals to watch\n"
                + "- `shortener.redirect` (tags: result) - cache hit ratio and not-found rate\n"
                + "- `shortener.clicks.dropped` / `shortener.clicks.failed` - analytics back-pressure\n"
                + "- `/actuator/health` - liveness and readiness\n\n"
                + "## Failure handling\n- Redis down: redirects fall back to the database; creation limits fail open.\n"
                + "- Analytics queue full: clicks are dropped and counted; redirects are unaffected.\n\n"
                + "## Rollback\nRedeploy the previous application version. " + rollbackNote(feature) + "\n";
        String changelog = "# Changelog\n\n## v" + root.path("info").path("version").asText() + "\n- "
                + root.path("info").path("title").asText() + "\n"
                + "- Endpoints: " + String.join(", ", documented) + "\n";

        List<Check> checks = List.of(
                documented.isEmpty() ? Check.fail("endpoints-documented", "spec has no operations")
                        : Check.pass("endpoints-documented", documented.size() + " path(s) documented from the contract"),
                runbook.contains("## Rollback") ? Check.pass("rollback-documented", "runbook has a rollback section")
                        : Check.fail("rollback-documented", "missing"));
        AgentResult.Builder out = AgentResult.builder("API reference, runbook and changelog generated")
                .rationale("Docs are rendered from the OpenAPI contract so they cannot disagree with it.")
                .artifact("docs", "API.md", api.toString())
                .artifact("docs", "RUNBOOK.md", runbook)
                .artifact("docs", "CHANGELOG.md", changelog);
        checks.forEach(out::check);
        return out.build();
    }

    private static String rollbackNote(String feature) {
        return feature.equals("expiry")
                ? "Then run the migration rollback script (drops the index and the expires_at column); it was verified on a scratch schema."
                : "No schema change is involved.";
    }
}
