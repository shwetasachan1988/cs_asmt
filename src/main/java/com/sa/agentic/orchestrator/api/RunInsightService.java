package com.sa.agentic.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.engine.WorkflowEngine;
import com.sa.agentic.orchestrator.metrics.MetricsService;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.AuditEvent;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.persistence.ArtifactRepository;
import com.sa.agentic.orchestrator.persistence.AuditLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Read models over a run: decision lineage (who/what influenced what) and the final engineering summary. */
@Service
public class RunInsightService {

    private static final Set<String> DECISION_EVENTS = Set.of("PLAN_CREATED", "REPLAN", "REPLAN_REJECTED",
            "FALLBACK_ENGAGED", "UPSTREAM_CHANGED", "TASK_REOPENED", "APPROVAL_DECISION", "REWORK_REQUESTED",
            "SAFE_STOP", "RUN_RESUMED", "ROLLBACK", "POLICY_VIOLATION", "RUN_FAILING");

    private final WorkflowEngine engine;
    private final ArtifactRepository artifacts;
    private final AuditLog audit;
    private final ObjectMapper json;

    public RunInsightService(WorkflowEngine engine, ArtifactRepository artifacts, AuditLog audit, ObjectMapper json) {
        this.engine = engine;
        this.artifacts = artifacts;
        this.audit = audit;
        this.json = json;
    }

