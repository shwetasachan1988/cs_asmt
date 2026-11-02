package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.Check;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Assembles the release bundle as a <em>dry run</em>: a manifest binding version, evidence hashes and the rollback
 * procedure. It only starts after a human approval (BEFORE checkpoint enforced by the engine and ChangeControlPolicy);
 * actual deployment stays outside this system by design.
 */
@Component
public class ReleasePackagerAgent extends BaseAgent {

    public ReleasePackagerAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "release-packager";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Artifact readiness = ctx.upstreamArtifact("release-readiness")
                .orElseThrow(() -> new NonRetryableAgentException("release readiness report missing"));
        Artifact evidence = ctx.upstreamArtifact("evidence-manifest")
                .orElseThrow(() -> new NonRetryableAgentException("evidence manifest missing"));
        if (!readiness.content().contains("GO (pending human approval)")) {
            throw new NonRetryableAgentException("readiness report does not recommend GO");
        }
        JsonNode items = parse(evidence.content());

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("tag", "v1.1.0-" + ctx.runId());
        bundle.put("dryRun", true);
        bundle.put("readinessSha256", readiness.sha256());
        bundle.put("evidenceCount", items.size());
        bundle.put("evidence", items);
        bundle.put("rollback", "redeploy previous version; apply rollback script if a migration shipped");
        bundle.put("note", "nothing was deployed - approval gate passed, artefacts are ready for the real pipeline");
        String body = toJson(bundle);
        bundle.put("bundleSha256", sha256(body));

        return AgentResult.builder("Release bundle assembled (dry run) over " + items.size() + " evidence artifacts")
                .rationale("The manifest pins exactly what was reviewed (by hash), so what is deployed can be proven identical.")
                .artifact("release-bundle", "release-bundle.json", toJson(bundle))
                .check(Check.pass("evidence-bound", items.size() + " artifact hash(es) bound into the manifest"))
                .build();
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
