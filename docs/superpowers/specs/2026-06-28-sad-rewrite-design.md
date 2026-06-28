# SAD Rewrite — Design Spec

**Date:** 2026-06-28
**Goal:** Replace the bloated, hard-to-read Notion SAD (2026-06-15) with a lean, low-redundancy **System Analysis & Design** document in English, faithful to the refined `架构设计（粗略版？）` architecture doc.
**Primary reader:** the author (engineer), as a technical reference — *not* an examiner. No academic ceremony.
**Output location:** a **new Notion page** (English SAD), created under the `HireAi` parent page. The existing Chinese arch doc and the old Notion SAD are left untouched.

> This spec is the blueprint for the new Notion SAD page. It is not the SAD itself.

## Design principles

1. **One concept, one place.** The old SAD's core defect: domain model appeared 3× (relationship map, bounded contexts, class model); every module appeared 2× (functional reqs *and* per-module design). Each concept is stated exactly once here and referenced elsewhere.
2. **Lean over exhaustive.** Cut academic chapters (Evaluation, Project Management) and the "forward-looking 18-week" voice. Compress assumptions/deps/deployment into a short appendix.
3. **Built-vs-pending honesty.** Reflect real status with lightweight markers (✅ built / 🚧 pending) instead of describing an aspirational system.
4. **Faithful to the arch doc.** Content and diagrams conform to `架构设计（粗略版？）`. Where the arch doc carried images, those are re-authored as mermaid.
5. **English; mermaid for every diagram** (renders natively in Notion, as in the arch doc).

## Structure (Plan A — concept-layered)

```
1. Overview
     1.1 What HireAI is (broker model · escrow · binding output contract)
     1.2 Actors (Client · Agent Builder · Third-Party Agent · Arbitrator Agent · Administrator)
     (build status moves to per-module markers in §6 — no standalone module table)
2. Architecture
     2.1 Service topology                       [D1 mermaid]
     2.2 Backend layering (COLA modules + deps) [D2 mermaid]
     2.3 Communication patterns (REST/JWT · webhook+signed callback · RabbitMQ · correlation IDs)
     2.4 Technology stack (table)
3. Domain Model                                  ← replaces old 2.3.3 + 2.4.2 + 2.4.3
     3.1 Subdomains & context map               [D3 mermaid]
     3.2 Ubiquitous language (glossary table)
     3.3 Aggregates / entities / value objects  [D4 classDiagram]
4. Behavior & Lifecycles                         ← replaces old 2.3.4 + 2.1.4
     4.1 State machines (7)                      [D5–D11 mermaid]
     4.2 Sequence diagrams (main · dispute · failure-compensation) [D12–D14 mermaid]
5. Data Model
     5.1 Schema / ERD                           [D15 erDiagram]
     5.2 Append-only ledger & audit (invariant #2)
     5.3 Status enums & settlement rules
6. Modules (each = responsibility + clear explanation incl. the "why" + happy-path UML + failure/fallback 兜底 UML + status; no separate numbered flow — the sequence diagram carries the steps) ← deep-dive chapter
     6.1 Task Submission & Specification
     6.2 Agent Registration & Versioning
     6.3 Routing & Execution
     6.4 Quality Validation & Dispute Resolution
     6.5 Reputation & Settlement
     6.6 Discovery & Builder Dashboard
7. Cross-cutting Concerns
     7.1 The six hard invariants (stated once; referenced elsewhere)
     7.2 Identity, auth & security (JWT · RBAC · OAuth no-silent-link · signed webhooks)
     7.3 Non-functional requirements (brief)
8. Appendix (compressed)
     Out-of-scope / future · Assumptions & external dependencies · Deployment & DevOps
```

## Content source mapping

| SAD section | Source of truth |
|---|---|
| 1. Overview | arch doc §1 (use cases); CLAUDE.md build status |
| 2.1 Topology / 2.3 Comms / 2.4 Stack | `docs/details/architecture.md`; arch doc §6 image |
| 2.2 Layering | `docs/details/ddd-conventions.md`; arch doc §6 table |
| 3.1 Subdomains & context map | arch doc §2.2 table |
| 3.2 Ubiquitous language | arch doc §2.3 glossary (translate to EN) |
| 3.3 Domain class model | arch doc §2.4 classDiagram |
| 4.1 State machines | arch doc §3.1–3.7 (translate to EN) |
| 4.2 Sequence diagrams | arch doc §4.1–4.3 (translate to EN) |
| 5. Data model | `docs/details/data-model.md` |
| 6. Modules | CLAUDE.md + `data-model.md`; arch doc per-domain content; `docs/task-matching-design.md` (§6.3 matching: multi-factor ranking, per-channel shortlist/auto-route) |
| 7.1 Invariants | CLAUDE.md "Hard invariants" |
| 7.2 Auth/security | `docs/details/identity-and-authz.md` |

