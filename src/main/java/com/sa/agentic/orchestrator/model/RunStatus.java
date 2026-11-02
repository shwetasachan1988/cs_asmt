package com.sa.agentic.orchestrator.model;

public enum RunStatus {
    RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, SAFE_STOPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
