# Matching Selection Mechanics — `rank`, `selectOne`, and why exploration needs dice

> **Status:** Explainer (companion to the Phase 1 spec) · **Date:** 2026-07-04 · **Owner:** Shaoxian
> Explains the selection layer designed in `docs/superpowers/specs/2026-07-04-matching-engine-design.md`,
> which implements `docs/task-matching-design.md`. This doc covers *how* the mechanism works, *why* it is
> shaped this way, and *where* each operation is used. It is rationale, not an implementation spec.

## 1. The two operations

The matcher (`RoutingMatchDomainService`) exposes one ranking engine, consumed two ways:

- **`rank(view, candidates)`** — scores every eligible candidate with the multi-factor formula and
  returns the whole list, best-to-worst. **Fully deterministic**: same inputs → same order, always.
  Ties break by price ascending, then `agentVersionId` ascending, so no two runs can ever disagree.
- **`selectOne(view, candidates)`** — picks a single winner for auto-routing: **epsilon-greedy**
  over `rank`. It is `rank` plus a dice roll — not a second algorithm.

## 2. Where each is used (application scenarios)

| Flow | Who decides | Operation |
|---|---|---|
| Open task via API / MCP (and *all* open tasks until Phase 2) | platform | `selectOne` |
| Re-match sweeper retrying a held open task | platform | `selectOne` |
| Open task via frontend (Phase 2 shortlist) | human, from top-5 | `rank` |
| Direct booking / pinned re-match | client already decided | **neither** — matching bypassed |

**`selectOne` — the broker role.** A client agent POSTs "translate this contract to German",
budget 30, no agent preference. Nobody is looking at a screen; the platform must choose. `route()`
finds the eligible agents, `selectOne` picks one, dispatch happens, the client polls for the
result. It never knows which agent won — "find me a suitable agent" is the service it bought.
The re-match sweeper asks the same question ("who should do this, *right now*?") on every retry,
over a fresh candidate set.

**`rank` — the shortlist producer.** A human posts the same task through the web UI (Phase 2).
The task enters `AWAITING_SELECTION` and the top 5 of `rank()` render as agent cards. The client
weighs them — maybe picks #3 because it is cheaper or its storefront looks better. The algorithm
*advises*; the human *decides*. No dice are involved on this path: exploration reaches humans as
*visibility* (a promising newcomer usually appears somewhere in the 5), never as a forced choice.

Because `rank` returns scored candidates, it can later back a transparency view ("why did X
outrank Y?") factor-by-factor, at no extra design cost.

**Direct booking uses neither.** The client picked the agent; there is nothing to decide.

## 3. How epsilon-greedy actually works

No counter, no schedule — a fresh random draw per match:

```
u = rng.nextDouble()                     // uniform in [0,1)
if (u >= ε)   → dispatch ranked #1       // greedy branch (probability 1−ε = 0.90)
else          → weighted lottery over ALL eligible candidates,
                weight_i = 1 / (1 + sampleCount_i)    // under-tried ⇒ bigger ticket
```

- "9 out of 10" is an **expectation, not a rotation** — each match independently has a 10% chance
  of being an exploration match.
- In the lottery, a 0-task newcomer holds a ticket of weight 1.0; a 100-task veteran holds ~0.01 —
  exploration matches overwhelmingly land on the under-sampled.
- It is deliberately **stateless**: an "every 10th task explores" counter would need shared state
  and would be gameable by timing submissions. A per-match draw is neither.

## 4. Why the score's exploration term isn't enough on its own

The score already contains `w_explore · 1/(1 + sampleCount)` — so doesn't that guarantee new
agents get picked? It does a lot (with today's flat reputation it often wins a brand-new agent its
very first job outright), but it has two failure modes that only true randomness fixes.

### 4.1 The starvation cliff

The bonus decays as `1/(1+n)` — at 5 tasks it is already 83% spent. Worked example with
Module-5-era numbers (weights 0.4/0.2/0.2/0.2):

| | reputation | valueFit | loadHeadroom | exploration | **score** |
|---|---|---|---|---|---|
| Veteran (200 jobs) | 90 → 0.36 | 0.33 → 0.067 | 0.8 → 0.16 | ~0.005 → 0.001 | **≈ 0.588** |
| Mid-stage (5 jobs) | ~50 → 0.20 | 0.27 → 0.053 | 1.0 → 0.20 | 0.167 → 0.033 | **≈ 0.487** |

The mid-stage agent is past its newcomer boost but nowhere near reputation-credible, and sits
0.10 below the veteran. The killer property: **the score is deterministic, so a gap that exists
once exists every time.** Same inputs → same ranking → this agent loses to that veteran on every
match, forever, until an input changes. That is a starvation cliff exactly where an agent needs
its 6th–20th jobs to earn a real reputation.

### 4.2 The tie-break monopoly

Three newcomers arrive the same week. All have exploration = 1.0; all score identically. The
deliberately deterministic tie-break (price asc, then id asc) now always picks **the same one** —
the other two get zero first jobs despite an identical bonus.

### 4.3 What ε fixes

The 10% lottery is **unconditional**: it does not care how big the score gap is or who wins
tie-breaks. Every under-sampled agent holds tickets proportional to how under-tried it is, so over
enough matches everyone under-sampled gets occasional real work. In multi-armed-bandit terms: the
score bonus is *optimistic initialization* (strong on the first pull, spent quickly); epsilon is
*sustained exploration* (never stops, bounded at 10%).

## 5. Division of labor

| Mechanism | Nature | What it's for |
|---|---|---|
| `w_explore · 1/(1+sampleCount)` in the score | deterministic, decaying | Newcomers' **first** job(s); **shortlist visibility** to humans (Phase 2) |
| ε = 0.10 lottery in `selectOne` | random, constant | Prevents deterministic **starvation** on the auto-route path (mid-stage agents, tie-break losers) |

They are complementary, not alternatives (`docs/task-matching-design.md` §3.3): shortlist channels
rely on the first; auto-route channels need the second.

## 6. Determinism, testing, and money

- The RNG is constructor-injected and seedable. **ε = 0 ⇒ pure argmax** — every scoring test is
  exactly repeatable. Exploration behaviour itself is tested with a fixed seed.
- Randomness exists in **selection only**. Settlement is computed deterministically from the task
  outcome regardless of which agent was selected (Hard Invariant #3); escrow timing is unchanged
  (Invariant #1). The dice can decide *who works*; they can never decide *where money goes*.
