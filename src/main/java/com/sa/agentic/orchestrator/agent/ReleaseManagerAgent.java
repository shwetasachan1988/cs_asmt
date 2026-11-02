package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.Check;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Release readiness: the synchronisation point of the DAG. It only runs once every evidence track has
 * succeeded, then re-derives the verdict from the evidence itself rather than trusting task statuses.
 */
@Component
public class ReleaseManagerAgent extends BaseAgent {

    public ReleaseManagerAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "release-manager";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        List<Artifact> tests = ctx.upstreamArtifacts("test-report");
        List<Artifact> security = ctx.upstreamArtifacts("security-report");
        List<Artifact> docs = ctx.upstreamArtifacts("docs");

        List<Check> criteria = new ArrayList<>();
        long failingRows = tests.stream().filter(a -> a.content().contains("| FAIL |")).count();
        criteria.add(!tests.isEmpty() && failingRows == 0
                ? Check.pass("tests-green", tests.size() + " test report(s), no failing rows")
                : Check.fail("tests-green", tests.isEmpty() ? "no test evidence" : failingRows + " report(s) contain failures"));
        criteria.add(!security.isEmpty() && security.stream().allMatch(a -> a.content().contains("Verdict: PASS"))
                ? Check.pass("security-pass", "security review verdict PASS")
                : Check.fail("security-pass", "missing or failing security review"));
        boolean docsComplete = List.of("API.md", "RUNBOOK.md", "CHANGELOG.md").stream()
                .allMatch(n -> docs.stream().anyMatch(a -> a.name().equals(n)));
        criteria.add(docsComplete ? Check.pass("docs-complete", "API reference, runbook, changelog present")
                : Check.fail("docs-complete", "documentation set incomplete"));
        criteria.add(docs.stream().anyMatch(a -> a.name().equals("RUNBOOK.md") && a.content().contains("## Rollback"))
                ? Check.pass("rollback-plan", "documented in runbook") : Check.fail("rollback-plan", "no rollback plan"));

        boolean ready = criteria.stream().allMatch(Check::passed);
        StringBuilder md = new StringBuilder("# Release readiness\n\nRecommendation: **")
                .append(ready ? "GO (pending human approval)" : "NO-GO").append("**\n\n| Criterion | Result | Evidence |\n|---|---|---|\n");
        criteria.forEach(c -> md.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "FAIL")
                .append(" | ").append(c.detail()).append(" |\n"));
        md.append("\n## Risk register\n")
                .append("- Click analytics is best-effort by design (bounded queue, drops counted).\n")
                .append("- Cache and database can briefly disagree after deletion; mitigated by explicit eviction and short negative-cache TTL.\n")
                .append("- Agent output is reviewed by humans at the design and release checkpoints; nothing is deployed by this run.\n");

        List<Map<String, String>> evidence = new ArrayList<>();
        ctx.upstream().forEach((task, list) -> list.forEach(a -> {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("task", task);
            e.put("artifact", a.name());
            e.put("type", a.type());
            e.put("version", String.valueOf(a.version()));
            e.put("sha256", a.sha256());
            evidence.add(e);
        }));

        AgentResult.Builder out = AgentResult.builder(ready ? "Ready for release: " + criteria.size() + "/" + criteria.size()
                        + " criteria met over " + evidence.size() + " evidence artifact(s)" : "Not ready for release")
                .rationale("Verdict is recomputed from the evidence artifacts, not from upstream task statuses.")
                .artifact("release-readiness", "release-readiness.md", md.toString())
                .artifact("evidence-manifest", "evidence-manifest.json", toJson(evidence));
        criteria.forEach(out::check);
        return out.build();
    }
}