## Diagram inventory — all freshly authored, English mermaid

| ID | Diagram | Type | Section | Notes |
|---|---|---|---|---|
| D1 | Service topology | flowchart | 2.1 | Next.js ↔ Spring Boot ↔ {Postgres, RabbitMQ, object storage}; Python arbitrator → Claude; backend ⇄ agent webhooks |
| D2 | Backend COLA layering | flowchart | 2.2 | controller/infra/repository → application → domain → utility; deps point inward |
| D3 | Subdomain context map | flowchart | 3.1 | 6 subdomains; core/supporting/generic; relationships (ACL, customer-supplier) |
| D4 | Domain class model | classDiagram | 3.3 | re-author of arch doc §2.4 (roots/entities/VOs, multiplicities) |
| D5–D11 | State machines ×7 | stateDiagram-v2 | 4.1 | Task, Dispute, Payment, Agent, AgentVersion, DepositOrder, WithdrawalOrder |
| D12–D14 | Sequence diagrams ×3 | sequenceDiagram | 4.2 | main flow, dispute, failure-compensation |
| D15 | Schema | erDiagram | 5.1 | core tables from `data-model.md` |

> **Note (post-deepening).** §6 additionally carries, *per module*, a happy-path sequence diagram **and** a failure/fallback (兜底) diagram beyond D1–D15; §6.3 also carries a `flowchart` contrasting the two safety nets (DLQ consumer vs. timeout Sweeper). These are authored inline in the module sections, not enumerated here. Total mermaid blocks ≈ 27.

## §6.3 reliability design (sweeper + retry — recorded here for the backend refactor)

Captured in the repo because it is **design, not yet built**, and will drive the planned backend refactor (the canonical copy lives in the SAD §6.3 + the Notion "A:Task vs B:Matchmaking" note):

- **Two safety nets.** A **DLQ consumer** catches *a message that failed* (dispatch threw / webhook unreachable); a **timeout Sweeper** catches *nothing happened* (Agent went silent, deadline passed) — the queue layer records a silent Agent as a delivered success, so only deadline-sweeping can catch it.
- **Three retry layers.** (1) *Dispatch retry* — RabbitMQ exponential-backoff redelivery → DLQ → `FAILED` + auto-refund. (2) *Matching retry* — no `ACTIVE` candidate ⇒ `AWAITING_CAPACITY`, re-matched (bounded) as capacity frees. (3) *Execution timeout* — `@Scheduled` Sweeper claims overdue rows via `SELECT … FOR UPDATE SKIP LOCKED`, drives `EXECUTING → TIMED_OUT`, auto-refund + reputation event.
- **Design A (chosen):** timeout ⇒ direct refund. **Design B (future):** a `DispatchOrder` aggregate enabling re-dispatch / 改派.
- **Idempotency:** the task state machine is the natural idempotency key — a duplicate callback or re-delivered job is a no-op once past the guard state.
- **Crash safety:** the submit→dispatch hand-off uses the **outbox pattern** (an after-commit publish can be lost on crash); the Sweeper is the backstop. Polling is preferred over delayed messages.
- **New persistence the refactor introduces:** `execution_deadline` (+ optional `retry_count`) on `tasks`, a `TIMED_OUT` task status, and an `outbox` table. **`docs/details/data-model.md` and the Flyway schema must be updated when this lands — not before** (these are unbuilt).

## What is cut from the old SAD (and why)

- **Ch. 4 Evaluation, Ch. 5 Project Management** → dropped (zero value to an engineer reader; PM ceremony irrelevant).
- **Duplicate domain views** (2.3.3 relationship map + 2.4.2 bounded contexts + 2.4.3 class model) → collapsed into §3.
- **Duplicate module coverage** (2.4.4–2.4.11 designs vs 3.1.x reqs) → merged into one §6 per-module section.
- **Repeated invariant statements** → stated once in §7.1.
- **"Forward-looking 18-week" framing** → replaced by ✅/🚧 status markers.

## Terminology (locked to arch doc)

- Subdomain: **Agent Offering** (Agent供应域) — not "Agent Registry" / "Third Party Agent".
- **Arbitrator Agent** = the dispute-arbitration actor; its implementation is the Python *arbitration service* (FastAPI + LangGraph).
- **Administrator (小二)** = ADMIN operations role (tier-2 dispute review).
- **Third-Party Agent** = the external executing AI; bare "Agent" defaults to it.

## Non-goals

- Not touching the Chinese arch doc or the Notion SAD/PRD.
- Not the academic FYP report (the `§5.x` family that `docs/sad-5.9-ui-section.md` belongs to).
- Not writing code.
