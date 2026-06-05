# Marketplace Spine (Modules 2 + 3) — Design Spec

**Date:** 2026-06-05
**Status:** Approved (design); pending per-track implementation plans
**Author:** solo FYP build
**Build window:** ~1 week. Execution via **option 1** — in-session Workflow with worktree-isolated agents.

## Goal

Deliver a demonstrable end-to-end **marketplace spine**: a client submits a task (already built), the platform routes it to a registered third-party Agent, dispatches it over a signed webhook via RabbitMQ, the Agent executes and posts a result back, and the result is recorded. This closes the loop **Task → Agent → Routing → Execution → Result**.

## Scope

**In scope (3 modules total for the demo):**
- **Module 1 — Task Submission** — *already built* (submit + atomic escrow freeze + binding `output_spec`). Reused; extended with `category` + routing/execution status transitions + `task_results`.
- **Module 2 — Agent Registration** — register an Agent + its first version, activate it, list/get owned Agents.
- **Module 3 — Routing & Execution** — match a submitted task to an ACTIVE Agent, dispatch via RabbitMQ + signed HTTPS webhook, receive the result on a token-authenticated callback.

**Out of scope (explicitly deferred):**
- **Module 4** — full automated `output_spec` *validation* and dispute/arbitration. The spine *stores* the result; at most a minimal format sanity check. No arbitration service yet.
- **Module 5 settlement** — escrow *release/refund/split* and reputation events. Escrow *freeze* stays as-is; credits do not leave escrow in this slice.
- Multi-version Agent publishing, suspend/deactivate flows, Agent discovery catalogue (Module 6).
- Object storage for attachments/results — results are stored inline (JSONB/URL) for the demo.

## Context — what exists today

- **Task aggregate** (`V2`): `tasks(id, client_id, title, description, budget, output_spec jsonb, status, gmt_*)`. Only `SUBMITTED` reachable; full lifecycle enum already present and forward-compatible. `output_spec` holds `{format, schema, acceptanceCriteria}`.
- **Wallet aggregate** (`V1`): append-only `ledger_entries` (DB triggers block UPDATE/DELETE), atomic escrow freeze on submit.
- **`TaskSubmittedDomainEvent`** already published after submit — this is the routing trigger seam.
- DDD layering enforced: `controller → application → domain ← infrastructure`; domain is framework-free. **Every service class = interface + `impl/`** (app services Spring-managed; domain services framework-free, wired in `DomainServiceConfig`).

## Architecture — build shape (how option 1 fans out)

```
[Contracts-first]  define + commit the seam types        (sequential, 1 agent)
        │
        ├──► Track A: Agent Registration aggregate        (worktree)  Flyway V3
        ├──► Track B: Dispatch infra (RabbitMQ) + stub     (worktree)
        ├──► Track C: Task aggregate extensions            (worktree)  Flyway V4
        │
[Synthesis]  Routing matcher wires A + B + C end-to-end   (sequential)
        │
[Verify]  adversarial review + live run + integration test
```

The three tracks edit **disjoint files** (see *File isolation* below), so they run concurrently in isolated git worktrees with no merge conflicts. Routing is the convergence point and runs sequentially after the wave merges.

## Contracts-first (defined and committed to the base branch before the fan-out)

These seam types let A/B/C build in parallel against stable interfaces. All live in the backend; each track branches from this commit.

- **`AgentCandidate`** (read-model for matching) — `record(UUID agentId, UUID agentVersionId, List<String> capabilityCategories, BigDecimal price, String webhookUrl, int maxExecutionSeconds, BigDecimal reputationScore)`.
- **`AgentRepository.findActiveCandidates(String category, BigDecimal maxPrice)` → `List<AgentCandidate>`** — interface signature only (Track A implements).
- **`DispatchMessage`** (RabbitMQ payload) — `record(UUID taskId, UUID agentVersionId, String webhookUrl, String correlationId, TaskDispatchPayload payload)` where `TaskDispatchPayload(String title, String description, String category, String expectedDeliverableJson, String outputSpecJson, String callbackUrl)`.
- **`AgentResultCallbackRequest`** (callback body) — `record(String agentStatus, String resultPayloadJson, String resultUrl, String message)`; `agentStatus ∈ {COMPLETED, FAILED}`.
- **`DispatchTokenService`** — interface is an **application-layer port** (impl in `infrastructure/security`, Track B): `String issue(UUID taskId, UUID agentVersionId, Duration ttl)` and `DispatchTokenClaims verify(String token)` (throws on invalid/expired); `DispatchTokenClaims(UUID taskId, UUID agentVersionId, Instant expiresAt)`.
- **Queue constants** — `task.dispatch` (exchange + queue + routing key) and `task.dispatch.dlq` (dead-letter). Single class of string constants.

