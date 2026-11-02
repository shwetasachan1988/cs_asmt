package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Generated source is later compiled and executed by the test agents, so it may only use a pure-computation
 * subset of the JDK: no process, file, network, reflection or thread APIs.
 */
@Component
public class UnsafeCodePolicy implements Policy {

    static final List<String> FORBIDDEN = List.of(
            "ProcessBuilder", "Runtime.getRuntime", "System.exit", "java.net.", "java.io.File", "java.nio.file",
            "Class.forName", "setAccessible", "System.getenv", "System.setProperty", "new Thread", "java.lang.reflect",
            "ClassLoader", "Unsafe");

    @Override
    public String id() {
        return "SEC-002-unsafe-code";
    }

    @Override
    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        List<Violation> out = new ArrayList<>();
        for (ArtifactDraft a : result.artifacts()) {
            if (!a.type().equals("source")) {
                continue;
            }
            for (String token : FORBIDDEN) {
                if (a.content().contains(token)) {
                    out.add(new Violation(id(), Severity.BLOCK,
                            "generated source '" + a.name() + "' uses restricted API '" + token + "'"));
                    break;
                }
            }
        }
        return out;
    }
}
