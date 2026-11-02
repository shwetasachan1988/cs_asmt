package com.sa.agentic.orchestrator.model;

import java.time.Instant;

public record ApprovalRecord(String decision, String approver, String comment, Instant at) {
    public boolean approved() {
        return "APPROVE".equals(decision);
    }
}
