package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Check;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Implementation agent. Emits source (plus its executable check class), schema and a dependency manifest for one
 * module. The primary agent is deliberately the ambitious one: for two modules its first answer violates policy
 * (an unapproved third-party SDK; raw IP storage) so the governance loop - block, feedback, retry, fallback - is
 * exercised by real policy decisions rather than staged failures.
 */
@Component
public class CodegenAgent extends BaseAgent {

    public CodegenAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "codegen";
    }

    /** The fallback agent never reaches for external SDKs or personal data. */
    protected boolean conservative() {
        return false;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String module = ctx.param("module", "");
        return switch (module) {
            case "bulk" -> pure("Bulk planner", "generated/bulk/BulkLinkPlanner.java", CodeTemplates.BULK_PLANNER,
                    "generated/bulk/BulkLinkPlannerCheck.java", CodeTemplates.BULK_CHECK,
                    "Batch planning is pure logic so partial-failure semantics are verifiable without a database.");
            case "expiry" -> pure("Expiry policy", "generated/expiry/ExpiryPolicy.java", CodeTemplates.EXPIRY_POLICY,
                    "generated/expiry/ExpiryPolicyCheck.java", CodeTemplates.EXPIRY_CHECK,
                    "Expiry rules are isolated from Spring/JDBC so every boundary (exactly-at-expiry, 5-year cap) is testable.");
            case "expiry-cache" -> pure("Cache TTL calculator", "generated/expiry/CacheTtlCalculator.java", CodeTemplates.CACHE_TTL,
                    "generated/expiry/CacheTtlCalculatorCheck.java", CodeTemplates.CACHE_TTL_CHECK,
                    "The cache is the main way an expired link could keep working, so its TTL rule gets its own unit.");
            case "performance" -> pure("Hot-link detector", "generated/performance/HotLinkDetector.java", CodeTemplates.HOT_LINKS,
                    "generated/performance/HotLinkDetectorCheck.java", CodeTemplates.HOT_LINKS_CHECK,
                    "A bounded in-memory sliding window keeps the optimisation cheap and failure-tolerant (it is only a hint).");
            case "reputation" -> reputation(ctx);
            case "visitor-privacy" -> privacy(ctx);
            default -> throw new NonRetryableAgentException("codegen has no module '" + module + "'");
        };
    }

    private AgentResult pure(String title, String srcName, String src, String checkName, String check, String why) {
        return AgentResult.builder(title + " generated (source + executable check)")
                .rationale(why)
                .artifact("source", srcName, src)
                .artifact("source", checkName, check)
                .artifact("dependency-manifest", "dependencies.txt", "# pure JDK - no new third-party libraries\n")
                .check(Check.pass("generated", "2 source files, no new dependencies"))
                .build();
    }

    private AgentResult reputation(AgentContext ctx) {
        if (!conservative()) {
            return AgentResult.builder("Reputation gate via third-party SDK")
                    .rationale("A maintained commercial feed gives the broadest coverage with the least code.")
                    .artifact("source", "generated/safety/ReputationGate.java", CodeTemplates.REPUTATION_VENDOR_SDK)
                    .artifact("dependency-manifest", "dependencies.txt", "com.shady:phishguard-sdk:1.0.3\n")
                    .check(Check.pass("generated", "1 source file, 1 new dependency"))
                    .build();
        }
        return AgentResult.builder("In-house host blocklist (no new dependencies)")
                .rationale("Fallback after the SDK proposal was rejected by supply-chain policy: smaller, auditable, "
                        + "and the list can be fed from any approved source.")
                .artifact("source", "generated/safety/HostBlocklist.java", CodeTemplates.BLOCKLIST)
                .artifact("source", "generated/safety/HostBlocklistCheck.java", CodeTemplates.BLOCKLIST_CHECK)
                .artifact("dependency-manifest", "dependencies.txt", "# pure JDK - no new third-party libraries\n")
                .check(Check.pass("generated", "2 source files, no new dependencies"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private AgentResult privacy(AgentContext ctx) {
        Map<String, Object> caps = ctx.shared("capabilities", Map.class).orElse(Map.of());
        String tracking = String.valueOf(caps.getOrDefault("tracking", "hashed_visitor"));
        boolean repairing = ctx.feedback() != null && ctx.feedback().contains("raw personal data");

        if (tracking.equals("aggregate_only")) {
            return AgentResult.builder("Aggregate-only click counters (no visitor-level data)")
                    .rationale("Stakeholder chose aggregate_only: the least privacy-invasive option, so nothing identifies a visitor.")
                    .artifact("source", "generated/privacy/DailyCounter.java", CodeTemplates.DAILY_COUNTER)
                    .artifact("source", "generated/privacy/DailyCounterCheck.java", CodeTemplates.DAILY_COUNTER_CHECK)
                    .artifact("schema", "V4__daily_clicks.sql", """
                            -- clicks per link per UTC day; no visitor-level data
                            CREATE TABLE daily_click (
                                link_id BIGINT NOT NULL,
                                day DATE NOT NULL,
                                clicks BIGINT NOT NULL,
                                PRIMARY KEY (link_id, day)
                            );
                            """)
                    .check(Check.pass("generated", "counter + schema, no personal data"))
                    .build();
        }
        if (!repairing && !conservative()) {
            // first, naive answer to "who is clicking": store the address. Compliance policy must stop this.
            return AgentResult.builder("Visitor log storing client addresses")
                    .rationale("Directly answers 'who is clicking' by recording the client address per click.")
                    .artifact("source", "generated/privacy/VisitorHasher.java", CodeTemplates.VISITOR_HASHER)
                    .artifact("schema", "V4__visitor_events.sql", """
                            CREATE TABLE visitor_event (
                                id BIGSERIAL PRIMARY KEY,
                                link_id BIGINT NOT NULL,
                                ip_address VARCHAR(45) NOT NULL,
                                seen_at TIMESTAMP WITH TIME ZONE NOT NULL
                            );
                            """)
                    .check(Check.pass("generated", "source + schema"))
                    .build();
        }
        return AgentResult.builder("Pseudonymous visitor analytics (salted daily hash)")
                .rationale("Repaired after compliance feedback" + (tracking.equals("identified_users")
                        ? " - individual identification was requested but cannot be built under policy CMP-001; "
                        + "unique-visitor counts are delivered instead" : "")
                        + ". Marketing gets unique visitors per day without any personal data being stored.")
                .artifact("source", "generated/privacy/VisitorHasher.java", CodeTemplates.VISITOR_HASHER)
                .artifact("source", "generated/privacy/VisitorHasherCheck.java", CodeTemplates.VISITOR_HASHER_CHECK)
                .artifact("schema", "V4__visitor_day.sql", """
                        -- one pseudonymous hash per visitor per UTC day; the salt rotates daily so days cannot be linked
                        CREATE TABLE visitor_day (
                            link_id BIGINT NOT NULL,
                            day DATE NOT NULL,
                            visitor_hash CHAR(64) NOT NULL,
                            PRIMARY KEY (link_id, day, visitor_hash)
                        );
                        """)
                .check(Check.pass("generated", "2 source files + schema, no personal data"))
                .build();
    }
}
