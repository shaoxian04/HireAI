# Routed-submit spec compatibility — design in progress

**Status:** grilling in progress, no code written. Started 2026-08-11 via `/mattpocock-skills:grill-with-docs`.

## How to resume this in a fresh session

```
/mattpocock-skills:grill-with-docs continue the design in
docs/superpowers/specs/2026-08-11-routed-spec-compatibility-design.md —
pick up at the first unresolved open question
```

Everything under **Verified facts** was read out of the code in this session and should be trusted
without re-deriving; several items contradict what the docs imply, so re-check only if the code has
moved. Everything under **Open questions** is undecided and is what the grill should walk.

---

## The problem

On the **open/routed** submit path (`POST /api/tasks`) the *client* authors the task's binding
output spec. The matcher then selects an agent on **capability category + price only**. So an agent
can be dispatched a contract it never agreed to, fail automated validation, and land in
`SPEC_VIOLATION` — earning nothing, having burnt real compute, with the failure counted in the stats
that feed the matcher.

The harm is asymmetric. The client is protected (escrow auto-refunds, they lose only time). The
builder absorbs the entire cost of a matching decision **the platform** made.

**Direct booking has no such problem** — the task adopts the agent's own declared spec at creation,
so the contract is agreed by construction.

---

## Verified facts (read from code, 2026-08-11)

### The two submit paths differ in who authors the contract

- `POST /api/tasks` → `SubmitTaskRequest` carries `outputSpec {format, schema, acceptanceCriteria}`.
  Client-authored, frozen onto the task at submit (Hard Invariant #4).
- `POST /api/tasks/direct` → `DirectBookRequest` has **no spec field at all** (title, description,
  budget, agentId). `DirectBookingAppServiceImpl.book()` copies `agent.currentVersion().outputSpec()`
  onto the task.

### The human UI never authors a spec

`frontend/app/client/tasks/new/page.tsx` collects category + budget, calls
`GET /api/tasks/match-preview`, opens the shortlist modal, and books the pick via `POST /api/tasks/direct`.
There is **no output-spec form on the client submit path**. So the client-authored spec exists only
on the routed path, which in practice is the **machine** channel (MCP / API-key callers).

### What MCP actually sends on the routed path

`mcp/app/client.py::submit_routed` → `{"format": output_format, "schema": schema, "acceptanceCriteria": ...}`
where `server.py::submit_task` defaults `output_format="TEXT"` and never populates `schema`
(its `output_spec` tool parameter maps to **acceptanceCriteria**, not to `schema`). So the typical
routed contract today is *"return some non-empty text."*

### Only `format` is required, and strictness is a spectrum

`OutputSpec` (domain) validates **only** that `format` is non-null. `schema` and `acceptanceCriteria`
are optional free-form strings, `@Size(max = 5000)`, with no structural validation at the boundary.

`ValidationDomainServiceImpl.validate` checks format only:

| Client sends | Machine-enforced |
|---|---|
| `TEXT` | payload non-blank |
| `JSON`, no schema | parses as JSON (`SCHEMA_SKIPPED`, passes) |
| `JSON` + prose in `schema` | parses as JSON; prose ignored (`SCHEMA_SKIPPED`, passes) |
| `JSON` + real JSON Schema | parses **and** conforms (`SCHEMA_MATCH`) |
| `FILE` | `resultUrl` is an HTTPS URL |

`NetworkntSchemaValidator` (JSON Schema 2020-12) **skips rather than fails** when the schema is
blank, unparseable, or not a JSON object. `acceptanceCriteria` is never machine-checked — it reaches
only the arbitrator, during a dispute.

**Consequence:** the unfairness surface is narrow — a **format** mismatch, or a **real JSON Schema**
the agent never agreed to. Prose in `schema` can never unfairly fail anyone.

### The routing path, as built

`RoutingAppServiceImpl.route(taskId)`:

1. `agentRepository.findActiveCandidates(category, budget)` — SQL: agent `ACTIVE`, row is the agent's
   current version, `capability_categories && ARRAY[category]`, `price <= budget`.
2. `routingMatchDomainService.selectOne(view, candidates)` — weighted score, then epsilon-greedy.
3. Empty → `markAwaitingCapacity` → re-match sweeper 3× at 10s → `CANCELLED` + full refund.
4. Winner → `assignAndQueue` (stamps `execution_deadline`) → publish dispatch. The payload carries the
   **task's** spec, deliberately not re-read from the agent version (Invariant #4).

### Both sides of a compatibility check are already in memory

- `TaskRoutingView` already carries `outputSpecJson` (fetched for the dispatch payload).
- `AgentCandidate` already carries the agent's declared `outputSpec` (the candidate SQL selects
  `v.output_spec`).
- **Gap:** `selectOne` takes `MatchCriteria`, which exposes only `category()` and `budget()`. The seam
  was narrowed deliberately so the task-less frontend match-preview could reuse the scorer
  (`PreviewCriteria`). Adding a spec filter means widening that seam and deciding what preview passes.

### Routed vs direct-booked is distinguishable after the fact

`TaskWriteAppServiceImpl.submitDirectlyBooked` calls `taskRepository.pinAgentVersion(taskId, …)`;
routed tasks are never pinned. (Needed if the "don't penalise the builder" safety net is built.)

