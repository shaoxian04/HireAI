# Backend capability re-division & shape-deepening — design

**Date:** 2026-06-29
**Status:** approved design, pending implementation plan
**Branch:** `refactor/backend-capability-redivision` (off the marketplace-spine backend)
**Authoritative target:** SAD §3.1 (six subdomains), §3.3 (aggregate model), §7.2 (identity). Conventions: `docs/details/ddd-conventions.md`.

## 1. Context & goal

The backend is a working, well-tested marketplace spine (322 tests) organised as a COLA multi-module reactor, but its `domain`/`application`/`repository` code is grouped by the **flat aggregate set** `biz.{wallet, task, agent, routing, review, user}` — which predates the SAD's strategic **six-subdomain capability map**. Several aggregates are also thinner than the SAD §3.3 model draws them (anemic `User`, settlement-as-a-stateless-service, single pinned `AgentVersion`).

**Goal:** re-divide the backend into the SAD's six capability subdomains across every module **and** deepen the shapes the re-division naturally touches into their SAD §3.3 form — without breaking the green suite, and leaving the larger feature builds (Adjudication, reliability, reputation engine) as cleanly-scoped future specs.

This is a **refactor + targeted model rebuild**, not a feature build. It implements the "subdomain re-division" that the SAD and prior notes already named as planned, and it is the structural precondition that makes the deferred modules (§7) drop in cleanly.

## 2. Constraints & decisions

| Decision | Choice | Rationale |
|---|---|---|
| **Depth** | Re-organise **and** deepen the shapes the re-division touches | A pure rename wouldn't realise the capability model; the deepening is bounded to Identity/Ledger/Offering. |
| **Blast radius** | Everything fair game, but **exercise judgment** | Routes/tables only change where it genuinely serves the new structure. In practice: routes stay stable, migrations are additive. |
| **Execution** | **Incremental strangler**, one subdomain at a time, always-green | Solo project + 322-test suite → reviewable, pausable/resumable; tolerates a temporary mixed old/new state. |
| **Migrations** | **Additive `V13+`, no squash** | Preserves the demo DB + Flyway-discipline story; `V1–V12` stay immutable. |
| **Verification** | Run the suite **with Docker up** at each milestone; Playwright pass after the Offering frontend change | Testcontainers integration tests auto-skip without Docker and have masked a regression before — green-without-Docker is not trusted. |

## 3. Target taxonomy & package map

**Rule:** group `domain` / `application` / `repository` by **subdomain**; nest by aggregate only when a subdomain holds more than one aggregate. Controllers stay grouped by **HTTP route** (so `/api/*` stays stable). Tech adapters (`infrastructure.{messaging, security, client}`) stay grouped by technology.

```
com.hireai.domain.biz / application.biz / infrastructure.repository
│
├── identity/          (was: user + auth)            ← Identity 身份域 (Generic)
│     └─ User(root) + Credential(VO) + OAuthIdentity(entity)
│
├── ledger/            (was: wallet)                 ← Ledger 账务域 (Generic)
│     ├─ wallet/       Wallet(root) + LedgerEntry
│     └─ settlement/   Settlement(root) + SettlementPolicy   [promoted out of task]
│
├── offering/          (was: agent + catalogue)      ← Agent Offering 供应域 (Supporting)
│     ├─ agent/        Agent(root) + AgentVersion(child, real lifecycle)
│     ├─ storefront/   Storefront(root) + Media(VO)  (was AgentProfileModel)
│     └─ (catalogue read-model lives on the application + repository read side)
│
├── task/              (was: task + routing + agentcallback)  ← Task 任务域 (Core)
│     └─ Task(root) + TaskResult; RoutingMatchDomainService (pure);
│        Routing / Execution / Callback app services
│
├── reputation/        (was: review)                 ← Reputation 信誉域 (Supporting)
│     └─ Review(root)   [AgentRating + ReputationEvent reserved — Module 5, deferred]
│
└── adjudication/      (NEW — name reserved, no code this pass)  ← Adjudication 裁决域 (Core)
```

**Key moves**
- `wallet → ledger.wallet`; **Settlement** promoted from a stateless service inside `task` into its own aggregate `ledger.settlement`.
- `agent + catalogue → offering`; storefront becomes its own aggregate (`AgentProfileModel → Storefront` + `Media` VO).
- `routing` + `agentcallback` **fold into `task`** (SAD §3.1: Task owns matching, dispatch, routing). `RoutingAppService` / `RoutingEventListener` / `AgentCallbackAppService` → `application.biz.task`; `RoutingMatchDomainService` → `domain.biz.task.service`. The RabbitMQ / HMAC / webhook **infrastructure** adapters stay tech-grouped.
- `user + auth → identity`; `review → reputation`.
- Controllers keep route-grouping; `/api/*` routes unchanged.

