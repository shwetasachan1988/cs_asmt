package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.OrchestratorProperties;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Check;
import com.sa.agentic.orchestrator.model.ReplanRequest;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Brownfield reasoning on the real source tree (read-only). It searches for the symbols a change must touch,
 * derives impacted modules and data flows from the evidence - and lets that evidence shape the plan: the cache
 * and analytics follow-up tasks exist only if the scan actually found those couplings.
 */
@Component
public class CodebaseAnalystAgent extends BaseAgent {

    record Probe(String id, Pattern pattern, String reason) {
    }

    private static final List<Probe> EXPIRY_PROBES = List.of(
            new Probe("entity", Pattern.compile("record ShortLink|class ShortLink"), "entity gains an expiresAt attribute"),
            new Probe("table", Pattern.compile("short_link"), "table/queries affected by the new column"),
            new Probe("lookup", Pattern.compile("findByCode|ResolvedLink resolve\\("), "lookup path must enforce the expiry rule"),
            new Probe("cache", Pattern.compile("CacheStore|cacheTtlSeconds|cache\\.put"), "cache TTL must not outlive the expiry"),
            new Probe("redirect", Pattern.compile("HttpStatus\\.FOUND"), "redirect endpoint must answer 410 for expired links"),
            new Probe("errors", Pattern.compile("enum Kind"), "error taxonomy needs an EXPIRED kind"),
            new Probe("analytics", Pattern.compile("ClickRepository|click_event"), "click history must stay intact and readable"),
            new Probe("stats", Pattern.compile("links\\.get\\("), "stats endpoint goes through the same lookup"));

    private final OrchestratorProperties props;

    public CodebaseAnalystAgent(ObjectMapper json, OrchestratorProperties props) {
        super(json);
        this.props = props;
    }

