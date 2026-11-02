package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.RunStatus;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The three required scenarios, end to end, against the real engine, policies, agents and live HTTP API. */
class ScenarioIntegrationTest extends OrchestratorTestBase {

    private String content(String runId, String task, String name) {
        return artifacts.latestByNode(runId, task).stream().filter(a -> a.name().equals(name)).map(Artifact::content)
                .findFirst().orElseThrow(() -> new AssertionError(task + "/" + name + " not produced"));
    }

    // ------------------------------------------------------------------ greenfield

    @Test
    void greenfieldRunsFullSdlcWithParallelTracksApprovalsAndAGreenJoin() {
        WorkflowRun started = start("greenfield-bulk-links", "none");
        String id = started.id;

        WorkflowRun atDesign = engine.awaitQuiescent(id, 30_000);
        assertThat(atDesign.status).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(atDesign.tasks.get("design").status).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(atDesign.tasks.get("impl-core").status).as("nothing downstream starts before sign-off")
                .isEqualTo(TaskStatus.PENDING);

        WorkflowRun end = driveToEnd(id, null);
        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(end.tasks.values()).allSatisfy(t -> assertThat(t.status).isEqualTo(TaskStatus.SUCCEEDED));

        // fan-out: documentation and implementation are launched in the same scheduler tick (parallel branches)
        int docsStarted = firstSeq(id, "TASK_STARTED", "docs");
        int implStarted = firstSeq(id, "TASK_STARTED", "impl-core");
        int implDone = firstSeq(id, "TASK_SUCCEEDED", "impl-core");
        int docsDone = firstSeq(id, "TASK_SUCCEEDED", "docs");
        assertThat(docsStarted).isLessThan(implDone);
        assertThat(implStarted).isLessThan(docsDone);

        // join: release readiness starts only after all five evidence tracks finished
        int readinessStart = firstSeq(id, "TASK_STARTED", "release-readiness");
        for (String evidence : new String[]{"unit-tests", "contract-tests", "security-review", "integration-tests", "docs"}) {
            assertThat(firstSeq(id, "TASK_SUCCEEDED", evidence)).as(evidence).isLessThan(readinessStart);
        }

        // real evidence: generated code was compiled and executed, live API exercised, contract validated
        assertThat(content(id, "unit-tests", "unit-tests.md")).contains("BulkLinkPlannerCheck").contains("| PASS |")
                .doesNotContain("| FAIL |");
        assertThat(content(id, "integration-tests", "integration-baseline.md")).contains("create-201").doesNotContain("| FAIL |");
        assertThat(content(id, "contract-tests", "contract-tests.md")).contains("refs-resolve").doesNotContain("| FAIL |");
        assertThat(content(id, "intake", "llm-review.md")).contains("stub-llm");
        assertThat(end.tasks.get("intake").checks).extracting(c -> c.name()).contains("llm-advisory-review");
        assertThat(content(id, "release-readiness", "release-readiness.md")).contains("GO (pending human approval)");
        assertThat(content(id, "release-package", "release-bundle.json")).contains("\"dryRun\" : true").contains("bundleSha256");

        // governance: both human checkpoints recorded with the approver's identity
        assertThat(countEvents(id, "APPROVAL_DECISION")).isEqualTo(2);
        assertThat(events(id)).filteredOn(e -> e.type().equals("APPROVAL_DECISION"))
                .allSatisfy(e -> assertThat(e.actor()).isEqualTo(APPROVER));
        assertThat(audit.verify(id).valid()).isTrue();
    }

    @Test
    void greenfieldAbsorbsTransientFailuresWithBoundedRetriesAndRecordsMttr() {
        WorkflowRun end = driveToEnd(start("greenfield-bulk-links", "transient").id, null);
        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(end.counters.retries).isEqualTo(2);
        assertThat(end.counters.taskFailures).isEqualTo(2);
        assertThat(end.counters.recoveryMillis).hasSize(2).allSatisfy(ms -> assertThat(ms).isGreaterThanOrEqualTo(0));
        assertThat(end.tasks.get("unit-tests").attempts).isEqualTo(2);
        assertThat(countEvents(end.id, "RETRY_SCHEDULED")).isEqualTo(2);
    }

