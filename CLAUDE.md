# HireAI

Keep this file short — it's an index, not documentation. Put depth in `docs/details/` and link to it with a "Read before X" trigger. Re-apply the test on every edit: *does an agent need this on every task?* If not, move it out and link it.

## Project

HireAI is a **task-driven AI Agent distribution and execution platform** — a neutral marketplace broker. Clients submit well-defined tasks in natural language and pay in virtual credits held in escrow; third-party **AI Agents** (self-hosted, registered by **Agent Builders**) execute them via webhook and earn credits on accepted, spec-conformant work. The platform provides the registry, routing, output-contract validation, tiered dispute resolution, reputation, and escrow settlement — it hosts no Agents itself.

Six functional modules: (1) Task Submission, (2) Agent Registration, (3) Routing & Execution, (4) Quality Validation & Dispute Resolution, (5) Reputation & Virtual Settlement, (6) Discovery & Builder Dashboard. This is a solo Final Year Project; Module 6 is the stretch deliverable.

## Repository status

All six modules and the programmatic API-key channel are substantially built. Source trees: **`backend/`** (Spring Boot Java 21, DDD as a COLA multi-module Maven reactor), **`arbitration/`** (Python FastAPI + LangGraph dispute arbitrator, OpenAI `gpt-4o`), **`frontend/`** (Next.js 16, App Router + TypeScript + Tailwind), **`mcp/`** (Python MCP-server facade over the programmatic API, stdio), and **`demo-agent/`** (stub Agent for demos).

**[`docs/details/build-status.md`](docs/details/build-status.md) is the source of truth for what's built and for known gaps. Read it before starting any feature.**

## Build / run / test

| Service | Location | Run | Build | Test |
|---|---|---|---|---|
| Backend | `backend/` | `mvn -f backend/hireai-main/pom.xml spring-boot:run` — after a one-off `mvn -f backend/pom.xml -pl hireai-main -am -DskipTests install` (needs Postgres at `DB_URL` **and** a RabbitMQ broker; JWT auth enforced by default) | `mvn -f backend/pom.xml -q -B package` | `mvn -f backend/pom.xml -B test` |
| Arbitration | `arbitration/` | `cd arbitration && uv run uvicorn app.main:app` (needs RabbitMQ + `OPENAI_API_KEY` + backend callback reachable) | `cd arbitration && uv sync` | `cd arbitration && OPENAI_API_KEY= ARBITRATION_CALLBACK_SECRET= uv run pytest` (blank keys, or `test_health_ok` hangs on a real `.env`; + `uv run ruff check .`) |
| Frontend | `frontend/` | `npm --prefix frontend run dev` (proxies `/api/*` → `:8080`) | `npm --prefix frontend run build` | `npm run lint && npx vitest run` (in `frontend/`) |
| MCP server | `mcp/` | `HIREAI_API_KEY=hk_live_… uv run hireai-mcp` (stdio facade; needs the backend reachable at `HIREAI_API_BASE_URL`, default `:8080`) | `cd mcp && uv sync` | `cd mcp && uv run pytest` (+ `uv run ruff check .`) |