    @Override
    public String name() {
        return "codebase-analyst";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws IOException {
        Path root = Path.of(props.codebaseRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new NonRetryableAgentException("codebase root not found: " + root);
        }
        Map<String, Map<String, List<Map<String, Object>>>> hitsByFile = new LinkedHashMap<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).filter(CodebaseAnalystAgent::isRelevant)
                    .sorted().toList();
            for (Path f : files) {
                scanned++;
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    for (Probe p : EXPIRY_PROBES) {
                        if (p.pattern().matcher(lines.get(i)).find()) {
                            hitsByFile.computeIfAbsent(rel(root, f), k -> new LinkedHashMap<>())
                                    .computeIfAbsent(p.id(), k -> new ArrayList<>())
                                    .add(Map.of("line", i + 1, "text", lines.get(i).trim()));
                        }
                    }
                }
            }
        }

        Set<String> probesHit = new LinkedHashSet<>();
        List<Map<String, Object>> impacted = new ArrayList<>();
        hitsByFile.forEach((path, byProbe) -> {
            probesHit.addAll(byProbe.keySet());
            List<String> reasons = byProbe.keySet().stream()
                    .map(id -> EXPIRY_PROBES.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().reason())
                    .toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("module", moduleOf(path));
            entry.put("reason", String.join("; ", reasons));
            entry.put("probes", byProbe.keySet());
            entry.put("evidence", byProbe.values().stream().flatMap(List::stream).limit(4).toList());
            impacted.add(entry);
        });
        impacted.sort(Comparator.comparingInt((Map<String, Object> m) -> -((Set<?>) m.get("probes")).size()));

        boolean cacheCoupled = hitsByFile.entrySet().stream()
                .anyMatch(e -> e.getKey().contains("service/") && e.getValue().containsKey("cache"));
        boolean analyticsCoupled = hitsByFile.values().stream().anyMatch(m -> m.containsKey("analytics"))
                && hitsByFile.values().stream().anyMatch(m -> m.containsKey("stats"));

        List<String> flows = new ArrayList<>();
        if (probesHit.containsAll(List.of("lookup", "cache", "table"))) {
            flows.add("GET /{code} -> RedirectController -> LinkService.resolve -> CacheStore(link:<code>) -> "
                    + "LinkRepository.findByCode -> short_link  (expiry must be enforced on both cache hit and DB read)");
        }
        if (probesHit.contains("entity")) {
            flows.add("POST /api/v1/links -> LinkService.create -> LinkRepository.insert -> short_link + CacheStore.put "
                    + "(expiresAt must be validated, persisted and used to cap the cache TTL)");
        }
        if (analyticsCoupled) {
            flows.add("GET /api/v1/links/{code}/stats -> LinkService.get -> ClickRepository  (stats lookup shares the "
                    + "lookup path, so expired links must stay readable here)");
        }

        List<Map<String, Object>> modules = new ArrayList<>();
        impacted.stream().collect(java.util.stream.Collectors.groupingBy(m -> (String) m.get("module"),
                        LinkedHashMap::new, java.util.stream.Collectors.counting()))
                .forEach((m, n) -> modules.add(Map.of("module", m, "impactedFiles", n)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedFiles", scanned);
        report.put("impactedFiles", impacted);
        report.put("impactedModules", modules);
        report.put("dataFlows", flows);
        report.put("impactedApis", List.of("POST /api/v1/links (new optional field)", "GET /{code} (new 410 outcome)",
                "GET /api/v1/links/{code}/stats (must keep working for expired links)"));
        report.put("schemaImpact", "short_link: add nullable expires_at (+ index); existing rows unaffected");
        report.put("planDecisions", Map.of("cacheTtlTaskAdded", cacheCoupled, "analyticsRegressionAdded", analyticsCoupled));

        AgentResult.Builder out = AgentResult.builder("Scanned " + scanned + " files; " + impacted.size()
                        + " impacted in " + modules.size() + " module(s)")
                .rationale("Impact is derived from symbol matches in the real source tree, not assumed. "
                        + "The cache and analytics follow-up tasks are added only where the scan found coupling.")
                .artifact("impact-report", "impact-report.json", toJson(report))
                .check(impacted.isEmpty()
                        ? Check.fail("impact-found", "no impacted files - wrong codebase root?")
                        : Check.pass("impact-found", impacted.size() + " impacted file(s)"));

        List<TaskSpec> add = new ArrayList<>();
        Map<String, List<String>> rewire = new LinkedHashMap<>();
        if (cacheCoupled) {
            add.add(TaskSpec.of("impl-cache-ttl", Stage.IMPLEMENTATION, "codegen").title("Cap cache TTL by expiry")
                    .after("design").param("module", "expiry-cache").rollbackOnFailure().produces("source").build());
            rewire.put("unit-tests", List.of("impl-cache-ttl"));
            rewire.put("security-review", List.of("impl-cache-ttl"));
        }
        if (analyticsCoupled) {
            add.add(TaskSpec.of("analytics-regression", Stage.TESTING, "smoke-tester")
                    .title("Verify click analytics still work end to end").after("design")
                    .param("focus", "analytics").produces("test-report").retries(2).build());
            rewire.put("release-readiness", List.of("analytics-regression"));
        }
        if (!add.isEmpty()) {
            out.replan(ReplanRequest.add("codebase evidence: "
                    + (cacheCoupled ? "LinkService is coupled to CacheStore -> cache TTL work required. " : "")
                    + (analyticsCoupled ? "stats shares the lookup path with ClickRepository -> analytics regression required."
                    : ""), add, rewire));
        }
        return out.build();
    }

    private static boolean isRelevant(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.contains("/orchestrator/") || s.contains("/static/")) {
            return false;
        }
        return s.endsWith(".java") || s.endsWith(".sql") || s.endsWith("application.yml");
    }

    private static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    private static String moduleOf(String path) {
        if (path.contains("shortener/")) {
            String after = path.substring(path.indexOf("shortener/") + "shortener/".length());
            int slash = after.indexOf('/');
            return "shortener." + (slash > 0 ? after.substring(0, slash) : "core");
        }
        if (path.contains("db/migration")) {
            return "db.migration";
        }
        return "config";
    }
}
