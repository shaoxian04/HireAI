# Shortlist Selection (Frontend Match → Pick → Book) — Design Spec (Phase 2)

> **Status:** Approved design · **Date:** 2026-07-10 · **Owner:** Shaoxian
> Phase 2 of the matching roadmap. Builds on Phase 1 (merged to `main`, PR #20, `894d1d0`):
> the scored matcher `RoutingMatchDomainService.rank`/`selectOne`, candidate enrichment, and the
> reliability sweepers. Delivers the **frontend shortlist** promised in
> `docs/superpowers/specs/2026-07-04-matching-engine-design.md` §10.
> Companion phases: 3 = programmatic spine (API keys), 4 = push webhooks, 5 = MCP facade.

## 1. Summary

Turn frontend open-task submission from **"auto-route to the single best agent"** into
**"match → show the client a shortlist → the client picks."** The client fills a task form, we
return a ranked shortlist of bookable agents (plus a few near-misses just above budget), and the
client selects one — at which point the task is created, escrow is frozen, and dispatch happens.

The pivotal design choice (agreed during brainstorming): **the shortlist is a stateless read;
picking is the existing direct booking.** No task and no escrow exist until the client commits, so:

- **No new task state** (`AWAITING_SELECTION` is *not* introduced), **no abandonment sweeper**,
  **no Flyway migration**, and **the money path is untouched**.
- The half-filled form lives in the browser (`localStorage`), so editing / reloading / re-searching
  never loses the client's work and never leaves an abandoned row in the database — the concern that
  drove us away from persisting drafts.

This supersedes the §10 sketch (which assumed a persisted `AWAITING_SELECTION` task + an abandonment
refund sweeper). The end state is the same UI; the mechanism is simpler and cheaper.

## 2. Scope

**In:**
- A read-only **match-preview** endpoint + query + application read service that reuses the Phase-1
  domain `rank()` for ordering — **no new matching logic**.
- **Shortlist** (in-budget, top-5, `rank()`-ordered) and **near-miss** (above-budget, 3 cheapest,
  price-ascending) lists, both **selectable**.
- Frontend: task-submit page reworked into **form → find agents → shortlist → confirm → book**, with
  a `localStorage` form draft.
- **Pay-the-agent's-price** pricing (see §5), achieved with zero money-path change.
- A minimal `MatchCriteria` domain seam so the matcher no longer requires a full task view (see §6.5).

**Out (deferred):**
- The auto-route "pick for me" frontend button (auto-route stays dormant, reserved for Phase 3).
- Multi-factor ranking of the **near-miss** list (near-miss is price-ordered only, by decision).
- Persisted drafts / cross-device resume / a "notify me when an agent appears" waitlist.
- Builder-editable `maxConcurrent` (separate small follow-up).
- Semantic matching; API keys / programmatic anything (Phase 3+); push webhooks (Phase 4);
  reputation updates (Module 5).

**Channel note:** this phase changes only the **frontend** open-task flow. Direct booking (the
storefront "hire this agent" button) is unchanged. The Phase-1 auto-route path (`POST /api/tasks` →
`selectOne` → `AWAITING_CAPACITY`) stays in the backend, fully tested, with **no live caller** until
the Phase-3 programmatic channel — the scorer itself is still exercised, because the shortlist
ordering **is** `rank()`.

## 3. Decisions ledger

| Decision | Choice | Why |
|---|---|---|
| Selection model | **Preview → pick → book** (stateless preview; task+escrow created at pick, via existing direct booking) | Eliminates abandoned-draft rows and money-held-during-browsing; maximal reuse; no new state/sweeper/migration |
| Draft persistence | **`localStorage` only** (same browser/session) | Meets "don't lose my work + edit and re-search" without DB rows; cross-device resume is YAGNI for the demo |
| Pricing | **Pay the chosen agent's price** — pick passes `budget := agent.price` to `/api/tasks/direct` | Cheaper agent → client actually pays less, so `valueFit` is real money; zero money-path change |
| Budget meaning | **A discovery filter ("show agents up to this rate")**, not a fixed charge | The charge is the picked agent's price; the near-miss list lets the client see the price just above the filter |
| Shortlist ordering | **Phase-1 `rank()`** (reputation · valueFit · loadHeadroom · exploration), top-5 | One ranking engine; new agents still surface via the exploration term (cold-start fairness) |
| Near-miss ordering | **Price ascending, 3 shown** | Legible price range → informs a budget bump; kept deliberately simple (not multi-factor) |
| Near-miss selectable | **Yes** — Select books at that agent's (higher) price with an explicit above-budget confirm | Matches §10's "direct-booking links" intent; convenience over forcing a budget edit |
| Bookable filter | **ACTIVE + listed** (`a.status='ACTIVE' AND p.is_listed`) | Exactly what direct booking requires; never show an agent the client can't book |
| Output spec | **Adopted from the agent on booking** (client authors none) | Direct booking already adopts the agent's `output_spec` (Invariant #4); simplifies the form |
| `AWAITING_SELECTION` state | **Not introduced** | No persisted pre-pick task exists to hold |
| Matcher input type | **Extract `MatchCriteria` (category+budget)**; `TaskRoutingView implements` it | Preview has no task to project; source-compatible widening keeps all Phase-1 callers/tests green (§6.5) |

## 4. The flow

```
Client fills form: title, description, category, budget
      │
      ▼
GET /api/tasks/match-preview?category=&budget=      ← read-only; NO task, NO escrow
      │  { shortlist:[…≤5], nearMisses:[…≤3] }
      ▼
Shortlist screen (form draft saved to localStorage)
   ├─ In budget      → Select ─┐
   ├─ Above budget   → Select ─┤   (near-miss: explicit "above your budget" confirm)
   └─ Empty state    → edit & re-search
      │                        │
      ▼                        ▼
Confirm step (price · escrow · balance; top-up if short)
      │
      ▼
POST /api/tasks/direct { title, description, budget := chosenAgent.price, agentId }
      │  (existing path: ACTIVE+listed check → assertAffordable → adopt agent spec →
      │   atomic submit + escrow freeze + pinned dispatch)
      ▼
Existing task-detail pipeline (poll → result → accept/reject) — UNCHANGED
```

**Key property:** the first database write for a task is the committed direct booking. Everything
before it is a read plus browser-local form state.

## 5. Pricing model

Today direct booking freezes and settles on whatever `budget` is passed, requiring
`budget ≥ agentVersion.price` (`AgentVersionModel.assertAffordable`); on accept the builder receives
`SettlementPolicy.netOf(budget)` (budget − 15%). The agent's `price` is a floor, not the charge.

Phase 2 uses this as-is: **the pick passes `budget := chosenAgent.price`.** Then:
- **In-budget pick:** the client pays that agent's price (≤ their filter). Picking a cheaper agent
  saves them money — `valueFit` becomes a real signal, not cosmetic.
- **Near-miss pick:** `price > filter`; the client pays the higher price. The confirm step states
  this plainly and the wallet freeze enforces sufficient balance (top-up prompt if short).

No settlement, escrow, or schema change. The typed **budget is a filter**; the **charge is the
picked agent's price**.

## 6. Backend design

All read-side. Three thin pieces plus DTOs; the domain scorer and direct booking are reused verbatim.

### 6.1 Query — `MatchPreviewQueryPort` + `JdbcMatchPreviewQueryDao`
A new application-layer port (mirroring `CatalogueQueryPort`) with one JDBC DAO beside
`JdbcCatalogueQueryDao`. One query returns **all bookable candidates for the category** — the
catalogue-card projection **plus** the scorer's per-agent metrics — with **no price filter**, so one
round-trip feeds both lists:

```sql
SELECT a.id                    AS agent_id,
       v.id                    AS agent_version_id,
       a.name                  AS agent_name,
       p.tagline               AS tagline,
       p.logo_url              AS logo_url,
       a.reputation_score      AS reputation_score,
       v.capability_categories AS capability_categories,
       v.price                 AS price,
       v.webhook_url           AS webhook_url,
       v.max_execution_seconds AS max_execution_seconds,
       v.output_spec::text     AS output_spec_json,
       v.max_concurrent        AS max_concurrent,
       (SELECT COUNT(*) FROM tasks t
          JOIN agent_versions av ON av.id = t.agent_version_id
         WHERE av.agent_id = a.id AND t.status IN ('QUEUED','EXECUTING'))          AS in_flight,
       (SELECT COUNT(*) FROM tasks t
          JOIN agent_versions av ON av.id = t.agent_version_id
         WHERE av.agent_id = a.id
           AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION'))     AS sample_count
FROM agents a
JOIN agent_profiles p ON p.agent_id = a.id
JOIN agent_versions v ON v.id = a.current_version_id
WHERE a.status = 'ACTIVE'
  AND p.is_listed
  AND v.capability_categories @> ARRAY[:category]::text[]
```

- **`is_listed` is the material difference from `findActiveCandidates`** — the routing query omits it
  (auto-route ignores listing), which would let the client pick an unbookable agent.
- Category is lowercased before binding (same normalization as the catalogue DAO and the matcher).
- Bounded naturally by category; the `idx_tasks_agent_version_status` index (V24) keeps the two
  counts as range scans. If this ever shows in a slow-query log, the same projection-swap seam noted
  in the Phase-1 spec §5.1 applies.

Row projection `ShortlistCandidateRow` carries every field above (display + scorer metrics).

### 6.2 Application read service — `MatchPreviewAppService`
```
MatchPreview preview(String category, Money budget)
```
No `clientId` parameter: the preview is a public-catalogue read (same bookable-agent projection
storefront browsing already exposes) with no owner-scoped data to filter by — there is no task, no
wallet, nothing belonging to the caller. Invariant #5 (server-side identity) is not engaged because
nothing here is owner-scoped; the endpoint still requires an authenticated caller (§6.3), just not
their identity.

Impl (`@Transactional(readOnly = true)`):
1. `rows = queryPort.findBookableCandidates(category)`.
2. Map each row → a full `AgentCandidate` (for scoring) and keep the row for display, keyed by
   `agentVersionId`.
3. `ranked = routingMatchDomainService.rank(criteria, candidates)` where `criteria` is a
   `MatchCriteria` (category + budget) — see §6.5 → `rank()` self-filters to `price ≤ budget` and
   orders best-first → **take top 5** = shortlist.
4. **Near-miss:** from the same rows, keep `price > budget`, sort by price ascending, **take 3**.
5. Map both to `AgentOption` view objects (join scores/rows back by `agentVersionId`); return
   `MatchPreview(shortlist, nearMisses)`.

`availability` is derived here: `inFlight < maxConcurrent → AVAILABLE`, else `BUSY` (display only —
never blocks selection; direct booking may exceed `maxConcurrent`). `outputFormat` is parsed from
`output_spec_json`.

Interface + `impl/` per the app-service convention; no writes.

### 6.5 Small domain seam — `MatchCriteria`
`rank`/`selectOne` currently take a `TaskRoutingView` — a 5-field *Task* projection — but only read
`category()` and `budget()`. The preview has no task, so it must not fabricate a task-less view.
Extract a minimal input:

```java
public interface MatchCriteria { String category(); java.math.BigDecimal budget(); }
```

- `TaskRoutingView implements MatchCriteria` — **source-compatible**: its record accessors
  `category()`/`budget()` already satisfy the interface, so this is a one-line `implements`.
- `rank(MatchCriteria, …)` / `selectOne(MatchCriteria, …)` **widen** their parameter from
  `TaskRoutingView`. Every Phase-1 caller (the routing app service) and every Phase-1 test passes a
  `TaskRoutingView`, which *is-a* `MatchCriteria` — **nothing else changes, all stay green**.
- The preview passes a tiny `record PreviewCriteria(String category, BigDecimal budget)
  implements MatchCriteria`.

This makes the matcher honestly declare "I need a category and a budget," not "I need a whole task."

### 6.3 Controller
Add to `TaskController`:
```
GET /api/tasks/match-preview?category={c}&budget={b}  → WebResult<MatchPreviewDTO>
```
Thin: validate `category` non-blank and `budget` positive; call the read service directly (no
`clientId` to resolve — see §6.2); wrap. Behind `anyRequest().authenticated()`, the same posture as
every other authenticated endpoint — but no `CurrentUserProvider` lookup is needed, since the result
is not scoped to the caller. `category`/`budget` in the query string are non-sensitive (no personal
data).

### 6.4 DTOs
```
MatchPreviewDTO { List<AgentOptionDTO> shortlist; List<AgentOptionDTO> nearMisses; }
AgentOptionDTO  { UUID agentId; UUID agentVersionId; String agentName; String tagline;
                  String logoUrl; BigDecimal price; BigDecimal reputationScore;
                  String availability;   // AVAILABLE | BUSY
                  String outputFormat; List<String> capabilityCategories; }
```
The raw numeric score is **not** exposed. The pick reuses the existing `DirectBookRequest`
(`title`, `description`, `budget`, `agentId`) — **no new write DTO**.

## 7. Frontend design

Rework the client task-submit page (`frontend/app/client/tasks/new` and its form component; exact
paths pinned in the plan). Reuse the existing UI kit, the `api()` client, and the task-detail page.

1. **Form** — title, description, category, budget. **Remove the output-spec builder** (adopted from
   the agent on booking). Primary action: **Find agents**.
2. **Shortlist screen** (client-side route/step; the form draft is written to `localStorage` on
   change and restored on mount):
   - **In budget** — up to 5 cards ordered by `rank()`: name, price, reputation, `availability`
     ("available"/"busy"), output format, category, **Select**.
   - **Above your budget** — up to 3 cards, price-ascending, each with **Select** and a clear
     "above your Z budget" marker.
   - **Empty state** — no bookable agents in this category → guidance to adjust category/budget.
   - **Edit task** — returns to the form (values intact) to re-search.
3. **Confirm step** — on Select: show "You'll pay **X** (this agent's price), frozen in escrow ·
   balance **Y**"; for a near-miss also show it exceeds the budget filter. On confirm →
   `POST /api/tasks/direct` with `budget := option.price` → redirect to the task-detail page.
   Insufficient balance → top-up prompt (existing wallet flow).
4. **Draft lifecycle** — persist under a client-scoped `localStorage` key; restore on return; **clear
   on successful booking**.

## 8. Error handling

1. **No bookable agents** (both lists empty) → 200 with empty lists → empty-state UI (not an error).
2. **Invalid params** (blank category / non-positive budget) → 400 via bean validation.
3. **Stale shortlist at pick** (agent deactivated / unlisted / superseded between preview and Select)
   → direct booking already returns `NOT_FOUND` / affordability failure; the UI surfaces it and
   invites a re-search. The preview is advisory; booking is the source of truth.
4. **Insufficient balance at confirm** → wallet freeze fails → top-up prompt; no task created.
5. **Read service never throws on data** — empty query result → empty `MatchPreview`.

## 9. Invariants check

| # | Invariant | Status |
|---|---|---|
| 1 | Escrow before execution | ✅ still frozen atomically at booking, before any dispatch; the preview creates nothing. Wording shifts "freeze on submit" → "freeze on booking" (submit is now a read) |
| 2 | Append-only money/audit | ✅ untouched — no new settlement/ledger paths |
| 3 | Deterministic money path | ✅ untouched — settlement still computed from outcome |
| 4 | Output spec binding | ✅ the booked agent's `output_spec` is adopted as the task contract, exactly as direct booking does today |
| 5 | Server-side identity | ✅ `clientId` from JWT on both preview and booking; ownership unaffected |
| 6 | Signed, HTTPS-only I/O | ✅ untouched (dispatch is the existing path) |

## 10. Test plan

Regression bar: all backend + frontend suites stay green. In particular, the Phase-1
`RoutingMatchDomainService` tests must still pass **unchanged** after `rank`/`selectOne` widen to
`MatchCriteria` (they pass `TaskRoutingView`, which now implements it) — proof the seam is
source-compatible.

**Match-preview read service (unit, Mockito over the query port + real `RoutingMatchDomainService`):**
1. Mixed candidates → shortlist holds only `price ≤ budget`, ordered by `rank()`; capped at 5.
2. Above-budget candidates → near-miss holds only `price > budget`, **price ascending**; capped at 3.
3. An agent appears in exactly one list (partition by `price ≤ budget`), never both.
4. `availability` = BUSY when `inFlight ≥ maxConcurrent`, AVAILABLE otherwise.
5. Empty query result → empty shortlist **and** empty near-miss.
6. Fewer than 5 in-budget / fewer than 3 near-miss → returns what exists (no padding).
7. `outputFormat` parsed from the candidate's `output_spec`.

**Query DAO (Testcontainers, Postgres):**
8. Seed ACTIVE+listed, ACTIVE+unlisted, and inactive agents in a category → **only ACTIVE+listed**
   returned (the bookability filter).
9. `in_flight` / `sample_count` computed per-agent across versions (mirrors the Phase-1 count tests).
10. Category match is case-insensitive after normalization; a non-matching category → empty.
11. No price filter — both in- and over-budget agents come back in one call.

**Controller (slice / integration):**
12. `GET /api/tasks/match-preview` returns `{shortlist, nearMisses}` for a valid client + params.
13. Blank category or non-positive budget → 400.
14. No `clientId` is resolved or required: the preview is a public-catalogue read behind
    `anyRequest().authenticated()` only — any authenticated caller gets the same result for the same
    `category`/`budget`, regardless of identity (nothing is owner-scoped).

**Frontend (vitest):**
15. Shortlist zone renders in-budget cards in server order; each has a working **Select**.
16. Near-miss zone renders up to 3 above-budget cards, price-ascending, with the above-budget marker.
17. In-budget **Select** → `POST /api/tasks/direct` called with `budget === option.price`.
18. Near-miss **Select** → `/direct` called with `budget === nearMissOption.price` after the
    above-budget confirm.
19. Empty preview → empty-state UI (no crash).
20. Form fields persist to `localStorage` and restore on remount; cleared after a successful booking.

**End-to-end (Playwright, live stack — backend on Supabase + RabbitMQ + stub agent):**
21. Log in as the seeded client → open the task form → fill title/description/category/budget →
    **Find agents** → assert the shortlist renders with the expected agents.
22. **Select** an in-budget agent → confirm → assert redirect to the task-detail page and that escrow
    equals that agent's price (not the typed budget).
23. Set a budget below every agent's price → assert the near-miss zone shows 3 price-ascending
    options and the empty in-budget zone → **Select** a near-miss → confirm the above-budget notice →
    assert the booking succeeds at the near-miss price.
24. Drive it through to a returned result and **Accept**, confirming the existing pipeline is intact
    end-to-end. Capture screenshots at the shortlist, confirm, and settled steps.

(Playwright is the required E2E tool for this phase, consistent with how Phase 1 was verified — the
`form_input`/controlled-input issues with the older browser tooling do not apply.)

## 11. Non-goals recap

No `AWAITING_SELECTION` state, no abandonment sweeper, no migration, no money-path change, no
auto-route frontend button, no multi-factor near-miss, no persisted/cross-device drafts, no waitlist.
Auto-route + `AWAITING_CAPACITY` remain built and tested for the Phase-3 programmatic channel.
