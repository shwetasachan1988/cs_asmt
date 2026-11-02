package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Check;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Produces the design document (context, proposal, decisions with trade-offs, failure modes, rollout/rollback)
 * and the OpenAPI contract for the change. Brownfield designs are grounded in the upstream impact report.
 */
@Component
public class ArchitectAgent extends BaseAgent {

    record Decision(String decision, String alternatives, String why) {
    }

    record Design(String title, String context, List<String> proposal, List<Decision> decisions,
                  List<String> failureModes, List<String> rollout, String openApi) {
    }

    public ArchitectAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "architect";
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String feature = ctx.param("feature", "bulk");
        Design d = switch (feature) {
            case "bulk" -> bulk();
            case "expiry" -> expiry(ctx);
            case "ambiguous-bundle" -> bundle(ctx);
            default -> throw new NonRetryableAgentException("no design template for feature '" + feature + "'");
        };
        String feedback = ctx.feedback() == null ? "" : "\n> Revised after reviewer feedback: " + ctx.feedback() + "\n";
        return AgentResult.builder("Design for '" + d.title() + "' with " + d.decisions().size() + " recorded decision(s)")
                .rationale("Every non-obvious choice is listed with the alternative that was rejected, so reviewers can challenge it.")
                .artifact("design-doc", "design.md", render(d) + feedback)
                .artifact("api-spec", "openapi.yaml", d.openApi())
                .check(Check.pass("decisions-recorded", d.decisions().size() + " decision(s), " + d.failureModes().size()
                        + " failure mode(s)"))
                .build();
    }

    private static String render(Design d) {
        StringBuilder sb = new StringBuilder("# Design: ").append(d.title()).append("\n\n## Context\n").append(d.context())
                .append("\n\n## Proposed change\n");
        d.proposal().forEach(p -> sb.append("- ").append(p).append('\n'));
        sb.append("\n## Key decisions and trade-offs\n| Decision | Alternatives considered | Why |\n|---|---|---|\n");
        d.decisions().forEach(x -> sb.append("| ").append(x.decision()).append(" | ").append(x.alternatives())
                .append(" | ").append(x.why()).append(" |\n"));
        sb.append("\n## Failure modes and mitigations\n");
        d.failureModes().forEach(f -> sb.append("- ").append(f).append('\n'));
        sb.append("\n## Rollout and rollback\n");
        d.rollout().forEach(r -> sb.append("1. ").append(r).append('\n'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- greenfield: bulk creation

    private Design bulk() {
        return new Design("Bulk link creation",
                "Marketing imports campaign URLs in batches. Calling POST /api/v1/links hundreds of times is slow and "
                        + "makes partial failure handling the client's problem.",
                List.of("New endpoint POST /api/v1/links/bulk accepting 1..100 URLs",
                        "BulkLinkPlanner (pure logic) trims, de-duplicates and validates each item independently",
                        "Accepted items are created through the existing LinkService so validation, caching and metrics are reused",
                        "Rate limiter is charged once per item, so bulk cannot be used to bypass the 60/min limit"),
                List.of(new Decision("HTTP 200 with per-item results", "207 Multi-Status; fail the whole batch (400)",
                                "one bad URL must not discard 99 good ones; 207 is poorly supported by common clients"),
                        new Decision("Hard cap of 100 items", "unbounded; paging",
                                "bounds worst-case latency (p95 < 500 ms) and DB transaction size"),
                        new Decision("De-duplicate identical URLs inside a batch", "create a link per occurrence",
                                "avoids accidental duplicate links; result reports the shared code"),
                        new Decision("Items created independently, not in one DB transaction", "single transaction",
                                "a transaction would reintroduce all-or-nothing failure; code generation is collision-free so no ordering hazard")),
                List.of("Batch larger than 100 -> 400, nothing created",
                        "Rate limit reached mid-batch -> remaining items marked RATE_LIMITED, earlier ones kept",
                        "Database outage -> 500 with no partial state visible to the client beyond created items"),
                List.of("Deploy behind no schema change - purely additive endpoint",
                        "Roll back by redeploying the previous version; created links remain valid"),
                """
                        openapi: 3.0.3
                        info:
                          title: URL Shortener - Bulk Link API
                          version: 1.1.0
                        paths:
                          /api/v1/links/bulk:
                            post:
                              summary: Create up to 100 short links in one call
                              operationId: createLinksBulk
                              requestBody:
                                required: true
                                content:
                                  application/json:
                                    schema:
                                      $ref: '#/components/schemas/BulkCreateRequest'
                              responses:
                                '200':
                                  description: Per-item results; the call succeeds even when some items are rejected
                                  content:
                                    application/json:
                                      schema:
                                        $ref: '#/components/schemas/BulkCreateResponse'
                                '400':
                                  description: Batch is empty or larger than 100 items
                                '429':
                                  description: Rate limit exceeded; every item counts against the per-client limit
                        components:
                          schemas:
                            BulkCreateRequest:
                              type: object
                              required: [urls]
                              properties:
                                urls:
                                  type: array
                                  minItems: 1
                                  maxItems: 100
                                  items:
                                    type: string
                                    maxLength: 2048
                            BulkItemResult:
                              type: object
                              required: [url, status]
                              properties:
                                url:
                                  type: string
                                status:
                                  type: string
                                  enum: [CREATED, DUPLICATE, REJECTED, RATE_LIMITED]
                                code:
                                  type: string
                                reason:
                                  type: string
                            BulkCreateResponse:
                              type: object
                              required: [results]
                              properties:
                                results:
                                  type: array
                                  items:
                                    $ref: '#/components/schemas/BulkItemResult'
                        """);
    }

    // ---------------------------------------------------------------- brownfield: link expiry

    private Design expiry(AgentContext ctx) {
        List<String> impacted = new ArrayList<>();
        List<String> flows = new ArrayList<>();
        ctx.upstreamArtifact("impact-report").ifPresent(a -> {
            JsonNode r = parse(a.content());
            r.path("impactedFiles").forEach(f -> impacted.add(f.path("path").asText() + " - " + f.path("reason").asText()));
            r.path("dataFlows").forEach(f -> flows.add(f.asText()));
        });
        List<String> proposal = new ArrayList<>(List.of(
                "Add nullable column short_link.expires_at (migration V3, with a rollback script)",
                "POST /api/v1/links accepts optional expiresAt; must be in the future and within 5 years",
                "Redirect returns 410 Gone once expired; stats and history stay readable",
                "Cache entries are written with TTL = min(default TTL, time until expiry)"));
        impacted.forEach(i -> proposal.add("Impacted (from codebase analysis): " + i));
        flows.forEach(f -> proposal.add("Data flow: " + f));
        return new Design("Link expiration",
                "Links currently live forever. Campaign links must stop working on a given date without manual deletion.",
                proposal,
                List.of(new Decision("410 Gone for expired links", "404 Not Found; 301 to a landing page",
                                "410 states the link existed and is intentionally gone; 404 would hide expiry bugs"),
                        new Decision("Nullable expires_at, NULL = never", "sentinel far-future date",
                                "existing rows need no backfill, so the migration is metadata-only and instantly reversible"),
                        new Decision("Expiry checked on read, no sweeper job", "background job that deactivates links",
                                "no new moving part; a sweeper can be added later purely for storage hygiene"),
                        new Decision("Cache TTL capped by expiry", "evict on a timer; ignore",
                                "prevents the cache from serving an expired link after expiry - the main correctness risk")),
                List.of("Clock skew between app nodes -> expiry may differ by seconds; acceptable, documented",
                        "Stale cache serving expired link -> mitigated by TTL cap and checked-on-read in LinkService",
                        "Migration on a large table -> adding a nullable column is metadata-only in PostgreSQL",
                        "Analytics for expired links -> intentionally still readable (history preserved)"),
                List.of("Apply V3 (additive, backward compatible: old app versions ignore the column)",
                        "Deploy application; verify with regression suite",
                        "Rollback: deploy previous app, then run the rollback script (drops index and column)"),
                """
                        openapi: 3.0.3
                        info:
                          title: URL Shortener - Link Expiration
                          version: 1.1.0
                        paths:
                          /api/v1/links:
                            post:
                              summary: Create a short link, optionally with an expiry
                              operationId: createLink
                              requestBody:
                                required: true
                                content:
                                  application/json:
                                    schema:
                                      $ref: '#/components/schemas/CreateLinkRequest'
                              responses:
                                '201':
                                  description: Created
                                  content:
                                    application/json:
                                      schema:
                                        $ref: '#/components/schemas/LinkResponse'
                                '400':
                                  description: Invalid URL or expiresAt not in the future / beyond 5 years
                          /{code}:
                            get:
                              summary: Redirect to the target URL
                              operationId: redirect
                              parameters:
                                - name: code
                                  in: path
                                  required: true
                                  schema:
                                    type: string
                              responses:
                                '302':
                                  description: Redirect to the target
                                '404':
                                  description: Unknown or deleted link
                                '410':
                                  description: The link existed but has expired
                        components:
                          schemas:
                            CreateLinkRequest:
                              type: object
                              required: [url]
                              properties:
                                url:
                                  type: string
                                  maxLength: 2048
                                expiresAt:
                                  type: string
                                  format: date-time
                                  nullable: true
                            LinkResponse:
                              type: object
                              required: [code, shortUrl, targetUrl]
                              properties:
                                code:
                                  type: string
                                shortUrl:
                                  type: string
                                targetUrl:
                                  type: string
                                expiresAt:
                                  type: string
                                  format: date-time
                                  nullable: true
                        """);
    }

    // ---------------------------------------------------------------- ambiguous: safer + faster + tracking

    @SuppressWarnings("unchecked")
    private Design bundle(AgentContext ctx) {
        Map<String, Object> caps = ctx.shared("capabilities", Map.class).orElse(Map.of());
        List<String> proposal = new ArrayList<>();
        proposal.add("Resolved capabilities (from normalized requirements): " + caps);
        List<Decision> decisions = new ArrayList<>();
        if (caps.containsKey("safety")) {
            proposal.add("Reject known-bad destination hosts at creation time with HTTP 422");
            decisions.add(new Decision("In-house host blocklist", "third-party reputation SDK",
                    "new third-party code on the creation path is a supply-chain risk and must be on the approved-library list"));
        }
        if (caps.containsKey("speed")) {
            proposal.add("Detect hot links and pin them in the cache so redirects stay off the database");
            decisions.add(new Decision("Sliding-window hit counter in memory", "full LFU cache; CDN",
                    "smallest change that measurably protects p99; CDN is a separate platform decision"));
        }
        if (caps.containsKey("tracking")) {
            proposal.add("Visitor analytics through a salted daily hash - never raw IP addresses");
            decisions.add(new Decision("Pseudonymous visitor hash", "store IP / user identity",
                    "marketing needs unique-visitor counts, not identities; raw personal data would need consent and retention controls"));
        }
        decisions.add(new Decision("Analytics stays off the redirect path", "synchronous write",
                "redirects must keep working when analytics storage is slow or down"));
        return new Design("Safer, faster links with privacy-preserving analytics",
                "The request was ambiguous; this design implements only the interpretation the stakeholder confirmed.",
                proposal, decisions,
                List.of("Blocklist stale -> documented refresh process; fail-open would defeat the purpose, so unknown hosts are allowed but known-bad are blocked",
                        "Hot-link counter lost on restart -> only a performance hint, no correctness impact",
                        "Hash salt rotates daily -> unique visitors are per-day by design (privacy trade-off)"),
                List.of("Ship behind no schema change for safety/performance; visitor hash column is additive",
                        "Roll back by redeploying the previous version"),
                """
                        openapi: 3.0.3
                        info:
                          title: URL Shortener - Safety, Speed and Visitor Analytics
                          version: 1.2.0
                        paths:
                          /api/v1/links:
                            post:
                              summary: Create a short link (destination is checked against the blocklist)
                              operationId: createLink
                              requestBody:
                                required: true
                                content:
                                  application/json:
                                    schema:
                                      $ref: '#/components/schemas/CreateLinkRequest'
                              responses:
                                '201':
                                  description: Created
                                '422':
                                  description: Destination host is on the blocklist
                          /api/v1/links/{code}/stats:
                            get:
                              summary: Click analytics with pseudonymous unique visitors
                              operationId: getStats
                              parameters:
                                - name: code
                                  in: path
                                  required: true
                                  schema:
                                    type: string
                              responses:
                                '200':
                                  description: Statistics
                                  content:
                                    application/json:
                                      schema:
                                        $ref: '#/components/schemas/Stats'
                        components:
                          schemas:
                            CreateLinkRequest:
                              type: object
                              required: [url]
                              properties:
                                url:
                                  type: string
                            Stats:
                              type: object
                              properties:
                                totalClicks:
                                  type: integer
                                uniqueVisitors:
                                  type: integer
                                  description: Distinct salted-hash visitors per UTC day - not identities
                        """);
    }
}