    @Test
    void permanentFailureExhaustsRetriesThenRollsBackCompletedWorkAndStops() {
        WorkflowRun end = driveToEnd(start("greenfield-bulk-links", "permanent").id, null);
        assertThat(end.status).isEqualTo(RunStatus.FAILED);
        assertThat(end.tasks.get("integration-tests").status).isEqualTo(TaskStatus.FAILED);
        assertThat(end.tasks.get("integration-tests").attempts).as("1 attempt + 2 retries").isEqualTo(3);
        assertThat(end.tasks.get("impl-core").status).isEqualTo(TaskStatus.ROLLED_BACK);
        assertThat(end.tasks.get("release-package").status).isEqualTo(TaskStatus.CANCELLED);
        assertThat(end.counters.rollbacks).isEqualTo(1);
        assertThat(countEvents(end.id, "ROLLBACK")).isEqualTo(1);
        assertThat(countEvents(end.id, "RUN_FAILED")).isEqualTo(1);
        assertThat(Files.exists(Path.of("target/test-workspace", end.id, "impl-core")))
                .as("rolled-back task's workspace output is removed").isFalse();
        assertThat(audit.verify(end.id).valid()).isTrue();
    }

    // ------------------------------------------------------------------ brownfield

    @Test
    void brownfieldReasonsOverTheRealCodebaseAndGrowsThePlanFromEvidence() {
        WorkflowRun started = start("brownfield-link-expiry", "none");
        String id = started.id;
        assertThat(started.plan).extracting(t -> t.id()).doesNotContain("impl-cache-ttl", "analytics-regression");

        WorkflowRun end = driveToEnd(id, null);
        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);

        // the plan grew because of what the scan found in the real source tree
        assertThat(end.plan).extracting(t -> t.id()).contains("impl-cache-ttl", "analytics-regression");
        assertThat(end.planVersion).isGreaterThan(1);
        String impact = content(id, "codebase-analysis", "impact-report.json");
        assertThat(impact).contains("LinkService.java").contains("RedirectController.java").contains("LinkRepository.java")
                .contains("short_link").contains("CacheStore");
        assertThat(content(id, "design", "design.md")).contains("Impacted (from codebase analysis)").contains("LinkService.java");

        // schema change: reviewed (HIGH risk), rollback shipped, and both directions executed on the live DB engine
        assertThat(end.tasks.get("migration").approval.approved()).isTrue();
        String validation = content(id, "migration-validate", "migration-validation.md");
        assertThat(validation).contains("migration-applies").contains("rollback-restores-schema").contains("migration-repeatable")
                .doesNotContain("| FAIL |");

