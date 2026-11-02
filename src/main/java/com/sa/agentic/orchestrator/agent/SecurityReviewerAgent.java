package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Check;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.policy.PolicyContext;
import com.sa.agentic.orchestrator.policy.PolicyEngine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Independent second look: re-runs every policy scanner over everything upstream (so a gate that was misconfigured
 * or skipped cannot let a defect through) and adds a STRIDE-style threat model for the feature.
 */
@Component
public class SecurityReviewerAgent extends BaseAgent {

    private record Threat(String category, String threat, String control) {
    }

    private final PolicyEngine policies;

    public SecurityReviewerAgent(ObjectMapper json, PolicyEngine policies) {
        super(json);
        this.policies = policies;
    }

    @Override
    public String name() {
        return "security-reviewer";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        List<ArtifactDraft> reviewed = new ArrayList<>();
        for (List<Artifact> list : ctx.upstream().values()) {
            for (Artifact a : list) {
                reviewed.add(new ArtifactDraft(a.type(), a.name(), a.content()));
            }
        }
        AgentResult synthetic = new AgentResult("review", "", reviewed, List.of(), java.util.Map.of(), List.of());
        // exit-phase policies only read the artifacts, never run/state, so the review needs no live task state
        List<Violation> findings = policies.checkExit(new PolicyContext(null, ctx.task(), null), synthetic);

        List<Check> checks = new ArrayList<>();
        checks.add(findings.isEmpty()
                ? Check.pass("policy-rescan", reviewed.size() + " artifact(s) re-scanned: secrets, PII, unsafe APIs, dependencies, paths")
                : Check.fail("policy-rescan", findings.stream().map(v -> v.policyId() + " " + v.message()).toList().toString()));

        List<Threat> threats = threatModel(ctx.param("feature", ""));
        StringBuilder md = new StringBuilder("# Security review\n\nVerdict: ")
                .append(findings.isEmpty() ? "PASS" : "FAIL").append("\n\n## Automated findings\n");
        if (findings.isEmpty()) {
            md.append("None. ").append(reviewed.size()).append(" artifact(s) scanned by ")
                    .append(policies.policyIds().size()).append(" policies.\n");
        }
        findings.forEach(v -> md.append("- [").append(v.severity()).append("] ").append(v.policyId()).append(": ")
                .append(v.message()).append('\n'));
        md.append("\n## Threat model (STRIDE)\n| Category | Threat | Control |\n|---|---|---|\n");
        threats.forEach(t -> md.append("| ").append(t.category()).append(" | ").append(t.threat()).append(" | ")
                .append(t.control()).append(" |\n"));
        md.append("\n## Residual risk\n- Static review only: no dynamic/penetration testing in this prototype.\n")
                .append("- Generated code is executed in-process for checks; production would isolate that step.\n");

        AgentResult.Builder out = AgentResult.builder(findings.isEmpty()
                        ? "No security findings; " + threats.size() + " threats modelled"
                        : findings.size() + " security finding(s)")
                .rationale("A reviewer that shares no state with the producing agent re-derives the verdict from the artifacts alone.")
                .artifact("security-report", "security-review.md", md.toString());
        checks.forEach(out::check);
        return out.build();
    }

    private static List<Threat> threatModel(String feature) {
        return switch (feature) {
            case "bulk" -> List.of(
                    new Threat("Denial of service", "one request creates unbounded links", "hard cap of 100 items; rate limit charged per item"),
                    new Threat("Tampering", "malicious URLs smuggled in a batch", "each item passes the same UrlValidator as single create"),
                    new Threat("Information disclosure", "error details leak internals", "per-item reasons are fixed strings, no stack traces"));
            case "expiry" -> List.of(
                    new Threat("Tampering", "client supplies past or absurd expiry", "validated: must be future and within 5 years"),
                    new Threat("Elevation of privilege", "expired link still served from cache", "cache TTL capped by expiry; expiry re-checked on read"),
                    new Threat("Repudiation", "disputes about when a link stopped working", "410 Gone is deterministic; expiry stored in UTC"));
            default -> List.of(
                    new Threat("Spoofing", "phishing destinations behind short links", "host blocklist enforced at creation (HTTP 422)"),
                    new Threat("Information disclosure", "visitor identities exposed through analytics", "only salted daily hashes stored; raw addresses never persisted"),
                    new Threat("Denial of service", "hot link overwhelms the database", "hot-link detection keeps redirects in cache"));
        };
    }
}
