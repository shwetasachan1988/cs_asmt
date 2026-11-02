package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.Check;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates the OpenAPI document structurally: version, paths, operations, responses and $ref resolution. */
@Component
public class ContractTesterAgent extends BaseAgent {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ContractTesterAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "contract-tester";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        Artifact spec = ctx.upstreamArtifact("api-spec")
                .orElseThrow(() -> new NonRetryableAgentException("api-spec missing upstream"));
        JsonNode root;
        try {
            root = yaml.readTree(spec.content());
        } catch (Exception e) {
            return AgentResult.builder("OpenAPI document does not parse")
                    .check(Check.fail("yaml-parses", e.getMessage()))
                    .artifact("test-report", "contract-tests.md", "# Contract tests\n\nYAML did not parse.\n").build();
        }

        List<Check> checks = new ArrayList<>();
        checks.add(root.path("openapi").asText("").startsWith("3.")
                ? Check.pass("openapi-version", root.path("openapi").asText())
                : Check.fail("openapi-version", "expected 3.x"));
        checks.add(!root.path("info").path("title").asText("").isBlank() && !root.path("info").path("version").asText("").isBlank()
                ? Check.pass("info-block", root.path("info").path("title").asText())
                : Check.fail("info-block", "info.title and info.version are required"));

        JsonNode paths = root.path("paths");
        checks.add(paths.isObject() && paths.size() > 0
                ? Check.pass("paths-present", paths.size() + " path(s)") : Check.fail("paths-present", "no paths"));

        Set<String> operationIds = new HashSet<>();
        List<String> problems = new ArrayList<>();
        int operations = 0;
        for (var it = paths.fields(); it.hasNext(); ) {
            var path = it.next();
            if (!path.getKey().startsWith("/")) {
                problems.add("path must start with '/': " + path.getKey());
            }
            for (var op = path.getValue().fields(); op.hasNext(); ) {
                var e = op.next();
                if (!METHODS.contains(e.getKey())) {
                    continue;
                }
                operations++;
                String where = e.getKey().toUpperCase() + " " + path.getKey();
                JsonNode responses = e.getValue().path("responses");
                if (!responses.isObject() || responses.size() == 0) {
                    problems.add(where + ": no responses");
                }
                responses.fields().forEachRemaining(r -> {
                    if (r.getValue().path("description").asText("").isBlank()) {
                        problems.add(where + " response " + r.getKey() + ": description required");
                    }
                });
                String id = e.getValue().path("operationId").asText("");
                if (id.isBlank() || !operationIds.add(id)) {
                    problems.add(where + ": operationId missing or duplicated");
                }
            }
        }
        checks.add(problems.isEmpty()
                ? Check.pass("operations-well-formed", operations + " operation(s), unique operationIds, documented responses")
                : Check.fail("operations-well-formed", String.join("; ", problems)));

        List<String> unresolved = new ArrayList<>();
        collectRefs(root, root, unresolved);
        checks.add(unresolved.isEmpty()
                ? Check.pass("refs-resolve", "every $ref resolves to a component")
                : Check.fail("refs-resolve", "unresolved: " + unresolved));

        StringBuilder report = new StringBuilder("# Contract tests (OpenAPI)\n\n| Check | Result | Detail |\n|---|---|---|\n");
        checks.forEach(c -> report.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "FAIL")
                .append(" | ").append(c.detail().replace("|", "/")).append(" |\n"));
        AgentResult.Builder out = AgentResult.builder(checks.stream().allMatch(Check::passed)
                        ? "OpenAPI contract is structurally valid" : "OpenAPI contract has defects")
                .rationale("Structural validation catches broken references and undocumented responses before any client sees them.")
                .artifact("test-report", "contract-tests.md", report.toString());
        checks.forEach(out::check);
        return out.build();
    }

    private void collectRefs(JsonNode root, JsonNode node, List<String> unresolved) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getKey().equals("$ref") && e.getValue().isTextual()) {
                    String ref = e.getValue().asText();
                    if (!ref.startsWith("#/") || root.at(ref.substring(1)).isMissingNode()) {
                        unresolved.add(ref);
                    }
                } else {
                    collectRefs(root, e.getValue(), unresolved);
                }
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectRefs(root, n, unresolved));
        }
    }
}
