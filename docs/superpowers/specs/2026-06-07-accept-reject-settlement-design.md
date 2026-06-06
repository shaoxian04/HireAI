# Accept/Reject + Deterministic Settlement (minimal Modules 4+5 slice)

**Date:** 2026-06-07 · **Branch:** `feat/marketplace-spine` · **Status:** approved

## Goal

Close the money loop for the demo: after an agent returns a result (`RESULT_RECEIVED`), the
client can **Accept** (escrow → builder payout, 15% platform commission) or **Reject**
(escrow → full refund). Task ends `RESOLVED`. Everything else from Modules 4/5 is deferred.

## Decisions

| Decision | Choice |
|---|---|
| Scope | Settlement core + Accept/Reject buttons only ("demo the whole flow") |
| Commission | **15%**, per the SAD; deducted on release, recorded as `COMMISSION` |
| Settlement mechanics | **Synchronous, in the accept/reject request transaction** (no events, no outbox) |
| Reject semantics | Full refund on the client's word; optional free-text reason stored |
| Review window | None — task stays `RESULT_RECEIVED` until the client acts (no auto-accept timeout) |
| `PENDING_REVIEW` | Stays **reserved/unused** until automated validation (full Module 4) lands |

## Out of scope (explicitly deferred)

- Disputes / arbitration service; tiered resolution. Consequence accepted: **reject = free,
  instant, full refund** — a client can keep the work and take the money back. The dispute
  tier is the future fix.
- Automated output-spec validation (lean Invariant #4 enforcement) and `SPEC_VIOLATION` flow.
- Auto-refund on `FAILED` / `TIMED_OUT` (existing frozen escrow on old failed tasks stays frozen).
- Reputation events, earned reviews, auto-accept timeout, platform wallet (commission is
  recorded in the ledger and leaves the system).

## Domain

### Task aggregate

- New enum `TaskResolution { ACCEPTED, REJECTED }`.
- `TaskModel.accept()` and `TaskModel.reject(String reason)`:
  - legal **only** from `RESULT_RECEIVED`; anything else →
    `DomainException(DOMAIN_RULE_VIOLATION)` (→ HTTP 409). This is also the exactly-once
    settlement guard — a `RESOLVED` task can never settle again.
  - set `status = RESOLVED`, `resolution`, `resolvedAt`; `reject` additionally stores the
    optional trimmed reason (max 500 chars, nullable).
- Immutable-copy style matching the existing transition methods (`markFailed()` etc.).

### Settlement domain service

`SettlementDomainService` (framework-free interface + impl, wired in `DomainServiceConfig`).
The LLM never touches this path; pure arithmetic from the resolution (Invariant #3).

- `SettlementPolicy.COMMISSION_RATE = 0.15` (single constant, domain layer).
- `settleAcceptance(clientWallet, builderWallet, budget, taskId, correlationId)`:
  - `commission = round2(budget × 0.15, HALF_UP)`; `net = budget − commission`
    (net + commission ≡ budget by construction — ledger reconciles exactly).
  - client wallet: `release(net, PAYOUT)` + `release(commission, COMMISSION)`
  - builder wallet: `credit(net, PAYOUT)`
  - 3 ledger entries total, same `correlationId`.
- `settleRejection(clientWallet, budget, taskId, correlationId)`:
  - client wallet: `refund(budget)` — 1 ledger entry.
- Reuses the existing `WalletModel.release/refund/credit` operations unchanged.

## Schema — V9

```sql
ALTER TABLE tasks
    ADD COLUMN resolution        text  NULL CHECK (resolution IN ('ACCEPTED','REJECTED')),
    ADD COLUMN resolved_at       timestamptz NULL,
    ADD COLUMN rejection_reason  text  NULL CHECK (char_length(rejection_reason) <= 500);
```

No ledger/trigger changes — `ledger_entries` append-only triggers (V1) already cover the new
entry usage. `PAYOUT` / `COMMISSION` / `REFUND` entry types already exist in the enum and schema.

## Application + API

`TaskReviewAppService` (interface + `impl/`, Spring-managed, `@Transactional`):

1. `clientId` from JWT via `CurrentUserProvider`; load task; **owner check** → 404 if not the
   submitter (Invariant #5).
2. `task.accept()` / `task.reject(reason)` (state guard inside the model).
3. Resolve builder: task → `agentVersionId` → agent → `ownerId`.
4. Wallets: client wallet must exist (it froze the escrow); builder wallet
   `findByUserId(...).orElse(WalletModel.openFor(ownerId))` — **opened on first payout**.
5. Call the settlement domain service; save task + both wallets in the same transaction.
   `correlationId = "settle-" + taskId`.
6. Accepting your own agent's task (client == builder) settles within one wallet — legal,
   the ledger keeps it honest.

Endpoints (`TaskController`):

| Endpoint | Body | Success | Errors |
|---|---|---|---|
| `POST /api/tasks/{id}/accept` | — | task view (`RESOLVED`/`ACCEPTED`) | 404 not owner/unknown; 409 not `RESULT_RECEIVED` |
| `POST /api/tasks/{id}/reject` | `{ "reason": "…" }` (optional, ≤500) | task view (`RESOLVED`/`REJECTED`) | same |

Task read views gain `resolution`, `resolvedAt`, `rejectionReason`, plus display amounts
(`payoutAmount`, `commissionAmount` when ACCEPTED; `refundAmount` when REJECTED) computed
**server-side from `SettlementPolicy`** — the rate lives in exactly one place; the frontend
never hardcodes 85/15. Amounts of record live in the ledger.

## Frontend

- **Task detail page** (`/client/tasks/[id]`): when status is `RESULT_RECEIVED`, render an
  action bar under the result panel — **Accept result ▸** (primary lime) and **Reject**
  (subtle; expands an optional-reason input + confirm). On success, refetch task: SETTLE node
  lights, badge `RESOLVED · ACCEPTED` (lime) or `RESOLVED · REJECTED` (red), escrow line
  becomes "settled — 17 cr paid out · 3 cr commission" or "12 cr refunded".
- **My tasks list**: RESOLVED rows show the resolution.
- **Wallet**: existing balances/ledger reflect the movement on refetch (no new endpoint).
- Existing UI kit + Mission Control tokens; `api()` client; MSW handlers + vitest for both flows.

## Tests

- **Domain unit:** commission rounding (incl. odd amounts, e.g. 0.01 / 33.33), net+commission ≡
  budget, wrong-state accept/reject → DOMAIN_RULE_VIOLATION, double-settle impossible, refund math,
  reason trimming/length.
- **App-service / integration (Testcontainers):** freeze → accept → client escrow −budget,
  builder available +net, 3 ledger rows w/ shared correlationId; freeze → reject → full refund;
  non-owner → NOT_FOUND; double accept → 409; builder wallet auto-open; ledger reconstruction
  invariant still holds.
- **Controller slice:** request/response mapping, validation (reason length), envelope codes.
- **Frontend vitest:** buttons render only at `RESULT_RECEIVED`; accept flow happy path;
  reject with reason; resolved badge rendering.

## Demo flow (the point of all this)

Client books/submits → agent completes → client opens task → **Accept** → builder's wallet
gains 85% of the budget, ledger shows PAYOUT + COMMISSION, task pipeline fully lit through
SETTLE. Or **Reject** → money back, ledger shows REFUND.
