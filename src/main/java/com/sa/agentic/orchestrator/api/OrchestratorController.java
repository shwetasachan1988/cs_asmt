package com.sa.agentic.orchestrator.api;

import com.sa.agentic.orchestrator.agent.AgentRegistry;
import com.sa.agentic.orchestrator.engine.ApprovalDecision;
import com.sa.agentic.orchestrator.engine.StartRequest;
import com.sa.agentic.orchestrator.engine.WorkflowEngine;
import com.sa.agentic.orchestrator.metrics.MetricsService;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.AuditEvent;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.persistence.ArtifactRepository;
import com.sa.agentic.orchestrator.persistence.AuditLog;
import com.sa.agentic.orchestrator.policy.PolicyEngine;
import com.sa.agentic.orchestrator.scenario.ScenarioCatalog;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Control plane of the agentic SDLC orchestrator. Every mutating call is attributed to an actor and audited. */
@RestController
@RequestMapping("/api/orchestrator")
@Tag(name = "Orchestrator")
public class OrchestratorController {

    public record StartBody(String scenario, String requirement, String failureMode, String actor) {
    }

    public record DecisionBody(String decision, String approver, String comment, Map<String, Object> answers) {
    }

    public record ReopenBody(String actor, String reason, Map<String, Object> answers) {
    }

    public record StopBody(String actor, String reason) {
    }

    public record SwitchBody(boolean engaged, String actor) {
    }

    private final WorkflowEngine engine;
    private final ScenarioCatalog catalog;
    private final AgentRegistry agents;
    private final PolicyEngine policies;
    private final ArtifactRepository artifacts;
    private final AuditLog audit;
    private final MetricsService metrics;
    private final RunInsightService insights;

    public OrchestratorController(WorkflowEngine engine, ScenarioCatalog catalog, AgentRegistry agents,
                                  PolicyEngine policies, ArtifactRepository artifacts, AuditLog audit,
                                  MetricsService metrics, RunInsightService insights) {
        this.engine = engine;
        this.catalog = catalog;
        this.agents = agents;
        this.policies = policies;
        this.artifacts = artifacts;
        this.audit = audit;
        this.metrics = metrics;
        this.insights = insights;
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return catalog.all().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("title", s.title());
            m.put("kind", s.kind());
            m.put("description", s.description());
            m.put("defaultRequirement", s.defaultRequirement());
            m.put("planRationale", s.planRationale());
            return m;
        }).toList();
    }

    @GetMapping("/governance")
    public Map<String, Object> governance() {
        return Map.of("policies", policies.policyIds(), "agents", agents.names(),
                "killSwitchEngaged", engine.killSwitchEngaged());
    }

    @PostMapping("/runs")
    public WorkflowRun start(@RequestBody StartBody b) {
        return engine.start(new StartRequest(b.scenario(), b.requirement(), b.failureMode(), null, b.actor()));
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return engine.list().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("scenario", r.scenario);
            m.put("status", r.status);
            m.put("createdAt", r.createdAt);
            m.put("finishedAt", r.finishedAt);
            m.put("tasks", r.tasks.size());
            m.put("awaitingApproval", r.tasks.values().stream().filter(t -> t.status == TaskStatus.AWAITING_APPROVAL).count());
            return m;
        }).toList();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable String id) {
        WorkflowRun r = engine.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", r);
        m.put("metrics", MetricsService.forRun(r));
        return m;
    }

    @PostMapping("/runs/{id}/tasks/{taskId}/decision")
    public WorkflowRun decide(@PathVariable String id, @PathVariable String taskId, @RequestBody DecisionBody b) {
        return engine.decide(id, taskId, new ApprovalDecision(b.decision(), b.approver(), b.comment(), b.answers()));
    }

    @PostMapping("/runs/{id}/tasks/{taskId}/reopen")
    public WorkflowRun reopen(@PathVariable String id, @PathVariable String taskId, @RequestBody ReopenBody b) {
        return engine.reopen(id, taskId, b.actor(), b.reason(), b.answers());
    }

    @PostMapping("/runs/{id}/safe-stop")
    public WorkflowRun safeStop(@PathVariable String id, @RequestBody StopBody b) {
        return engine.safeStop(id, b.actor(), b.reason());
    }

    @PostMapping("/runs/{id}/resume")
    public WorkflowRun resume(@PathVariable String id, @RequestBody StopBody b) {
        return engine.resume(id, b.actor(), b.reason());
    }

    @PostMapping("/kill-switch")
    public Map<String, Object> killSwitch(@RequestBody SwitchBody b) {
        engine.setKillSwitch(b.engaged(), b.actor() == null ? "anonymous" : b.actor());
        return Map.of("engaged", engine.killSwitchEngaged());
    }

    @GetMapping("/runs/{id}/artifacts")
    public List<Map<String, Object>> artifactList(@PathVariable String id) {
        engine.get(id);
        return artifacts.findByRun(id).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("task", a.nodeId());
            m.put("name", a.name());
            m.put("type", a.type());
            m.put("version", a.version());
            m.put("sha256", a.sha256());
            m.put("bytes", a.content().length());
            return m;
        }).toList();
    }

    @GetMapping(value = "/runs/{id}/artifacts/{artifactId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String artifact(@PathVariable String id, @PathVariable String artifactId) {
        Artifact a = artifacts.find(artifactId).filter(x -> x.runId().equals(id))
                .orElseThrow(() -> new NoSuchElementException("unknown artifact " + artifactId));
        return a.content();
    }

    @GetMapping("/runs/{id}/audit")
    public List<AuditEvent> auditTrail(@PathVariable String id) {
        engine.get(id);
        return audit.events(id);
    }

    @GetMapping("/runs/{id}/audit/verify")
    public AuditLog.VerifyResult verify(@PathVariable String id) {
        engine.get(id);
        return audit.verify(id);
    }

    @GetMapping("/runs/{id}/lineage")
    public Map<String, Object> lineage(@PathVariable String id) {
        return insights.lineage(id);
    }

    @GetMapping(value = "/runs/{id}/summary", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public String summary(@PathVariable String id) {
        return insights.finalSummary(id);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.summary();
    }
}
