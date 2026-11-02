package com.sa.agentic.orchestrator.model;

public enum TaskStatus {
    PENDING, READY, RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, ROLLED_BACK, CANCELLED;

    public boolean isDone() {
        return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK || this == CANCELLED;
    }
}
