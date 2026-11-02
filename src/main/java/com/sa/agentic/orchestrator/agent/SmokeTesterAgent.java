package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.OrchestratorProperties;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Check;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * End-to-end tests against the <em>running</em> shortener over HTTP: baseline behaviour (create, redirect, read,
 * stats, delete, validation) or the analytics pipeline (async click capture -> stats). An unreachable service is an
 * infrastructure failure, so it throws (retryable) instead of reporting a misleading test failure.
 */
@Component
public class SmokeTesterAgent extends BaseAgent {

    private final OrchestratorProperties props;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();

    public SmokeTesterAgent(ObjectMapper json, OrchestratorProperties props, Environment env) {
        super(json);
        this.props = props;
        this.env = env;
    }

    /** Explicit override wins; otherwise the port this very process is serving on (read lazily - it is only known after startup). */
    private String baseUrl() {
        if (props.smokeBaseUrl() != null && !props.smokeBaseUrl().isBlank()) {
            return props.smokeBaseUrl().replaceAll("/+$", "");
        }
        return "http://localhost:" + env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
    }

    @Override
    public String name() {
        return "smoke-tester";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        String base = baseUrl();
        List<Check> checks = new ArrayList<>();
        String focus = ctx.param("focus", "baseline");
        try {
            if (focus.equals("analytics")) {
                analytics(base, ctx, checks);
            } else {
                baseline(base, ctx, checks);
            }
        } catch (IOException e) {
            throw new IllegalStateException("service under test unreachable at " + base + ": " + e.getMessage(), e);
        }

        StringBuilder report = new StringBuilder("# Integration tests (" + focus + ")\n\nTarget: `" + base
                + "`\n\n| Check | Result | Detail |\n|---|---|---|\n");
        checks.forEach(c -> report.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "FAIL")
                .append(" | ").append(c.detail().replace("|", "/")).append(" |\n"));
        AgentResult.Builder out = AgentResult.builder(checks.stream().allMatch(Check::passed)
                        ? checks.size() + " live HTTP check(s) passed (" + focus + ")" : "Live regression check failed")
                .rationale("These calls hit the deployed service, so they catch wiring, serialization and persistence "
                        + "problems that unit-level checks cannot see.")
                .artifact("test-report", "integration-" + focus + ".md", report.toString());
        checks.forEach(out::check);
        return out.build();
    }

    private void baseline(String base, AgentContext ctx, List<Check> checks) throws IOException, InterruptedException {
        String target = "https://example.com/smoke/" + ctx.runId();
        HttpResponse<String> created = post(base + "/api/v1/links", "{\"url\":\"" + target + "\"}");
        JsonNode body = json.readTree(created.body().isBlank() ? "{}" : created.body());
        String code = body.path("code").asText("");
        // details must be deterministic: a report that embeds random codes/ids looks "changed" on every re-run
        // and would needlessly invalidate everything downstream (and revoke its approvals)
        checks.add(created.statusCode() == 201 && code.length() == 7
                ? Check.pass("create-201", "201 Created with a 7-character code")
                : Check.fail("create-201", "status " + created.statusCode()));
        if (code.isEmpty()) {
            return;
        }
        HttpResponse<String> redirect = get(base + "/" + code, null);
        checks.add(redirect.statusCode() == 302 && target.equals(redirect.headers().firstValue("Location").orElse(""))
                ? Check.pass("redirect-302", "Location matches target")
                : Check.fail("redirect-302", "status " + redirect.statusCode()));
        HttpResponse<String> meta = get(base + "/api/v1/links/" + code, null);
        checks.add(meta.statusCode() == 200 && target.equals(json.readTree(meta.body()).path("targetUrl").asText())
                ? Check.pass("metadata-200", "targetUrl round-trips") : Check.fail("metadata-200", "status " + meta.statusCode()));
        HttpResponse<String> stats = get(base + "/api/v1/links/" + code + "/stats", null);
        checks.add(stats.statusCode() == 200 && json.readTree(stats.body()).has("totalClicks")
                ? Check.pass("stats-200", "stats payload present") : Check.fail("stats-200", "status " + stats.statusCode()));
        HttpResponse<String> bad = post(base + "/api/v1/links", "{\"url\":\"javascript:alert(1)\"}");
        checks.add(bad.statusCode() == 400 ? Check.pass("rejects-unsafe-url", "javascript: URL -> 400")
                : Check.fail("rejects-unsafe-url", "status " + bad.statusCode()));
        HttpResponse<String> del = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links/" + code)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> gone = get(base + "/" + code, null);
        checks.add(del.statusCode() == 204 && gone.statusCode() == 404
                ? Check.pass("delete-then-404", "deleted link no longer redirects")
                : Check.fail("delete-then-404", "delete " + del.statusCode() + ", redirect " + gone.statusCode()));
    }

    private void analytics(String base, AgentContext ctx, List<Check> checks) throws IOException, InterruptedException {
        String target = "https://example.com/analytics/" + ctx.runId();
        String code = json.readTree(post(base + "/api/v1/links", "{\"url\":\"" + target + "\"}").body()).path("code").asText("");
        if (code.isEmpty()) {
            checks.add(Check.fail("create-link", "could not create a link"));
            return;
        }
        get(base + "/" + code, "https://news.example.org/");
        get(base + "/" + code, "https://news.example.org/");
        JsonNode stats = null;
        for (int i = 0; i < 40; i++) { // click capture is asynchronous by design
            stats = json.readTree(get(base + "/api/v1/links/" + code + "/stats", null).body());
            if (stats.path("totalClicks").asLong() >= 2) {
                break;
            }
            Thread.sleep(150);
        }
        checks.add(stats != null && stats.path("totalClicks").asLong() == 2
                ? Check.pass("clicks-counted", "2 redirects -> totalClicks 2")
                : Check.fail("clicks-counted", "stats: " + stats));
        checks.add(stats != null && stats.path("uniqueVisitors").asLong() == 1
                ? Check.pass("unique-visitors", "same client counted once") : Check.fail("unique-visitors", "stats: " + stats));
        checks.add(stats != null && stats.path("topReferrers").has("https://news.example.org/")
                ? Check.pass("referrer-tracked", "referrer attributed") : Check.fail("referrer-tracked", "stats: " + stats));
        http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/links/" + code)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> post(String url, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url, String referer) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
        if (referer != null) {
            b.header("Referer", referer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
