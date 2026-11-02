package com.sa.agentic.orchestrator.model;

import java.time.Instant;

/** Immutable, versioned, content-hashed output of a task. */
public record Artifact(String id, String runId, String nodeId, String type, String name, int version, String sha256,
                       String content, Instant createdAt) {
}
