package com.sa.agentic.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * Command-line driver for the orchestrator's REST API: starts the scenarios, shows progress, walks the human
 * checkpoints (interactively or with --auto) and saves each run's final summary and snapshot under docs/runs.
 *
 * <pre>
 *   ./mvnw -q compile exec:java -Dexec.args="--auto"
 *   ./mvnw -q compile exec:java -Dexec.args="--scenario brownfield --failure transient --auto"
 *   ./mvnw -q compile exec:java -Dexec.args="--scenario ambiguous --auto --answers tracking=identified_users --change tracking=aggregate_only"
 *   docker compose --profile demo run --rm demo --auto
 * </pre>
 */
public final class DemoRunner {

    private static final Map<String, String> SCENARIOS = Map.of(
            "greenfield", "greenfield-bulk-links",
            "brownfield", "brownfield-link-expiry",
            "ambiguous", "ambiguous-safer-faster-tracking");
    private static final List<String> ORDER = List.of("greenfield", "brownfield", "ambiguous");
    private static final Set<String> SHOW = Set.of("REPLAN", "POLICY_VIOLATION", "FALLBACK_ENGAGED", "RETRY_SCHEDULED",
            "ROLLBACK", "SAFE_STOP", "UPSTREAM_CHANGED", "REWORK_REQUESTED", "REPLAN_REJECTED", "RUN_FAILED", "TASK_REOPENED");

    private record Options(String base, String scenario, String failure, String approver, boolean auto,
                           Map<String, String> answers, Map<String, String> change, Path out) {
    }

    private final Options opt;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Scanner stdin = new Scanner(System.in);

    private DemoRunner(Options opt) {
        this.opt = opt;
    }

    public static void main(String[] args) throws Exception {
        Options o = parse(args);
        DemoRunner d = new DemoRunner(o);
        List<String> keys = o.scenario().equals("all") ? ORDER : List.of(o.scenario());
        Map<String, String> results = new LinkedHashMap<>();
        for (String k : keys) {
            results.put(k, d.runScenario(k));
        }
        System.out.println("\nSummary: " + results);
        System.out.println("Global run counts: " + d.get("/metrics").path("runs"));
    }

    // ---------------------------------------------------------------- one scenario

    private String runScenario(String key) throws Exception {
        String scenario = SCENARIOS.get(key);
        System.out.printf("%n%s%n%s - %s   (failure injection: %s)%n%s%n", "=".repeat(78), key.toUpperCase(), scenario,
                opt.failure(), "=".repeat(78));
        ObjectNode body = json.createObjectNode().put("scenario", scenario).put("failureMode", opt.failure())
                .put("actor", opt.approver());
        JsonNode started = post("/runs", body);
        String id = started.path("id").asText();
        System.out.printf("run %s started; initial plan has %d task(s)%n", id, started.path("plan").size());

        Map<String, String> answers = new LinkedHashMap<>(opt.answers());
        Map<String, String> lastStatus = new LinkedHashMap<>();
        int[] seen = {0};
        JsonNode state = drive(id, answers, lastStatus, seen);

        if (!opt.change().isEmpty() && "SUCCEEDED".equals(state.path("status").asText()) && state.path("tasks").has("clarify")) {
            System.out.println("\n  ** UPSTREAM CHANGE: re-opening 'clarify' with " + opt.change());
            ObjectNode reopen = json.createObjectNode().put("actor", opt.approver())
                    .put("reason", "stakeholder changed an answer after completion");
            reopen.set("answers", json.valueToTree(opt.change()));
            post("/runs/" + id + "/tasks/clarify/reopen", reopen);
            answers.putAll(opt.change());
            state = drive(id, answers, lastStatus, seen);
        }

        JsonNode detail = get("/runs/" + id);
        JsonNode run = detail.path("run");
        JsonNode m = detail.path("metrics");
        JsonNode verify = get("/runs/" + id + "/audit/verify");
        System.out.printf("%n  RESULT: %s  tasks=%d (plan v%d)  audit chain %s (%d events)%n", run.path("status").asText(),
                run.path("plan").size(), run.path("planVersion").asInt(),
                verify.path("valid").asBoolean() ? "intact" : "BROKEN", verify.path("events").asInt());
        System.out.printf("  e2e %d ms (human wait %d ms) | executions %d retries %d fallbacks %d rollbacks %d "
                        + "policy-blocks %d re-plans %d invalidated %d MTTR %s%n",
                m.path("endToEndMillis").asLong(), m.path("humanWaitMillis").asLong(), m.path("taskExecutions").asInt(),
                m.path("retries").asInt(), m.path("fallbacks").asInt(), m.path("rollbacks").asInt(),
                m.path("policyBlocks").asInt(), m.path("replans").asInt(), m.path("invalidations").asInt(),
                m.path("mttrMillis").asText("-"));

        Files.createDirectories(opt.out());
        String name = key + "-" + opt.failure() + "-" + id + (opt.change().isEmpty() ? "" : "-change");
        Files.writeString(opt.out().resolve(name + ".md"), text("/runs/" + id + "/summary"), StandardCharsets.UTF_8);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("run", run);
        snapshot.set("metrics", m);
        snapshot.set("audit", get("/runs/" + id + "/audit"));
        Files.writeString(opt.out().resolve(name + ".json"), json.writeValueAsString(snapshot), StandardCharsets.UTF_8);
        System.out.println("  final engineering summary -> " + opt.out().resolve(name + ".md"));
        return run.path("status").asText();
    }