## Module 2 — Agent Registration (Track A)

**Aggregate:** `AgentModel` (root) + `AgentVersionModel` (child); `OutputSpec`, `Pricing` value objects. One repository (`AgentRepository`), one root.

**Schema — Flyway `V3`:**
- `agents(id, owner_id FK users, name, status, current_version_id, reputation_score NUMERIC(5,2) DEFAULT 50.00, gmt_create, gmt_modified)`; `status CHECK IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DEACTIVATED')`; index on `owner_id`. `current_version_id` is a plain UUID (no FK — avoids circular-FK ordering with `agent_versions`).
- `agent_versions(id, agent_id FK agents, version_number INT, output_spec JSONB, capability_categories TEXT[], webhook_url TEXT, max_execution_seconds INT CHECK > 0, price NUMERIC(14,2) CHECK >= 0, gmt_create, gmt_modified, UNIQUE(agent_id, version_number))`; GIN index on `capability_categories` for matching.

**Status enum:** `PENDING_VERIFICATION → ACTIVE → SUSPENDED / DEACTIVATED` (only PENDING→ACTIVE implemented this slice).

**Endpoints (MVP slice):**
| Endpoint | Behaviour | Invariant |
|---|---|---|
| `POST /api/agents` | Register agent + version v1 (returns agentId) | `builderId` from JWT (#5); `webhook_url` must be **HTTPS** (#6); status starts `PENDING_VERIFICATION` |
| `POST /api/agents/{agentId}/activate` | `PENDING_VERIFICATION → ACTIVE`, set `current_version_id` | requires a version; only ACTIVE agents route |
| `GET /api/agents` | List the caller's agents | owner-scoped by JWT |
| `GET /api/agents/{agentId}` | Get one owned agent | explicit owner check |

**Domain services (interface + `impl/`, framework-free):** `AgentRegisterDomainService` (build `AgentModel` + `AgentVersionModel`, enforce HTTPS webhook, non-blank name, ≥1 capability category, positive max_execution_seconds, non-negative price), `AgentActivateDomainService` (legal transition + current version present). Registered in `DomainServiceConfig`.

**App services:** `AgentWriteAppService` (register, activate), `AgentReadAppService` (list/get → DTOs, plus the `findActiveCandidates` read used by routing lives on `AgentRepository`).

**Events:** `AgentRegisteredDomainEvent`, `AgentActivatedDomainEvent` (published; no consumers required this slice).

## Module 3 — Routing & Execution

### End-to-end flow

```
TaskSubmittedDomainEvent ─(@TransactionalEventListener AFTER_COMMIT)─► RoutingAppService.route(taskId)
  read task routing view (category, budget, status)
  candidates = AgentRepository.findActiveCandidates(category, ≤ budget)
  choice = RoutingMatchDomainService.selectAgentVersion(criteria, candidates)
  ├─ match → TaskWriteAppService.assignAndQueue(taskId, agentVersionId)   [SUBMITTED→QUEUED]
  │           └─ TaskDispatchPublisher.publish(DispatchMessage) → RabbitMQ task.dispatch
  └─ none  → TaskWriteAppService.markAwaitingCapacity(taskId)             [→AWAITING_CAPACITY]

TaskDispatchConsumer (@RabbitListener task.dispatch)
  token = DispatchTokenService.issue(taskId, agentVersionId, ttl = maxExecutionSeconds + buffer)
  AgentDispatchClient.dispatch(webhookUrl, payload, token, correlationId)  -- signed HTTPS POST
  TaskWriteAppService.markExecuting(taskId)                                [QUEUED→EXECUTING]
  on exception → RabbitMQ retry; exhausted → task.dispatch.dlq → markTimedOut/markFailed

Agent → POST /api/agent-callbacks/{taskId}/result  (Authorization: Bearer <dispatch token>)
  DispatchTokenService.verify(token)  -- 401 if invalid/expired/taskId mismatch
  TaskWriteAppService.recordResult(taskId, AgentResultCallbackRequest)    [EXECUTING→RESULT_RECEIVED]
  persist task_results
```

### Track B — Dispatch infrastructure + stub agent

- `infrastructure/messaging/` — RabbitMQ config (declare `task.dispatch` queue/exchange/binding + DLQ with TTL/retry policy), `TaskDispatchPublisher`, `TaskDispatchConsumer` (`@RabbitListener`), DLQ consumer.
- `infrastructure/client/AgentDispatchClient` — `RestClient`/`WebClient` POST to `webhook_url`; **enforces HTTPS** (#6); sets `X-Correlation-ID` and `Authorization: Bearer <token>`; honors a connect/read timeout.
- `infrastructure/security/DispatchTokenService` impl — HMAC-signed compact token (server secret from env), claims `{taskId, agentVersionId, exp}`; `verify` rejects bad signature / expiry / wrong taskId.
- **`demo-agent/`** — a minimal standalone stub Agent process (recommended: ~40-line FastAPI, or Node/Express if Python setup is undesirable now). Receives the dispatch POST, checks the Bearer token is present, waits briefly, then POSTs a spec-conforming result to `callbackUrl` with the same token.
- **Owns** the `pom.xml` (add `spring-boot-starter-amqp`) and `application.yml` (RabbitMQ connection) edits.

### Track C — Task aggregate extensions

- **`TaskModel`** guarded transition methods (legal-transition checks; immutable copies): `assignAndQueue(agentVersionId)`, `markExecuting()`, `recordResult(TaskResultModel)`, `markAwaitingCapacity()`, `markTimedOut()`, `markFailed()`.
- **`TaskResultModel`** child + `task_results` persistence; `TaskRepository` save loads/saves the child through the root.
- **`TaskWriteAppService`** new methods + impl: `assignAndQueue`, `markExecuting`, `recordResult`, `markAwaitingCapacity`, `markTimedOut`, `markFailed`. Add a routing read (`TaskReadAppService.getRoutingView(taskId) → TaskRoutingView(taskId, category, budget, status)`).
- **Callback controller** `controller/biz/agentcallback/AgentCallbackController` — `POST /api/agent-callbacks/{taskId}/result`; thin — delegates to `AgentCallbackAppService.recordResult(taskId, bearerToken, body)`, which calls `DispatchTokenService.verify` (invalid/expired/mismatched token → mapped to **401**) then the Task `recordResult` transition. Keeps the controller out of `infrastructure`. Token-authenticated, **not** JWT-authenticated (it is the Agent calling back).
- **Schema — Flyway `V4`:** `ALTER TABLE tasks ADD COLUMN agent_version_id UUID` (no FK — keeps Track A/C independent), `ADD COLUMN category TEXT`; new `task_results(id, task_id UNIQUE FK tasks, result_payload JSONB, result_url TEXT, agent_status TEXT, received_at, gmt_*)`. Extend `SubmitTaskRequest`/`TaskSubmitInfo`/`TaskModel.submit` to accept `category`.

### Synthesis — Routing (sequential, after the wave merges)

- `domain/biz/routing/service/RoutingMatchDomainService` (interface + `impl/`, framework-free): given `TaskRoutingView` + `List<AgentCandidate>`, pick the best ACTIVE candidate (covers category, `price ≤ budget`; tie-break by highest `reputationScore`). Returns the chosen `agentVersionId` or empty. Registered in `DomainServiceConfig`.
- `application/biz/routing/RoutingAppService` + `impl/`: orchestrates read → match → assign/queue or awaiting-capacity → publish. Depends on `TaskReadAppService`, `TaskWriteAppService`, `AgentRepository`, `TaskDispatchPublisher` (interfaces only).
- `application/biz/routing/RoutingEventListener`: `@TransactionalEventListener(phase = AFTER_COMMIT)` on `TaskSubmittedDomainEvent` → `RoutingAppService.route`.

## File isolation (why the parallel wave is conflict-free)

| Track | Owns (no overlap with other parallel tracks) |
|---|---|
| **A** | `…/domain/biz/agent/**`, `…/application/biz/agent/**`, `…/infrastructure/repository/agent/**`, `…/controller/biz/agent/**`, Flyway `V3`, `DomainServiceConfig` (only A in the wave) |
| **B** | `…/infrastructure/messaging/**`, `…/infrastructure/client/**`, `…/infrastructure/security/DispatchTokenService*`, `demo-agent/`, `pom.xml`, `application.yml` |
| **C** | `…/domain/biz/task/**`, `…/application/biz/task/**`, `…/application/biz/agentcallback/**`, `…/infrastructure/repository/task/**`, `…/controller/biz/agentcallback/**`, Flyway `V4` |
| **Synthesis** | `…/domain/biz/routing/**`, `…/application/biz/routing/**`, plus a `DomainServiceConfig` bean (sequential — edits the merged result) |

`tasks.agent_version_id` is an unconstrained UUID (no cross-track FK); FK hardening is a later step. The routing trigger re-reads the task rather than enriching `TaskSubmittedDomainEvent`, so the event contract stays stable.

## Security & invariants preserved

- **#1 escrow before execution** — escrow is frozen atomically at submit (existing). Routing fires only `AFTER_COMMIT`, so dispatch never precedes a committed freeze.
- **#4 binding output_spec** — the contract on the chosen `agent_version` is carried in `DispatchMessage` and stored with the result; full validation deferred to Module 4.
- **#5 server-side identity** — Agent register/activate/list derive `builderId` from the JWT; explicit owner checks. The callback endpoint is the one non-JWT route, secured instead by the dispatch token.
- **#6 signed HTTPS Agent I/O** — `AgentDispatchClient` rejects non-HTTPS webhooks; dispatch carries a short-lived HMAC token; the callback returns **401** on invalid/expired/mismatched token. *Local-demo exception:* a `dev` profile may allow `http://localhost` stub URLs so the demo runs without a public HTTPS tunnel; the signed-token check stays enforced in all profiles.

## Testing strategy

- **Unit:** matching selection (category coverage, budget filter, reputation tie-break), token issue/verify (happy + tampered + expired), each `TaskModel` transition guard (legal vs illegal).
- **Integration (Testcontainers Postgres **+ RabbitMQ**, auto-skips without Docker):** submit → routing listener → dispatch consumer → stub-agent callback → assert `RESULT_RECEIVED` + persisted `task_results`. A no-match case → `AWAITING_CAPACITY`. A bad-token callback → `401`.
- **Demo script:** one scripted happy-path (register agent → activate → submit task → observe routing/execution → result recorded) for the presentation.

## Risks & mitigations

- **RabbitMQ + two modules in one week is ambitious solo.** Mitigation: dispatch sits behind the `TaskDispatchPublisher` / `TaskDispatcher` seam, so a direct-dispatch fallback needs no domain/routing change; Testcontainers RabbitMQ keeps tests hermetic.
- **HTTPS stub locally (#6).** Mitigation: `dev`-profile localhost exception, or an HTTPS tunnel (cloudflared/ngrok) for a more faithful demo. Decided in the plan.
- **Cross-track read coupling (Synthesis needs Track C's `getRoutingView`).** Mitigation: Synthesis runs after the wave merges, so it consumes a merged, committed method.

## Parallel orchestration (option 1)

A single Workflow: (1) one agent does **contracts-first** and commits the seam types to the base branch; (2) `parallel()` fans out **A, B, C** as `isolation:"worktree"` agents from that commit; (3) after the wave merges, a sequential **Synthesis** agent builds routing and wires everything; (4) a **verify** phase runs adversarial review + the live run + the integration test. Per-track implementation plans (next step) become the precise task lists each worktree agent executes.
