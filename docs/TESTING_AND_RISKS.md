# Testing approach, risks, trade-offs and limitations

## 1. Testing approach

**68 automated tests, all green** (`./mvnw test` or `docker compose --profile test run --rm tests`), plus manual verification on the
real PostgreSQL/Redis stack. The strategy is *test the behaviour that makes the system trustworthy*, not just the happy path.

| Suite | Tests | What it proves |
|---|---|---|
| `CodeGeneratorTest` | 4 | codes are unique over 200k ids, fixed length/alphabet, non-sequential, reject out-of-space ids |
| `UrlValidatorTest` | 21 | scheme/credential/private-host/length rules, incl. boundary cases (`172.32.x` is public, `172.20.x` private) |
| `ShortenerApiIntegrationTest` | 7 | create -> redirect -> stats (async clicks drained), delete -> 404, negative cache, rate limit 429 + redirects unthrottled, RFC 7807 |
| `DagValidatorTest` | 4 | diamond accepted; cycles, self/unknown dependencies, duplicates rejected; transitive downstream order |
| `PolicyTest` | 8 | every policy: positive and negative cases, severities, entry-gate approval integrity, release entry gate |
| `ScenarioIntegrationTest` | 8 | the three scenarios end to end (below) |
| `GovernanceIntegrationTest` | 9 | approval rules, rework and rework budget, safe-stop/resume, kill switch, secret leak, agent over-reach, runaway re-planning |
| `OrchestratorApiTest` | 3 | REST lifecycle over real HTTP, error mapping (404/400/409), metrics shape |
| `AuditLogIntegrationTest` | 2 | chain links; editing or deleting an event breaks verification |
| `LlmAdvisorTest` + advisor stand-in in the scenario tests | 2 | advisor is off without a key; a stand-in that returns different text on every call never triggers re-planning (advisory artifacts are excluded from the output hash) |

`ScenarioIntegrationTest` asserts orchestration properties, not just "status = SUCCEEDED": parallel fan-out by event ordering, the
join (readiness starts after all five evidence tasks), approvals recorded with identity, retry counts and MTTR samples, rollback
(task `ROLLED_BACK`, workspace folder deleted, dependants `CANCELLED`), plan growth from real code-scan evidence, policy blocks,
fallback, that blocked artifacts were never persisted, and that changing an answer invalidates dependants and revokes approvals - while
an unchanged re-run does **not** cascade.

**Why integration tests carry the weight.** The risk in this system is in the interactions (gates x retries x approvals x re-planning),
so those tests boot the real Spring context, call the live HTTP API (the smoke agent hits the test server), compile and execute
generated code, and run migrations on a real SQL engine.

### Verified beyond the test suite (real stack, `docker compose`)

* Four recorded runs on PostgreSQL 16 + Redis 7 (`docs/runs/`), including the migration + rollback executed in a PostgreSQL scratch schema.
* **Restart recovery:** the app container was restarted mid-run; orphaned `RUNNING` tasks were re-queued (`TASK_RECOVERED`), the run
  completed to the release checkpoint and the audit chain still verified.
* **Redis outage:** with Redis stopped, create and redirect still succeed (database fallback), liveness stays UP; after Redis returns the
  redirect is served in ~35 ms again.
* **Dashboard** rendered in a headless browser (screenshots in `docs/images/`), JavaScript syntax-checked.
* Maven wrapper bootstraps Maven 3.9.9 inside a JDK-only container.

### Defects found by this process (and fixed) - evidence the validation is real

1. `MAX(version)` over zero rows returned NULL and `JdbcClient.single()` refused it - **found because the audit trail of a failed run
   pointed straight at it** (first end-to-end run failed at task 1).
2. The smoke agent's base URL used a `${local.server.port}` placeholder that cannot be resolved at bind time; the agent now reads the
   actual port lazily.
3. Re-plan budget counted every cascade hop, so a legitimate upstream change safe-stopped the run; the budget now bounds only
   *agent-proposed* plan changes, cascades are bounded by the DAG.
4. When the re-plan budget tripped, the triggering task stayed `RUNNING` forever (run never settled); it now returns to `PENDING`.
5. The integration-test report embedded a random link code, so every re-run looked like a changed output and revoked release approvals.
   Found while reading the recorded evidence; outputs must be deterministic - documented in the architecture.
6. "Human wait" ignored approvals still pending, so a parked run looked like slow agent work.

## 2. Risk register

