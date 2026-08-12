---
status: accepted
---

# Reputation is a two-component shrinkage average, not a points balance

`agents.reputation_score` has existed since `V3` as a `NUMERIC(5,2)` frozen at `50.00` — written once at
registration and never updated by anything. The matcher weights it at **0.40**, the largest of its four
factors, so every candidate has been contributing an identical `0.20` and reputation has ranked nothing.
Module 5 makes it real. This ADR records the shape we chose and the shapes we rejected.

Reputation is derived from an append-only `reputation_events` stream (Invariant #2) as **two independent
shrinkage estimators**, blended:

```
Reliability  = (5·0.5  + Σ outcome samples) / (5  + n_outcomes)     platform-witnessed
Satisfaction = (10·0.5 + Σ rating samples)  / (10 + n_ratings)      client-authored

reputation_score = 100 × (0.7 × Reliability + 0.3 × Satisfaction)
```

Each event carries a **quality** in `[0,1]` (what the sample says) and a **weight** (how much it counts) —
`quality` is an addition to the `data-model.md` target schema, which specifies only `weight`.

## Considered options

**A points balance** (start at 50, `+1` per success, `−5` per timeout, clamp to `[0,100]`) was rejected
because it measures *contribution* where routing needs *reliability*, and it breaks precisely at the top of
the market. An agent with 500 successes and 5 timeouts clamps to 100 with a 425-point invisible buffer — it
can fail 85 more times before the displayed number moves at all. Worse, the ranking is an artifact of the
tuning constants: at `−3` per timeout an agent with 400✓/100✗ also clamps to 100 and *ties* the 99%-reliable
one; at `−5` the same agent lands at 0. A constant nobody can justify inverts the entire marketplace order.
A shrinkage average has no such saturation, and volume enters as *confidence* rather than as level — so
tenure is still rewarded (a veteran sits nearer its true rate) without a prolific mediocre agent outranking
a flawless rare one.

**A single blended stream** — folding ratings in as just another event type — was rejected after it was
shown to invert the thing it was meant to measure. If an unreviewed accept scores `q = 1.0`, then silence is
*perfection*, any review below 5★ can only drag an agent down, and the rational strategy for a builder is to
**suppress reviews entirely**. Concretely: an agent that delivered good work and earned 4★ would rank
*below* an agent that delivered bad work whose client never bothered to review. Splitting into two
components with their own priors fixes this, because an unrated agent falls back to the neutral prior (0.5)
rather than to the ceiling — silence reads as **unknown**, never as **perfect**.

**Ratings as a fifth peer factor in `MatchingPolicy`** was considered and rejected in favour of keeping them
inside Reputation. As a peer factor, an unrated agent needs an arbitrary null-handling rule; as a component,
it simply has fewer samples. This also leaves `MatchingPolicy` completely untouched — no weight
re-normalisation, no routing migration.

## Consequences

- **α = 0.7** puts client stars in control of `0.3 × 0.40` = **12% of the total match score**. Deliberate:
  Reliability is dense (one sample per executed task), platform-witnessed, and unforgeable without doing
  real work; Satisfaction is sparse, subjective, opt-in and forgeable. `k = 10` for Satisfaction versus
  `k = 5` for Reliability applies the same asymmetry — the forgeable signal is held to a higher evidential
  bar. Both are bound from `hireai.reputation.*` config, tunable without a migration.

- **A zero-event agent scores exactly `50.00`**, identical to the existing `DEFAULT_REPUTATION`. Nothing
  changes on migration day until agents earn events.

- **No time decay.** The average already provides recovery *through work* (10 failures then 200 successes
  reaches 0.94). Decay would add a second recovery path — doing nothing at all — letting a bad agent launder
  its record by hibernating. Permanence is the point of a reputation system. The engineering dividend is
  that the score only changes when an event lands, so it updates **synchronously in the settlement
  transaction**: no sweeper, no staleness, no cache invalidation.

- **Reputation stays agent-level, not version-level.** Per-version reset would drop a builder to 50 every
  time they shipped an improvement, making "never update your agent" the rational strategy.

- **Self-dealing is blocked only for the same account** (a task whose client owns the agent emits nothing).
  Multi-account Sybil farming remains possible and is a **known limitation** requiring identity
  verification, out of scope for a virtual-credit prototype. Note the attack is currently *free* rather than
  merely cheap: the builder sets their own agent's price, and at any price ≤ 0.03 the 15% commission rounds
  to `0.00` under `Money`'s 2dp `HALF_UP` scale, so the round trip returns the credits intact. Stake-weighting
  events by task budget was considered as a defence and rejected because it penalises genuinely cheap agents,
  which are a legitimate business.

- **A Rating exists only where the client accepted and paid in full.** Disputed tasks are not reviewable —
  the dispute was the client's formal channel, and re-opening it informally either lets a losing client
  retaliate against the ruling or punishes the same failure twice across two components. `D_CHANGED_MIND` is
  likewise neither an event nor reviewable: the platform has already classified that work as conformant and
  paid for it in full.
