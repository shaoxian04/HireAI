# Builder Earnings View (Module 6 follow-up)

**Date:** 2026-06-07 · **Branch:** `feat/marketplace-spine` · **Status:** approved

## Goal

Give the builder a single place that answers "how much did I earn?": a dedicated
`/builder/earnings` page showing lifetime earnings, pending escrow, a per-agent breakdown, and a
payout history — fed by one new read endpoint.

## Decisions

| Decision | Choice |
|---|---|
| Scope | Summary totals + payout history + per-agent breakdown |
| Placement | Dedicated `/builder/earnings` page, linked from a new builder header nav |
| Data source | **Derived from the `tasks` table via `SettlementPolicy`** — not by summing ledger rows |
| Endpoint | `GET /api/builder/earnings`, identity from JWT (invariant #5), no parameters |
| History depth | Latest 50 payouts, no pagination UI (documented cap) |
| Console tile | Existing "credits earned" tile relabelled **`wallet cr`** and linked to the earnings page |

## Why tasks, not the ledger (key decision)

`PAYOUT` ledger entries exist on **both** wallets of a settlement: the client's escrow-release row
and the builder's credit row. In the legal self-settle case (a client accepting their own agent's
task) both rows land in the **same wallet** with the same type and amount — distinguishable only by
comparing `balance_after` across neighbouring rows, which is fragile in SQL.

Deriving from tasks is unambiguous and **equal to the ledger credit by construction**: settlement
computed the credit as `SettlementPolicy.netOf(budget)` from the same task row. This follows the
precedent set by the accept/reject spec (task-page display amounts derive from the policy; amounts
of record live in the ledger). The 15% rate stays in exactly one place: `SettlementPolicy`.

## Semantics

All sums fold per task through `SettlementPolicy.netOf(task.budget)` in Java (rounding is per-task
— HALF_UP to 2dp — so no SQL arithmetic).

| Field | Definition |
|---|---|
| `lifetimeEarned` | Σ net over tasks with `resolution = 'ACCEPTED'` whose `agent_version_id` belongs to an agent owned by the caller |
| `pendingIfAccepted` | Σ net over the caller's agents' tasks in **non-terminal** statuses: `QUEUED`, `EXECUTING`, `RESULT_RECEIVED`, `PENDING_REVIEW`, or `AWAITING_CAPACITY` with `agent_version_id` set (direct-booking race). Escrow is still held and would pay out on accept |
| `paidTaskCount` | Count of the `lifetimeEarned` task set |
| `perAgent[]` | The same three numbers grouped by agent: `{ agentId, agentName, earned, pendingIfAccepted, paidTaskCount }`. Agents with no routed tasks appear with zeros (the builder sees every agent they own) |
| `payouts[]` | The accepted tasks, newest first, max 50: `{ taskId, taskTitle, agentName, amount, settledAt }` where `amount` = net and `settledAt` = `resolved_at` |

Excluded everywhere: `resolution = 'REJECTED'` (refunded), `FAILED`, `TIMED_OUT`, `SPEC_VIOLATION`
(no payout possible).

## API

| Endpoint | Auth | Success | Notes |
|---|---|---|---|
| `GET /api/builder/earnings` | JWT; caller's own earnings only | `BuilderEarningsDTO` | A CLIENT caller gets zeros + empty lists (owns no agents) — not an error |

```
BuilderEarningsDTO {
  lifetimeEarned:      number   // 2dp
  pendingIfAccepted:   number   // 2dp
  paidTaskCount:       number
  perAgent: [{ agentId, agentName, earned, pendingIfAccepted, paidTaskCount }]
  payouts:  [{ taskId, taskTitle, agentName, amount, settledAt }]   // newest first, ≤50
}
```

## Backend structure

Read-side CQRS, mirroring the existing catalogue/stats reads:

- `controller/biz/wallet/BuilderEarningsController` — thin: JWT identity → app service → `WebResult`.
- `application/biz/wallet/BuilderEarningsReadAppService` (interface) + `impl/` — folds DAO rows
  through `SettlementPolicy`, groups per agent, assembles the DTO.
- Infrastructure read DAO — one query:
  `tasks ⋈ agent_versions (agent_version_id) ⋈ agents WHERE agents.owner_id = :userId`,
  returning per-task rows (taskId, title, budget, status, resolution, resolvedAt, agentId,
  agentName) plus the caller's agents (for zero rows).
- **No schema change. No new tables. No domain service** (no state transition).

## Frontend

- **`app/builder/earnings/page.tsx`** (RoleGuard BUILDER, AppShell):
  - Three `StatTile`s: *lifetime earned* (lime accent) · *pending — if accepted* (amber) · *paid tasks*.
  - Per-agent panel: rows of agent name · earned · pending (mono, tabular).
  - Payout history list: task title, agent name, date, `+10.20 cr` in lime mono.
  - Empty state: "No payouts yet — earnings land here when a client accepts your agent's work."
- **Builder nav** (AppShell): builder role gains `My agents` (`/builder`) · `Earnings`
  (`/builder/earnings`) — it currently has no nav links.
- **Console tile**: relabel "credits earned" → **`wallet cr`** (it shows the wallet balance, which
  is not the same thing as lifetime earnings once builders can spend) and wrap it in a link to
  `/builder/earnings`.
- Types in `lib/types.ts`; data via the `api()` client; Mission Control tokens + existing UI kit.

## Tests

- **App-service unit (Mockito):** totals fold correctly; per-agent grouping; rejected/failed
  excluded; zero-commission flip-point budgets (0.03 → net 0.03, 0.04 → net 0.04 − 0.01);
  client caller → zeros + empty.
- **Integration (Testcontainers Postgres):** seed two agents + accepted/rejected/open tasks →
  one GET returns correct totals, breakdown rows, payout order; **self-settle case** (client
  accepts own agent's task) counts the payout exactly once.
- **Controller slice:** envelope shape, amounts serialised 2dp, auth required.
- **Frontend vitest + MSW:** page renders tiles, per-agent rows, payout list from a stubbed DTO;
  empty state renders; builder nav shows the Earnings link.

## Out of scope (deferred)

- Earnings time-series chart; CSV export; date filtering.
- Pagination beyond the latest 50 payouts.
- A `direction` column on `ledger_entries` (the durable fix for credit-vs-release PAYOUT
  disambiguation) — backlog alongside the wallet `@Version` note in `data-model.md`.
- Withdrawals / cash-out (virtual credits never leave the system in the MVP).

## Demo flow

Builder logs in → **Earnings** in the nav → lifetime 30.60 cr, per-agent split (Summariser Bot
30.60 · Analyst Bot 0.00), payout history listing the accepted demo tasks — then a fresh accept
during the demo bumps every number live on refresh.
