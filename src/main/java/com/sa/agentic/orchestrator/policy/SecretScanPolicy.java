package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Security: no credential material may leave an agent. CRITICAL - a leaked secret safe-stops the run. */
@Component
public class SecretScanPolicy implements Policy {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("ghp_[A-Za-z0-9]{36}"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|access[_-]?token)\\s*[:=]\\s*['\"]?[A-Za-z0-9/+=_\\-]{8,}"));

    @Override
    public String id() {
        return "SEC-001-secret-scan";
    }

    @Override
    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        List<Violation> out = new ArrayList<>();
        for (ArtifactDraft a : result.artifacts()) {
            for (Pattern p : PATTERNS) {
                if (p.matcher(a.content()).find()) {
                    out.add(new Violation(id(), Severity.CRITICAL,
                            "possible credential in artifact '" + a.name() + "' (pattern " + p.pattern().substring(0, Math.min(18, p.pattern().length())) + "...)"));
                    break;
                }
            }
        }
        return out;
    }
}
