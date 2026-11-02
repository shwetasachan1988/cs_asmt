# The three scenarios

Each scenario shows **decomposition** (how the requirement becomes a DAG), **orchestration** (what the engine did that a linear
chain could not) and **validation** (evidence produced by actually running things). Every number below comes from a recorded run in
[`docs/runs/`](runs/) (summary `.md` + full snapshot and audit trail `.json`).

Reproduce any of them:

```bash
docker compose up -d --build
docker compose --profile demo run --rm demo --scenario greenfield  --auto
docker compose --profile demo run --rm demo --scenario brownfield  --auto --failure transient
docker compose --profile demo run --rm demo --scenario ambiguous   --auto --answers tracking=identified_users --change tracking=aggregate_only
docker compose --profile demo run --rm demo --scenario greenfield  --auto --failure permanent      # rollback demonstration
```

---

## 1. Greenfield - bulk link creation API

**Requirement (well defined):** *Add a bulk API to create up to 100 short links in one call. Each item validated independently with a
per-item result; one bad URL must not fail the batch. The 60/min rate limit counts every item. p95 for 100 items < 500 ms.*

### Decomposition

Requirement analysis finds measurable criteria (100 items, 60/min, 500 ms) -> ambiguity 0 -> no clarification needed, so the whole
DAG is declared up front (10 tasks):

```
intake -> design [human review] -> impl-core ----+--> unit-tests ----------+
                       |                          +--> security-review -----+
                       +-> docs (parallel with impl) -------------------------+--> release-readiness -> release-package
                       +-> contract-tests ------------------------------------+      (join)            [human approval,
                       +-> integration-tests (live HTTP) --------------------+                          HIGH risk]
```

### Orchestration (what is non-linear)

* After design sign-off, `impl-core`, `docs`, `contract-tests` and `integration-tests` start **in the same scheduler tick**
  (parallel fan-out); `unit-tests` and `security-review` follow `impl-core`.
* `release-readiness` is a **synchronisation join** over five evidence tracks - asserted in the test suite by event ordering.
* Two human checkpoints: design (`AFTER`, review artifacts) and release packaging (`BEFORE`, HIGH risk, justification required).
* Exit gates reject output unless required artifacts exist and every agent check passed.

### Validation (real, not asserted)

| Evidence | How it is produced |
|---|---|
| Generated `BulkLinkPlanner.java` + `BulkLinkPlannerCheck.java` | compiled in memory with the JDK compiler and the checks **executed** (batch limits, partial failure, de-duplication) |
| OpenAPI contract | parsed; versions, operations, responses, every `$ref` resolved |
| Baseline regression | live HTTP against the running service: create 201, redirect 302, metadata, stats, unsafe URL 400, delete then 404 |
| Release readiness | verdict recomputed from the evidence artifacts; evidence hashes pinned in `release-bundle.json` (dry run) |

**Recorded run** `greenfield-none-run-861164ef`: SUCCEEDED, 10 tasks, 10 executions, 0 retries, 27 audit events, 5.4 s end to end
(0.5 s human wait), audit chain intact.

### Failure injection on the same scenario

* `--failure transient`: `unit-tests` and `integration-tests` each crash once -> 2 bounded retries (exponential back-off) -> recovered;
  **MTTR** is recorded per task.
* `--failure permanent`: `integration-tests` fails every attempt. Recorded run `greenfield-permanent-run-dfa8a339`: 1 attempt + 2 retries,
  then the engine fails the run, **rolls back** `impl-core` (agent hook + workspace deleted), cancels `release-readiness` and
  `release-package`. Counters: 3 failures, 2 retries, 1 rollback.

---

## 2. Brownfield - link expiration on the existing service

**Requirement:** *Links support an optional expiry. After expiry the redirect returns 410 Gone, cached entries must not outlive the
expiry, existing click history is preserved, existing links keep working.*

### Codebase reasoning (on the real source tree)

`codebase-analysis` scans `src/main` (read-only) with probes for the symbols this change must touch, and produces an **impact
report**: impacted files with line-level evidence, modules, API impact, schema impact and data flows, e.g.

* `shortener/domain/ShortLink.java` (entity gains `expiresAt`), `infra/LinkRepository.java` + `db/migration/V1__*` (`short_link`),
  `service/LinkService.java` (lookup path **and** `CacheStore` coupling), `api/RedirectController.java` (new 410 outcome),
  `domain/LinkException.java` (error kind), `api/LinkController.java` (stats shares the lookup).
* Data flow: `GET /{code} -> RedirectController -> LinkService.resolve -> CacheStore(link:<code>) -> LinkRepository.findByCode -> short_link`
  - *expiry must be enforced on both the cache hit and the DB read*.

### Decomposition - the plan **grows from evidence**

Initial DAG: 13 tasks. The scan found cache coupling and stats/lookup coupling, so the analysis agent proposed two more tasks,
which the engine validated and added (`REPLAN`, plan v2 -> **15 tasks**): `impl-cache-ttl` (cache TTL capped by expiry) and
`analytics-regression`, and rewired `unit-tests`, `security-review` and `release-readiness` to wait for them.

