package com.sa.agentic.orchestrator.agent;

import java.util.Optional;

/**
 * Optional source of real LLM judgement for agents. Its output is <em>advisory</em>: it is attached as an
 * {@code advisory-*} artifact and never drives planning, gates or other agents, so the deterministic path stays the
 * source of truth and the system behaves identically with the advisor off.
 */
public interface LlmAdvisor {

    boolean enabled();

    /** A short human-readable name of the model in use (for evidence and audit). */
    String modelName();

    /** @return the model's answer, or empty when disabled or the call failed (failures are never fatal). */
    Optional<String> advise(String systemPrompt, String userPrompt);
}
