package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Controlled autonomy: agents only ever emit artifacts into their own sandboxed workspace. Path traversal,
 * absolute paths and oversized outputs are rejected, so an agent cannot touch the source tree or the host.
 */
@Component
public class AutonomyBoundaryPolicy implements Policy {

    static final int MAX_ARTIFACT_CHARS = 200_000;
    static final int MAX_ARTIFACTS_PER_TASK = 25;

    @Override
    public String id() {
        return "AUT-001-workspace-boundary";
    }

    @Override
    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        List<Violation> out = new ArrayList<>();
        if (result.artifacts().size() > MAX_ARTIFACTS_PER_TASK) {
            out.add(new Violation(id(), Severity.BLOCK, "too many artifacts emitted by one task"));
        }
        for (ArtifactDraft a : result.artifacts()) {
            String n = a.name();
            if (n == null || n.isBlank() || n.contains("..") || n.startsWith("/") || n.startsWith("\\")
                    || n.contains(":") || n.contains("\\")) {
                out.add(new Violation(id(), Severity.BLOCK, "artifact path escapes the workspace: '" + n + "'"));
            }
            if (a.content().length() > MAX_ARTIFACT_CHARS) {
                out.add(new Violation(id(), Severity.BLOCK, "artifact '" + n + "' exceeds size limit"));
            }
        }
        return out;
    }
}
