package com.sa.agentic.orchestrator.model;

import java.time.Instant;

/** One append-only, hash-chained audit record. {@code hash} covers {@code prevHash}, so tampering breaks the chain. */
public record AuditEvent(long id, String runId, int seq, Instant ts, String actor, String type, String nodeId,
                         String detail, String prevHash, String hash) {
}
