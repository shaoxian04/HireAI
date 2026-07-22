# HireAI

Keep this file short — it's an index, not documentation. Put depth in `docs/details/` and link to it with a "Read before X" trigger. Re-apply the test on every edit: *does an agent need this on every task?* If not, move it out and link it.

## Project

HireAI is a **task-driven AI Agent distribution and execution platform** — a neutral marketplace broker. Clients submit well-defined tasks in natural language and pay in virtual credits held in escrow; third-party **AI Agents** (self-hosted, registered by **Agent Builders**) execute them via webhook and earn credits on accepted, spec-conformant work. The platform provides the registry, routing, output-contract validation, tiered dispute resolution, reputation, and escrow settlement — it hosts no Agents itself.

Six functional modules: (1) Task Submission, (2) Agent Registration, (3) Routing & Execution, (4) Quality Validation & Dispute Resolution, (5) Reputation & Virtual Settlement, (6) Discovery & Builder Dashboard. This is a solo Final Year Project; Module 6 is the stretch deliverable.

## Repository status

**Early implementation.** Canonical design lives in Notion (PRD + SAD, linked below). Three source trees: **`backend/`** (Spring Boot Java 21, DDD as a COLA multi-module Maven reactor), **`arbitration/`** (Python FastAPI + LangGraph dispute arbitrator, OpenAI `gpt-4o`), **`frontend/`** (Next.js 16, App Router + TypeScript + Tailwind). Modules 1–4 and 6 are substantially built (task submit + escrow, agent registration, routing/execution, validation gate, tiered dispute resolution with arbitrator + admin backstop, discovery/storefront, accept/reject settlement) plus the **programmatic API-key channel** (submit → deterministic auto-settle → signed push webhooks; **MCP server facade + OpenAPI**); **Module 5** (reputation) is the main pending work. ~725 backend + 131 vitest tests green.

**The full per-module / per-migration build narrative — the source of truth for what's built vs pending — lives in [`docs/details/build-status.md`](docs/details/build-status.md). Read it before starting any feature, to see what already exists.**

## Build / run / test

Backend, arbitration service, and frontend are built and tested.

| Service | Location | Run | Build | Test |
|---|---|---|---|---|
| Backend | `backend/` | `mvn -f backend/pom.xml -pl hireai-main -am spring-boot:run` (needs Postgres at `DB_URL` **and** a RabbitMQ broker; JWT auth enforced by default) | `mvn -f backend/pom.xml -q -B package` | `mvn -f backend/pom.xml -B test` |
| Arbitration | `arbitration/` | `cd arbitration && uv run uvicorn app.main:app` (needs RabbitMQ + `OPENAI_API_KEY` + backend callback reachable) | `cd arbitration && uv sync` | `cd arbitration && uv run pytest` (+ `uv run ruff check .`) |
| Frontend | `frontend/` | `npm --prefix frontend run dev` (proxies `/api/*` → `:8080`) | `npm --prefix frontend run build` | `npx vitest run` (in `frontend/`) |
| Local stack | repo root | `docker compose up` _(planned)_ | — | — |

Backend notes: JDK 21 + Maven. The `backend/pom.xml` is the reactor **parent** (`packaging=pom`); `package`/`test` there build all seven modules in layer order. The only **bootable** module is `hireai-main` — it owns `HireAiApplication`, the Flyway migrations + `application.yml`/`application-oauth.yml`, the `spring-boot-maven-plugin`, and the **entire test suite** (run against the fully-assembled context); hence `spring-boot:run` must target it (`-pl hireai-main -am`). Integration tests (`*IntegrationTest`) use Testcontainers (Postgres **+ RabbitMQ**) and **skip automatically when no Docker daemon is reachable** — they do not fail the build. The default/`prod` profile **enforces JWT auth**; the `test` profile (applied to existing context-loading tests via `@ActiveProfiles("test")`) is permissive with a fixed dev user. Flyway owns the schema (`V1`–`V26`); Hibernate runs `ddl-auto: validate`.

## Stack at a glance

Next.js + Spring Boot (DDD) + Python FastAPI/LangGraph arbitrator (OpenAI `gpt-4o`) + PostgreSQL + RabbitMQ, deployed on Railway. Full diagram and rationale: see `docs/details/architecture.md`.

## Hard invariants (never compromise)

These are needed on every task and are too important to risk being unread. Enforced in code, schema triggers, and review.

