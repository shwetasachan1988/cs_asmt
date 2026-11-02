package com.sa.agentic.orchestrator.agent;

import com.sa.agentic.orchestrator.model.AgentResult;

/**
 * A unit of autonomous work. Agents are pure functions of their {@link AgentContext}: they return artifacts,
 * validation evidence and (optionally) plan-change proposals, and never touch the DAG, approvals or the source tree.
 * The shipped agents are deterministic so the prototype runs offline; an LLM-backed agent implements the same contract.
 */
public interface Agent {

    String name();

    AgentResult execute(AgentContext ctx) throws Exception;

    /** Undo external side effects (e.g. scratch schema). Workspace files are removed by the engine. */
    default void rollback(AgentContext ctx) throws Exception {
    }
}