**Embedded decisions (approved):** Storefront becomes its own aggregate with a `Media` VO; routing is *not* its own subdomain (it is an app-service over Task+Agent, never an aggregate).

## 4. Per-subdomain shape-deepening (in scope)

- **Identity** — `UserModel` record → proper root; introduce `Credential` VO (wraps the password hash) and an `OAuthIdentity` domain entity (provider+subject) owned by the root, mapped onto the existing `user_identities` table. `becomeBuilder`'s role mutation becomes a domain behavior on `User`. **No migration.**
- **Ledger** — promote **Settlement to a persisted aggregate**: `SettlementModel(root)` (taskId + breakdown + type `ACCEPT`/`REJECT`/`SPLIT`) + `SettlementRepository` + a new `settlements` table; `SettlementPolicy` stays the pure 85/15 source. Move accept/reject orchestration out of `TaskReviewAppService` into a `SettlementWriteAppService`. Settlement is shaped to support `SPLIT`, but the SPLIT producer lands later with Adjudication. **Add `@Version` optimistic lock on Wallet** (small migration) — closes the concurrent-freeze window.
- **Agent Offering** (largest) — give `AgentVersion` a **real lifecycle**: `AgentVersionStatus` enum (`DRAFT`/`ACTIVE`/`DEPRECATED`), drop the hardcoded `versionNumber=1`, allow many versions per agent, and a **publish-new-version → supersession** use case (demote prior `ACTIVE` in the same tx; repo loads the current `ACTIVE`, keeps `DEPRECATED` history). Replaces the in-place `updateCommercials` mutation. Migration adds `agent_versions.status` + a partial-unique index (one `ACTIVE` per agent). Plus **Storefront → own aggregate + `Media` VO** and **manual Agent `SUSPEND`/`DEACTIVATE` transitions** (enum values already exist).
- **Task** — *relocation, not reshaping*: routing/execution/callback move in; fix the stale "only SUBMITTED reachable" comments; make duplicate callbacks **first-result-wins** (graceful) instead of throwing.
- **Reputation** — *relocation only*: move the `Review` aggregate.
- **Cross-cutting** — move the misplaced `JdbcBuilderEarningsQueryDao` into the Ledger read model.

The deepening concentrates in **Identity, Ledger, Agent Offering**; Task and Reputation are relocate-only.

## 5. Schema & migration strategy + API/frontend impact

**Migrations — additive, three small:**
- **`V13` — `agent_versions.status`**: add column, backfill existing → `ACTIVE`, partial-unique index (one `ACTIVE` per agent).
- **`V14` — `settlements` table**: id, task_id, type CHECK(`ACCEPT`/`REJECT`/`SPLIT`), net, commission, created_at. Optional backfill of `RESOLVED` tasks via `SettlementPolicy`; the ledger stays the money truth.
- **`V15` — `wallets.version`**: integer default 0, for `@Version`.

Identity (`Credential` VO + `OAuthIdentity` entity) and Storefront (`Media` VO) need **no migration** — they are domain shapes over existing columns/tables.

**API & frontend impact — one consequential change:**
- Routes stay put → tasks, wallet, settlement summary, reviews, catalogue, auth: **frontend untouched**.
- **Builder agent-management console:** `PUT /api/agents/{id}/pricing` (in-place edit of v1) → `POST /api/agents/{id}/versions` (**publish a new version**, supersedes the old). Agent detail/catalogue responses gain additive fields (version status / current version) — tolerant for existing UI.
- **New optional endpoints:** `POST /api/agents/{id}/suspend|deactivate|reactivate` → small new controls in the builder console.

## 6. Test strategy & incremental sequencing