| # | Risk / failure scenario | Likelihood | Impact | Controls in the system | Residual |
|---|---|---|---|---|---|
| 1 | Requirement misunderstood, work built on a guess | M | H | ambiguity scoring, clarification checkpoint, assumptions recorded, human sign-off on design | analyst is rule-based; subtle ambiguity may pass |
| 2 | Agent emits unsafe artifact (secret, PII, unapproved library, dangerous API) | M | H | six exit policies, CRITICAL -> safe-stop and **no persistence**, independent security re-scan | pattern-based detection can be evaded |
| 3 | Agent escalates its own scope (adds risky tasks, endless replans) | M | H | proposals validated by the engine; HIGH risk needs a checkpoint; plan-change and task budgets; safe-stop | budgets are static |
| 4 | Rubber-stamp approvals | M | H | identity recorded, separation of duties, written justification for HIGH risk and rejections, revocation on upstream change | identity is asserted, not authenticated |
| 5 | Half-finished change leaves side effects | M | M | reverse-order rollback of side-effecting tasks, workspace cleanup, `ROLLBACK_FAILED` surfaced | rollback is best-effort |
| 6 | Schema migration breaks production | L | H | additive/nullable design, rollback script mandatory, migration + rollback **executed** on real engine, HIGH-risk approval | not tested on production-sized data/locks |
| 7 | Expired/deleted link still served from cache | M | M | TTL capped by expiry, eviction on delete, 302 not 301, short negative cache | seconds of skew across nodes |
| 8 | Audit trail tampered with | L | H | hash chain + verify endpoint | an attacker who can rewrite the whole chain defeats it; anchor hashes externally in production |
| 9 | Non-deterministic agent output causes invalidation storms | M | M | committed-hash comparison; deterministic reports; tests for "unchanged -> no cascade" | LLM agents need normalisation of volatile text |
| 10 | Orchestrator crash mid-run | M | M | snapshot per transition, generation counters, `TASK_RECOVERED` on startup | duplicate execution of a side-effecting agent is possible (agents are idempotent here) |
| 11 | Redis outage | M | M | cache and rate limiter fail open/fall back to DB; verified manually | each request pays the 200 ms timeout while Redis is down - add a circuit breaker |
| 12 | Analytics loss under load | M | L | bounded queue, drops counted and exported | analytics is best-effort by design |
| 13 | Abuse of link creation / phishing destinations | M | M | rate limit per client, URL validation, blocklist capability (ambiguous scenario) | fixed window allows 2x burst at boundaries |
| 14 | Generated code executed in-process | L (trusted templates) | H (untrusted LLM) | restricted-API policy, platform-only classloader | **not a sandbox**; isolate in a container before using an LLM agent |
| 15 | Single-node scheduler is a SPOF | M | M | state is durable; restart recovers | needs lease-based scheduling to scale out |

## 3. Trade-offs made (and why)

* **Deterministic agents over LLM calls** - reproducible demo and tests, zero credentials; the `Agent` contract is the seam for an LLM.
* **Snapshot + audit chain over event sourcing** - far less machinery; audit is evidence, not the source of truth.
* **Modular monolith over a workflow product** - the orchestration logic is the thing being evaluated, so it is plain code.
* **H2 in tests over Testcontainers** - runs inside Docker builds/CI; the real-engine gap is covered by the migration validator and compose runs.
* **Hash-based minimal re-execution over "always rerun"** - cheaper and preserves approvals when nothing changed, at the cost of demanding deterministic outputs.
* **Fail-open rate limiter** - availability of link creation beats strict throttling during a cache outage.
* **Release as dry run** - deployment adds credentials and environments that are out of scope; the pinned manifest is the contract with a real pipeline.

## 4. Limitations

* Agents do not call an LLM; "requirement understanding" is rule-based (lexicon + measurability heuristics).
* Approver identity is not authenticated; the optional API key is a stopgap, not authN/authZ.
* Scale-out of the orchestrator (multiple instances) is not implemented; kill switch lives in process memory.
* The dashboard polls (1.2 s) instead of using server-sent events.
* The migration validator proves apply/rollback semantics, not lock behaviour or runtime on large tables.
* No load/performance test: the 500 ms bulk target is a requirement the scenario *carries*, not one this prototype measures.
* `mvnw.cmd` (Windows wrapper) was not run here; Docker and the Linux wrapper were.
* Chaos injection simulates agent crashes; it does not model slow agents beyond the per-task timeout.

## 5. Assumptions

* A single trusted operator environment (no multi-tenant isolation); reviewers use the dashboard or CLI.
* PostgreSQL 16 and Redis 7 are available (compose provides both); JDK 21 only for local development.
* Requirements are English text; scenario catalogue is fixed (new scenarios implement `ScenarioDefinition`).
* "Production-grade" is interpreted as: clear boundaries, tests, safe failure behaviour and observability - not hardened infrastructure.
