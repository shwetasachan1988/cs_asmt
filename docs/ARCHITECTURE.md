# Architecture

## 1. Goals and design principles

1. **Controlled autonomy.** Agents do the work inside a sandboxed contract; the *engine* owns every state transition,
   gate, approval and plan change. An agent can only return artifacts, evidence and *proposals*.
2. **Governance is code.** Policies are evaluated by the engine at plan time, entry and exit. They are not prompts and an agent
   cannot negotiate with them.
3. **Everything is explainable.** Each decision (plan, replan, retry, fallback, approval, rollback, stop) is an audit event in a
   hash chain; each artifact is immutable, versioned and content-hashed; lineage links artifacts to the tasks that consumed them.
4. **Fail small, stay recoverable.** Bounded retries, fallbacks, reverse-order rollback, safe-stop. State survives a restart.
5. **Boring technology.** One deployable (modular monolith), PostgreSQL + Redis, no workflow framework - so the orchestration
   logic is visible, testable and reviewable (the whole `engine` package is about 1,300 lines).

## 2. System context

```
                         +----------------------------- Spring Boot application ------------------------------+
  Browser dashboard ---->|  orchestrator.api  ->  WorkflowEngine  ->  agents (14)   policies (6)               |
  DemoRunner (CLI) ->|                         | gates, retries,   | read-only scan of src/main            |
  (human approvals)      |                         | rollback, replan  | scratch-schema SQL | live HTTP smoke  |
                         |                         v                                                           |
                         |  persistence: workflow_run (snapshot) | audit_event (hash chain) | artifact        |
                         |                                                                                     |
  Clients  ------------->|  shortener.api -> LinkService -> CacheStore(Redis) / LinkRepository / Analytics     |
                         +-------------------------------------+-----------------------------------------------+
                                                               |
                                         PostgreSQL 16  <------+------>  Redis 7 (cache, rate limit)
```

The orchestrator *governs changes to* the shortener: its agents read the shortener's source tree (read-only), validate
migrations against the real database engine (scratch schema) and regress the running shortener over HTTP.

## 3. Components

