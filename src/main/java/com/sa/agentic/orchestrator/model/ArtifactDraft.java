package com.sa.agentic.orchestrator.model;

/** What an agent hands back; the engine assigns id, version, hash and persists it. */
public record ArtifactDraft(String type, String name, String content) {
}