### Where the failure currently lands on the builder

The candidate SQL's `sample_count` subquery counts
`RESOLVED, FAILED, TIMED_OUT, SPEC_VIOLATION` — so a spec violation feeds the matcher's exploration
term `1/(1 + sampleCount)`, and the builder stats endpoint's success rate.

---

## Decisions locked

**D1 — Fix it at the routing decision (option A).** Filter the candidate set to agents whose declared
spec can satisfy the task's spec, *before* scoring. Rejected alternatives:

- **B — rebind the task to the winner's spec.** Inverts the unfairness: the client becomes bound to
  terms they never saw. Breaks the frozen-at-submit property validation and arbitration both rely on.
- **C — delete client-authored specs, category + budget only.** Removes the machine client's ability
  to demand a shape, which is the whole reason a programmatic caller uses the routed path.
- **D — leave routing alone, only stop counting the failure against the builder.** Removes the
  penalty without removing the cause, *and* deliberately denies the matcher the only signal that
  would steer it away — so the same agent wins the same bad match on the next identical task. Viable
  only as a safety net *underneath* A (see Q4).

---

## Open questions

### Q1 — Should an unparseable `schema` be rejected at submit? *(asked, not answered)*

Today it is accepted silently and then silently ignored at validation: the client believes they set a
constraint the platform never enforces, and the only trace is a `SCHEMA_SKIPPED` check nobody reads.

- **a.** Leave it — permissive submit, skip on unparseable.
- **b.** Reject at submit: `format = JSON` + non-blank `schema` must parse as valid JSON Schema, else 400.
- **c.** Accept but surface a warning on the task that the schema isn't machine-checkable.

**Recommendation: b.** A silently-ignored constraint is worse than none; it's a cheap boundary check
at the only moment the client can fix it, and it makes "the task's schema is real" an invariant the
compatibility filter can rely on. Nothing in-tree breaks (MCP puts prose in `acceptanceCriteria`, and
leaves `schema` empty).

### Q2 — Multi-format specs *(raised by the user, unexplored — SCOPE EXPANSION)*

Idea: let the client pick **several acceptable formats** on submit (a dropdown, mirroring the agent
registration form), e.g. "JSON or TEXT both fine."

This is a *widening* move that attacks the mismatch from the client side rather than the routing
side, and it composes with D1 rather than replacing it: a wider declared acceptance set means a
larger eligible candidate pool, which directly mitigates the starvation risk in Q3.

Unresolved sub-questions:
- Does the agent side become multi-format too, or stay single? (`AgentVersionModel` declares one
  `OutputSpec`; agent registration reuses the same `OutputSpecFields` component as… nothing on the
  client side, since the client form doesn't exist — see Verified facts.)
- If the client accepts JSON-or-TEXT, which format does *validation* enforce — whichever the agent
  declared and was matched on, or "any of the accepted set"?
- Does the dispatch payload tell the agent which format was expected of it?
- Schema is per-format; does a multi-format spec carry a schema per JSON branch?
- Data-model impact: `output_spec` is a single JSONB object on `tasks`. Multi-format means either an
  array-shaped spec or a new column — first real migration in this effort.

### Q3 — What does "can satisfy" mean?

The compatibility rule itself. Easy case: TEXT can't satisfy JSON. Hard case: same format, different
JSON Schema — schema subsumption is undecidable in general. Candidate pragmatic rules:

- format equality only (cheapest; catches the common case)
- format equality + agent's declared `required` fields ⊇ task's `required` fields
- format equality + full subsumption attempt with a conservative fallback

Also: what if the **agent** declares no schema and the task declares one? Eligible or not?

### Q4 — Behaviour when nothing is eligible

Under D1, a task whose spec nothing can satisfy falls to `AWAITING_CAPACITY` → 3 sweeps → `CANCELLED`
+ full refund. Is ~30s of silence then a cancel the right client experience, or should an
unsatisfiable spec fail fast at submit (before escrow freezes) with "no agent serves this contract"?

Note the money outcome is identical to today; only the wasted builder compute differs.

### Q5 — Is the D safety net worth building alongside A?

Exclude `SPEC_VIOLATION` on **routed** (unpinned) tasks from the builder's stats and from
`sample_count`. Catches whatever slips past the Q3 rule. Cost: a second concept of "failure that
isn't the builder's fault" to keep consistent once Module 5 reputation lands.

### Q6 — What does match-preview pass?

The frontend preview has no task and therefore no spec. Options: preview stays unfiltered (and can
therefore show agents that a real submit would reject), or `PreviewCriteria` gains an optional spec
that the preview UI populates once a client-side spec form exists (which today it doesn't — see Q2).

---

## Constraints this must not violate

- **Hard Invariant #4** — the declared output spec is the binding contract; frozen at submit, used by
  both validation and arbitration. Rules out option B.
- **Hard Invariant #1** — escrow freezes before dispatch. Any "fail fast at submit" answer to Q4 must
  land *before* the freeze, not unwind it.
- **Invariant #3** — the money path stays deterministic and domain-owned.
- `CONTEXT.md` has **no entry yet** for `OutputSpec`, `AgentVersion`, or `Task`. If the grill sharpens
  what "output spec" / "compatible" / "declared contract" mean, those terms belong in the glossary —
  vocabulary only, no implementation detail.
