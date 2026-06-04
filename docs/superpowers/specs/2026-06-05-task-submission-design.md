# Task Submission — Design Spec

**Date:** 2026-06-05
**Module:** 1 — Task Submission (PRD)
**Status:** Approved for planning
**Slice scope:** Submit + freeze + persist (the first vertical slice of Module 1)

## Goal

Deliver the first demonstrable end-to-end marketplace journey: a client submits a
well-defined task with a budget, credits freeze in escrow against that task, and the
task persists as `SUBMITTED`. This closes the escrow loop that the already-built Wallet
slice left open (the tested `WalletWriteAppService.freeze(...)` currently has no caller
because no `taskId` exists yet).

This slice exercises **Hard Invariant #1 (escrow before execution)** end-to-end for the
first time and establishes the first cross-aggregate coordination pattern in the codebase.

### Out of scope (deliberately deferred)

- Task lifecycle transitions / state machine (only `SUBMITTED` is reachable here).
- Cancel/refund endpoint.
- Real file-attachment upload (object storage). Attachments are *modeled* but not uploadable.
- Routing, dispatch, validation, dispute, settlement (downstream modules).
- Real JWT auth — identity continues to come from the `CurrentUserProvider` dev stub.

## Aggregate boundary

`TaskModel` is a new aggregate root with its own repository (one-repository-per-root rule).

| Element | Type | Role |
|---|---|---|
| `TaskModel` | aggregate root | Identity, client owner (`clientId`), `status`, budget, title, description, timestamps. Owns invariants. |
| `OutputSpec` | value object | The **binding output contract** (Invariant #4): `format` (TEXT/JSON/FILE), optional `schema`, `acceptanceCriteria`. Stored faithfully and immutably; later consumed by validation + arbitration. |
| `TaskAttachmentModel` | child entity | Modeled but storage deferred — carries reference metadata only. Reached only through the root. |
| budget | `Money` VO | Reuses the existing shared `Money` value object; the amount frozen in escrow. |

`TaskStatus` enum carries the full designed lifecycle
(`SUBMITTED → ROUTING → IN_PROGRESS → SUBMITTED_FOR_REVIEW → VALIDATING → ACCEPTED →
REJECTED → DISPUTED → SETTLED → CANCELLED`) for schema forward-compatibility, but only
`SUBMITTED` is reachable in this slice. No transition methods are implemented.

## Key architectural decision: how Task triggers the escrow freeze

**Chosen: Option A — synchronous app-service orchestration.**

`TaskWriteAppService.submit(...)` runs in a single `@Transactional`:

1. Build + validate the `TaskModel` via `TaskSubmitDomainService`.
2. Persist it via `TaskRepository` to obtain a `taskId`.
3. Call the already-tested `WalletWriteAppService.freeze(clientId, budget, taskId, corr)`.

If `freeze` throws `INSUFFICIENT_BALANCE`, the entire transaction rolls back — **no task
row is created and no escrow change occurs**. Invariant #1 becomes an *atomicity*
guarantee, the strongest available form.

### Why not the alternatives

- **Domain event** (`TaskSubmittedDomainEvent` → wallet listener): events model *eventual*
  reactions. A synchronous same-transaction listener would just be Option A with extra
  indirection; a normal async listener would *break* escrow-before-execution.
- **Dedicated saga/orchestrator**: overkill for a single synchronous step.

This respects the scaffolding rule that cross-aggregate coordination happens in the
**application layer** (app-service → app-service), never via a domain cross-import between
`biz/<aggregate>` packages.

## Slice, layer by layer (mirrors the Wallet slice)

```
controller/biz/task/    TaskController            POST /api/tasks · GET /api/tasks/{id} · GET /api/tasks
                        SubmitTaskRequest (Bean Validation) · TaskDTO · TaskModel2DTOConverter
application/biz/task/    TaskWriteAppService  (@Transactional submit → returns taskId)
                        TaskReadAppService   (@Transactional(readOnly) getById, listForClient)
domain/biz/task/        model/TaskModel, OutputSpec, TaskAttachmentModel
                        enums/TaskStatus · repository/TaskRepository + TaskQuery
                        service/TaskSubmitDomainService · event/TaskSubmittedDomainEvent
infrastructure/repository/task/  TaskRepositoryImpl + TaskJpaEntity (+ TaskAttachmentJpaEntity)
resources/db/migration/  V2__tasks.sql
```

### Invariants enforced

- **#1 escrow before execution** — guaranteed by the atomic transaction in `TaskWriteAppService`.
- **#4 binding output spec** — `OutputSpec` persisted faithfully and immutably (JSONB).
- **#5 server-side identity** — `clientId` derived from `CurrentUserProvider`, never the request body.
- `TaskSubmitDomainService` enforces: non-blank title/description, positive budget,
  well-formed non-null `OutputSpec`.

### Persistence

- `tasks` table: `id`, `client_id` (FK → users), `title`, `description`, `budget` NUMERIC(14,2)
  CHECK > 0, `output_spec` JSONB NOT NULL, `status` TEXT with CHECK over the lifecycle set,
  `gmt_create`, `gmt_modified`.
- `task_attachments` table: `id`, `task_id` (FK → tasks), reference metadata columns. No file bytes.
- `output_spec` stored as JSONB so the contract shape can evolve without migration.
- Flyway `V2__tasks.sql`; Hibernate stays `ddl-auto: validate`.

## Error handling & demo flow

| Condition | Result |
|---|---|
| Insufficient balance | `DomainException(INSUFFICIENT_BALANCE)` → mapped HTTP status; task **not** persisted (rollback). |
| Blank title/description, non-positive budget, missing spec | `400` via Bean Validation. |
| Valid submit | `201`/`200` with task `SUBMITTED`; budget moved to escrow. |

**Showable journey:** `POST /api/wallet/topup` → `POST /api/tasks` → response shows task
`SUBMITTED`; `GET /api/wallet` shows the budget in `escrow`; `GET /api/wallet/transactions`
shows an `ESCROW_FREEZE` ledger entry carrying the `taskId`.

## Testing

**Unit (no Docker):**
- `TaskModelTest` — valid submit builds a `SUBMITTED` task; rejects blank title, non-positive
  budget, null `OutputSpec`.
- `OutputSpecTest` — construction/validation of the value object.

**Integration (Testcontainers, `@EnabledIf("dockerAvailable")`, per-test users):**
1. Top up → submit → assert task `SUBMITTED` **and** escrow frozen **and** an `ESCROW_FREEZE`
   ledger entry tied to the `taskId` exists.
2. Submit with insufficient balance → assert **task NOT persisted and escrow unchanged**
   (proves the atomic rollback enforces Invariant #1). This is the crown-jewel test.

## Acceptance criteria

- `POST /api/tasks` creates a `SUBMITTED` task and freezes its budget in escrow atomically.
- A failed freeze leaves neither a task row nor any escrow/ledger change.
- `clientId` is always taken from `CurrentUserProvider`, never the request body.
- `output_spec` round-trips faithfully through JSONB.
- Domain layer has zero framework imports; cross-aggregate freeze goes through the
  application layer only.
- `mvn -B test`: all unit tests pass; integration tests pass under Docker and skip cleanly without it.
