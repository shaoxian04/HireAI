---
status: accepted
---

# Matching filters on a coarse category list and a relevance floor, then weighs relevance below reputation

**Status:** decided, not built (#52). Supersedes the **0.40** reputation weight that
[ADR 0003](./0003-two-component-shrinkage-reputation.md) quotes, and the free-form categories that
[ADR 0004](./0004-launch-vertical-document-grounded-extraction.md) describes.

Matching used to filter on free-text category tags plus budget and nothing else, so a typo meant "no
agent" and the task text never reached the matcher. We split capability matching into two layers.
First, a **coarse, platform-owned category list** (roughly 10–15 buckets plus `other`, changed only by
migration) acts as a hard filter. Second, inside that bucket, **embedding similarity** between the task
text and the agent's profile (name, tagline, description) does the fine-grained work. Similarity is
used twice: a **raw-cosine floor τ** makes clearly irrelevant agents ineligible, and among the agents
that pass, relevance is a **weighted factor (0.25), deliberately below reputation (0.30)**.

## Considered options

- **Relevance as the largest weight.** Rejected. Once the floor has removed incapable agents, a track
  record earned from platform-witnessed outcomes should beat a slightly closer description, and
  descriptions are builder-written and can be keyword-stuffed. Reputation stays the marketplace's one
  consequential quality number, as the glossary defines it.
- **Relevance as a pass/fail gate only.** Rejected: it throws away the ordering signal among the agents
  that are all plausibly capable.
- **A fine-grained or builder-extensible category list.** Rejected: fine-grained taxonomies reject niche
  agents, and free text brings back the typo problem. Embeddings cover the niches. New buckets are added
  from evidence (an optional "suggest a category" field, and clusters inside `other`).
- **An LLM re-rank of the top candidates.** Deferred: 1–3s and thousands of tokens on the submit path,
  plus a prompt-injection surface, with no evidence yet that embeddings fall short.

## Consequences

- **The floor uses raw cosine, never the normalised score.** Relevance is min-max normalised across the
  agents that pass the floor, so the best one always scores 1. That makes the normalised score unable to
  say "all irrelevant", so it can't serve as the floor.
- **τ is model-specific and starts as a guess.** It must be configurable, start low, and be tuned on a
  test set of task and agent pairs. Every vector records its model, and changing the model means
  re-embedding and re-tuning τ.
- **Matching never depends on the embedding provider being up.** If the task vector fails, the floor and
  the relevance term are skipped. An agent with no vector passes the floor and scores a neutral 0.5.
- **One matcher, two uses.** The web shortlist and routed submit share the same eligibility (category,
  listing, budget, client deadline, floor) and the same score. Only routed selection adds the ε lottery.
  The shortlist therefore needs the task text, so match-preview becomes a `POST`.
- **Ratings now control less of the score:** `0.3 × 0.30` = 9% of the total, down from 12% in ADR 0003.
- **The category list must include the launch vertical** (document extraction, ADR 0004) as its own
  bucket from day one.
- **Task text now also goes to the embedding provider** (OpenAI, the same provider the arbitrator
  already uses).