    /** Polls until the run needs nothing more from us, approving human checkpoints on the way. */
    private JsonNode drive(String id, Map<String, String> answers, Map<String, String> last, int[] seen) throws Exception {
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = get("/runs/" + id).path("run");
            for (var it = run.path("tasks").fields(); it.hasNext(); ) {
                var e = it.next();
                String status = e.getValue().path("status").asText();
                if (!status.equals(last.get(e.getKey()))) {
                    int attempts = e.getValue().path("attempts").asInt();
                    String agent = e.getValue().path("agentUsed").asText("");
                    System.out.printf("  %-22s %s%s%s%n", e.getKey(), status, attempts > 1 ? " (attempt " + attempts + ")" : "",
                            agent.isEmpty() ? "" : " via " + agent);
                    last.put(e.getKey(), status);
                }
            }
            JsonNode events = get("/runs/" + id + "/audit");
            for (int i = seen[0]; i < events.size(); i++) {
                JsonNode ev = events.get(i);
                if (SHOW.contains(ev.path("type").asText())) {
                    String d = ev.path("detail").asText();
                    System.out.printf("      [%s] %s %s%n", ev.path("type").asText(), ev.path("nodeId").asText(""),
                            d.length() < 200 ? d : d.substring(0, 197) + "...");
                }
            }
            seen[0] = events.size();

            String status = run.path("status").asText();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                return run;
            }
            if (status.equals("SAFE_STOPPED")) {
                System.out.println("  SAFE-STOPPED: " + run.path("safeStopReason").asText());
                return run;
            }
            for (var it = run.path("tasks").fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().path("status").asText().equals("AWAITING_APPROVAL")) {
                    approve(id, run, e.getKey(), answers);
                }
            }
            Thread.sleep(400);
        }
        throw new IllegalStateException("run did not settle within 5 minutes");
    }

    private void approve(String runId, JsonNode run, String taskId, Map<String, String> answers) throws Exception {
        JsonNode spec = null;
        for (JsonNode t : run.path("plan")) {
            if (t.path("id").asText().equals(taskId)) {
                spec = t;
            }
        }
        System.out.printf("%n  >> HUMAN CHECKPOINT: %s [%s, risk %s] %s%n", taskId, spec.path("approval").asText(),
                spec.path("risk").asText(), spec.path("title").asText());
        ObjectNode body = json.createObjectNode().put("decision", "APPROVE").put("approver", opt.approver())
                .put("comment", "Reviewed artifacts and evidence in the dashboard; approved.");
        if ("true".equals(spec.path("params").path("humanInput").asText())) {
            body.set("answers", json.valueToTree(askAnswers(runId, answers)));
        }
        if (!opt.auto()) {
            System.out.print("     press Enter to approve (Ctrl+C to abort) ");
            stdin.nextLine();
        }
        post("/runs/" + runId + "/tasks/" + taskId + "/decision", body);
        System.out.println("     approved by " + opt.approver());
    }

    private Map<String, String> askAnswers(String runId, Map<String, String> given) throws Exception {
        String artifactId = null;
        for (JsonNode a : get("/runs/" + runId + "/artifacts")) {
            if (a.path("name").asText().equals("clarification-questions.json")) {
                artifactId = a.path("id").asText();
            }
        }
        JsonNode doc = json.readTree(text("/runs/" + runId + "/artifacts/" + artifactId));
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode q : doc.path("questions")) {
            String qid = q.path("id").asText();
            String recommended = q.path("recommended").asText();
            System.out.printf("%n  ? %s   (because: %s)%n", q.path("question").asText(), q.path("because").asText());
            for (JsonNode o : q.path("options")) {
                System.out.printf("     %s %-26s %s%n", o.path("value").asText().equals(recommended) ? "*" : " ",
                        o.path("value").asText(), o.path("consequence").asText());
            }
            String answer = given.getOrDefault(qid, recommended);
            if (!given.containsKey(qid) && !opt.auto()) {
                System.out.printf("    answer [%s]: ", recommended);
                String typed = stdin.nextLine().trim();
                answer = typed.isEmpty() ? recommended : typed;
            }
            System.out.println("    -> " + answer);
            out.put(qid, answer);
        }
        return out;
    }

    // ---------------------------------------------------------------- HTTP + args

    private JsonNode get(String path) throws Exception {
        return json.readTree(send(HttpRequest.newBuilder(uri(path)).GET().build()));
    }

    private String text(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        return json.readTree(send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()));
    }

    private URI uri(String path) {
        return URI.create(opt.base() + "/api/orchestrator" + path);
    }

    private String send(HttpRequest req) throws Exception {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + r.statusCode() + " on " + req.method() + " " + req.uri().getPath()
                        + ": " + r.body());
            }
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach " + opt.base() + " (" + e.getMessage()
                    + "). Is the stack up? docker compose up -d", e);
        }
    }

    private static Options parse(String[] args) {
        String base = "http://localhost:8080", scenario = "all", failure = "none", approver = "demo.reviewer", out = "docs/runs";
        boolean auto = false;
        Map<String, String> answers = new LinkedHashMap<>(), change = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>(List.of(args));
        Set<String> multi = new HashSet<>(List.of("--answers", "--change"));
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            switch (a) {
                case "--base" -> base = rest.get(++i);
                case "--scenario" -> scenario = rest.get(++i);
                case "--failure" -> failure = rest.get(++i);
                case "--approver" -> approver = rest.get(++i);
                case "--out" -> out = rest.get(++i);
                case "--auto" -> auto = true;
                case "--answers", "--change" -> {
                    Map<String, String> target = a.equals("--answers") ? answers : change;
                    while (i + 1 < rest.size() && !rest.get(i + 1).startsWith("--")) {
                        String[] kv = rest.get(++i).split("=", 2);
                        target.put(kv[0], kv.length > 1 ? kv[1] : "");
                    }
                }
                default -> throw new IllegalArgumentException("unknown option " + a
                        + " (use --scenario greenfield|brownfield|ambiguous|all --failure none|transient|permanent --auto "
                        + "--answers k=v --change k=v --approver NAME --base URL --out DIR)");
            }
        }
        if (!scenario.equals("all") && !SCENARIOS.containsKey(scenario)) {
            throw new IllegalArgumentException("scenario must be greenfield, brownfield, ambiguous or all");
        }
        return new Options(base.replaceAll("/+$", ""), scenario, failure, approver, auto, answers, change, Path.of(out));
    }
}