        // new dependencies were wired into verification and the join
        assertThat(end.plan.stream().filter(t -> t.id().equals("unit-tests")).findFirst().orElseThrow().dependsOn())
                .contains("impl-expiry", "impl-cache-ttl");
        assertThat(content(id, "unit-tests", "unit-tests.md")).contains("ExpiryPolicyCheck").contains("CacheTtlCalculatorCheck");
        assertThat(content(id, "analytics-regression", "integration-analytics.md")).contains("clicks-counted")
                .doesNotContain("| FAIL |");
        assertThat(countEvents(id, "REPLAN")).isEqualTo(1);
    }

    @Test
    void brownfieldTransientMigrationFailureIsRetriedAndScratchSchemaIsCleanedUp() {
        WorkflowRun end = driveToEnd(start("brownfield-link-expiry", "transient").id, null);
        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(end.tasks.get("migration-validate").attempts).isEqualTo(2);
        assertThat(end.counters.retries).isEqualTo(2); // migration-validate + unit-tests
    }

    // ------------------------------------------------------------------ ambiguous

    @Test
    void ambiguousRequirementIsClarifiedByAHumanBeforeAnyDesignWorkAndPoliciesStopUnsafeProposals() {
        WorkflowRun started = start("ambiguous-safer-faster-tracking", "none");
        String id = started.id;
        assertThat(started.plan).as("only intake is planned up-front").hasSize(1);

        WorkflowRun atClarify = engine.awaitQuiescent(id, 30_000);
        assertThat(atClarify.status).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(atClarify.tasks.get("clarify").status).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(atClarify.tasks).doesNotContainKey("design");
        assertThat(content(id, "intake", "requirements-spec.json")).contains("\"classification\" : \"AMBIGUOUS\"")
                .contains("safer").contains("faster").contains("who");
        assertThat(content(id, "clarify", "clarification-questions.json")).contains("hashed_visitor").contains("identified_users");

        // stakeholder asks for the risky option; governance must still hold
        WorkflowRun end = driveToEnd(id, Map.of("speed", "p99_50ms_cache_pin", "safety", "blocklist",
                "tracking", "identified_users"));
        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(end.plan).extracting(t -> t.id()).contains("design", "impl-safety", "impl-performance", "impl-privacy",
                "unit-tests", "release-package");

        // supply-chain policy blocked the SDK, the conservative fallback agent took over
        assertThat(events(id)).anyMatch(e -> e.type().equals("POLICY_VIOLATION") && e.detail().contains("SUP-001"));
        assertThat(countEvents(id, "FALLBACK_ENGAGED")).isEqualTo(1);
        assertThat(end.tasks.get("impl-safety").agentUsed).isEqualTo("codegen-conservative");
        // compliance policy blocked raw IP storage; the retry repaired it using the feedback
        assertThat(events(id)).anyMatch(e -> e.type().equals("POLICY_VIOLATION") && e.detail().contains("CMP-001"));
        assertThat(end.tasks.get("impl-privacy").attempts).isEqualTo(2);
        assertThat(content(id, "impl-privacy", "V4__visitor_day.sql")).contains("visitor_hash").doesNotContain("ip_address");
        assertThat(end.counters.policyBlocks).isEqualTo(2);
        // the unsafe artifacts were never persisted
        assertThat(artifacts.findByRun(id)).noneMatch(a -> a.content().contains("phishguard") || a.content().contains("ip_address VARCHAR"));
        assertThat(end.context.get("answers")).isEqualTo(Map.of("speed", "p99_50ms_cache_pin", "safety", "blocklist",
                "tracking", "identified_users"));
    }

    @Test
    void changingAnAnswerLaterInvalidatesDependantsRevokesApprovalsAndReExecutes() {
        String id = start("ambiguous-safer-faster-tracking", "none").id;
        WorkflowRun first = driveToEnd(id, Map.of("tracking", "hashed_visitor"));
        assertThat(first.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(content(id, "impl-privacy", "V4__visitor_day.sql")).contains("visitor_hash");
        String designHashBefore = first.tasks.get("design").committedHash;

        engine.reopen(id, "clarify", "bob.pm", "legal asked for counters only", Map.of("tracking", "aggregate_only"));
        WorkflowRun end = driveToEnd(id, null);

        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(countEvents(id, "UPSTREAM_CHANGED")).isGreaterThanOrEqualTo(1);
        assertThat(events(id)).filteredOn(e -> e.type().equals("UPSTREAM_CHANGED")).first()
                .satisfies(e -> assertThat(e.detail()).contains("design").contains("impl-privacy"));
        assertThat(end.counters.invalidations).isGreaterThan(5);
        assertThat(content(id, "impl-privacy", "V4__daily_clicks.sql")).contains("daily_click");
        assertThat(end.tasks.get("design").committedHash).isNotEqualTo(designHashBefore);
        // approvals were revoked and had to be given again: design and release were each approved twice
        long designApprovals = events(id).stream().filter(e -> e.type().equals("APPROVAL_DECISION") && "design".equals(e.nodeId())).count();
        assertThat(designApprovals).isEqualTo(2);
        assertThat(audit.verify(id).valid()).isTrue();
    }

    @Test
    void reopeningWithAnUnchangedOutcomeDoesNotRerunDependants() {
        String id = start("greenfield-bulk-links", "none").id;
        WorkflowRun first = driveToEnd(id, null);
        TaskState implBefore = first.tasks.get("impl-core");

        engine.reopen(id, "intake", "bob.pm", "re-check the analysis", null);
        WorkflowRun end = driveToEnd(id, null);

        assertThat(end.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(end.tasks.get("intake").attempts).isEqualTo(1);
        assertThat(end.tasks.get("impl-core").attempts).as("identical output -> no cascade").isEqualTo(implBefore.attempts);
        assertThat(countEvents(id, "UPSTREAM_CHANGED")).isZero();
    }
}
