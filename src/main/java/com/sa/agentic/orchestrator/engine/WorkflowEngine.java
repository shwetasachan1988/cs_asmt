package com.sa.agentic.orchestrator.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.OrchestratorProperties;
import com.sa.agentic.orchestrator.agent.Agent;
import com.sa.agentic.orchestrator.agent.AgentContext;
import com.sa.agentic.orchestrator.agent.AgentRegistry;
import com.sa.agentic.orchestrator.agent.NonRetryableAgentException;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.ApprovalRecord;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.ArtifactDraft;
import com.sa.agentic.orchestrator.model.Check;
import com.sa.agentic.orchestrator.model.ReplanRequest;
import com.sa.agentic.orchestrator.model.RunStatus;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.persistence.ArtifactRepository;
import com.sa.agentic.orchestrator.persistence.AuditLog;
import com.sa.agentic.orchestrator.persistence.RunRepository;
import com.sa.agentic.orchestrator.policy.PolicyContext;
import com.sa.agentic.orchestrator.policy.PolicyEngine;
import com.sa.agentic.orchestrator.scenario.ScenarioCatalog;
import com.sa.agentic.orchestrator.scenario.ScenarioDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Stateful, event-driven DAG executor.
 *
 * <p>All state transitions for a run happen under a per-run lock and are snapshotted to the database, so the engine
 * is restart-safe. Agent execution itself runs outside the lock on virtual threads, which gives parallel branches;
 * a task with several dependencies only becomes runnable when all of them have succeeded (synchronisation join).
 * Every result re-enters through {@link #onResult}, where the exit gate, retry/fallback/rollback policy, plan changes
 * and approval checkpoints are applied. Agents never call back into the engine.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int MAX_REWORKS = 2;

    private final ScenarioCatalog catalog;
    private final AgentRegistry agents;
    private final PolicyEngine policies;
    private final PlanMutator planner;
    private final RunRepository runs;
    private final ArtifactRepository artifacts;
    private final AuditLog audit;
    private final WorkspaceService workspace;
    private final OrchestratorProperties props;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    private final Map<String, WorkflowRun> cache = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "orchestrator-retry-timer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean killSwitch = new AtomicBoolean(false);

    public WorkflowEngine(ScenarioCatalog catalog, AgentRegistry agents, PolicyEngine policies, PlanMutator planner,
                          RunRepository runs, ArtifactRepository artifacts, AuditLog audit, WorkspaceService workspace,
                          OrchestratorProperties props, ObjectMapper json, MeterRegistry metrics) {
        this.catalog = catalog;
        this.agents = agents;
        this.policies = policies;
        this.planner = planner;
        this.runs = runs;
        this.artifacts = artifacts;
        this.audit = audit;
        this.workspace = workspace;
        this.props = props;
        this.json = json;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ public API

    public WorkflowRun start(StartRequest req) {
        ScenarioDefinition scenario = catalog.get(req.scenario());
        String requirement = req.requirement() == null || req.requirement().isBlank()
                ? scenario.defaultRequirement() : req.requirement().trim();
        String actor = blankTo(req.actor(), "anonymous");

        WorkflowRun run = new WorkflowRun();
        run.id = "run-" + UUID.randomUUID().toString().substring(0, 8);
        run.scenario = scenario.id();
        run.requirement = requirement;
        run.createdAt = Instant.now();

        List<TaskSpec> plan = scenario.initialPlan(requirement);
        DagValidator.validate(plan);
        for (TaskSpec t : plan) {
            if (!agents.has(t.agent()) || (t.fallbackAgent() != null && !agents.has(t.fallbackAgent()))) {
                throw new IllegalArgumentException("scenario references unregistered agent in task " + t.id());
            }
            List<Violation> v = policies.checkPlan(run, t);
            if (!v.isEmpty()) {
                throw new IllegalArgumentException("plan rejected by policy: " + v.get(0).message());
            }
            run.plan.add(t);
            run.tasks.put(t.id(), new TaskState(t.id()));
        }
        if (req.chaos() != null && !req.chaos().isEmpty()) {
            run.chaos.putAll(req.chaos());
        } else if ("transient".equals(req.failureMode())) {
            run.chaos.putAll(scenario.transientFailures());
        } else if ("permanent".equals(req.failureMode())) {
            run.chaos.putAll(scenario.permanentFailures());
        }

        runs.save(run);
        cache.put(run.id, run);
        audit.append(run.id, actor, "RUN_STARTED", null, Map.of("scenario", scenario.id(), "requirement", requirement,
                "chaos", run.chaos));
        audit.append(run.id, "orchestrator", "PLAN_CREATED", null, Map.of("tasks", plan.stream().map(TaskSpec::id).toList(),
                "rationale", scenario.planRationale(), "policies", policies.policyIds()));
        metrics.counter("orchestrator.runs.started", "scenario", scenario.id()).increment();
        advance(run.id);
        return get(run.id);
    }

    /** Defensive snapshot: callers never see (or mutate) engine-owned state. */
    public WorkflowRun get(String runId) {
        return withLock(runId, () -> copy(load(runId)));
    }

    public List<WorkflowRun> list() {
        return runs.findAll();
    }

    public WorkflowRun decide(String runId, String taskId, ApprovalDecision d) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            TaskSpec spec = specOrThrow(run, taskId);
            TaskState st = run.tasks.get(taskId);
            if (run.status.isTerminal()) {
                throw new IllegalStateException("run is already " + run.status);
            }
            if (st.status != TaskStatus.AWAITING_APPROVAL) {
                throw new IllegalStateException("task " + taskId + " is " + st.status + ", not awaiting approval");
            }
            validateDecision(spec, d);
            boolean approve = "APPROVE".equals(d.decision());
            Instant now = Instant.now();
            st.approval = new ApprovalRecord(d.decision(), d.approver(), d.comment(), now);
            if (st.awaitingApprovalSince != null) {
                run.approvalWaitMillis += Duration.between(st.awaitingApprovalSince, now).toMillis();
                st.awaitingApprovalSince = null;
            }
            audit.append(runId, d.approver(), "APPROVAL_DECISION", taskId, Map.of("decision", d.decision(),
                    "mode", spec.approval().name(), "risk", spec.risk().name(),
                    "comment", blankTo(d.comment(), ""), "answers", d.answers() == null ? Map.of() : d.answers()));

            if (approve) {
                mergeAnswers(run, d.answers());
                if (spec.approval() == ApprovalMode.BEFORE) {
                    st.status = TaskStatus.READY;
                } else {
                    commitSuccess(run, spec, st);
                }
            } else if (spec.approval() == ApprovalMode.BEFORE) {
                failRun(run, spec, st, "approval rejected by " + d.approver() + ": " + d.comment());
            } else {
                run.counters.rejections++;
                st.reworks++;
                if (st.reworks > MAX_REWORKS) {
                    failRun(run, spec, st, "rejected " + st.reworks + " times; rework budget exhausted");
                } else {
                    st.generation++;
                    st.attempts = 0;
                    st.status = TaskStatus.PENDING;
                    st.lastError = "rejected by reviewer " + d.approver() + ": " + d.comment();
                    audit.append(runId, "orchestrator", "REWORK_REQUESTED", taskId,
                            Map.of("feedback", st.lastError, "rework", st.reworks));
                }
            }
            save(run);
            return null;
        });
        advance(runId);
        return get(runId);
    }

    /**
     * Upstream change by a human (e.g. a revised answer or requirement): the task is re-executed and, if its
     * output genuinely differs, dependants are invalidated. If the output is unchanged nothing downstream reruns.
     */
    public WorkflowRun reopen(String runId, String taskId, String actor, String reason,
                              Map<String, Object> contextUpdates) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            TaskSpec spec = specOrThrow(run, taskId);
            if (run.status == RunStatus.FAILED) {
                throw new IllegalStateException("a failed run cannot be reopened; start a new run");
            }
            if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("actor and reason are required to reopen a task");
            }
            TaskState st = run.tasks.get(taskId);
            mergeAnswers(run, contextUpdates);
            st.generation++;
            st.status = TaskStatus.PENDING;
            st.attempts = 0;
            st.reworks = 0;
            st.onFallback = false;
            st.approval = null;
            st.notBefore = null;
            st.staleReason = "reopened by " + actor + ": " + reason;
            run.counters.replans++;
            if (run.status == RunStatus.SUCCEEDED) {
                run.status = RunStatus.RUNNING;
                run.finishedAt = null;
            }
            audit.append(runId, actor, "TASK_REOPENED", spec.id(),
                    Map.of("reason", reason, "contextUpdates", contextUpdates == null ? Map.of() : contextUpdates));
            save(run);
            return null;
        });
        advance(runId);
        return get(runId);
    }

    public WorkflowRun safeStop(String runId, String actor, String reason) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.status.isTerminal()) {
                throw new IllegalStateException("run is already " + run.status);
            }
            doSafeStop(run, actor, blankTo(reason, "operator requested safe stop"));
            save(run);
            return null;
        });
        return get(runId);
    }

    public WorkflowRun resume(String runId, String actor, String comment) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.status != RunStatus.SAFE_STOPPED) {
                throw new IllegalStateException("run is " + run.status + ", not safe-stopped");
            }
            if (killSwitch.get()) {
                throw new IllegalStateException("global kill switch is engaged; release it first");
            }
            if (actor == null || actor.isBlank()) {
                throw new IllegalArgumentException("actor is required to resume");
            }
            run.status = RunStatus.RUNNING;
            run.safeStopReason = null;
            audit.append(runId, actor, "RUN_RESUMED", null, Map.of("comment", blankTo(comment, "")));
            save(run);
            return null;
        });
        advance(runId);
        return get(runId);
    }

    /** Global emergency brake: halts every active run and blocks resumption until released. */
    public void setKillSwitch(boolean engaged, String actor) {
        killSwitch.set(engaged);
        if (engaged) {
            for (String id : runs.findIdsByStatus(RunStatus.RUNNING.name(), RunStatus.AWAITING_APPROVAL.name())) {
                withLock(id, () -> {
                    WorkflowRun run = load(id);
                    if (!run.status.isTerminal() && run.status != RunStatus.SAFE_STOPPED) {
                        doSafeStop(run, actor, "global kill switch engaged");
                        save(run);
                    }
                    return null;
                });
            }
        }
        log.warn("kill switch {} by {}", engaged ? "ENGAGED" : "released", actor);
    }

    public boolean killSwitchEngaged() {
        return killSwitch.get();
    }

    /** Blocks until the run stops making autonomous progress (needs a human, finished, failed or stopped). */
    public WorkflowRun awaitQuiescent(String runId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            WorkflowRun snap = get(runId);
            boolean running = snap.tasks.values().stream().anyMatch(t -> t.status == TaskStatus.RUNNING);
            if (snap.status != RunStatus.RUNNING && !running) {
                return snap;
            }
            sleep(15);
        }
        throw new IllegalStateException("run " + runId + " did not settle within " + timeoutMillis + " ms");
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverAfterRestart() {
        for (String id : runs.findIdsByStatus(RunStatus.RUNNING.name())) {
            withLock(id, () -> {
                WorkflowRun run = load(id);
                for (TaskState st : run.tasks.values()) {
                    if (st.status == TaskStatus.RUNNING) {
                        st.generation++;
                        st.status = TaskStatus.READY;
                        audit.append(id, "orchestrator", "TASK_RECOVERED", st.id,
                                Map.of("note", "orphaned by restart; re-queued"));
                    }
                }
                save(run);
                return null;
            });
            advance(id);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        timers.shutdownNow();
    }

    // ------------------------------------------------------------------ scheduling

    /** The scheduler tick: launch every task whose dependencies, approvals and policies allow it. */
    void advance(String runId) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.status.isTerminal() || run.status == RunStatus.SAFE_STOPPED) {
                return null;
            }
            if (killSwitch.get()) {
                doSafeStop(run, "orchestrator", "global kill switch engaged");
                save(run);
                return null;
            }
            for (TaskSpec spec : new ArrayList<>(run.plan)) {
                TaskState st = run.tasks.get(spec.id());
                if ((st.status != TaskStatus.PENDING && st.status != TaskStatus.READY) || !dependenciesMet(run, spec)) {
                    continue;
                }
                if (st.notBefore != null && Instant.now().isBefore(st.notBefore)) {
                    continue;
                }
                if (countRunning(run) >= props.maxParallelTasks()) {
                    break;
                }
                tryLaunch(run, spec, st);
                if (run.status == RunStatus.SAFE_STOPPED || run.status.isTerminal()) {
                    break;
                }
            }
            refreshRunStatus(run);
            save(run);
            return null;
        });
    }

    private void tryLaunch(WorkflowRun run, TaskSpec spec, TaskState st) {
        // 1. human checkpoint before the action
        boolean approved = st.approval != null && st.approval.approved();
        if (spec.approval() == ApprovalMode.BEFORE && !approved) {
            st.status = TaskStatus.AWAITING_APPROVAL;
            st.awaitingApprovalSince = Instant.now();
            audit.append(run.id, "orchestrator", "APPROVAL_REQUESTED", spec.id(),
                    Map.of("mode", "BEFORE", "risk", spec.risk().name(), "title", spec.title()));
            return;
        }
        // 2. entry gate (policy)
        List<Violation> entry = policies.checkEntry(new PolicyContext(run, spec, st));
        recordViolations(run, spec, entry, "entry");
        if (PolicyEngine.hasSeverity(entry, Severity.CRITICAL)) {
            doSafeStop(run, "policy-engine", firstMessage(entry, Severity.CRITICAL));
            return;
        }
        if (PolicyEngine.hasSeverity(entry, Severity.BLOCK)) {
            run.counters.policyBlocks++;
            st.lastError = firstMessage(entry, Severity.BLOCK);
            failRun(run, spec, st, "entry gate blocked: " + st.lastError);
            return;
        }
        // 3. launch
        st.status = TaskStatus.RUNNING;
        st.attempts++;
        st.startedAt = Instant.now();
        st.notBefore = null;
        st.agentUsed = st.onFallback ? spec.fallbackAgent() : spec.agent();
        Map<String, List<Artifact>> upstream = new LinkedHashMap<>();
        List<String> consumed = new ArrayList<>();
        for (String dep : spec.dependsOn()) {
            List<Artifact> latest = artifacts.latestByNode(run.id, dep);
            upstream.put(dep, latest);
            latest.forEach(a -> consumed.add(a.id()));
        }
        st.consumedArtifactIds = consumed;
        st.inputHash = sha256(run.requirement + "|" + writeJson(run.context.get("answers")) + "|"
                + upstream.values().stream().flatMap(List::stream).map(Artifact::sha256).sorted()
                .reduce("", (a, b) -> a + b));
        run.counters.taskExecutions++;
        boolean injectFailure = !st.onFallback && run.chaos.containsKey(spec.id())
                && (run.chaos.get(spec.id()) < 0 || st.attempts <= run.chaos.get(spec.id()));
        audit.append(run.id, st.agentUsed, "TASK_STARTED", spec.id(), Map.of("attempt", st.attempts,
                "fallback", st.onFallback, "inputHash", st.inputHash, "consumedArtifacts", consumed));

        AgentContext ctx = new AgentContext(run.id, spec, st.attempts, st.onFallback, run.requirement,
                deepCopyContext(run), upstream, st.lastError);
        String agentName = st.agentUsed;
        long generation = st.generation;
        workers.submit(() -> execute(run.id, spec.id(), agentName, generation, injectFailure, ctx));
    }

    // ------------------------------------------------------------------ execution (outside the lock)

    private void execute(String runId, String taskId, String agentName, long generation, boolean injectFailure,
                         AgentContext ctx) {
        long t0 = System.nanoTime();
        AgentResult result = null;
        Throwable error = null;
        try {
            if (props.agentThinkMillis() > 0) {
                sleep(props.agentThinkMillis());
            }
            if (injectFailure) {
                throw new IllegalStateException("injected failure: agent '" + agentName + "' crashed (chaos test)");
            }
            result = callWithTimeout(agents.get(agentName), ctx);
            if (result == null) {
                throw new IllegalStateException("agent '" + agentName + "' returned no result");
            }
        } catch (Throwable t) {
            error = t;
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        AgentResult finalResult = result;
        Throwable finalError = error;
        try {
            withLock(runId, () -> {
                onResult(runId, taskId, generation, finalResult, finalError, millis, ctx);
                return null;
            });
            advance(runId);
        } catch (RuntimeException e) {
            log.error("engine failure while applying result of {}/{}", runId, taskId, e);
        }
    }

    private AgentResult callWithTimeout(Agent agent, AgentContext ctx) throws Exception {
        Future<AgentResult> f = workers.submit((Callable<AgentResult>) () -> agent.execute(ctx));
        try {
            return f.get(props.taskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IllegalStateException("agent '" + agent.name() + "' timed out after "
                    + props.taskTimeoutSeconds() + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ result handling (under the lock)

    private void onResult(String runId, String taskId, long generation, AgentResult result, Throwable error,
                          long millis, AgentContext ctx) {
        WorkflowRun run = load(runId);
        TaskSpec spec = run.spec(taskId);
        TaskState st = run.tasks.get(taskId);
        if (st.generation != generation || st.status != TaskStatus.RUNNING) {
            audit.append(runId, "orchestrator", "RESULT_DISCARDED", taskId,
                    Map.of("reason", "upstream change or stop superseded this execution"));
            return;
        }
        st.agentMillis += millis;

        if (error != null) {
            boolean retryable = !(error instanceof NonRetryableAgentException);
            handleFailure(run, spec, st, describe(error), retryable);
            save(run);
            return;
        }

        // ---- exit gate: policies, required artifacts, agent-reported checks
        PolicyContext pc = new PolicyContext(run, spec, st);
        List<Violation> violations = policies.checkExit(pc, result);
        recordViolations(run, spec, violations, "exit");
        List<String> gateFailures = new ArrayList<>();
        violations.stream().filter(v -> v.severity() != Severity.WARN).forEach(v -> gateFailures.add(v.message()));
        for (String required : spec.requiredArtifacts()) {
            if (result.artifacts().stream().noneMatch(a -> a.type().equals(required))) {
                gateFailures.add("required artifact '" + required + "' was not produced");
            }
        }
        for (Check c : result.checks()) {
            if (!c.passed()) {
                gateFailures.add("check '" + c.name() + "' failed: " + c.detail());
            }
        }
        st.lastViolations = violations.stream().map(v -> v.policyId() + ": " + v.message()).toList();

        if (PolicyEngine.hasSeverity(violations, Severity.CRITICAL)) {
            // artifacts are NOT persisted: a leaked credential must not be copied into our own stores
            st.status = TaskStatus.PENDING;
            st.attempts = Math.max(0, st.attempts - 1);
            st.lastError = firstMessage(violations, Severity.CRITICAL);
            doSafeStop(run, "policy-engine", st.lastError);
            save(run);
            return;
        }
        if (!gateFailures.isEmpty()) {
            if (PolicyEngine.hasSeverity(violations, Severity.BLOCK)) {
                run.counters.policyBlocks++;
            }
            audit.append(runId, "orchestrator", "EXIT_GATE_FAILED", taskId,
                    Map.of("failures", gateFailures, "checks", result.checks()));
            handleFailure(run, spec, st, String.join("; ", gateFailures), true);
            save(run);
            return;
        }

        // ---- success: persist artifacts, publish context, apply plan proposals
        List<String> ids = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        try {
            for (ArtifactDraft d : result.artifacts()) {
                Artifact a = new Artifact("art-" + UUID.randomUUID().toString().substring(0, 12), runId, taskId,
                        d.type(), d.name(), artifacts.nextVersion(runId, taskId, d.type(), d.name()),
                        sha256(d.content()), d.content(), Instant.now());
                artifacts.insert(a);
                workspace.write(runId, taskId, d.name(), d.content());
                ids.add(a.id());
                if (!d.type().startsWith("advisory-")) { // advisory (LLM) text is non-deterministic and must not drive re-planning
                    hashes.add(a.sha256());
                }
            }
        } catch (RuntimeException e) {
            handleFailure(run, spec, st, "could not persist artifacts: " + e.getMessage(), true);
            save(run);
            return;
        }
        Collections.sort(hashes);
        st.artifactIds = ids;
        st.outputHash = sha256(String.join("", hashes));
        st.summary = result.summary();
        st.rationale = result.rationale();
        st.checks = new ArrayList<>(result.checks());
        st.lastError = null;
        run.context.putAll(result.contextUpdates());

        for (ReplanRequest rp : result.replans()) {
            PlanMutator.Outcome out = planner.applyReplan(run, taskId, rp);
            if (out.budgetExceeded()) {
                // the proposal is not applied; the task goes back to PENDING so a human can resume (or re-plan) deliberately
                st.status = TaskStatus.PENDING;
                st.attempts = Math.max(0, st.attempts - 1);
                st.lastError = out.error();
                doSafeStop(run, "orchestrator", out.error());
                save(run);
                return;
            }
            if (!out.applied()) {
                audit.append(runId, "orchestrator", "REPLAN_REJECTED", taskId, Map.of("reason", out.error()));
                handleFailure(run, spec, st, "plan change rejected: " + out.error(), false);
                save(run);
                return;
            }
        }

        audit.append(runId, st.agentUsed, "TASK_SUCCEEDED", taskId, Map.of("summary", result.summary(),
                "rationale", result.rationale(), "artifacts", ids, "checks", result.checks(), "millis", millis,
                "attempt", st.attempts));
        metrics.counter("orchestrator.task.result", "result", "success").increment();

        if (spec.approval() == ApprovalMode.AFTER) {
            st.status = TaskStatus.AWAITING_APPROVAL;
            st.awaitingApprovalSince = Instant.now();
            audit.append(runId, "orchestrator", "APPROVAL_REQUESTED", taskId,
                    Map.of("mode", "AFTER", "risk", spec.risk().name(), "title", spec.title(), "artifacts", ids));
        } else {
            commitSuccess(run, spec, st);
        }
        save(run);
    }

    private void commitSuccess(WorkflowRun run, TaskSpec spec, TaskState st) {
        String human = "true".equals(spec.params().get("humanInput")) ? writeJson(run.context.get("answers")) : "";
        String committed = sha256(st.outputHash + "|" + human);
        String previous = st.committedHash;
        st.committedHash = committed;
        st.status = TaskStatus.SUCCEEDED;
        st.finishedAt = Instant.now();
        st.staleReason = null;
        if (st.firstFailureAt != null) {
            run.counters.recoveryMillis.add(Duration.between(st.firstFailureAt, st.finishedAt).toMillis());
            st.firstFailureAt = null;
        }
        if (previous != null && !previous.equals(committed)) {
            // bounded by the DAG itself (each hop needs a genuinely different output), so it is not budgeted
            planner.invalidateDownstream(run, spec.id(), "output of '" + spec.id() + "' changed");
        }
    }

    private void handleFailure(WorkflowRun run, TaskSpec spec, TaskState st, String message, boolean retryable) {
        st.lastError = message;
        run.counters.taskFailures++;
        metrics.counter("orchestrator.task.result", "result", "failure").increment();
        if (st.firstFailureAt == null) {
            st.firstFailureAt = Instant.now();
        }
        audit.append(run.id, st.agentUsed == null ? "orchestrator" : st.agentUsed, "TASK_FAILED", spec.id(),
                Map.of("error", message, "attempt", st.attempts, "retryable", retryable, "fallback", st.onFallback));

        if (retryable && st.attempts <= spec.maxRetries()) {
            long delay = props.retryBackoffMillis() * (1L << Math.min(st.attempts - 1, 6));
            st.status = TaskStatus.READY;
            st.notBefore = Instant.now().plusMillis(delay);
            run.counters.retries++;
            metrics.counter("orchestrator.retries").increment();
            audit.append(run.id, "orchestrator", "RETRY_SCHEDULED", spec.id(),
                    Map.of("nextAttempt", st.attempts + 1, "backoffMillis", delay, "of", spec.maxRetries() + 1,
                            "feedbackForAgent", message));
            String runId = run.id;
            timers.schedule(() -> safeAdvance(runId), delay + 5, TimeUnit.MILLISECONDS);
        } else if (retryable && spec.fallbackAgent() != null && !st.onFallback) {
            st.onFallback = true;
            st.attempts = 0;
            st.status = TaskStatus.READY;
            run.counters.fallbacks++;
            metrics.counter("orchestrator.fallbacks").increment();
            audit.append(run.id, "orchestrator", "FALLBACK_ENGAGED", spec.id(),
                    Map.of("from", spec.agent(), "to", spec.fallbackAgent(), "because", message,
                            "rationale", "primary agent exhausted its retry budget; switching to conservative fallback"));
        } else {
            failRun(run, spec, st, message);
        }
    }

    /** Failure policy: stop the run, roll back completed side-effectful work in reverse order, cancel the rest. */
    private void failRun(WorkflowRun run, TaskSpec failed, TaskState st, String reason) {
        st.status = TaskStatus.FAILED;
        st.finishedAt = Instant.now();
        st.lastError = reason;
        audit.append(run.id, "orchestrator", "RUN_FAILING", failed.id(), Map.of("reason", reason,
                "policy", "bounded retries exhausted -> rollback completed work -> stop"));

        List<TaskSpec> toRollback = run.plan.stream()
                .filter(t -> t.rollbackOnFailure() && run.tasks.get(t.id()).status == TaskStatus.SUCCEEDED)
                .sorted(Comparator.comparing((TaskSpec t) -> run.tasks.get(t.id()).finishedAt).reversed())
                .toList();
        for (TaskSpec t : toRollback) {
            rollbackTask(run, t);
        }
        for (TaskSpec t : run.plan) {
            TaskState ts = run.tasks.get(t.id());
            if (!ts.status.isDone()) {
                if (ts.status == TaskStatus.RUNNING) {
                    ts.generation++;
                }
                ts.status = TaskStatus.CANCELLED;
                ts.staleReason = "cancelled: run failed at " + failed.id();
            }
        }
        run.status = RunStatus.FAILED;
        run.finishedAt = Instant.now();
        audit.append(run.id, "orchestrator", "RUN_FAILED", failed.id(), Map.of("reason", reason,
                "rolledBack", toRollback.stream().map(TaskSpec::id).toList()));
        recordRunMetrics(run);
    }

    private void rollbackTask(WorkflowRun run, TaskSpec t) {
        TaskState ts = run.tasks.get(t.id());
        try {
            AgentContext rc = new AgentContext(run.id, t, ts.attempts, ts.onFallback, run.requirement,
                    deepCopyContext(run), Map.of(), null);
            agents.get(ts.agentUsed == null ? t.agent() : ts.agentUsed).rollback(rc);
            workspace.deleteTask(run.id, t.id());
            ts.status = TaskStatus.ROLLED_BACK;
            run.counters.rollbacks++;
            metrics.counter("orchestrator.rollbacks").increment();
            audit.append(run.id, "orchestrator", "ROLLBACK", t.id(), Map.of("outcome", "reverted",
                    "workspaceCleared", true));
        } catch (Exception e) {
            audit.append(run.id, "orchestrator", "ROLLBACK_FAILED", t.id(),
                    Map.of("error", describe(e), "action", "manual clean-up required"));
        }
    }

    // ------------------------------------------------------------------ status, gates, helpers

    private void refreshRunStatus(WorkflowRun run) {
        if (run.status.isTerminal() || run.status == RunStatus.SAFE_STOPPED) {
            return;
        }
        boolean allDone = run.tasks.values().stream()
                .allMatch(t -> t.status == TaskStatus.SUCCEEDED || t.status == TaskStatus.CANCELLED);
        if (allDone) {
            run.status = RunStatus.SUCCEEDED;
            run.finishedAt = Instant.now();
            audit.append(run.id, "orchestrator", "RUN_COMPLETED", null, Map.of("tasks", run.tasks.size(),
                    "replans", run.counters.replans, "retries", run.counters.retries));
            recordRunMetrics(run);
            return;
        }
        boolean progressing = run.plan.stream().anyMatch(t -> {
            TaskState s = run.tasks.get(t.id());
            return s.status == TaskStatus.RUNNING
                    || ((s.status == TaskStatus.PENDING || s.status == TaskStatus.READY) && dependenciesMet(run, t));
        });
        boolean awaiting = run.tasks.values().stream().anyMatch(t -> t.status == TaskStatus.AWAITING_APPROVAL);
        if (progressing) {
            run.status = RunStatus.RUNNING;
        } else if (awaiting) {
            run.status = RunStatus.AWAITING_APPROVAL;
        } else {
            TaskSpec any = run.plan.stream().filter(t -> !run.tasks.get(t.id()).status.isDone()).findFirst().orElseThrow();
            failRun(run, any, run.tasks.get(any.id()), "plan deadlock: no runnable task and nothing awaiting a human");
        }
    }

    private boolean dependenciesMet(WorkflowRun run, TaskSpec spec) {
        return spec.dependsOn().stream().map(run.tasks::get)
                .allMatch(s -> s.status == TaskStatus.SUCCEEDED || s.status == TaskStatus.CANCELLED);
    }

    private long countRunning(WorkflowRun run) {
        return run.tasks.values().stream().filter(t -> t.status == TaskStatus.RUNNING).count();
    }

    private void doSafeStop(WorkflowRun run, String actor, String reason) {
        if (run.status == RunStatus.SAFE_STOPPED) {
            return;
        }
        run.status = RunStatus.SAFE_STOPPED;
        run.safeStopReason = reason;
        audit.append(run.id, actor, "SAFE_STOP", null, Map.of("reason", reason,
                "inFlight", run.tasks.values().stream().filter(t -> t.status == TaskStatus.RUNNING).map(t -> t.id).toList(),
                "note", "no new tasks start; a human must resume"));
        metrics.counter("orchestrator.safe_stops").increment();
    }

    private void recordViolations(WorkflowRun run, TaskSpec spec, List<Violation> violations, String phase) {
        for (Violation v : violations) {
            audit.append(run.id, "policy-engine", "POLICY_VIOLATION", spec.id(),
                    Map.of("policy", v.policyId(), "severity", v.severity().name(), "message", v.message(),
                            "phase", phase));
            metrics.counter("orchestrator.policy.violations", "policy", v.policyId(), "severity", v.severity().name())
                    .increment();
        }
    }

    private void recordRunMetrics(WorkflowRun run) {
        if (run.createdAt != null && run.finishedAt != null) {
            metrics.timer("orchestrator.run.duration", "scenario", run.scenario, "status", run.status.name())
                    .record(Duration.between(run.createdAt, run.finishedAt));
        }
    }

    private void validateDecision(TaskSpec spec, ApprovalDecision d) {
        if (d == null || (!"APPROVE".equals(d.decision()) && !"REJECT".equals(d.decision()))) {
            throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        }
        if (d.approver() == null || d.approver().isBlank() || d.approver().length() > 64) {
            throw new IllegalArgumentException("approver identity is required");
        }
        if (d.approver().toLowerCase().startsWith("agent")) {
            throw new IllegalArgumentException("agents cannot approve their own work (separation of duties)");
        }
        boolean needsComment = "REJECT".equals(d.decision()) || spec.risk() == com.sa.agentic.orchestrator.model.Risk.HIGH;
        if (needsComment && (d.comment() == null || d.comment().trim().length() < 10)) {
            throw new IllegalArgumentException("a justification of at least 10 characters is required for "
                    + ("REJECT".equals(d.decision()) ? "rejections" : "high-risk approvals"));
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeAnswers(WorkflowRun run, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, Object> answers = (Map<String, Object>) run.context.computeIfAbsent("answers",
                k -> new LinkedHashMap<String, Object>());
        answers.putAll(updates);
    }

    private TaskSpec specOrThrow(WorkflowRun run, String taskId) {
        return run.plan.stream().filter(t -> t.id().equals(taskId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("unknown task " + taskId));
    }

    private WorkflowRun load(String runId) {
        WorkflowRun cached = cache.get(runId);
        if (cached != null) {
            return cached;
        }
        WorkflowRun loaded = runs.find(runId).orElseThrow(() -> new NoSuchElementException("unknown run " + runId));
        WorkflowRun prior = cache.putIfAbsent(runId, loaded);
        return prior != null ? prior : loaded;
    }

    private void save(WorkflowRun run) {
        runs.save(run);
    }

    private <T> T withLock(String runId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(runId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void safeAdvance(String runId) {
        try {
            advance(runId);
        } catch (RuntimeException e) {
            log.error("scheduled advance failed for {}", runId, e);
        }
    }

    private WorkflowRun copy(WorkflowRun run) {
        try {
            return json.readValue(json.writeValueAsString(run), WorkflowRun.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot snapshot run", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyContext(WorkflowRun run) {
        return json.convertValue(run.context, Map.class);
    }

    private String writeJson(Object o) {
        try {
            return o == null ? "" : json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    private static String firstMessage(List<Violation> v, Severity s) {
        return v.stream().filter(x -> x.severity() == s).map(Violation::message).findFirst().orElse("policy violation");
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
