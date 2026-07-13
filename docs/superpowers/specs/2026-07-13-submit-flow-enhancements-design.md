# Submit-flow enhancements — design

**Date:** 2026-07-13
**Status:** Approved (design) — ready for implementation plan
**Branch:** `feat/shortlist-selection` (continues the Phase-2 shortlist work)
**Visual preview:** https://claude.ai/code/artifact/067f522c-5c85-420b-b667-d7216b617374

## Context

Phase 2 shipped the client submit flow: type a category + budget → `GET /tasks/match-preview` → a
shortlist of ranked agents → pick one → book at the agent's price. It works, but three rough edges
remain, surfaced during manual end-to-end testing:

1. **Category is a blind text box.** The client must guess a category string that matches an active
   agent; a typo yields an empty shortlist with no hint.
2. **The shortlist is a flat stack of wide rows.** It reads like a list to scroll past, not a
   decision to make.
3. **Failures are silent.** Spec-violation, timeout, dead-webhook and no-agent all refund escrow
   correctly, but the client sees only a small red status word — no explanation, no visible refund.
   ("Did I just lose my credits?")

This design fixes all three. The money paths, matching engine, and booking logic are untouched — this
is almost entirely presentation, plus **one small owner-scoped read endpoint** for feature 3.

## Goals

- Replace the category text box with a **searchable dropdown of real categories** (with agent counts).
- Present the matched agents as a **focused modal popout of ranked cards**, pick-by-button.
- Give every terminal failure a **plain-English panel** that says what happened and confirms the refund,
  with the **real validation reason** shown for spec-violation.

## Non-goals

- No change to the matching engine, scoring, `match-preview` payload, escrow, or settlement.
- No new task lifecycle states and **no DB migration**.
- No change to the auto-route / open-submit path (unchanged; not client-facing UI).

## Locked decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Category picker | **Strict** — the client can only pick a category that has active agents. |
| 2 | Shortlist layout | **Modal popout** — overlay of ranked cards; collapses to one column on mobile. |
| 3 | Failure detail | **Generic copy + real spec-violation reason** — needs one read endpoint. |

---

## Feature 1 — Category picker (searchable combobox, strict)

### Behaviour
- On focus, the field shows a dropdown of every category that has active, listed agents, each with an
  agent-count chip (e.g. `summarization · 4 agents`).
- Typing filters the list case-insensitively (substring match), highlighting the matched span.
- **Strict:** the committed `category` value is only ever a real category. It is set by clicking an
  option or keyboard-selecting one (↑/↓ + Enter). Free text that matches no option cannot be committed;
  **"Find agents" stays disabled until a real category is selected.**
- Empty filter result → an inline "no matching category — browse the marketplace" hint.

### Data source
- `GET /api/catalogue/categories` → `CategoryCountDTO[] = [{ category, agentCount }]`. **Already exists**
  (`CatalogueController.categories()`), and it counts the same active-listed pool that `match-preview`
  books from, so the dropdown can never suggest a dead-end category. **No backend change.**

### Frontend
- New `frontend/components/CategoryCombobox.tsx` — a controlled combobox: props `{ value, onChange }`.
  Fetches categories once on mount via the `api()` client; owns its own open/query/highlight state.
- ARIA combobox pattern: `role="combobox"`, `aria-expanded`, `aria-controls`, a `role="listbox"` popup
  with `role="option"` + `aria-selected`, `aria-activedescendant` for the highlighted row. Keyboard:
  ↑/↓ move, Enter select, Esc close, click-outside close. Visible focus ring.
- New `CategoryCount` type in `lib/types.ts` (mirror of `CategoryCountDTO`, already present as
  `CategoryCountDTO` — reuse it).
- `app/client/tasks/new/page.tsx`: replace the `<Input id="category">` with `<CategoryCombobox>`; the
  "Find agents" button's `disabled` also requires a non-empty committed category. Draft persistence of
  `category` is unchanged (still a string in the localStorage draft).

### States
- Loading categories (brief spinner / skeleton row), loaded, filtered, empty-match, error (fall back to
  a plain text input so submission is never fully blocked by a categories-fetch failure).

---

## Feature 2 — Agent shortlist modal popout

### Behaviour
- After "Find agents" succeeds and a preview exists (and none is selected yet), the shortlist opens as a
  **modal dialog** over the dimmed form.
- The top-5 in-budget agents render as a **card grid** (2-up desktop, 1-up mobile). Rank #1 is
  highlighted as **"★ Best match"** (accent border + soft glow); #2–#5 are plain cards.
- Each card: agent logo/avatar (from `logoUrl`, initial-fallback), name, tagline, **price** (prominent,
  accent), reputation as ★ stars + numeric, availability pill (● available / busy), output-format tag,
  and a **Select ▸** button.
- Above-budget near-misses collapse into a `<details>` drawer inside the dialog ("Above your budget · N")
  with muted rows and `Select · pays N cr` buttons.
