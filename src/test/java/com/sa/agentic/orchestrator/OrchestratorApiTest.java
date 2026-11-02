package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.metrics.MetricsService;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The control-plane REST API as the dashboard uses it, over real HTTP. */
class OrchestratorApiTest extends OrchestratorTestBase {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired MetricsService metricsService;

    private JsonNode post(String url, Object body) throws Exception {
        ResponseEntity<String> r = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body), String.class);
        return json.readTree(r.getBody());
    }

    @Test
    void exposesScenariosGovernanceAndTheFullRunLifecycle() throws Exception {
        JsonNode scenarios = json.readTree(http.getForObject("/api/orchestrator/scenarios", String.class));
        assertThat(scenarios).extracting(n -> n.path("kind").asText())
                .contains("GREENFIELD", "BROWNFIELD", "AMBIGUOUS");

        JsonNode gov = json.readTree(http.getForObject("/api/orchestrator/governance", String.class));
        assertThat(gov.path("policies").toString()).contains("SEC-001", "CMP-001", "SUP-001", "CHG-001", "AUT-001");
        assertThat(gov.path("agents").size()).isGreaterThanOrEqualTo(14);

        JsonNode run = post("/api/orchestrator/runs",
                Map.of("scenario", "greenfield-bulk-links", "failureMode", "none", "actor", "api-tester"));
        String id = run.path("id").asText();
        assertThat(id).startsWith("run-");

        WorkflowRun end = driveToEnd(id, null);
        assertThat(end.status.name()).isEqualTo("SUCCEEDED");

        JsonNode detail = json.readTree(http.getForObject("/api/orchestrator/runs/" + id, String.class));
        assertThat(detail.path("run").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(detail.path("metrics").path("retries").asInt()).isZero();

        JsonNode artifactList = json.readTree(http.getForObject("/api/orchestrator/runs/" + id + "/artifacts", String.class));
        assertThat(artifactList.size()).isGreaterThan(10);
        String artifactId = artifactList.get(0).path("id").asText();
        assertThat(http.getForObject("/api/orchestrator/runs/" + id + "/artifacts/" + artifactId, String.class)).isNotBlank();

        JsonNode verify = json.readTree(http.getForObject("/api/orchestrator/runs/" + id + "/audit/verify", String.class));
        assertThat(verify.path("valid").asBoolean()).isTrue();
        assertThat(verify.path("events").asInt()).isGreaterThan(20);

        JsonNode lineage = json.readTree(http.getForObject("/api/orchestrator/runs/" + id + "/lineage", String.class));
        assertThat(lineage.path("artifactFlow").toString()).contains("\"producedBy\":\"design\"").contains("\"consumedBy\":\"impl-core\"");
        assertThat(lineage.path("decisions").toString()).contains("PLAN_CREATED").contains("APPROVAL_DECISION");

        String summary = http.getForObject("/api/orchestrator/runs/" + id + "/summary", String.class);
        assertThat(summary).contains("# Final engineering summary").contains("## 2. Plan and rationale")
                .contains("## 6. Risks, trade-offs and decisions").contains("## 8. Limitations").contains("impl-core");

        JsonNode metrics = json.readTree(http.getForObject("/api/orchestrator/metrics", String.class));
        assertThat(metrics.path("runSuccessRate").isNumber()).isTrue();
        assertThat(metrics.path("endToEndLatencyMillis").path("p95").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void mapsErrorsToProblemResponses() throws Exception {
        assertThat(http.getForEntity("/api/orchestrator/runs/run-nope", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/orchestrator/runs", Map.of("scenario", "nope"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode run = post("/api/orchestrator/runs", Map.of("scenario", "greenfield-bulk-links", "actor", "api-tester"));
        String id = run.path("id").asText();
        engine.awaitQuiescent(id, 30_000);
        ResponseEntity<String> bad = http.postForEntity("/api/orchestrator/runs/" + id + "/tasks/design/decision",
                Map.of("decision", "APPROVE", "approver", "agent-1", "comment", COMMENT), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> conflict = http.postForEntity("/api/orchestrator/runs/" + id + "/resume",
                Map.of("actor", "x", "reason", "y"), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void metricsAggregationComputesPercentilesAndRates() {
        assertThat(MetricsService.percentile(java.util.List.of(10L, 20L, 30L, 40L, 50L), 50)).isEqualTo(30);
        assertThat(MetricsService.percentile(java.util.List.of(10L, 20L, 30L, 40L, 50L), 95)).isEqualTo(50);
        assertThat(metricsService.summary()).containsKeys("runSuccessRate", "retryRate", "rollbackFrequency", "mttrMillis",
                "endToEndLatencyMillis", "byScenario");
    }
}