```
intake -> codebase-analysis --(evidence)--> design [human review]
design -> migration [HIGH, human review] -> migration-validate -----------+
design -> impl-expiry, impl-cache-ttl* -> unit-tests, security-review ----+
design -> contract-tests, integration-tests, docs, analytics-regression* -+--> release-readiness --> release-package
                                                                                    (join)           [HIGH, approval]
                                                      (* added by the re-plan from code-scan evidence)
```

### Orchestration and governance

* Schema change is **HIGH risk** -> mandatory human checkpoint; the change-control policy also refuses a schema task that does
  not ship a **rollback script**.
* The migration and code tracks run in parallel and join at release readiness.
* **Change control in action:** `migration-validate` executes V1 + V2 + the candidate migration on the real PostgreSQL engine in a
  throw-away schema, asserts the new column, runs the **rollback script**, asserts the column is gone, re-applies, then drops the
  schema. (`migration-applies`, `rollback-restores-schema`, `migration-repeatable` all PASS.)
* Transient failure injection: `migration-validate` and `unit-tests` each fail once and recover (MTTR 971 ms).

**Recorded run** `brownfield-transient-run-bdd2bb0c`: SUCCEEDED, 15 tasks, 17 executions, 2 retries, 1 re-plan, 46 audit events,
7.1 s end to end (0.6 s human wait).

---

## 3. Ambiguous - "safer, faster, and see who clicks"

**Requirement:** *Make our short links safer and faster, and give marketing a way to see who is clicking them.*

### Understanding

The analyst scores ambiguity **1.0**: three vague terms (`faster`, `safer`, `who`) and no measurable criteria. It does **not** plan
on guesses. Only `intake` exists up front (1 task).

### Decomposition emerges at run time (1 -> 3 -> 14 tasks)

1. `intake` proposes `clarify` + `normalize` (re-plan v2): *ambiguity >= 50% -> insert a human clarification checkpoint*.
2. `clarify` produces three multiple-choice questions, each with the consequence of every option and a recommended default. The
   stakeholder answers at the approval checkpoint (dashboard form or CLI).
3. `normalize` folds the answers into explicit capabilities and **assumptions**, then proposes the rest of the SDLC graph - only the
   tracks the answers justify (re-plan v3): design, `impl-safety`, `impl-performance`, `impl-privacy`, verification tracks, release.

### Guardrails stop the agents' first attempts (real policy decisions, not staged failures)

The stakeholder chose the risky option `identified_users`:

| Event | What happened |
|---|---|
| `impl-safety` | primary `codegen` proposed a third-party reputation SDK -> `SUP-001` **blocked** (`com.shady:phishguard-sdk` not on the allow-list) -> retries 0 -> **fallback** `codegen-conservative` produced an in-house host blocklist |
| `impl-privacy` | first attempt stored `ip_address` -> `CMP-001` **blocked** -> **retry with the policy message as feedback** -> hashed visitor schema (`visitor_hash`), and the rationale records that individual identification cannot be built under policy |
| Unsafe artifacts | never persisted (asserted: no `phishguard`, no `ip_address VARCHAR` anywhere in the artifact store) |

### Upstream change -> dynamic re-plan with governance preserved

After completion the stakeholder changes the tracking answer to `aggregate_only` (`--change tracking=aggregate_only`, dashboard
"Change an answer later"). `clarify` is re-opened; its committed hash changes, so the engine **invalidates only dependants whose
output changes**, hop by hop (`normalize` -> `design` -> impl tracks -> unit/security -> readiness -> release):

* 42 task resets, **approvals revoked and given again** (design and release were approved twice).
* `contract-tests`, `integration-tests`, `docs` re-ran but produced identical output, so nothing below them cascaded needlessly.
* `impl-privacy` now generates the aggregate-only counter and a `daily_click` schema.

**Recorded run** `ambiguous-none-run-168c82f9-change`: SUCCEEDED, 14 tasks (plan v3), 30 executions, 1 retry, **2 fallbacks**,
**3 policy blocks**, 95 audit events, 15.0 s end to end (2.0 s human wait), audit chain intact.

---

## Recorded runs at a glance

| Run (evidence in `docs/runs/`) | Result | Tasks (plan v) | Executions | Retries | Fallbacks | Rollbacks | Policy blocks | Re-plans | Invalidated | MTTR | End to end (human wait) | Audit events |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| greenfield, no failures (`861164ef`) | SUCCEEDED | 10 (v1) | 10 | 0 | 0 | 0 | 0 | 0 | 0 | - | 5.4 s (0.5 s) | 27 |
| greenfield, permanent failure (`dfa8a339`) | FAILED | 10 (v1) | 10 | 2 | 0 | 1 | 0 | 0 | 0 | - | 4.4 s (0.4 s) | 29 |
| brownfield, transient failures (`bdd2bb0c`) | SUCCEEDED | 15 (v2) | 17 | 2 | 0 | 0 | 0 | 1 | 0 | 971 ms | 7.1 s (0.6 s) | 46 |
| ambiguous + answer change (`168c82f9`) | SUCCEEDED | 14 (v3) | 30 | 1 | 2 | 0 | 3 | 10 | 42 | 781 ms | 15.0 s (2.0 s) | 95 |

*Re-plans* counts every re-planning event (agent proposals, human re-opens, upstream-change invalidations); *Invalidated* counts task
resets caused by upstream changes. Latencies include the artificial agent "think time" (700 ms per task) that makes progress visible in the dashboard.
