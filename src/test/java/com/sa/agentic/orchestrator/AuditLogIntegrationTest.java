package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The audit trail is evidence only if tampering is detectable. */
class AuditLogIntegrationTest extends OrchestratorTestBase {

    @Autowired JdbcClient jdbc;

    @Test
    void untouchedChainVerifiesAndEveryEventLinksToItsPredecessor() {
        String id = start("greenfield-bulk-links", "none").id;
        engine.awaitQuiescent(id, 30_000);

        var events = audit.events(id);
        assertThat(events).hasSizeGreaterThan(5);
        assertThat(events.get(0).prevHash()).isEqualTo(com.sa.agentic.orchestrator.persistence.AuditLog.GENESIS);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).prevHash()).isEqualTo(events.get(i - 1).hash());
            assertThat(events.get(i).seq()).isEqualTo(events.get(i - 1).seq() + 1);
        }
        assertThat(audit.verify(id).valid()).isTrue();
    }

    @Test
    void editingDeletingOrReorderingAnEventBreaksVerification() {
        String id = start("greenfield-bulk-links", "none").id;
        engine.awaitQuiescent(id, 30_000);
        assertThat(audit.verify(id).valid()).isTrue();

        jdbc.sql("UPDATE audit_event SET detail = '{\"decision\":\"forged\"}' WHERE run_id = ? AND seq = 3").param(id).update();
        var edited = audit.verify(id);
        assertThat(edited.valid()).isFalse();
        assertThat(edited.firstBrokenAt()).isEqualTo("seq 3");

        String other = start("greenfield-bulk-links", "none").id;
        engine.awaitQuiescent(other, 30_000);
        jdbc.sql("DELETE FROM audit_event WHERE run_id = ? AND seq = 2").param(other).update();
        assertThat(audit.verify(other).valid()).as("a deleted event leaves a gap in the chain").isFalse();
    }
}
