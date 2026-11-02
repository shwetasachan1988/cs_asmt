package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Compliance: generated schema/code must not persist raw personal identifiers. A column or field is accepted
 * only when it is a hash/pseudonym. Applies to SQL and code artifacts, not prose.
 */
@Component
public class PiiPolicy implements Policy {

    private static final Set<String> CHECKED_TYPES = Set.of("sql", "source", "schema");
    private static final Pattern RAW_PII = Pattern.compile(
            "(?i)\\b(ip_address|ipaddress|client_ip|remote_addr|email|e_mail|ssn|phone|full_name|device_id)\\b");

    @Override
    public String id() {
        return "CMP-001-no-raw-pii";
    }

    @Override
    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        List<Violation> out = new ArrayList<>();
        for (ArtifactDraft a : result.artifacts()) {
            if (!CHECKED_TYPES.contains(a.type())) {
                continue;
            }
            for (String line : a.content().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                var m = RAW_PII.matcher(line);
                if (m.find() && !line.toLowerCase().contains("hash")) {
                    out.add(new Violation(id(), Severity.BLOCK,
                            "artifact '" + a.name() + "' stores raw personal data field '" + m.group(1)
                                    + "'; use a salted hash/pseudonym instead"));
                    break;
                }
            }
        }
        return out;
    }
}