Backend notes:
- `backend/pom.xml` is the reactor **parent**, so `package`/`test` there build every module, but `spring-boot:run` only works on `hireai-main`, using the command above (`-pl hireai-main -am` fails on the parent).
- `backend/.env` is **not** loaded when booting that way (it's a cwd-relative import, and it points at Supabase). Pass `DB_*` and `SUPABASE_*` as environment variables.
- `*IntegrationTest` classes use Testcontainers and **skip silently without Docker**, so a green run without Docker proves less than it looks.
- The default profile enforces auth; the `test` profile is permissive with a fixed dev user.
- Flyway owns the schema (`backend/hireai-main/src/main/resources/db/migration`); Hibernate only validates.

## Stack at a glance

Next.js + Spring Boot (DDD) + Python FastAPI/LangGraph arbitrator + PostgreSQL + RabbitMQ. Details: `docs/details/architecture.md`.

## Hard invariants (never compromise)

These are needed on every task and are too important to risk being unread. Enforced in code, schema triggers, and review.

1. **Escrow before execution.** Credits freeze on task submit and leave escrow only via an explicit, recorded settlement (release / refund / split). No dispatch without a successful freeze.
2. **Append-only money & audit.** `ledger_entries` and `reputation_events` are append-only (DB triggers raise on UPDATE/DELETE). Corrections are compensating entries. Settlement and reputation must be fully reconstructable.
3. **Deterministic money path.** The LLM may produce a dispute *ruling* but never moves credits. All settlement is computed deterministically in the domain layer from the ruling category.
4. **The task's frozen output spec is the binding contract.** The `output_spec` stored on the task at submit is the single contract used by both automated validation and arbitration: the Agent's own spec on direct bookings, the client's on routed tasks (see #46). Validation runs before any client sees a result.
5. **Server-side identity from JWT.** Derive the user ID from the JWT principal; path/body IDs require an explicit ownership check. Builders act only on Agents they own; clients only on their own tasks/wallet.
6. **Signed, HTTPS-only Agent I/O.** Webhook URLs must be HTTPS; dispatch carries a short-lived signed token; result callbacks are rejected (401) unless the token is valid and unexpired. Extended to **outbound** client webhooks: SSRF-guarded target + HMAC signature (see `docs/details/programmatic-channel.md`).

## Doc rules

These keep the docs from going stale. Apply them to every doc edit.

- **Change the doc with the code.** If a change makes a doc wrong, fix it in the same commit.
- **One home per fact.** Link to it; don't restate it in a second doc.
- **No fast-changing facts.** No test counts, latest-migration numbers, branch or PR status, or copied config defaults. Point to the file that owns them.
- **Current behaviour only.** Known defects go in the Known gaps section of `build-status.md`, and plans go in issues or `docs/superpowers/specs/`.
- **Records stay dated.** ADRs, post-mortems, specs and `docs/reports/` are history: don't use them to learn how the system works today.

## Skill usage

- **`scaffolding-ddd-spring-boot`** — before laying out backend packages or adding an aggregate. Conventions distilled in `docs/details/ddd-conventions.md`.
- **`springboot-service` / `springboot-patterns` / `jpa-patterns` / `java-coding-standards`** — when writing backend service, persistence, or domain code.
- **`springboot-security`** — when touching auth, JWT, RBAC, or signed Agent I/O.
- **`frontend-design` / `frontend-patterns`** — when building Next.js surfaces.
- **`postgres-patterns`** — when writing schema, migrations, or queries.
- **`api-design`** — when designing REST endpoints.

## Agent skills

### Issue tracker

GitHub Issues in `shaoxian04/HireAI`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, each label string equal to its name. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the root, alongside the existing `docs/details/`. See `docs/agents/domain.md`.

## Post-mortems

Read before making auth, security-config, or controller-test changes — these are real mistakes from this project, not hypotheticals.

- **`docs/post-mortem/2026-07-17-api-key-lockout-401-vs-403.md`** — `SecurityConfig` has no `accessDeniedHandler`, so the full app returns **401 (not 403) for every authenticated-but-forbidden request** — but `@WebMvcTest` slices render it as 403. A slice-trusting integration-test assertion (403) would have failed only in CI. Lesson: assert denied-status against the full app, not the slice; expect 401 for forbidden app-wide.

## Detail index

Read the relevant file on demand — don't preload everything.

- **`docs/details/build-status.md`** — what's built in each module, and the single list of known gaps.
  **Read before starting any feature — to see what already exists.**
- **`docs/details/matching.md`** — how agents are ranked, shortlisted and routed, the re-match and timeout sweepers, and the WorkOrder an Agent receives.
  **Read before touching matching, routing, dispatch, or the submit flow.**
- **`docs/details/architecture.md`** — services, polyglot topology, tech stack, communication patterns.
  **Read before any cross-service or infrastructure change.**
- **`docs/details/ddd-conventions.md`** — layering (`controller → application → domain ← infrastructure`), aggregate boundaries, naming suffixes, the five rules.
  **Read before writing any Java code in `backend/`.**
- **`docs/details/architecture-decisions.md`** — the *why* behind the backend's structural choices (COLA modules, rich aggregates, thin app layer, exceptions in `utility`, `DO` naming, OAuth no-silent-link), incl. where HireAI deliberately diverges from the COLA reference.
  **Read before a structural/convention change, or when questioning why something is the way it is.**
- **`docs/details/data-model.md`** — aggregates, the 3NF schema, the append-only ledger, status enums.
  **Read before changing the schema, an entity, or the settlement/reputation logic.**
- **`docs/details/programmatic-channel.md`** — the API-key machine channel end to end: key auth, idempotency, the two spend caps, open/direct submit, deterministic auto-settlement, signed push webhooks (transactional outbox + sweeper + SSRF guard), and the **MCP server facade + OpenAPI** (§6a — stdio tools over the same REST surface).
  **Read before touching API keys, the programmatic submit path, auto-settlement, webhooks, or the MCP facade.**
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
