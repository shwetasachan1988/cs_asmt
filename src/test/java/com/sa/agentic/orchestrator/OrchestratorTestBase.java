package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.fail;

import com.sa.agentic.orchestrator.agent.Agent;
import com.sa.agentic.orchestrator.agent.AgentContext;
import com.sa.agentic.orchestrator.engine.ApprovalDecision;
import com.sa.agentic.orchestrator.engine.StartRequest;
import com.sa.agentic.orchestrator.engine.WorkflowEngine;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.AuditEvent;
import com.sa.agentic.orchestrator.model.ReplanRequest;
import com.sa.agentic.orchestrator.model.Risk;
import com.sa.agentic.orchestrator.model.RunStatus;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import com.sa.agentic.orchestrator.persistence.ArtifactRepository;
import com.sa.agentic.orchestrator.persistence.AuditLog;
import com.sa.agentic.orchestrator.scenario.ScenarioDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real application context on a random port (the smoke-test agents call the live HTTP API), H2 for persistence,
 * zero agent latency. Test-only agents/scenarios exercise policy and failure paths the shipped scenarios avoid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:orch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "shortener.rate-limit.max-requests=100000"})
@ActiveProfiles("test")
@Import(OrchestratorTestBase.TestAgents.class)
abstract class OrchestratorTestBase {

    static final String APPROVER = "alice.reviewer";
    static final String COMMENT = "reviewed the artifacts and approve for this test";

    @Autowired protected WorkflowEngine engine;
    @Autowired protected AuditLog audit;
    @Autowired protected ArtifactRepository artifacts;

    protected WorkflowRun start(String scenario, String failureMode) {
        return engine.start(new StartRequest(scenario, null, failureMode, null, "tester"));
    }

    /** Approves every checkpoint it meets until the run reaches a terminal or stopped state. */
    protected WorkflowRun driveToEnd(String runId, Map<String, Object> clarifyAnswers) {
        for (int i = 0; i < 60; i++) {
            WorkflowRun r = engine.awaitQuiescent(runId, 60_000);
            if (r.status == RunStatus.SUCCEEDED || r.status == RunStatus.FAILED || r.status == RunStatus.SAFE_STOPPED) {
                return r;
            }
            for (var e : r.tasks.entrySet()) {
                if (e.getValue().status == TaskStatus.AWAITING_APPROVAL) {
                    engine.decide(runId, e.getKey(), new ApprovalDecision("APPROVE", APPROVER, COMMENT,
                            e.getKey().equals("clarify") ? clarifyAnswers : null));
                }
            }
        }
        return fail("run did not finish: " + engine.get(runId).status);
    }

    protected List<AuditEvent> events(String runId) {
        return audit.events(runId);
    }

    protected long countEvents(String runId, String type) {
        return events(runId).stream().filter(e -> e.type().equals(type)).count();
    }

    protected int firstSeq(String runId, String type, String node) {
        return events(runId).stream().filter(e -> e.type().equals(type) && node.equals(e.nodeId()))
                .mapToInt(AuditEvent::seq).min().orElse(-1);
    }

    // ------------------------------------------------------------------ test-only agents and scenarios

    @TestConfiguration
    static class TestAgents {

        /** Stand-in for Claude: different text on every call, like a real LLM. Must never influence planning. */
        @Bean
        @org.springframework.context.annotation.Primary
        com.sa.agentic.orchestrator.agent.LlmAdvisor nondeterministicAdvisor() {
            return new com.sa.agentic.orchestrator.agent.LlmAdvisor() {
                public boolean enabled() { return true; }

                public String modelName() { return "stub-llm"; }

                public java.util.Optional<String> advise(String system, String user) {
                    return java.util.Optional.of("- take " + java.util.UUID.randomUUID());
                }
            };
        }

        @Bean
        Agent leakyAgent() {
            return new Agent() {
                public String name() { return "leaky"; }

                public AgentResult execute(AgentContext ctx) {
                    return AgentResult.builder("docs").artifact("docs", "notes.md",
                            "deploy with AKIAIOSFODNN7EXAMPLE and move on").build();
                }
            };
        }

        @Bean
        Agent overreachingPlannerAgent() {
            return new Agent() {
                public String name() { return "overreaching-planner"; }

                public AgentResult execute(AgentContext ctx) {
                    TaskSpec prod = TaskSpec.of("deploy-prod", Stage.RELEASE, "leaky").risk(Risk.HIGH).build();
                    return AgentResult.builder("plan").artifact("docs", "plan.md", "deploy straight to production")
                            .replan(ReplanRequest.add("ship it", List.of(prod), Map.of())).build();
                }
            };
        }

        @Bean
        Agent runawayPlannerAgent() {
            return new Agent() {
                public String name() { return "runaway-planner"; }

                public AgentResult execute(AgentContext ctx) {
                    String id = "child-" + System.nanoTime();
                    TaskSpec child = TaskSpec.of(id, Stage.IMPLEMENTATION, "runaway-planner").after(ctx.task().id())
                            .produces("docs").build();
                    return AgentResult.builder("spawn").artifact("docs", "x-" + id + ".md", id)
                            .replan(ReplanRequest.add("more work", List.of(child), Map.of())).build();
                }
            };
        }

        @Bean
        ScenarioDefinition secretLeakScenario() {
            return simple("test-secret-leak", List.of(TaskSpec.of("leak", Stage.IMPLEMENTATION, "leaky")
                    .produces("docs").build()));
        }

        @Bean
        ScenarioDefinition overreachScenario() {
            return simple("test-overreach", List.of(TaskSpec.of("plan", Stage.DESIGN, "overreaching-planner")
                    .retries(0).produces("docs").build()));
        }

        @Bean
        ScenarioDefinition runawayScenario() {
            return simple("test-runaway", List.of(TaskSpec.of("seed", Stage.IMPLEMENTATION, "runaway-planner")
                    .produces("docs").build()));
        }

        @Bean
        ScenarioDefinition approvalScenario() {
            return simple("test-approval", List.of(
                    TaskSpec.of("draft", Stage.DESIGN, "leaky-free-writer").approval(ApprovalMode.AFTER)
                            .produces("docs").build()));
        }

        @Bean
        Agent writerAgent() {
            return new Agent() {
                public String name() { return "leaky-free-writer"; }

                public AgentResult execute(AgentContext ctx) {
                    String feedback = ctx.feedback() == null ? "first draft" : "revised: " + ctx.feedback();
                    return AgentResult.builder("draft").artifact("docs", "draft.md", feedback).build();
                }
            };
        }

        private static ScenarioDefinition simple(String id, List<TaskSpec> plan) {
            return new ScenarioDefinition() {
                public String id() { return id; }
                public String title() { return id; }
                public Kind kind() { return Kind.GREENFIELD; }
                public String description() { return "test scenario"; }
                public String defaultRequirement() { return "test requirement"; }
                public String planRationale() { return "test"; }
                public List<TaskSpec> initialPlan(String r) { return plan; }
                public Map<String, Integer> transientFailures() { return Map.of(); }
                public Map<String, Integer> permanentFailures() { return Map.of(); }
            };
        }
    }
}