- Selecting an agent closes the modal and shows the existing **confirm-booking** card (unchanged flow →
  `POST /tasks/direct` at the agent's price). Closing without selecting (Esc / ✕ / backdrop) returns to
  the form with the preview retained (re-openable).

### Data source
- The same `GET /tasks/match-preview` payload (`{ shortlist, nearMisses }`). **No backend change.**
  Booking still **pays the agent's price**, not the typed budget — confirm step + escrow untouched.

### Frontend
- New `frontend/components/ui/Modal.tsx` primitive: portal-less fixed overlay, `role="dialog"`,
  `aria-modal="true"`, `aria-labelledby`; focus-trap within the dialog, focus restored to the trigger on
  close, Esc + backdrop-click to close, body scroll lock while open, `prefers-reduced-motion` respected.
  Reusable across the app (added to `components/ui/index.ts`).
- Refactor `frontend/components/ShortlistPanel.tsx`: keep the same data props, add `open`/`onClose`, and
  render its content inside `<Modal>` as the card grid described above (extract `AgentCard` into a
  richer card: rank, avatar, stars, availability, select).
- Reputation → stars: `filled = clamp(round(reputationScore / 20), 0, 5)` (score is 0–100), rendered as
  5 star glyphs with a numeric `NN rep` beside them (keeps the exact number visible).
- `app/client/tasks/new/page.tsx`: drive the modal's `open` from `preview && !selected`; `onSelect`
  sets `selected` (closes modal → confirm card); an explicit close clears nothing (preview retained).

---

## Feature 3 — Honest failure states

### Backend — one owner-scoped read endpoint (spec-violation reason)

The validation report already persists the reason: `validation_reports.checks` is a JSONB array of
`CheckResult(rule, passed, detail)`. We expose the failing checks for the task owner.

- **Repository:** add `Optional<ValidationReportModel> findLatestByTaskId(UUID taskId)` to
  `ValidationReportRepository` (highest `attempt_no`), implemented in `ValidationReportRepositoryImpl`
  (order by `attempt_no desc limit 1`). (`findByTaskIdAndAttemptNo` stays.)
- **App service (read):** a method that, given the current user + `taskId`, verifies the caller **owns
  the task** (same ownership rule the other `/tasks/{id}` reads use — Inv #5), then returns the latest
  validation report or empty.
- **DTO:** `ValidationReportDTO { verdict: "PASS"|"FAIL", checks: [{ rule, passed, detail }] }`.
- **Endpoint:** `GET /api/tasks/{id}/validation` on `TaskController`. 404 when no report exists (task
  never validated); 403/404 on non-owner per the existing convention.
- **No migration**, no write path, no money movement. Adjudication stays a soft-referenced read
  (`task_id` only), consistent with V16's cross-context independence note.

### Frontend

- New `frontend/components/TaskFailurePanel.tsx`: given `{ status, budget, detail? }` renders the right
  panel. One panel per terminal failure, slotted where the amber "Waiting for an available agent" box
  already sits on the task page; the bare red badge in the header is replaced by this panel (the pipeline
  instrument stays for the at-a-glance track).
- On `SPEC_VIOLATION`, the task page fetches `GET /tasks/{id}/validation` and passes the failing checks
  into the panel's **"Show what failed"** `<details>` drawer (lists each failed `rule` + `detail`). Fetch
  is best-effort: a 404/error just hides the drawer, panel still renders.
- Refund line: all four paths fully refund the frozen escrow, which equals the agent's price ==
  `task.budget`. Panel shows `✓ {task.budget} cr refunded to your wallet`.

### Copy (status → panel)

| Status | Tone | Headline | Why | Action |
|--------|------|----------|-----|--------|
| `SPEC_VIOLATION` | red | The result didn't meet the spec | The agent returned something, but it failed the automated output check — so you were never charged. | Submit a new task ▸ (+ Show what failed) |
| `TIMED_OUT` | amber | The agent ran out of time | This agent accepted your task but didn't return a result within its deadline. That's on the agent, not you. | Try another agent ▸ |
| `FAILED` | red | We couldn't reach the agent | We tried to hand your task to the agent but its service didn't respond. No work started. | Try another agent ▸ |
| `CANCELLED` | dim | No agent was available | We kept looking for a free agent in this category but none opened up in time, so we released your task. | Browse the marketplace ▸ |

`AWAITING_CAPACITY` keeps its existing amber "waiting" box (a live wait state, not a terminal failure).

---

## Scope & impact

| Layer | Work |
|-------|------|
| Backend | **1 read endpoint** + repo method + DTO + owner-scoped app-service read. No migration, no write path, no money path. |
| Frontend | 3 new components (`CategoryCombobox`, `Modal`, `TaskFailurePanel`) + `ShortlistPanel` refactor + 2 page edits. |
| DB | None. |
| Config / infra | None. |

## Hard-invariant check

- **#5 Server-side identity / ownership** — the new `GET /tasks/{id}/validation` derives the user from
  the JWT and requires task ownership before returning the report. This is the only invariant-relevant
  surface; all others are untouched (no money movement, no dispatch, no schema).
- The failure panels are read-only renderings of existing status; they move no credits.

## Testing

- **Frontend (vitest):** `CategoryCombobox` (fetch/filter/keyboard/strict-commit/disabled-until-selected),
  `Modal` (open/close/Esc/focus-trap/scroll-lock), `ShortlistPanel` modal render + best-match + near-miss
  drawer + select callback, `TaskFailurePanel` (each status → headline/refund/action; spec-violation
  drawer with + without detail). Keep the ≥80% gate.
- **Backend:** unit test the app-service ownership check (owner ok / non-owner rejected / no-report →
  empty) and `findLatestByTaskId`; a `TaskControllerTest` slice for `GET /tasks/{id}/validation`
  (200 with checks, 404 no report, non-owner rejected).
- **Gate:** `npm run lint` + `npx vitest run` (frontend) and `mvn -f backend/pom.xml -B test` (backend)
  must be green before the branch is offered for merge. Live E2E re-run of the three flows + the four
  failure paths.

## Rollout

Single branch, no migration, no config flag. Ships on `feat/shortlist-selection` alongside the Phase-2
work already there. PR updated (not merged) and left for explicit go-ahead.
