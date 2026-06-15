# HireAI

Keep this file short — it's an index, not documentation. Put depth in `docs/details/` and link to it with a "Read before X" trigger. Re-apply the test on every edit: *does an agent need this on every task?* If not, move it out and link it.

## Project

HireAI is a **task-driven AI Agent distribution and execution platform** — a neutral marketplace broker. Clients submit well-defined tasks in natural language and pay in virtual credits held in escrow; third-party **AI Agents** (self-hosted, registered by **Agent Builders**) execute them via webhook and earn credits on accepted, spec-conformant work. The platform provides the registry, routing, output-contract validation, tiered dispute resolution, reputation, and escrow settlement — it hosts no Agents itself.

Six functional modules: (1) Task Submission, (2) Agent Registration, (3) Routing & Execution, (4) Quality Validation & Dispute Resolution, (5) Reputation & Virtual Settlement, (6) Discovery & Builder Dashboard. This is a solo Final Year Project on a ~18-week build window; Module 6 is the stretch deliverable.

## Repository status

**Early implementation.** Canonical design lives in Notion (PRD + SAD, linked below). Source trees:

- `backend/` — Spring Boot (Java 21), DDD bounded contexts. **Built (on `feat/marketplace-spine`):** base classes (`WebResult`/`ResultCode`/`BaseController`), config; the **Wallet** aggregate (top-up + escrow freeze, append-only ledger, `V1`); the **Task** aggregate (submit + atomic escrow freeze + binding `output_spec`, `V2`); **Module 2 — Agent Registration** (`AgentModel`/`AgentVersionModel`, register/activate, `V3`); **Module 3 — Routing & Execution** (event-triggered matching → RabbitMQ dispatch → signed HTTPS webhook → token-authenticated callback → `task_results`, `V4`, read via `GET /api/tasks/{id}/result`); and **JWT auth + full self-registration + Google OAuth + dual-capability RBAC** (`POST /api/auth/login|register|become-builder`, `JwtCurrentUserProvider`, profile-scoped security chains, seeded demo users `V5`; roles in `user_roles` join table `V10`, OAuth identity links in `user_identities` `V11`, `users.display_name` `V12`; Google OAuth flag-gated via `oauth` Spring profile; `POST /api/auth/become-builder` idempotently adds `BUILDER` role and re-issues token) enforcing invariant #5; and **Module 6 — Discovery & storefront** (public catalogue + agent profiles `V6`, seeded reviews `V7`, direct booking `POST /api/tasks/direct`, builder storefront/media/pricing/stats endpoints, Supabase Storage media); and **client review + settlement** (accept → 85/15 payout, reject → full refund, `V9`, `POST /api/tasks/{id}/accept|reject`, `SettlementPolicy`/`SettlementDomainService`, race-safe pessimistic lock); and a **builder earnings read** (`GET /api/builder/earnings` — totals/per-agent/payout history derived from tasks via `SettlementPolicy`, never ledger sums). 311 backend tests green (59 Testcontainers auto-skip without Docker). All service classes use interface + `impl/` (app services Spring-managed; domain services framework-free, wired in `DomainServiceConfig`). **Pending:** Module 4 (automated output-spec validation + disputes); Module 5 (reputation events + earned reviews + auto-refund on failure — settlement core done).
- `arbitration/` — Python FastAPI + LangGraph dispute-arbitration microservice (Claude API). _Not started._
- `frontend/` — Next.js 16 (App Router, TypeScript, Tailwind). **Built:** the **Client + Builder** demo happy-path (login, **email/password register**, **Google OAuth callback** `/auth/callback`, **become-builder upgrade** `/client/become-builder`, agent register/activate, wallet/top-up, task submit, task-detail polling → result → accept/reject review flow with settled summary) and the **Marketplace + storefront + direct booking + builder manage console + earnings view** over a `/api/*` proxy with a JWT-bearing `api()` client; dual-role `Nav` surface switcher; `roles`/`hasRole`/`activeSurface` auth context; 59 vitest tests. **Pending:** Admin surface.

## Build / run / test

Backend + frontend are built and tested; the arbitration service is not started.

| Service | Location | Run | Build | Test |
|---|---|---|---|---|
| Backend | `backend/` | `mvn -f backend/pom.xml spring-boot:run` (needs Postgres at `DB_URL` **and** a RabbitMQ broker; JWT auth enforced by default) | `mvn -f backend/pom.xml -q -B package` | `mvn -f backend/pom.xml -B test` |
| Arbitration | `arbitration/` | _TBD_ | _TBD_ | _TBD_ |
| Frontend | `frontend/` | `npm --prefix frontend run dev` (proxies `/api/*` → `:8080`) | `npm --prefix frontend run build` | `npx vitest run` (in `frontend/`) |
| Local stack | repo root | `docker compose up` _(planned)_ | — | — |

Backend notes: JDK 21 + Maven. Integration tests (`*IntegrationTest`) use Testcontainers (Postgres **+ RabbitMQ**) and **skip automatically when no Docker daemon is reachable** — they do not fail the build. The default/`prod` profile **enforces JWT auth**; the `test` profile (applied to existing context-loading tests via `@ActiveProfiles("test")`) is permissive with a fixed dev user. Flyway owns the schema (`V1`–`V12`); Hibernate runs `ddl-auto: validate`.

## Stack at a glance

Next.js + Spring Boot (DDD) + Python FastAPI/LangGraph arbitrator + PostgreSQL + RabbitMQ + Anthropic Claude, deployed on Railway. Full diagram and rationale: see `docs/details/architecture.md`.

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
- **SAD** (architecture, domain model, schema — authoritative technical reference): https://app.notion.com/p/3752193af50f8111914bfdfb53b42135

The **SAD wins on technical matters** (architecture, schema, domain design); the **PRD wins on product scope** (what's in/out of the MVP). The local `docs/details/*` files distill these for fast access — when they disagree with Notion, Notion is authoritative; update the local file.