    /** Which artifact flowed from which task into which task, and the decisions that shaped the run. */
    public Map<String, Object> lineage(String runId) {
        WorkflowRun run = engine.get(runId);
        Map<String, Artifact> byId = artifacts.findByRun(runId).stream()
                .collect(Collectors.toMap(Artifact::id, a -> a, (a, b) -> a));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (TaskSpec spec : run.plan) {
            TaskState st = run.tasks.get(spec.id());
            for (String artifactId : st.consumedArtifactIds) {
                Artifact a = byId.get(artifactId);
                if (a != null) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("artifact", a.name());
                    e.put("artifactId", a.id());
                    e.put("version", a.version());
                    e.put("sha256", a.sha256().substring(0, 12));
                    e.put("producedBy", a.nodeId());
                    e.put("consumedBy", spec.id());
                    edges.add(e);
                }
            }
        }
        List<Map<String, Object>> decisions = audit.events(runId).stream()
                .filter(e -> DECISION_EVENTS.contains(e.type()))
                .map(e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("seq", e.seq());
                    d.put("ts", e.ts());
                    d.put("actor", e.actor());
                    d.put("type", e.type());
                    d.put("task", e.nodeId());
                    d.put("detail", parse(e.detail()));
                    return d;
                }).toList();
        return Map.of("runId", runId, "artifactFlow", edges, "decisions", decisions);
    }

    /** Assignment deliverable 8: plan/rationale, artifacts, risks/trade-offs/validation, assumptions, limitations. */
    public String finalSummary(String runId) {
        WorkflowRun run = engine.get(runId);
        List<AuditEvent> events = audit.events(runId);
        List<Artifact> all = artifacts.findByRun(runId);
        StringBuilder md = new StringBuilder();
        md.append("# Final engineering summary - ").append(run.id).append("\n\n");
        md.append("- Scenario: `").append(run.scenario).append("`\n- Status: **").append(run.status).append("**\n");
        if (run.safeStopReason != null) {
            md.append("- Safe-stop reason: ").append(run.safeStopReason).append('\n');
        }
        md.append("\n## 1. Requirement and understanding\n> ").append(run.requirement).append("\n\n");
        latest(all, "requirements-spec").ifPresent(a -> {
            JsonNode n = parse(a.content());
            md.append("Classification: **").append(n.path("classification").asText()).append("** (ambiguity score ")
                    .append(n.path("ambiguityScore").asText()).append(")\n\n");
            n.path("vagueTerms").forEach(v -> md.append("- vague term `").append(v.path("term").asText()).append("` -> ")
                    .append(v.path("question").asText()).append('\n'));
        });

        md.append("\n## 2. Plan and rationale (plan version ").append(run.planVersion).append(")\n");
        events.stream().filter(e -> e.type().equals("PLAN_CREATED")).findFirst()
                .ifPresent(e -> md.append(parse(e.detail()).path("rationale").asText()).append("\n\n"));
        md.append("| Task | Stage | Agent | Depends on | Risk | Approval | Status |\n|---|---|---|---|---|---|---|\n");
        for (TaskSpec t : run.plan) {
            TaskState s = run.tasks.get(t.id());
            md.append("| ").append(t.id()).append(" | ").append(t.stage()).append(" | ")
                    .append(s.agentUsed == null ? t.agent() : s.agentUsed).append(" | ")
                    .append(t.dependsOn().isEmpty() ? "-" : String.join(", ", t.dependsOn())).append(" | ")
                    .append(t.risk()).append(" | ").append(t.approval()).append(" | ").append(s.status).append(" |\n");
        }
        md.append("\nPlan changes decided during execution:\n");
        events.stream().filter(e -> e.type().equals("REPLAN") || e.type().equals("UPSTREAM_CHANGED")).forEach(e -> {
            JsonNode d = parse(e.detail());
            md.append("- ").append(e.type()).append(" by ").append(e.nodeId()).append(": ")
                    .append(d.has("rationale") ? d.path("rationale").asText() : d.path("reason").asText())
                    .append(d.has("added") ? " (added " + d.path("added") + ")" : "")
                    .append(d.has("invalidated") ? " (re-run " + d.path("invalidated") + ")" : "").append('\n');
        });

        md.append("\n## 3. Governance events\n");
        events.stream().filter(e -> Set.of("APPROVAL_DECISION", "POLICY_VIOLATION", "FALLBACK_ENGAGED", "RETRY_SCHEDULED",
                "ROLLBACK", "SAFE_STOP", "RUN_RESUMED", "REWORK_REQUESTED").contains(e.type())).forEach(e ->
                md.append("- #").append(e.seq()).append(' ').append(e.type()).append(" (").append(e.nodeId()).append(", ")
                        .append(e.actor()).append("): ").append(shorten(e.detail())).append('\n'));

        md.append("\n## 4. Artifacts produced\n| Task | Artifact | Type | Version | SHA-256 |\n|---|---|---|---|---|\n");
        all.forEach(a -> md.append("| ").append(a.nodeId()).append(" | ").append(a.name()).append(" | ").append(a.type())
                .append(" | v").append(a.version()).append(" | ").append(a.sha256(), 0, 12).append(" |\n"));

        md.append("\n## 5. Validation evidence\n");
        for (TaskSpec t : run.plan) {
            TaskState s = run.tasks.get(t.id());
            if (!s.checks.isEmpty()) {
                md.append("- **").append(t.id()).append("**: ").append(s.checks.stream()
                        .map(c -> (c.passed() ? "PASS " : "FAIL ") + c.name()).collect(Collectors.joining(", "))).append('\n');
            }
        }
        md.append("\n## 6. Risks, trade-offs and decisions\n");
        latest(all, "design-doc").ifPresent(a -> md.append(section(a.content(), "## Key decisions and trade-offs"))
                .append(section(a.content(), "## Failure modes and mitigations")));
        latest(all, "release-readiness").ifPresent(a -> md.append(section(a.content(), "## Risk register")));

        md.append("\n## 7. Assumptions\n");
        latest(all, "normalized-requirements").ifPresent(a -> {
            JsonNode n = parse(a.content());
            n.path("assumptions").forEach(x -> md.append("- ").append(x.asText()).append('\n'));
        });
        md.append("- Agents are deterministic and run offline; an LLM-backed agent implements the same contract.\n")
                .append("- The release step is a dry run: nothing is deployed.\n");

        md.append("\n## 8. Limitations\n")
                .append("- Run state is a JSON snapshot per run (optimistic locking); scale-out needs a leased scheduler.\n")
                .append("- Generated code is checked in-process (JDK compiler, restricted API policy) - not a hardened sandbox.\n")
                .append("- Migration is validated on the live engine in a scratch schema, not against production-sized data.\n")
                .append("- Static review only; no dynamic security testing.\n");

        md.append("\n## 9. Reliability metrics for this run\n");
        MetricsService.forRun(run).forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
        return md.toString();
    }

    private java.util.Optional<Artifact> latest(List<Artifact> all, String type) {
        return all.stream().filter(a -> a.type().equals(type)).max(java.util.Comparator.comparingInt(Artifact::version));
    }

    private static String section(String markdown, String heading) {
        int i = markdown.indexOf(heading);
        if (i < 0) {
            return "";
        }
        int next = markdown.indexOf("\n## ", i + heading.length());
        return markdown.substring(i, next < 0 ? markdown.length() : next).replace("## ", "### ") + "\n";
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static String shorten(String s) {
        return s.length() > 220 ? s.substring(0, 217) + "..." : s;
    }
}
