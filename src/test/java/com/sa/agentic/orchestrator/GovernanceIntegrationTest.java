package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sa.agentic.orchestrator.engine.ApprovalDecision;
import com.sa.agentic.orchestrator.model.RunStatus;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import org.junit.jupiter.api.Test;

/** Human-in-the-loop rules, safe-stop controls and guardrails around plan changes. */
class GovernanceIntegrationTest extends OrchestratorTestBase {

    @Test
    void approvalRulesAreEnforcedByTheEngine() {
        String id = start("brownfield-link-expiry", "none").id;
        driveUntilTaskAwaits(id, "design");

        assertThatThrownBy(() -> engine.decide(id, "design", new ApprovalDecision("APPROVE", "agent-007", COMMENT, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separation of duties");
        assertThatThrownBy(() -> engine.decide(id, "design", new ApprovalDecision("MAYBE", APPROVER, COMMENT, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.decide(id, "design", new ApprovalDecision("REJECT", APPROVER, "no", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("justification");
        assertThatThrownBy(() -> engine.decide(id, "impl-expiry", new ApprovalDecision("APPROVE", APPROVER, COMMENT, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not awaiting approval");

        engine.decide(id, "design", new ApprovalDecision("APPROVE", APPROVER, COMMENT, null));
        driveUntilTaskAwaits(id, "migration");
        assertThatThrownBy(() -> engine.decide(id, "migration", new ApprovalDecision("APPROVE", APPROVER, "ok", null)))
                .as("HIGH-risk approvals need a real justification")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("high-risk");
    }

    @Test
    void rejectionSendsWorkBackForReworkWithTheReviewersFeedback() {
        String id = start("test-approval", "none").id;
        driveUntilTaskAwaits(id, "draft");

        engine.decide(id, "draft", new ApprovalDecision("REJECT", APPROVER, "needs a rollback section", null));
        WorkflowRun reworked = engine.awaitQuiescent(id, 30_000);

        assertThat(reworked.tasks.get("draft").status).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(reworked.tasks.get("draft").reworks).isEqualTo(1);
        assertThat(reworked.counters.rejections).isEqualTo(1);
        assertThat(artifacts.latestByNode(id, "draft").get(0).content()).contains("needs a rollback section");
        assertThat(artifacts.latestByNode(id, "draft").get(0).version()).isEqualTo(2);
    }

    @Test
    void repeatedRejectionExhaustsTheReworkBudgetAndFailsTheRun() {
        String id = start("test-approval", "none").id;
        for (int i = 0; i < 3; i++) {
            driveUntilTaskAwaits(id, "draft");
            engine.decide(id, "draft", new ApprovalDecision("REJECT", APPROVER, "still not acceptable " + i, null));
        }
        WorkflowRun end = engine.awaitQuiescent(id, 30_000);
        assertThat(end.status).isEqualTo(RunStatus.FAILED);
        assertThat(end.tasks.get("draft").lastError).contains("rework budget exhausted");
    }

    @Test
    void safeStopHaltsNewWorkAndResumeContinuesFromTheSameState() {
        String id = start("greenfield-bulk-links", "none").id;
        driveUntilTaskAwaits(id, "design");

        WorkflowRun stopped = engine.safeStop(id, "oncall.sre", "suspicious activity");
        assertThat(stopped.status).isEqualTo(RunStatus.SAFE_STOPPED);

        engine.decide(id, "design", new ApprovalDecision("APPROVE", APPROVER, COMMENT, null));
        WorkflowRun still = engine.get(id);
        assertThat(still.status).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(still.tasks.get("design").status).as("human decisions are still recorded").isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(still.tasks.get("impl-core").status).as("but nothing new starts").isEqualTo(TaskStatus.PENDING);

        assertThatThrownBy(() -> engine.resume(id, " ", "go")).isInstanceOf(IllegalArgumentException.class);
        engine.resume(id, "oncall.sre", "all clear");
        assertThat(driveToEnd(id, null).status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(countEvents(id, "SAFE_STOP")).isEqualTo(1);
        assertThat(countEvents(id, "RUN_RESUMED")).isEqualTo(1);
    }

    @Test
    void globalKillSwitchStopsActiveRunsAndBlocksResumeUntilReleased() {
        String id = start("greenfield-bulk-links", "none").id;
        driveUntilTaskAwaits(id, "design");
        try {
            engine.setKillSwitch(true, "ciso");
            assertThat(engine.get(id).status).isEqualTo(RunStatus.SAFE_STOPPED);
            assertThatThrownBy(() -> engine.resume(id, "oncall.sre", "try")).hasMessageContaining("kill switch");
        } finally {
            engine.setKillSwitch(false, "ciso");
        }
        engine.resume(id, "oncall.sre", "released");
        assertThat(engine.get(id).status).isNotEqualTo(RunStatus.SAFE_STOPPED);
    }

    @Test
    void aLeakedSecretSafeStopsTheRunAndIsNeverPersisted() {
        String id = start("test-secret-leak", "none").id;
        WorkflowRun r = engine.awaitQuiescent(id, 30_000);

        assertThat(r.status).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(r.safeStopReason).contains("credential");
        assertThat(artifacts.findByRun(id)).as("quarantined: not copied into our own store").isEmpty();
        assertThat(events(id)).anyMatch(e -> e.type().equals("POLICY_VIOLATION") && e.detail().contains("CRITICAL"));
        assertThat(countEvents(id, "SAFE_STOP")).isEqualTo(1);
    }

    @Test
    void agentCannotAddHighRiskTasksWithoutAnApprovalCheckpoint() {
        String id = start("test-overreach", "none").id;
        WorkflowRun r = engine.awaitQuiescent(id, 30_000);

        assertThat(r.status).isEqualTo(RunStatus.FAILED);
        assertThat(r.plan).extracting(t -> t.id()).doesNotContain("deploy-prod");
        assertThat(countEvents(id, "REPLAN_REJECTED")).isEqualTo(1);
        assertThat(r.tasks.get("plan").lastError).contains("approval checkpoint");
    }

    @Test
    void runawayPlanGrowthHitsTheReplanBudgetAndSafeStops() {
        String id = start("test-runaway", "none").id;
        WorkflowRun r = engine.awaitQuiescent(id, 30_000);

        assertThat(r.status).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(r.safeStopReason).contains("re-plan budget");
        assertThat(r.counters.planChanges).isLessThanOrEqualTo(5);
    }

    @Test
    void unknownScenariosAndRunsAreRejectedCleanly() {
        assertThatThrownBy(() -> start("nope", "none")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown scenario");
        assertThatThrownBy(() -> engine.get("run-missing")).isInstanceOf(java.util.NoSuchElementException.class);
    }

    private void driveUntilTaskAwaits(String runId, String taskId) {
        for (int i = 0; i < 30; i++) {
            WorkflowRun r = engine.awaitQuiescent(runId, 30_000);
            if (r.tasks.containsKey(taskId) && r.tasks.get(taskId).status == TaskStatus.AWAITING_APPROVAL) {
                return;
            }
            for (var e : r.tasks.entrySet()) {
                if (e.getValue().status == TaskStatus.AWAITING_APPROVAL && !e.getKey().equals(taskId)) {
                    engine.decide(runId, e.getKey(), new ApprovalDecision("APPROVE", APPROVER, COMMENT, null));
                }
            }
        }
        throw new AssertionError("task " + taskId + " never awaited approval");
    }
}