| Package | Responsibility |
|---|---|
| `orchestrator.model` | `TaskSpec` (immutable DAG node **and** governance envelope), `WorkflowRun`/`TaskState` (mutable state, JSON snapshot), `Artifact`, `AgentResult`, `ReplanRequest`, `Violation` |
| `orchestrator.engine` | `WorkflowEngine` (scheduler, gates, failure policy, approvals, safe-stop), `PlanMutator` (validated plan changes + invalidation), `DagValidator`, `WorkspaceService` (only place agents' output touches disk) |
| `orchestrator.policy` | `Policy` interface (plan/entry/exit checks), `PolicyEngine`, six policies |
| `orchestrator.agent` | `Agent` contract + 14 deterministic agents |
| `orchestrator.scenario` | `ScenarioDefinition` + three scenarios, `SdlcPlans` (shared verification/release sub-graph) |
| `orchestrator.persistence` | `RunRepository` (versioned snapshots, optimistic lock), `AuditLog` (hash chain), `ArtifactRepository` |
| `orchestrator.metrics`, `orchestrator.api` | reliability metrics; REST control plane; lineage and final-summary read models |
| `shortener.*` | the product: API, services, ports/adapters (`CacheStore`, `RateLimiter` with Redis and in-memory adapters) |

## 4. Orchestration model

### 4.1 The plan is a DAG with a governance envelope

Each `TaskSpec` carries *what to run* and *how it may run*:

| Field | Meaning |
|---|---|
| `dependsOn` | edges of the DAG. A task is runnable only when **all** dependencies are `SUCCEEDED` (join/synchronisation) |
| `risk` (LOW/MEDIUM/HIGH) | HIGH **must** have an approval checkpoint - rejected at plan time otherwise, also for tasks added mid-run |
| `approval` (NONE/BEFORE/AFTER) | BEFORE = a human authorises the action; AFTER = a human reviews the artifacts before dependants may use them |
| `requiredArtifacts` | exit-gate contract: the task fails if these artifact types are missing |
| `maxRetries`, `fallbackAgent` | bounded retry budget, then a more conservative agent |
| `rollbackOnFailure` | completed work is undone (agent hook + workspace cleanup) if the run later fails |
| `params` | agent inputs and policy flags (`schemaChange=true` demands a rollback script) |

Branches without a dependency run **in parallel** (up to `max-parallel-tasks`, default 4, on virtual threads). The release
readiness task is the synchronisation point: it starts only when every evidence track has finished.

### 4.2 Task lifecycle

```
PENDING --deps ok--> READY --entry gate--> RUNNING --exit gate ok--> SUCCEEDED
   ^                   |  (BEFORE approval)    |  \--exit gate fails--> retry (READY, backoff) --> fallback --> FAILED
   |                   v                       |  \--CRITICAL violation--> run SAFE_STOPPED (task back to PENDING)
   |             AWAITING_APPROVAL             v
   |                   | approve -> READY    AWAITING_APPROVAL (AFTER) --approve--> SUCCEEDED
   |                   | reject  -> FAILED          \--reject--> rework (PENDING, feedback) up to 2x --> FAILED
   +---- upstream output changed (invalidation): any started dependant is reset here, approvals revoked
FAILED/others on run failure: completed rollbackOnFailure tasks -> ROLLED_BACK, the rest -> CANCELLED
```

Run status: `RUNNING`, `AWAITING_APPROVAL` (nothing runnable, a human is needed), `SUCCEEDED`, `FAILED`, `SAFE_STOPPED`.
If nothing is runnable and nothing awaits a human, the engine declares a *plan deadlock* and fails the run instead of hanging.

### 4.3 Scheduling and concurrency

* The engine is **event driven**: `advance(run)` is a scheduler tick, invoked on start, on every agent result, on approvals and
  on retry timers.
* All state transitions for a run happen under a **per-run lock** and are snapshotted to PostgreSQL (optimistic `version`).
  Agent execution runs **outside** the lock, so a slow agent never blocks approvals or other branches.
* Each `TaskState` has a **generation** counter. When upstream work is invalidated or a run is stopped, the generation is bumped
  and a late result from the old execution is discarded (`RESULT_DISCARDED`) rather than applied.
* On startup the engine re-queues tasks that were `RUNNING` when the process died (`TASK_RECOVERED`). Verified by restarting the
  container mid-run.

### 4.4 Gates

**Entry gate** (before an agent starts), in order: dependencies satisfied -> retry back-off elapsed -> parallelism cap ->
`BEFORE` approval requested/recorded -> policy entry checks (approval integrity: recorded, human, not an `agent*` identity;
release work needs every other task done) -> launch.

**Exit gate** (before anything the agent produced is accepted): policy scan of every artifact -> required artifacts present ->
all agent-reported validation checks passed. Only then are artifacts persisted, context published and plan proposals applied.

| Violation severity | Effect |
|---|---|
| `WARN` | recorded in the audit trail |
| `BLOCK` | exit gate fails; counts as a failed attempt (retry -> fallback -> fail). The violation text is fed back to the agent as feedback |
| `CRITICAL` | artifacts are **not persisted** (a leaked secret must not be copied into our own stores), the run is **safe-stopped** |

### 4.5 Human approvals

* `decide(run, task, APPROVE|REJECT, approver, comment, answers)`; the approver identity is recorded in the audit trail.
* **Separation of duties:** identities starting with `agent` are refused. HIGH-risk approvals and every rejection need a written
  justification (>= 10 chars).
* `AFTER` rejection sends the task back for **rework** with the reviewer's text as feedback (max 2 reworks, then the run fails).
* `answers` carries structured human input (clarification answers) into the shared context.

### 4.6 Failure handling

| Mechanism | Behaviour |
|---|---|
| **Bounded retries** | `maxRetries` per task, exponential back-off (`base * 2^(n-1)`, capped). Non-retryable agent errors (bad input, rejected plan proposal) go straight to failure - no retry, no fallback |
| **Fallback** | after retries are exhausted a `fallbackAgent` runs (fresh budget). Example: SDK-based reputation check blocked by policy -> in-house blocklist agent |
| **Rollback** | when a task finally fails, completed `rollbackOnFailure` tasks are unwound in **reverse completion order**: agent hook (e.g. drop scratch schema) + workspace cleanup. Best-effort; failures are audited as `ROLLBACK_FAILED` for manual clean-up |
| **Safe-stop** | no new task starts; in-flight results and human decisions are still recorded; only a human can `resume` |
| **Kill switch** | global emergency brake: safe-stops every active run and blocks `resume` until released |
| **Autonomy budgets** | max parallel tasks (4), max agent-proposed plan changes per run (5), max tasks per run (60), per-task timeout (60 s), artifact count/size caps. Exceeding a budget safe-stops |

### 4.7 Dynamic re-planning (two distinct mechanisms)

1. **Plan proposals.** An agent returns a `ReplanRequest` (add tasks, prune pending ones, add dependencies). `PlanMutator`
   validates it - registered agents, policy `checkPlan` (e.g. HIGH risk needs approval), only not-yet-started tasks can be rewired,
   DAG stays acyclic, budgets hold - and records a `REPLAN` decision with the agent's rationale. Proposals are **idempotent**, so
   a re-executed agent can re-propose safely.
   *Examples:* the brownfield code scan adds `impl-cache-ttl` and `analytics-regression` only because it found those couplings in
   the real source; the ambiguous scenario grows from 1 to 14 tasks as answers arrive.
2. **Upstream-change invalidation.** When a task becomes `SUCCEEDED` its *committed hash* (artifact hashes, plus human answers for
   human-input tasks) is compared with the previous one. If it differs, every started dependant is reset to `PENDING`, **its
   approvals are revoked**, and in-flight results are orphaned. If the output is identical nothing downstream reruns.
   Triggered by a human `reopen` (e.g. a changed answer), by a rework, or by any re-executed task. Outputs must therefore be
   deterministic - a lesson learned when a test report embedding a random link code caused needless cascades (now fixed).

### 4.8 Context, lineage and audit

* **Cross-stage context:** a shared blackboard (`run.context`: ambiguity score, resolved capabilities, human answers, migration
  facts) plus the latest artifacts of each dependency, handed to the agent in `AgentContext`. Agents receive a deep copy.
* **Lineage:** every task records the ids of the artifacts it consumed (and an input hash). `/lineage` returns the artifact flow
  (producer -> consumer, version, hash) and the decision events that shaped the run.
* **Audit log:** append-only `audit_event`; each row stores the previous row's hash and `sha256(prev|run|seq|ts|actor|type|node|detail)`.
  Editing, deleting or reordering any event breaks verification (`/audit/verify`, covered by tests).
* **Metrics:** run/task success rate, retry rate, fallback and rollback frequency, MTTR (first failure -> eventual success per task),
  end-to-end latency and *active* latency (minus human wait), p50/p95, per scenario. Also exported through Micrometer
  (`/actuator/prometheus`).

## 5. Control flow of one task (sequence)

```
advance() tick  [lock]            agent thread (virtual)                       onResult()  [lock]
  deps met? approval? entry policies?
  mark RUNNING, attempts++, record consumed artifacts + input hash
  audit TASK_STARTED --------------> think / (chaos injection) / agent.execute(ctx)  [timeout 60 s]
                                                          |
                                                          +--------------> generation still current?  else discard
                                                                           exit gate (policies, required artifacts, checks)
                                                                             fail -> retry / fallback / failRun(rollback)
                                                                             CRITICAL -> safe-stop (nothing persisted)
                                                                           persist artifacts, publish context
                                                                           apply replans (validated) -> REPLAN event
                                                                           AFTER approval? AWAITING_APPROVAL : commitSuccess
                                                                           commitSuccess: MTTR sample, hash compare, invalidate
                                                                           save snapshot  -> advance()
```

## 6. Policy guardrails

| Policy | Enforces | Phase | Severity |
|---|---|---|---|
| `SEC-001-secret-scan` | no credentials/keys/tokens in any artifact | exit | CRITICAL |
| `CMP-001-no-raw-pii` | schema/code must not store raw IP, e-mail, phone ... (hashes accepted) | exit | BLOCK |
| `SUP-001-dependency-allowlist` | only approved library groups may be introduced | exit | BLOCK |
| `SEC-002-unsafe-code` | generated source limited to a pure-computation JDK subset (no process/file/net/reflection/threads) | exit | BLOCK |
| `AUT-001-workspace-boundary` | artifact paths stay in the workspace; size and count caps | exit | BLOCK |
| `CHG-001-change-control` | HIGH risk needs a checkpoint; BEFORE needs a recorded human approval; schema change needs a rollback script; release needs all work done | plan / entry / exit | BLOCK |

The security-review agent re-runs all exit policies over every upstream artifact as an independent second look.

## 7. Data model

| Table | Notes |
|---|---|
| `workflow_run` | `id`, `status`, `version`, `state_json` (plan, task states, context, counters) - optimistic locking |
| `audit_event` | `(run_id, seq)` unique, `prev_hash`, `hash` - append-only chain |
| `artifact` | immutable, `(run, node, type, name, version)`, `sha256`, `content` |
| `short_link`, `click_event` | the product; `click_event` stores a salted daily visitor **hash**, never an IP |

## 8. The shortener: key design points

* **Codes:** DB sequence -> multiplied by a constant coprime to 62^7 (a bijection) -> Base62. Unique **by construction** (no
  collision retry loop, verified over 200k ids), yet not sequentially guessable.
* **Redirect path:** cache-aside in Redis (`id|url`), **negative caching** (30 s) against cache-penetration, 302 (not 301) so
  deletions take effect, DB fallback if Redis is down.
* **Analytics is off the hot path:** bounded queue + worker pool; overflow is dropped and counted (`shortener.clicks.dropped`) -
  redirects never wait for analytics. Visitors are pseudonymised (SHA-256 of daily salt + address).
* **Rate limit:** fixed window per client address on creation only; **fails open** if Redis is unavailable.
* **Validation:** http/https only, no embedded credentials, no loopback/private targets, length cap; RFC 7807 errors that never leak internals.
* **Soft delete** keeps click history for audit.

## 9. Key decisions and trade-offs

| Decision | Alternatives | Why / cost |
|---|---|---|
| Modular monolith, orchestrator in-process | separate service; Temporal / Camunda / Airflow | one artifact to run and review; the logic *is* the deliverable. Cost: no independent scaling |
| JSON snapshot per run + append-only audit | full event sourcing | simple, restart-safe, easy to query; cost: audit is evidence, not a rebuild source; snapshot rewrite per transition |
| Per-run lock + virtual threads | actors, reactive | straightforward reasoning about races; cost: single-node scheduler (see 11) |
| Agents **propose**, engine **disposes** | agents mutate the DAG | prevents a misbehaving agent from escalating its own scope |
| Policies as code in the engine | prompt-level guardrails | deterministic, testable, auditable |
| Committed-hash invalidation | always rerun / never rerun | re-executes exactly what changed; cost: needs deterministic outputs |
| Approvals BEFORE / AFTER | single mode | authorise risky *actions* vs review *artifacts* are different controls |
| Deterministic agents behind `Agent` | LLM calls | offline, reproducible demo and tests; LLM agent is a drop-in |
| H2 (PG mode) in tests | Testcontainers | works inside `docker build`/CI without Docker-in-Docker; mitigated by the migration validator and compose runs on real PostgreSQL |
| Dry-run release | real deploy | deployment needs credentials and environments outside scope; manifest pins what was reviewed |

## 10. Security notes

* Approver identity is **asserted, not authenticated** in this prototype (production: SSO/OIDC). State-changing orchestrator calls
  can be protected with `X-API-Key`.
* Dashboard builds DOM with text nodes only (no `innerHTML` with server data).
* Agents write only to `workspace/{run}/{task}/`; reads of the source tree are read-only; migration validation uses a throw-away schema.
* Generated code is compiled and executed **in-process** by the unit-test agent behind an API allow-list policy. This is defence in
  depth, not a sandbox - untrusted LLM code should run in an isolated container (listed under limitations).

## 11. Scaling path (not built)

Multi-instance scheduling needs a lease per run (`SELECT ... FOR UPDATE SKIP LOCKED` or a distributed lock) instead of the in-process
lock; events could be published through an outbox for the dashboard (SSE instead of polling); the agent pool would move to a
queue with workers; Redis would hold the kill switch instead of process memory.
