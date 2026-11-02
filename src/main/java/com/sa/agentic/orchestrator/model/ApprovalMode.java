package com.sa.agentic.orchestrator.model;

/** BEFORE = human authorises the action; AFTER = human reviews the produced artifacts before downstream work may use them. */
public enum ApprovalMode {
    NONE, BEFORE, AFTER
}
