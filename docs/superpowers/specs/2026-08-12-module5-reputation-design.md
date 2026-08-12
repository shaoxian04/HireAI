# Module 5 — Reputation — Design

> **Status:** Approved design, ready for an implementation plan. · **Date:** 2026-08-12 · **Owner:** Shaoxian
> **Roadmap:** the last unbuilt module of the six. Settlement (the other half of "Reputation & Virtual
> Settlement") is already built — accept/reject → 85/15 payout, dispute rulings → deterministic settlement.
> **Binding context:** [`docs/adr/0003-two-component-shrinkage-reputation.md`](../../adr/0003-two-component-shrinkage-reputation.md).

## 1. Summary

`agents.reputation_score` has been a lie since `V3`. It is `NUMERIC(5,2)` defaulted to `50.00`, written once
at registration via `AgentModel.DEFAULT_REPUTATION`, and **updated by nothing, anywhere in the codebase**.
The matcher weights it at **0.40** — the largest of its four factors — so today every candidate contributes an
identical `0.40 × 0.5 = 0.20` and reputation ranks nothing. `reviews` (`V7`) is worse: three **fabricated**
4–5★ reviews seeded per agent from the demo client, with `task_id NULL` and no client-authored write path.

Module 5 makes both real:

1. **An append-only `reputation_events` stream** (Invariant #2) fed by the seven terminal outcomes that
   already exist in the codebase, from which `reputation_score` is derived as **two independent shrinkage
   estimators** — Reliability (platform-witnessed) and Satisfaction (client-authored) — blended 70/30.
2. **An earned-review flow**: a client may rate a task they accepted, exactly once, and that rating is the
   only thing feeding Satisfaction. The fabricated seeds are purged and `reviews.task_id` becomes
   `NOT NULL UNIQUE`.

`MatchingPolicy` is **not touched**. No weight changes, no routing migration — the matcher simply starts
receiving a number that varies.

## 2. Why the two-component split (the pivot)

The design began as a single blended stream and had to be reworked. If an unreviewed accept scores `q = 1.0`,
then **silence is perfection**: any review below 5★ can only drag an agent down, and the rational strategy for
a builder becomes *suppress reviews entirely*. The concrete failure — an agent that delivered good work and
earned 4★ ranks **below** an agent that delivered bad work whose client never bothered to review:

| Model | Good work + 4★ | Bad work, silent | |
|---|---|---|---|
| Single stream, supersede | q = 0.75 | q = **1.0** | bad agent wins |
| Single stream, additive | mean 0.875 | mean **1.0** | bad agent wins |
| **Two components** | **83.0** | **78.0** | correct |

Two components with their own priors fix it: an unrated agent falls back to the neutral prior (0.5), not to
the ceiling. **Silence reads as *unknown*, never as *perfect*.** Full rationale and the rejected alternatives
(points balance, peer factor in `MatchingPolicy`) are in ADR 0003.

## 3. Scope

**In:** `reputation_events` + triggers (`V27`); the scoring domain service + policy; emission at seven
existing terminal-outcome sites; running aggregates on `agents` with a reconciliation path; the review write
endpoint; purge of the `V7` seeds + `task_id NOT NULL UNIQUE`; frontend rating prompt, client-facing plain
language, builder-facing breakdown.

**Out:** time decay; stake-weighted events; Sybil defence beyond same-account; per-version reputation;
reputation on *builders* (as opposed to agents); SSE push of score changes.

## 4. Key decisions (resolved in grilling)

| # | Decision | Rejected alternative |
|---|---|---|
| 1 | Reputation and Rating are distinct concepts; ratings **do** steer routing | rating as display-only |
| 2 | Rating is an **ingredient of Reputation**, not a 5th peer factor in `MatchingPolicy` | peer factor (needs a null rule; forces weight re-normalisation) |
| 3 | **Shrinkage average**, not a points balance | balance (saturates; ranking inverts on tuning constants) |
| 4 | **Two components** — Reliability + Satisfaction | single blended stream (silence = perfection) |
| 5 | **α = 0.7** — stars control 12% of the total match score | 0.5 (overweights a forgeable signal), 0.9 (ratings decorative) |
| 6 | `k` = **5** Reliability / **10** Satisfaction | 5/5 (trusts fake reviews twice as fast) |
| 7 | **L1** anti-gaming — owner-as-client emits nothing | L2 per-client decay (punishes legitimate repeat business); stake weighting (punishes cheap agents) |
| 8 | **Agent-level**, carries across versions | per-version reset (makes "never update" rational) |
| 9 | **No decay** | half-life decay (lets a bad agent launder by hibernating) |
| 10 | **Purge** the `V7` fabricated reviews | keep-as-display-only (publishes two disagreeing numbers) |
| 11 | **Accepted-only** reviews | reviewable disputes (retaliation against a ruling, or double-punishment) |

## 5. The scoring model

```
Reliability  = (kR·p₀ + Σ outcome quality) / (kR + n_outcomes)      kR = 5,  p₀ = 0.5
Satisfaction = (kS·p₀ + Σ rating quality)  / (kS + n_ratings)       kS = 10, p₀ = 0.5

reputation_score = 100 × (α × Reliability + (1−α) × Satisfaction)   α  = 0.7
```

A zero-event agent scores exactly **50.00** — identical to today's `DEFAULT_REPUTATION`, so nothing shifts on
migration day. Count enters only through the denominator: **volume moves an agent toward its true quality
rate and can never carry it past that rate.**

| Agent | Reliability | Satisfaction | Reputation |
|---|---|---|---|
| New, no events | 0.500 | 0.500 | **50.0** |
| 20 accepts, 20× 4★ | 0.900 | 0.667 | **83.0** |
| 20 accepts, no ratings | 0.900 | 0.500 | **78.0** |
| 20 accepts, 20× 1★ | 0.900 | 0.167 | **68.0** |
| 100 API auto-settles, never rateable | 0.976 | 0.500 | **83.3** |
| 100 accepts, 100× 5★ | 0.976 | 0.955 | **97.0** |

Two properties worth noting. **Good reviews > silence > bad reviews** (83.0 / 78.0 / 68.0) — soliciting
feedback is positive-expected-value if you are actually good. And an **API-only agent caps at 83.3** without
any special rule: it earns Reliability but can never earn Satisfaction, because no human ever judged it.

Recovery is earned by working, which is why no decay is needed:

| Reliability history | Reliability |
|---|---|
| 10 failures | 0.167 |
| …then 20 successes | 0.643 |
| …then 200 successes | 0.942 |

`ReputationPolicy` is a framework-free `record` holding `α`, `kR`, `kS`, `p₀`, validated in its compact
constructor (bad config = bean-creation crash, not a subtly wrong marketplace) and bound from
`hireai.reputation.*`. This mirrors `MatchingPolicy` exactly — same pattern, same failure mode.

## 6. Event model

Every emission site already exists and already resolves the data needed. `builderId` in particular is loaded
at each one to settle, so the L1 owner check adds a comparison, not a query.

| Emission site | Event | quality | Rateable |
|---|---|---|---|
| `TaskReviewAppServiceImpl.accept()` | `TASK_ACCEPTED` | 1.0 | **Yes** |
| `TaskReviewAppServiceImpl.reject(D_CHANGED_MIND)` | *(none)* | — | No |
| `ValidationAppServiceImpl` PASS + api-submitted | `TASK_ACCEPTED` | 1.0 | No (machine client) |
| `ValidationAppServiceImpl` FAIL | `SPEC_VIOLATION` | 0.0 | No |
| `AgentCallbackAppServiceImpl` non-`COMPLETED` | `EXECUTION_FAILED` | 0.0 | No |
| `TaskReliabilityAppServiceImpl` deadline passed | `EXECUTION_TIMEOUT` | 0.0 | No |
| `TaskWriteAppServiceImpl.cancelAwaitingCapacity…` | *(none)* | — | No |
| `DisputeAppServiceImpl.settleFromEffective()` `FULFILLED` | `DISPUTE_WON` | 1.0 | No |
| ⟶ `PARTIALLY_FULFILLED` | `DISPUTE_PARTIAL` | 0.5 | No |
| ⟶ `NOT_FULFILLED` | `DISPUTE_LOST` | 0.0 | No |
| `ReviewAppServiceImpl.review()` | `RATING` | (stars−1)/4 | — |

`CANCELLED` emits nothing: it fires from `AWAITING_CAPACITY`, meaning no agent had headroom. Being busy is
not a failure, and no agent received the work.

**A trap to respect.** `chargeChangedMind` writes `TaskResolution.REJECTED` while paying the builder the full
85/15 (`TaskModel:197-202`). The resolution column says *rejected*; the money says *accepted*. **Emission must
happen at the transition sites, never be derived from `task.resolution`** — a rule keyed off that field gets
this case backwards in both directions.

## 7. Anti-gaming — L1, and what it does not cover

A task whose client **owns the agent** emits no reputation events of any kind, and cannot be reviewed. This is
a comparison of `task.clientId()` against the `builderId` already resolved at every emission site.

**Documented limitation:** a builder using a *second* account can still farm, and it costs nothing. The
builder sets their own agent's price; `V3:30` and `RegisterAgentRequest:25` floor it at `0`, `V2:12` floors a
task budget at `0.01`, and at any price ≤ `0.03` the 15% commission rounds to `0.00` under `Money`'s 2dp
`HALF_UP` scale (`Money:19`) — so the round trip returns the credits intact and can be repeated indefinitely.
Real Sybil resistance needs identity verification or a payment rail, neither of which a virtual-credit
prototype has. See ADR 0003 for the rejected defences.

**Separate follow-up, not part of this module:** the zero-commission price band is a settlement defect on its
own terms — the platform performs escrow, routing, validation and settlement for free on any task priced at
or below 0.03. Worth an issue against the price floor.

## 8. Data model — migration `V27`

**`reputation_events`** — append-only, with `UPDATE`/`DELETE` triggers raising, exactly as `ledger_entries`
does (Invariant #2):

```
id, agent_id (FK agents), task_id, event_type, quality NUMERIC(4,3), weight NUMERIC(4,3),
occurred_at TIMESTAMPTZ
```

`task_id` is a **soft reference** (like `validation_reports` and `api_key_task`), not a Task-aggregate column,
so the Task aggregate is untouched. `quality` is an **addition** to the `data-model.md:35` target schema,
which lists only `weight` — that spec assumed a balance model. Update the doc.

**Running aggregates on `agents`** — `reliability_sum`, `reliability_count`, `satisfaction_sum`,
`satisfaction_count`. These make the score update **O(1)** instead of re-reading an agent's whole event
history on every settlement. They are a **derived cache**: `reputation_events` remains the source of truth,
and a reconciliation path replays the stream and asserts the aggregates agree (a direct, demonstrable
exercise of Invariant #2).

**`reviews` tightened** — drop the `V7` fabricated rows; `task_id` → `NOT NULL UNIQUE`, which is the
constraint `V7`'s own comment deferred "until the real review flow lands."

Writes to `agents` use a **targeted native `UPDATE`** of the five reputation columns rather than a full-row
`save()`, so a concurrent agent edit (a builder publishing a version mid-settlement) cannot lose the update.

## 9. API surface

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/tasks/{id}/review` | `{rating 1-5, reviewText?}`. Requires `RESOLVED`+`ACCEPTED`, caller-owned, not owner-of-agent, no existing review. |
| `GET` | `/api/agents/{agentId}/reputation` | Builder-owned: the two components, sample counts, recent event stream. |

Existing endpoints unchanged. `GET /api/agents/{agentId}/reviews` and the builder-response `PUT` already
exist and keep working; their data is simply no longer fabricated.

## 10. Where the logic lives

`domain/biz/reputation/` already exists (`ReviewModel`, `ReviewRepository`), so this extends it rather than
opening a subdomain.

- **domain** — `ReputationEventModel`, `ReputationEventType`, `ReputationPolicy` (record),
  `ReputationScoringDomainService` (+ `impl/`, framework-free, wired in `DomainServiceConfig`),
  `ReputationEventRepository`
- **application** — `ReputationWriteAppService` / `ReviewAppService` (interface + `impl/`, per the repo's
  service-layer convention)
- **repository** — `ReputationEventDO` / `…JpaRepository` / `…RepositoryImpl`; `AgentRepository` gains the
  targeted reputation update
- **controller** — the review write on `TaskController` (task-scoped); the breakdown read on `AgentController`

Per Invariant #3's spirit, all scoring arithmetic is **pure domain code** — the app layer orchestrates,
persists, and enforces ownership only.

## 11. Frontend

- **Client, task view** — an optional rating prompt on the accept flow (inline, for capture rate) backed by a
  distinct endpoint so it can also be left later. No deadline.
- **Client, agent profile + shortlist** — **replace** `rep 87.5` (`/client/agents/[id]:68`) with plain-language
  facts: *"Completed 38 of 40 tasks successfully · ★4.7 (12 reviews)."* A raw 0–100 composite is
  uninterpretable on a storefront, and showing `Satisfaction 73` next to `★4.7` publishes two numbers that
  appear to contradict each other (shrinkage explains the gap; nothing on the page does).
- **Builder portal** — the full breakdown: Reliability / Satisfaction with sample counts, and the event stream
  rendered as *"68, because 20 accepted tasks and 20 one-star ratings."* This is where the mechanics belong —
  the builder's income depends on the difference between "you're flaking" and "people don't love your output."
- **Empty states** wherever the fabricated seeds used to render.

## 12. Testing approach

- **Domain unit tests** — the scoring function against the §5 table; cold start = exactly 50.00; monotonicity;
  the volume-converges-to-rate property; the recovery table.
- **Emission tests** — one per site in §6, asserting both the event written *and* the recomputed score.
  Explicitly cover `D_CHANGED_MIND` (writes `REJECTED` but must emit nothing) and `CANCELLED`.
- **L1 test** — owner-as-client books their own agent; assert **zero** events and a rejected review.
- **Reconciliation test** — replay `reputation_events` and assert the `agents` aggregates agree.
- **Append-only test** — `UPDATE`/`DELETE` on `reputation_events` raises, mirroring the `ledger_entries` tests.
- **Four existing test files** (`CatalogueQueryDaoIntegrationTest`, `ReviewRepositoryIntegrationTest`, and the
  two storefront/catalogue controller tests) insert reviews without a `task_id` and must supply one.
- **Frontend** — vitest for the rating prompt and the breakdown; `npm run lint` is part of the gate
  (CI enforces eslint, not just vitest + build).

## 13. Expected behaviour change

The catalogue's `hot_score` includes `a.reputation_score * 0.5` (`JdbcCatalogueQueryDao:45`). Reputation
currently contributes a flat `25` to every agent; afterwards it varies `0–50`. **The "hot" sort will genuinely
reorder.** Intended, but it will be visible in the demo and in `CatalogueQueryDaoIntegrationTest`.

A fresh demo database shows every agent at exactly `50.0` with no reviews until tasks are actually run.
`demo-runbook.md` already stands up the full stack with a stub agent, so the demo can *earn* its numbers live —
a stronger thing to show an examiner than pre-baked figures that invite "where did those come from?"

## 14. Out of scope / future

Time decay; stake-weighted events; Sybil defence beyond same-account; per-version reputation; builder-level
(as opposed to agent-level) reputation; SSE push of score changes; reputation-based tiering or badges.

## Appendix — decision ledger

Grilling session 2026-08-12. Thirteen questions; the model was **reworked once**, at Q5, when the
silence-is-perfection pathology was identified — a single blended stream ranked a bad-work-silent agent above
a good-work-4★ one under *both* candidate resolutions (supersede and additive), which meant the fault lay in
the shared premise rather than in the choice between them. The two-component split is the repair.
`CONTEXT.md` gained a **Reputation** section: `Reputation`, `ReputationEvent`, `Reliability`, `Satisfaction`,
`Rating`, `Review`.
