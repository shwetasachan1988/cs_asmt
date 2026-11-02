package com.sa.agentic.orchestrator.model;

public record Violation(String policyId, Severity severity, String message) {

    /** WARN is logged; BLOCK fails the gate (retryable); CRITICAL fails it and safe-stops the whole run. */
    public enum Severity { WARN, BLOCK, CRITICAL }
}
