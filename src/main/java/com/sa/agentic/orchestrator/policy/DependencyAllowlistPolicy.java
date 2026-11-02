package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Supply-chain control: an agent may only introduce libraries from approved groups. Format: one group:artifact per line. */
@Component
public class DependencyAllowlistPolicy implements Policy {

    static final List<String> ALLOWED_GROUP_PREFIXES = List.of(
            "org.springframework", "com.fasterxml.jackson", "io.micrometer", "org.postgresql", "org.flywaydb",
            "io.swagger", "org.springdoc", "org.junit", "org.assertj", "org.slf4j", "jakarta.", "com.h2database",
            "io.lettuce");

    @Override
    public String id() {
        return "SUP-001-dependency-allowlist";
    }

    @Override
    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        List<Violation> out = new ArrayList<>();
        for (ArtifactDraft a : result.artifacts()) {
            if (!a.type().equals("dependency-manifest")) {
                continue;
            }
            for (String line : a.content().split("\\R")) {
                String dep = line.trim();
                if (dep.isEmpty() || dep.startsWith("#")) {
                    continue;
                }
                String group = dep.contains(":") ? dep.substring(0, dep.indexOf(':')) : dep;
                if (ALLOWED_GROUP_PREFIXES.stream().noneMatch(group::startsWith)) {
                    out.add(new Violation(id(), Severity.BLOCK,
                            "dependency '" + dep + "' is not on the approved-library allowlist"));
                }
            }
        }
        return out;
    }
}
