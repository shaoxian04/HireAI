# Task Matching Design

> **Status:** Draft (requirements + architecture alignment) · **Date:** 2026-06-24 · **Owner:** Shaoxian
> Design & requirements note, **not** an implementation spec. Companion to `programmatic-task-submission.md`.
> Supersedes the original single-winner `max(reputation)` matcher described in `docs/details/` / the routing code.
> **Revised 2026-06-29:** lifecycle states aligned to the now-canonical `TaskStatus` enum / SAD §6.3 — the
> no-eligible-candidate path uses the existing **`AWAITING_CAPACITY`** holding state (not a new state), and a
> matched agent that goes silent is swept to **`TIMED_OUT`** (SAD §6.3 reliability). The proposed frontend
> **`AWAITING_SELECTION`** shortlist state and the multi-factor scorer below are still pending (the live matcher
> is still single-winner `max(reputation)`).

## 1. Summary

Task matching is redesigned around **one ranking engine** consumed differently per channel. The platform ranks
eligible agents with a **multi-factor weighted score** plus an **exploration allowance** (to counter cold-start),
then:

- **Frontend open task** → return a **shortlist (~5)**; the **client selects** the agent.
- **API / MCP open task** → the platform acts as **broker** and **auto-selects the top-ranked agent** (no shortlist
  exposed).
- **Any channel** → a client may **book a specific agent directly**, bypassing matching entirely.

The same scoring engine feeds all paths — the frontend takes the top *N≈5*, the programmatic path takes the top *1*.

## 2. Per-channel model

| Channel | Open task | Specific agent |
|---|---|---|
| **Frontend** | platform ranks → **shortlist (~5)** → **client picks** | ✅ direct booking |
| **API / MCP** | platform **auto-routes to the top match** (broker; no shortlist returned) | ✅ direct booking |

Rationale: humans get *agency* (a shortlist they can weigh); headless agents get true *brokering* (they can't eyeball
a UI, and "find me a suitable agent" is exactly the value they came for). Both keep direct booking as the bypass.

## 3. The ranking engine

### 3.1 Hard filter (eligibility)
Unchanged from today's `findActiveCandidates`: keep only **ACTIVE** agents whose *current* version **covers the task
category** (Postgres GIN array-overlap) and whose **price ≤ budget**. Cheap, indexed, runs in SQL.

### 3.2 Multi-factor weighted score
Replaces the current `max(reputation)`. For each eligible candidate, compute a weighted sum of normalised factors
(each in `[0,1]`; weights configurable and sum to 1):

```
score(agent) = w_rep     · norm(reputation)            // quality signal
             + w_value   · valueFit(price, budget)     // reward better value within budget
             + w_load    · loadHeadroom(agent)         // reward spare capacity (spreads load)
             + w_explore · explorationBonus(agent)     // boost under-sampled / new agents
```

- **reputation** — normalised reputation score.
- **valueFit** — e.g. `(budget − price) / budget`, rewarding headroom; weight tunes how much price matters.
- **loadHeadroom** — e.g. `1 − inFlight / maxConcurrent`, rewarding agents with spare capacity to prevent the top
  agent hogging every task.
- **explorationBonus** — decays with completed-task count (e.g. `1 / (1 + completed)`), giving newer agents real
  visibility.

### 3.3 Exploration (cold-start)
Two complementary mechanisms:
- **Shortlist (frontend):** the exploration term ensures a newer/under-sampled agent usually *appears* in the top-5,
  so it can earn its first jobs — without forcing it on anyone (the human still chooses).
- **Auto-route (API/MCP):** **epsilon-greedy** — with probability `1−ε` take `argmax(score)`; with small probability
  `ε` sample from the eligible set weighted toward under-sampled agents, so newer agents occasionally win a real
  auto-routed job. `ε` is small (e.g. 0.1) and configurable.

This directly addresses PRD **Q4** (low-volume new agents) and **R4** (cold-start).

