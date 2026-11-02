package com.sa.agentic.orchestrator.model;

/** HIGH-risk tasks (schema change, release) must carry a human approval checkpoint - enforced by policy. */
public enum Risk {
    LOW, MEDIUM, HIGH
}
