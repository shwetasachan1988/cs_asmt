package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Check;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns detected ambiguities into concrete multiple-choice questions with a recommended default and the
 * consequence of each option. The human answers at the approval checkpoint; nothing proceeds on a guess.
 */
@Component
public class ClarifierAgent extends BaseAgent {

    private static final Map<String, String> CONSEQUENCES = Map.of(
            "p99_50ms_cache_pin", "adds hot-link cache pinning; measurable, small blast radius",
            "p99_100ms", "relaxed target; existing cache is probably sufficient",
            "no_change", "no performance work in this change",
            "blocklist", "reject known-bad destinations at creation time; needs a maintained list",
            "blocklist_and_rate_limit", "blocklist plus tighter creation limits; more moving parts",
            "rate_limit_only", "already in place; no new safety work",
            "aggregate_only", "counts only - lowest privacy risk, no per-visitor insight",
            "hashed_visitor", "unique-visitor counts via salted daily hash; no identity, GDPR-friendly",
            "identified_users", "individual identification - requires consent, legal review and PII storage (blocked by policy CMP-001)");

    public ClarifierAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "clarifier";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        JsonNode spec = parse(ctx.upstreamArtifact("requirements-spec")
                .orElseThrow(() -> new NonRetryableAgentException("requirements-spec missing")).content());
        List<Map<String, Object>> questions = new ArrayList<>();
        Map<String, Object> recommended = new LinkedHashMap<>();
        for (JsonNode vt : spec.path("vagueTerms")) {
            String key = vt.path("key").asText();
            if (key.equals("quality")) {
                continue;
            }
            List<Map<String, String>> options = new ArrayList<>();
            vt.path("options").forEach(o -> options.add(Map.of("value", o.asText(),
                    "consequence", CONSEQUENCES.getOrDefault(o.asText(), ""))));
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", key);
            q.put("because", "the requirement says '" + vt.path("term").asText() + "' without a measurable meaning");
            q.put("question", vt.path("question").asText());
            q.put("options", options);
            q.put("recommended", vt.path("default").asText());
            questions.add(q);
            recommended.put(key, vt.path("default").asText());
        }
        if (questions.isEmpty()) {
            throw new NonRetryableAgentException("nothing to clarify - plan should not contain this task");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("intro", "Please answer each question when approving; unanswered ones fall back to the recommendation "
                + "and are recorded as assumptions.");
        doc.put("questions", questions);
        return AgentResult.builder(questions.size() + " clarifying question(s) prepared for the stakeholder")
                .rationale("Each question is tied to the vague phrase that caused it and states the consequence of every option.")
                .artifact("clarification-questions", "clarification-questions.json", toJson(doc))
                .check(Check.pass("questions-generated", questions.size() + " question(s) with recommended defaults"))
                .context("recommendedAnswers", recommended)
                .build();
    }
}