### 3.4 Selection per channel
- **Frontend:** return the **top *N* (≈5)** eligible candidates by score → client selects.
- **API / MCP:** return the **top 1** (epsilon-greedy) → dispatch.
- **No eligible candidate (either channel):** the task does **not** fail immediately — it holds in
  **`AWAITING_CAPACITY`** with escrow still frozen and is re-matched (bounded) as capacity frees (SAD §6.3).

## 4. Determinism, testability, and the money path
- The **score is deterministic** given its inputs.
- **Exploration introduces bounded randomness in *selection only*** — never in money. Hard Invariant #3
  (deterministic money path) is untouched: settlement is still computed deterministically from the task outcome,
  regardless of which agent was selected.
- **Testability:** the RNG is injected/seedable; with `ε = 0` the matcher is a pure `argmax`, used in unit tests for
  the scoring. Exploration behaviour is tested with a fixed seed.

## 5. Escrow & task lifecycle
- **Escrow timing is uniform and unchanged:** the client states a **budget**, and the **budget is frozen in escrow
  at submit** on every path (Hard Invariant #1 timing preserved). The eligibility filter only considers agents with
  `price ≤ budget`, so any selection is affordable.
- **Proposed new state for the frontend shortlist** (not yet in the `TaskStatus` enum): after submit + freeze,
  an open frontend task enters **`AWAITING_SELECTION`** (shortlist attached) until the client picks, then
  → `QUEUED` → dispatch.
  - **Abandonment:** `AWAITING_SELECTION` expires after a timeout → task cancelled, **escrow released**.
- **No-match holding (canonical state, already in the enum):** if matching yields no eligible agent, the task
  holds in **`AWAITING_CAPACITY`** (escrow still frozen — it is an `isPendingEscrow` state) and is periodically
  re-matched as capacity frees (SAD §6.3), rather than being cancelled.
- **Silent executor:** a matched, dispatched agent that never calls back is swept to **`TIMED_OUT`** →
  auto-refund (SAD §6.3 reliability) — independent of which agent matching selected.
- **API/MCP open** and **direct booking** need no new state: `SUBMITTED → QUEUED → dispatch` as today.

## 6. Invariants check

| # | Invariant | Status |
|---|---|---|
| 1 | Escrow before execution | ✅ budget frozen at submit on all paths; nothing dispatched without it |
| 2 | Append-only money/audit | ✅ untouched |
| 3 | Deterministic money path | ✅ exploration affects *selection* only, never settlement |
| 4 | Output spec is the binding contract | ✅ winner's task-frozen `output_spec` still threaded to dispatch |
| 5 | Server-side identity | ✅ unchanged |
| 6 | Signed, HTTPS-only I/O | ✅ unchanged |

## 7. What changes vs the current implementation
- `RoutingMatchDomainService` — `max(reputation)` → **weighted multi-factor score + exploration**; add a `topN`
  variant for the shortlist alongside the `top1` auto-route.
- `findActiveCandidates` — keep the category+budget filter; the `ORDER BY reputation` can stay as a coarse pre-sort,
  but final ranking moves into the (testable, framework-free) domain matcher.
- New **`AWAITING_SELECTION`** task state (the no-match **`AWAITING_CAPACITY`** state already exists in the enum)
  + shortlist read model + selection endpoint for the frontend open-task flow.
- Routing trigger is unchanged for API/MCP and direct (auto-route / pinned dispatch as today).

## 8. Out of scope / future
Learned / ML-based ranking; **semantic (embedding) matching** of task text to agent capabilities (the strongest
future upgrade — would replace exact-category matching, see the matching discussion); predictive load balancing;
competitive bidding (PRD §7.6); agent-initiated task decline (PRD Q3).

## 9. Open questions
- **Weights & ε:** initial values for `w_rep / w_value / w_load / w_explore` and the exploration rate `ε` — tune
  empirically (ties to PRD Q8 on disclosing the algorithm).
- **valueFit direction:** reward *cheaper* within budget, or treat price as a quality proxy? (configurable weight,
  but the default stance is a product call.)
- **Shortlist size & `AWAITING_SELECTION` timeout:** `N≈5` and the abandonment window are both tunable defaults.
- **Tie-break:** explicit deterministic order on equal scores (e.g. price asc, then agent id).
