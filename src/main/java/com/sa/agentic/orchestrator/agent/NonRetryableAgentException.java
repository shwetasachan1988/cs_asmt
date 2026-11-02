package com.sa.agentic.orchestrator.agent;

/** Retrying cannot help (bad input, unsupported request): skip the retry budget and go to fallback/failure. */
public class NonRetryableAgentException extends RuntimeException {
    public NonRetryableAgentException(String message) {
        super(message);
    }
}