**Discipline:** the suite stays green at every commit. Each step is (1) **relocate** packages — mechanical, tests move with their code, run suite → green; then (2) **deepen** via TDD — RED→GREEN for the new behavior. Run the suite **with Docker up** at each milestone (don't trust auto-skip green); Playwright-drive the builder console after step 4. One refactor branch; one commit per sub-step; each step lands independently green.

**Sequence (dependency-ordered):**
1. **Identity** — relocate `user`+`auth` → `identity`; add `Credential` VO + `OAuthIdentity` entity. No migration. Establishes the subdomain-package pattern.
2. **Ledger / Wallet** — relocate `wallet` → `ledger.wallet`; add `wallets.version` + `@Version` (`V15`).
3. **Ledger / Settlement** — create `ledger.settlement` aggregate + `settlements` table (`V14`); move accept/reject orchestration → `SettlementWriteAppService`.
4. **Agent Offering** — relocate `agent`+`catalogue` → `offering`; AgentVersion lifecycle + supersession (`V13`); `Storefront`+`Media` VO; SUSPEND/DEACTIVATE. *(Builder-console frontend change + Playwright pass.)*
5. **Task** — relocate `task`+`routing`+`agentcallback` → `task` (deps AgentVersion + Settlement already moved); callback first-result-wins; fix stale comments.
6. **Reputation** — relocate `review` → `reputation`.
7. **Adjudication** — reserve the empty package (placeholder).

## 7. Deferred to future specs (do **not** forget)

Each is a separate brainstorm → spec → plan cycle, unlocked by this refactor's structure:
1. **Adjudication / Module 4** — validation gate enforcing Invariant #4 at runtime (`RESULT_RECEIVED → PENDING_REVIEW`/`SPEC_VIOLATION`), `Dispute`/`Ruling` + reason-gate + tiered resolution, the Python `arbitration/` FastAPI+LangGraph service (directory does not exist yet), `disputes`/`validation_reports` tables, `DISPUTED` task state.
2. **Reliability net §6.3** — `execution_deadline` column, `@Scheduled` sweeper (`SELECT … FOR UPDATE SKIP LOCKED → markTimedOut`), auto-refund + reputation event on timeout/spec-violation, transactional outbox + relay, matching-retry layer. (`markTimedOut()` already exists end-to-end with no production caller.)
3. **Reputation engine / Module 5** — `AgentRating` root + append-only `ReputationEvent` (+ `reputation_events` table & trigger), rolling-30-day weighted decay, `ReputationDroppedBelowThreshold → auto-suspend`, earned-review write path tied to `RESOLVED` tasks (`UNIQUE(task_id)`).
4. **Matcher** — multi-factor weighted score + epsilon-greedy + channels/shortlist + `AWAITING_SELECTION` (live matcher is `max(reputation)` single-winner).
5. **Ledger order aggregates** — `Payment`, `DepositOrder`, `WithdrawalOrder` (money-in/out workflows) + the `SPLIT` partial-settlement producer.
6. **Security** — per-endpoint role gating (`@EnableMethodSecurity` / `@PreAuthorize`), activate the dormant `ADMIN` role, the Admin surface.
7. **Agent Offering** — webhook reachability probe at activation; `is_featured` write path.

## 8. Reusable foundation (untouched by this refactor)

Verified solid and kept as-is: the COLA 7-module reactor (compiler-enforced layering); `hireai-utility` (ResultCode + exceptions); `Money` VO; `WebResult`/`BaseController`; the V1 append-only ledger triggers (`balance_after`, `correlation_id`); the submit+escrow-freeze atomic path (Inv #1); `OutputSpec` bind-at-submit-immutable (Inv #4); the `TaskSubmitted` after-commit handoff; the race-safe accept/reject pessimistic lock; the routing happy-path (hard-filter SQL, REQUIRES_NEW QUEUED-before-publish, RabbitMQ publisher + DLQ + bounded retry/backoff, HMAC dispatch token #6, HTTPS signed client, token-verified callback); the JWT/OAuth/security plumbing (3 chains, `CurrentUserProvider` seam #5, no-silent-link guard); the CQRS catalogue read model + GIN `capability_categories`; derived builder earnings.

## 9. Risks & mitigations

- **Big mechanical diff per relocation** → keep relocate and deepen as separate commits; rely on the compiler (layer modules) + green suite to catch wrong-way imports.
- **Settlement relocation couples Ledger↔Task** → do it as an app-layer move (step 3) while Task still in place; Task relocation (step 5) then inherits a lighter review service.
- **AgentVersion lifecycle is the riskiest change** (multi-version + supersession + migration + frontend) → isolated to step 4, guarded by a partial-unique index and TDD on the supersession invariant, with a Playwright pass.
- **Auto-skipping integration tests masking regressions** → run with Docker at each milestone.
- **Scope creep into the deferred list** → §7 is the contract; anything there is a separate spec.
