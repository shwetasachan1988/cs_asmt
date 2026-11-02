package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Check;
import org.springframework.stereotype.Component;

/** Writes an additive, reversible schema migration - always together with its rollback script (change control). */
@Component
public class MigrationAuthorAgent extends BaseAgent {

    public MigrationAuthorAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "migration-author";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        if (!"expiry".equals(ctx.param("feature", "expiry"))) {
            throw new NonRetryableAgentException("no migration template for feature " + ctx.param("feature", "?"));
        }
        String up = """
                -- V3: optional link expiry.
                -- Additive and nullable: existing rows need no backfill and PostgreSQL treats this as a
                -- metadata-only change, so it is fast on large tables and old application versions ignore it.
                ALTER TABLE short_link ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
                CREATE INDEX idx_short_link_expires_at ON short_link (expires_at);
                """;
        String down = """
                -- U3: rollback of V3. Safe to run any time after the previous application version is redeployed.
                DROP INDEX idx_short_link_expires_at;
                ALTER TABLE short_link DROP COLUMN expires_at;
                """;
        return AgentResult.builder("Migration V3 and its rollback script authored")
                .rationale("Additive + nullable keeps the change backward compatible, which is what makes rollback trivial.")
                .artifact("sql", "V3__add_link_expiry.sql", up)
                .artifact("rollback-script", "U3__rollback_link_expiry.sql", down)
                .check(Check.pass("rollback-shipped", "rollback script accompanies the migration"))
                .context("migration", java.util.Map.of("version", "V3", "table", "short_link", "column", "expires_at"))
                .build();
    }
}
