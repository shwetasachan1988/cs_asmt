package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.ApprovalRecord;
import com.sa.agentic.orchestrator.model.Risk;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.policy.AutonomyBoundaryPolicy;
import com.sa.agentic.orchestrator.policy.ChangeControlPolicy;
import com.sa.agentic.orchestrator.policy.DependencyAllowlistPolicy;
import com.sa.agentic.orchestrator.policy.PiiPolicy;
import com.sa.agentic.orchestrator.policy.PolicyContext;
import com.sa.agentic.orchestrator.policy.SecretScanPolicy;
import com.sa.agentic.orchestrator.policy.UnsafeCodePolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PolicyTest {

    private static final TaskSpec TASK = TaskSpec.of("t", Stage.IMPLEMENTATION, "codegen").build();
    private static final PolicyContext CTX = new PolicyContext(new WorkflowRun(), TASK, new TaskState("t"));

    private static AgentResult with(String type, String name, String content) {
        return AgentResult.builder("x").artifact(type, name, content).build();
    }

    @Test
    void secretScanFlagsCredentialsAsCritical() {
        var p = new SecretScanPolicy();
        assertThat(p.checkExit(CTX, with("docs", "a.md", "key AKIAIOSFODNN7EXAMPLE here"))).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.CRITICAL));
        assertThat(p.checkExit(CTX, with("docs", "a.md", "-----BEGIN RSA PRIVATE KEY-----"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("docs", "a.md", "db_password = hunter2hunter2"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("docs", "a.md", "Passwords must never be logged."))).isEmpty();
    }

    @Test
    void piiPolicyBlocksRawIdentifiersButAcceptsHashes() {
        var p = new PiiPolicy();
        assertThat(p.checkExit(CTX, with("schema", "s.sql", "ip_address VARCHAR(45) NOT NULL"))).singleElement()
                .satisfies(v -> assertThat(v.severity()).isEqualTo(Severity.BLOCK));
        assertThat(p.checkExit(CTX, with("source", "A.java", "String email = user.email();"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("schema", "s.sql", "visitor_hash CHAR(64) NOT NULL"))).isEmpty();
        assertThat(p.checkExit(CTX, with("schema", "s.sql", "-- we never store ip_address"))).isEmpty();
        assertThat(p.checkExit(CTX, with("design-doc", "d.md", "the ip_address is hashed"))).isEmpty(); // prose is not checked
    }

    @Test
    void dependencyAllowlistRejectsUnknownGroups() {
        var p = new DependencyAllowlistPolicy();
        assertThat(p.checkExit(CTX, with("dependency-manifest", "d.txt", "org.springframework.boot:spring-boot-starter\n"))).isEmpty();
        assertThat(p.checkExit(CTX, with("dependency-manifest", "d.txt", "# comment\ncom.shady:phishguard-sdk:1.0\n"))).hasSize(1);
    }

    @Test
    void unsafeCodePolicyRestrictsGeneratedSource() {
        var p = new UnsafeCodePolicy();
        assertThat(p.checkExit(CTX, with("source", "A.java", "Runtime.getRuntime().exec(\"rm -rf /\");"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("source", "A.java", "new ProcessBuilder(\"sh\")"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("source", "A.java", "int x = 1;"))).isEmpty();
    }

    @Test
    void autonomyBoundaryBlocksPathEscapesAndOversizedOutput() {
        var p = new AutonomyBoundaryPolicy();
        assertThat(p.checkExit(CTX, with("docs", "../../etc/passwd", "x"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("docs", "/abs/path.md", "x"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("docs", "C:\\win.md", "x"))).hasSize(1);
        assertThat(p.checkExit(CTX, with("docs", "ok/nested/file.md", "x"))).isEmpty();
        assertThat(p.checkExit(CTX, with("docs", "big.md", "x".repeat(200_001)))).hasSize(1);
    }

    @Test
    void changeControlRequiresApprovalForHighRiskAndRollbackForSchemaChanges() {
        var p = new ChangeControlPolicy();
        TaskSpec risky = TaskSpec.of("r", Stage.RELEASE, "x").risk(Risk.HIGH).build();
        assertThat(p.checkPlan(new WorkflowRun(), risky)).hasSize(1);
        assertThat(p.checkPlan(new WorkflowRun(), TaskSpec.of("r", Stage.RELEASE, "x").risk(Risk.HIGH)
                .approval(ApprovalMode.BEFORE).build())).isEmpty();

        TaskSpec schema = TaskSpec.of("m", Stage.IMPLEMENTATION, "x").param("schemaChange", "true").build();
        var ctx = new PolicyContext(new WorkflowRun(), schema, new TaskState("m"));
        assertThat(p.checkExit(ctx, with("sql", "V3.sql", "ALTER TABLE t ADD c INT;"))).hasSize(1);
        assertThat(p.checkExit(ctx, AgentResult.builder("x").artifact("sql", "V3.sql", "A")
                .artifact("rollback-script", "U3.sql", "B").build())).isEmpty();
    }

    @Test
    void entryGateRequiresRecordedHumanApprovalBeforeApprovedActions() {
        var p = new ChangeControlPolicy();
        TaskSpec gated = TaskSpec.of("g", Stage.RELEASE, "x").approval(ApprovalMode.BEFORE).build();
        TaskState st = new TaskState("g");
        assertThat(p.checkEntry(new PolicyContext(new WorkflowRun(), gated, st))).hasSize(1);
        st.approval = new ApprovalRecord("APPROVE", "agent-007", "self approve", Instant.now());
        assertThat(p.checkEntry(new PolicyContext(new WorkflowRun(), gated, st))).hasSize(1);
        st.approval = new ApprovalRecord("APPROVE", "alice", "ok", Instant.now());
        assertThat(p.checkEntry(new PolicyContext(new WorkflowRun(), gated, st))).isEmpty();
    }

    @Test
    void releaseEntryBlocksWhileAnyOtherTaskIsNotDone() {
        var p = new ChangeControlPolicy();
        WorkflowRun run = new WorkflowRun();
        TaskSpec impl = TaskSpec.of("impl", Stage.IMPLEMENTATION, "x").build();
        TaskSpec release = TaskSpec.of("rel", Stage.RELEASE, "x").build();
        run.plan.add(impl);
        run.plan.add(release);
        run.tasks.put("impl", new TaskState("impl"));
        run.tasks.put("rel", new TaskState("rel"));
        assertThat(p.checkEntry(new PolicyContext(run, release, run.tasks.get("rel")))).hasSize(1);
        run.tasks.get("impl").status = TaskStatus.SUCCEEDED;
        assertThat(p.checkEntry(new PolicyContext(run, release, run.tasks.get("rel")))).isEmpty();
    }
}
