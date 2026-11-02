package com.sa.agentic.orchestrator.engine;

import java.util.Map;

/**
 * A human's decision on a checkpoint.
 *
 * @param decision APPROVE or REJECT
 * @param approver human identity (must not be an agent identity)
 * @param comment  justification - mandatory for REJECT and for HIGH-risk approvals
 * @param answers  structured human input (e.g. answers to clarification questions), merged into the run context
 */
public record ApprovalDecision(String decision, String approver, String comment, Map<String, Object> answers) {
}