1. **Escrow before execution.** Credits freeze on task submit and leave escrow only via an explicit, recorded settlement (release / refund / split). No dispatch without a successful freeze.
2. **Append-only money & audit.** `ledger_entries` and `reputation_events` are append-only (DB triggers raise on UPDATE/DELETE). Corrections are compensating entries. Settlement and reputation must be fully reconstructable.
3. **Deterministic money path.** The LLM may produce a dispute *ruling* but never moves credits. All settlement is computed deterministically in the domain layer from the ruling category.
4. **Declared output spec is the binding contract.** An Agent's `output_spec` is the single contract used by both automated validation and arbitration. Validation runs before any client sees a result.
5. **Server-side identity from JWT.** Derive the user ID from the JWT principal; path/body IDs require an explicit ownership check. Builders act only on Agents they own; clients only on their own tasks/wallet.
6. **Signed, HTTPS-only Agent I/O.** Webhook URLs must be HTTPS; dispatch carries a short-lived signed token; result callbacks are rejected (401) unless the token is valid and unexpired. Extended to **outbound** client webhooks: SSRF-guarded target + HMAC signature (see `docs/details/programmatic-channel.md`).

## Skill usage

- **`scaffolding-ddd-spring-boot`** — before laying out backend packages or adding an aggregate. Conventions distilled in `docs/details/ddd-conventions.md`.
- **`springboot-service` / `springboot-patterns` / `jpa-patterns` / `java-coding-standards`** — when writing backend service, persistence, or domain code.
- **`springboot-security`** — when touching auth, JWT, RBAC, or signed Agent I/O.
- **`frontend-design` / `frontend-patterns`** — when building Next.js surfaces.
- **`postgres-patterns`** — when writing schema, migrations, or queries.
- **`api-design`** — when designing REST endpoints.

## Post-mortems

Read before making auth, security-config, or controller-test changes — these are real mistakes from this project, not hypotheticals.

- **`docs/post-mortem/2026-07-17-api-key-lockout-401-vs-403.md`** — `SecurityConfig` has no `accessDeniedHandler`, so the full app returns **401 (not 403) for every authenticated-but-forbidden request** — but `@WebMvcTest` slices render it as 403. A slice-trusting integration-test assertion (403) would have failed only in CI. Lesson: assert denied-status against the full app, not the slice; expect 401 for forbidden app-wide.

## Detail index

Read the relevant file on demand — don't preload everything.

- **`docs/details/build-status.md`** — the full per-module / per-migration record of what's built vs pending across backend / arbitration / frontend.
  **Read before starting any feature — to see what already exists.**
- **`docs/details/architecture.md`** — services, polyglot topology, tech stack, communication patterns.
  **Read before any cross-service or infrastructure change.**
- **`docs/details/ddd-conventions.md`** — layering (`controller → application → domain ← infrastructure`), aggregate boundaries, naming suffixes, the five rules.
  **Read before writing any Java code in `backend/`.**
- **`docs/details/architecture-decisions.md`** — the *why* behind the backend's structural choices (COLA modules, rich aggregates, thin app layer, exceptions in `utility`, `DO` naming, OAuth no-silent-link), incl. where HireAI deliberately diverges from the COLA reference.
  **Read before a structural/convention change, or when questioning why something is the way it is.**
- **`docs/details/data-model.md`** — aggregates, the 3NF schema, the append-only ledger, status enums.
  **Read before changing the schema, an entity, or the settlement/reputation logic.**
- **`docs/details/programmatic-channel.md`** — the API-key machine channel end to end: key auth, idempotency, the two spend caps, open/direct submit, deterministic auto-settlement, and signed push webhooks (transactional outbox + sweeper + SSRF guard).
  **Read before touching API keys, the programmatic submit path, auto-settlement, or webhooks.**
- **`docs/details/demo-runbook.md`** — stand up the full local demo stack (Postgres + RabbitMQ + stub agent + HTTPS tunnel + backend + frontend) and the seed logins.
  **Read before running a live end-to-end demo.**
- **`docs/details/frontend.md`** — Next.js app structure + conventions (the `/api/*` proxy, the `api()` client, the auth context + localStorage scheme, the UI kit, the route map).
  **Read before changing anything in `frontend/`.**
- **`docs/details/identity-and-authz.md`** — JWT auth, the two profile-scoped security chains, the `CurrentUserProvider` seam + owner checks, and the callback's dispatch-token auth (not JWT).
  **Read before touching auth, security config, or anything that derives the current user.**

## Source-of-truth & conflict resolution

- **PRD** (product scope, business rules): https://app.notion.com/p/35b2193af50f819d91f2dba13c739a80
- **SAD** (architecture, domain model, schema — authoritative technical reference): https://app.notion.com/p/38d2193af50f81a197f6da6b1e41a6f1 — the lean English rewrite (2026-06-28); blueprint in `docs/superpowers/specs/2026-06-28-sad-rewrite-design.md`. Note: §6.3 reliability (sweeper/outbox/retry) and any *target* design it describes are the planned backend refactor, not necessarily what's built today — `docs/details/build-status.md` is the source of truth for built-vs-pending.

The **SAD wins on technical matters** (architecture, schema, domain design); the **PRD wins on product scope** (what's in/out of the MVP). The local `docs/details/*` files distill these for fast access — when they disagree with Notion, Notion is authoritative; update the local file.
