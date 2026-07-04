# HireAI

Keep this file short — it's an index, not documentation. Put depth in `docs/details/` and link to it with a "Read before X" trigger. Re-apply the test on every edit: *does an agent need this on every task?* If not, move it out and link it.

## Project

HireAI is a **task-driven AI Agent distribution and execution platform** — a neutral marketplace broker. Clients submit well-defined tasks in natural language and pay in virtual credits held in escrow; third-party **AI Agents** (self-hosted, registered by **Agent Builders**) execute them via webhook and earn credits on accepted, spec-conformant work. The platform provides the registry, routing, output-contract validation, tiered dispute resolution, reputation, and escrow settlement — it hosts no Agents itself.

Six functional modules: (1) Task Submission, (2) Agent Registration, (3) Routing & Execution, (4) Quality Validation & Dispute Resolution, (5) Reputation & Virtual Settlement, (6) Discovery & Builder Dashboard. This is a solo Final Year Project; Module 6 is the stretch deliverable.

## Repository status

**Early implementation.** Canonical design lives in Notion (PRD + SAD, linked below). Source trees:

- `backend/` — Spring Boot (Java 21), DDD bounded contexts, organized as a **COLA multi-module Maven reactor** (`hireai-utility → hireai-domain → hireai-application → {hireai-repository, hireai-infrastructure, hireai-controller} → hireai-main`; layer dependencies are compiler-enforced, so `domain`/`utility` carry no Spring on their classpath). **Built (on `feat/marketplace-spine`):** base classes (`WebResult`/`BaseController` in `hireai-controller`, `ResultCode` + all exceptions in `hireai-utility`), config; the **Wallet** aggregate (top-up + escrow freeze, append-only ledger, `V1`); the **Task** aggregate (submit + atomic escrow freeze + binding `output_spec`, `V2`); **Module 2 — Agent Registration** (`AgentModel`/`AgentVersionModel`, register/activate, `V3`); **Module 3 — Routing & Execution** (event-triggered matching → RabbitMQ dispatch → signed HTTPS webhook → token-authenticated callback → `task_results`, `V4`, read via `GET /api/tasks/{id}/result`); and **JWT auth + full self-registration + Google OAuth + dual-capability RBAC** (`POST /api/auth/login|register|become-builder`, `JwtCurrentUserProvider`, profile-scoped security chains, seeded demo users `V5`; roles in `user_roles` join table `V10`, OAuth identity links in `user_identities` `V11`, `users.display_name` `V12`; Google OAuth flag-gated via `oauth` Spring profile, refusing to silently link to a pre-existing local account (account-takeover guard, `OAuthAccountLinkingDomainService`); `POST /api/auth/become-builder` idempotently adds `BUILDER` role and re-issues token) enforcing invariant #5; and **Module 6 — Discovery & storefront** (public catalogue + agent profiles `V6`, seeded reviews `V7`, direct booking `POST /api/tasks/direct`, builder storefront/media/pricing/stats endpoints, Supabase Storage media); and **client review + settlement** (accept → 85/15 payout, reject → full refund, `V9`, `POST /api/tasks/{id}/accept|reject`, `SettlementPolicy`/`SettlementDomainService`, race-safe pessimistic lock); and a **builder earnings read** (`GET /api/builder/earnings` — totals/per-agent/payout history derived from tasks via `SettlementPolicy`, never ledger sums). ~398 backend tests green (Testcontainers integration tests auto-skip without Docker). All service classes use interface + `impl/` (app services Spring-managed; domain services framework-free, wired in `DomainServiceConfig`). **Phase 3b built (arbitrator + ruling transparency):** Python arbitrator (see `arbitration/`), append-only `dispute_rulings` history (`V21`/`V22`), `GET /api/disputes/by-task/{taskId}` (client + owning builder; carries `reasonCategory`), `DisputeOutcomePanel` (read-only) on builder earnings; the client task view renders the appeal timeline (see frontend). **Module 4 admin backstop built:** tier-2 Administrator override (`adminRule` writes an `ADMINISTRATOR` tier-2 ruling → deterministic settlement; `/api/admin/**` gated by `ROLE_ADMIN`; `admin@hireai.local` seeded `V23`); the arbitration timeout sweeper (`@Scheduled ArbitrationSweeper` flips stale `ARBITRATING` disputes to `ESCALATED`) and the DLQ path (now escalates instead of auto-refunding) both route stranded disputes to the admin. **Module 4 client appeal + delayed settlement built:** the arbitrator ruling is now a *proposal* — settlement is decoupled from ruling (`settleAndResolve` split into `recordProposedRuling` + `settleFromEffective`), so escrow **holds at dispute status `RULED`** until the client `POST /api/disputes/{id}/accept-ruling` or `/appeal` (→ `ESCALATED` → admin), or an `@Scheduled RulingAcceptSweeper` auto-accepts a stale proposal (per-id txns, `ruling-accept-after`); every finalizer (`acceptRuling`/`adminRule`/`autoAcceptOne`) settles **exactly once** from the effective (highest-tier) ruling, under the task pessimistic lock with a re-read; client-scoped `GET /api/disputes/mine`; the arbitrator callback no longer moves money; **no schema migration** (reuses existing dispute states). **Module 4 validation gate built:** automated output-spec validation runs on the agent callback (`AgentCallbackAppServiceImpl.validateAndGate` → `ValidationDomainService`: format + JSON-Schema against the frozen `output_spec`, writing `validation_reports` `V16`) before any client sees a result — `RESULT_RECEIVED` → `PENDING_REVIEW` on PASS, `SPEC_VIOLATION` (auto-refund) on FAIL. **Still deferred (Module 4):** builder-side full-refund dispute discovery (no payout row → not on earnings); SSE push (UI polls); routing `adminRule` through the same lock-then-re-read (safe today via `settlements.task_id` UNIQUE). **Pending:** Module 5 (reputation events + earned reviews + auto-refund on failure — settlement core done).
- `arbitration/` — Python FastAPI + LangGraph dispute-arbitration microservice (**OpenAI `gpt-4o`** via `langchain-openai`). **Built (Phase 3b):** `aio-pika` consumer of `task.dispute.requested`; 6-node deliberation graph (gather_evidence → deliberate → classify → critique); SSRF-guarded evidence fetch + JSON-Schema validation; ack-after-2xx discipline (persistent failure → `nack(requeue=False)` → DLQ → Java escalates the dispute to the admin backstop, no auto-refund); shared-secret callback `POST /api/arbitration-callbacks/{id}/ruling`; returns `{category, rationale}` only (Inv #3 — Java domain owns all money movement). `uv` for deps.
- `frontend/` — Next.js 16 (App Router, TypeScript, Tailwind). **Built:** the **Client + Builder** demo happy-path (login, **email/password register**, **Google OAuth callback** `/auth/callback`, **become-builder upgrade** `/client/become-builder`, agent register/activate, wallet/top-up, task submit, task-detail polling → result → accept/reject review flow with settled summary) and the **Marketplace + storefront + direct booking + builder manage console + earnings view** over a `/api/*` proxy with a JWT-bearing `api()` client; dual-role `Nav` surface switcher; `roles`/`hasRole`/`activeSurface` auth context; and the **Admin surface** (`/admin` overview + read-only task/user-wallet/agent browsers, `/admin/disputes` queue → `/admin/disputes/[id]` detail with a tier-2 human-backstop ruling); and the **client dispute surfaces** — a `DisputeProgressPanel` reject→arbitrator→admin **timeline** with accept/appeal actions on the task view (replaces the execution pipeline once a task is in dispute; persists after `RESOLVED`), a `/client/disputes` list, and a nav **Disputes** badge counting disputes awaiting the client's decision. 83 vitest tests.

## Build / run / test

Backend, arbitration service, and frontend are built and tested.

| Service | Location | Run | Build | Test |
|---|---|---|---|---|
| Backend | `backend/` | `mvn -f backend/pom.xml -pl hireai-main -am spring-boot:run` (needs Postgres at `DB_URL` **and** a RabbitMQ broker; JWT auth enforced by default) | `mvn -f backend/pom.xml -q -B package` | `mvn -f backend/pom.xml -B test` |
| Arbitration | `arbitration/` | `cd arbitration && uv run uvicorn app.main:app` (needs RabbitMQ + `OPENAI_API_KEY` + backend callback reachable) | `cd arbitration && uv sync` | `cd arbitration && uv run pytest` (+ `uv run ruff check .`) |
| Frontend | `frontend/` | `npm --prefix frontend run dev` (proxies `/api/*` → `:8080`) | `npm --prefix frontend run build` | `npx vitest run` (in `frontend/`) |
| Local stack | repo root | `docker compose up` _(planned)_ | — | — |

Backend notes: JDK 21 + Maven. The `backend/pom.xml` is the reactor **parent** (`packaging=pom`); `package`/`test` there build all seven modules in layer order. The only **bootable** module is `hireai-main` — it owns `HireAiApplication`, the Flyway migrations + `application.yml`/`application-oauth.yml`, the `spring-boot-maven-plugin`, and the **entire test suite** (run against the fully-assembled context); hence `spring-boot:run` must target it (`-pl hireai-main -am`). Integration tests (`*IntegrationTest`) use Testcontainers (Postgres **+ RabbitMQ**) and **skip automatically when no Docker daemon is reachable** — they do not fail the build. The default/`prod` profile **enforces JWT auth**; the `test` profile (applied to existing context-loading tests via `@ActiveProfiles("test")`) is permissive with a fixed dev user. Flyway owns the schema (`V1`–`V23`); Hibernate runs `ddl-auto: validate`.

## Stack at a glance

Next.js + Spring Boot (DDD) + Python FastAPI/LangGraph arbitrator (OpenAI `gpt-4o`) + PostgreSQL + RabbitMQ, deployed on Railway. Full diagram and rationale: see `docs/details/architecture.md`.

## Hard invariants (never compromise)

These are needed on every task and are too important to risk being unread. Enforced in code, schema triggers, and review.

1. **Escrow before execution.** Credits freeze on task submit and leave escrow only via an explicit, recorded settlement (release / refund / split). No dispatch without a successful freeze.
2. **Append-only money & audit.** `ledger_entries` and `reputation_events` are append-only (DB triggers raise on UPDATE/DELETE). Corrections are compensating entries. Settlement and reputation must be fully reconstructable.
3. **Deterministic money path.** The LLM may produce a dispute *ruling* but never moves credits. All settlement is computed deterministically in the domain layer from the ruling category.
4. **Declared output spec is the binding contract.** An Agent's `output_spec` is the single contract used by both automated validation and arbitration. Validation runs before any client sees a result.
5. **Server-side identity from JWT.** Derive the user ID from the JWT principal; path/body IDs require an explicit ownership check. Builders act only on Agents they own; clients only on their own tasks/wallet.
6. **Signed, HTTPS-only Agent I/O.** Webhook URLs must be HTTPS; dispatch carries a short-lived signed token; result callbacks are rejected (401) unless the token is valid and unexpired.

## Skill usage

- **`scaffolding-ddd-spring-boot`** — before laying out backend packages or adding an aggregate. Conventions distilled in `docs/details/ddd-conventions.md`.
- **`springboot-service` / `springboot-patterns` / `jpa-patterns` / `java-coding-standards`** — when writing backend service, persistence, or domain code.
- **`springboot-security`** — when touching auth, JWT, RBAC, or signed Agent I/O.
- **`frontend-design` / `frontend-patterns`** — when building Next.js surfaces.
- **`postgres-patterns`** — when writing schema, migrations, or queries.
- **`api-design`** — when designing REST endpoints.

## Detail index

Read the relevant file on demand — don't preload everything.

- **`docs/details/architecture.md`** — services, polyglot topology, tech stack, communication patterns.
  **Read before any cross-service or infrastructure change.**
- **`docs/details/ddd-conventions.md`** — layering (`controller → application → domain ← infrastructure`), aggregate boundaries, naming suffixes, the five rules.
  **Read before writing any Java code in `backend/`.**
- **`docs/details/architecture-decisions.md`** — the *why* behind the backend's structural choices (COLA modules, rich aggregates, thin app layer, exceptions in `utility`, `DO` naming, OAuth no-silent-link), incl. where HireAI deliberately diverges from the COLA reference.
  **Read before a structural/convention change, or when questioning why something is the way it is.**
- **`docs/details/data-model.md`** — aggregates, the 3NF schema, the append-only ledger, status enums.
  **Read before changing the schema, an entity, or the settlement/reputation logic.**
- **`docs/details/demo-runbook.md`** — stand up the full local demo stack (Postgres + RabbitMQ + stub agent + HTTPS tunnel + backend + frontend) and the seed logins.
  **Read before running a live end-to-end demo.**
- **`docs/details/frontend.md`** — Next.js app structure + conventions (the `/api/*` proxy, the `api()` client, the auth context + localStorage scheme, the UI kit, the route map).
  **Read before changing anything in `frontend/`.**
- **`docs/details/identity-and-authz.md`** — JWT auth, the two profile-scoped security chains, the `CurrentUserProvider` seam + owner checks, and the callback's dispatch-token auth (not JWT).
  **Read before touching auth, security config, or anything that derives the current user.**

## Source-of-truth & conflict resolution

- **PRD** (product scope, business rules): https://app.notion.com/p/35b2193af50f819d91f2dba13c739a80
- **SAD** (architecture, domain model, schema — authoritative technical reference): https://app.notion.com/p/38d2193af50f81a197f6da6b1e41a6f1 — the lean English rewrite (2026-06-28); blueprint in `docs/superpowers/specs/2026-06-28-sad-rewrite-design.md`. Note: §6.3 reliability (sweeper/outbox/retry) and any *target* design it describes are the planned backend refactor, not necessarily what's built today — CLAUDE.md's build status is the source of truth for built-vs-pending.

The **SAD wins on technical matters** (architecture, schema, domain design); the **PRD wins on product scope** (what's in/out of the MVP). The local `docs/details/*` files distill these for fast access — when they disagree with Notion, Notion is authoritative; update the local file.
