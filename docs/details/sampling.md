# Sampling inspection (spec S8)

How a web-channel multi-Item Task is inspected: instead of checking every Item, the buyer's reviewer
checks a random sample against the sources, and the sample decides the rest. Decisions D4, D6, D17′,
D19, D21 in the [decision log](../superpowers/specs/2026-11-15-acceptance-contract-design.md). Code:
`domain/biz/task/sampling/` (rules) and `application/biz/task/sampling/` (orchestration).

## The lot and the plan

- **The lot** is the Items that passed the Contract's checks when the Agent delivered (`PENDING_REVIEW`).
  Items that failed their checks or that the Agent could not do were refunded at once and are not in it.
- **Items flagged for review are not in the lot** (#106, D7), up to a cap: an Item a check sent to review (for an
  invoice, figures that don't add up) is never drawn, accepted with the lot, reworked with it or settled pro rata.
  The reviewer judges it on its own, like an extra check (never counted), on the reviewer page's "Flagged for your
  own check" list. An Agent's anomaly holds an Item out only by softening a failed anomaly-eligible check on it; one
  that explains nothing is ignored here. **The flagged-share cap** (`FlaggedShareCap`,
  `hireai.acceptance.flagged-share` / `flagged-minimum`) decides sample membership only: the first flagged Items
  in Item order, up to that share of the Task's Items, are never drawn; flagged Items beyond it can be drawn
  (`TaskItems.sampleable`) and then count in the error rate like any sampled Item, so an Agent cannot shrink the
  sample or move Items it is unsure of out of it by flagging them. They share the lot's penalties too: a failed
  lot reworks them and settles them pro rata with the rest. But no flagged Item is ever paid without its own
  judgement (`TaskItems.lot`: not accepted with a passed lot, nor at once on the API channel): the reviewer
  judges it, or when the review window closes it is **accepted and paid** like any other (owner decision on #87: no refund unless the Agent is shown wrong) and counts as accepted in the
  reputation event.
- **One table for every Template** (`SampleSizeTable`, D21): ISO 2859-1 Table I gives the code letter for
  the lot size and inspection level; Table III-A gives the double sampling plan for that letter at
  **AQL 2.5**, normal inspection. Where the table has no double plan (its single plan accepts on zero
  defects, small lots) the single plan is used. A sample as large as the lot inspects every Item.
  There are no switching rules (tightened or reduced inspection) and no link to Reputation.
- **The level** is II by default. A buyer may choose III on a direct booking (`inspectionLevel`) when the
  Agent's Offer allows it (`strictestInspectionLevel: "III"`); a looser level, or one beyond the Offer,
  is a 400. It is recorded on `task_inspections` at booking.

## The draw

The first sample is drawn when the Items are delivered, the second when the first is inconclusive, and
a fresh first sample when the reworked Items come back (D6). Each draw takes a new 64-bit seed from
`SecureRandom` and records it (`sample_draws.seed`, the inspection API, and each Item's
[receipt](receipts.md)). The Agent never sees it while the inspection is open: no WorkOrder, rework
WorkOrder or rejection notice carries it, the inspection API is the buyer's only, and Item receipts
(which the Builder can read) are issued only once the inspection has ended. A rejection notice does tell
the Agent that one Item was judged wrong, but not whether it was sampled or an extra check.

Anyone with the seed can redraw a sample: sort the candidate Item numbers ascending, run a partial
Fisher–Yates shuffle with `java.util.Random(seed)` (`j = i + nextInt(n - i)`, swap, for the first `size`
positions), and take those `size` numbers in ascending order (`SampleDraw`). The second stage's
candidates are the lot Items awaiting review outside the first sample.

## Judging the sample

- **Ground truth is the buyer's reviewer** (D4): a person on the reviewer page (`/client/tasks/{id}/review`),
  never an LLM. `POST /api/tasks/{id}/items/{n}/judgement {passed, reasonCategory?, reason?}` (human-only).
  A pass accepts and pays the Item at once. A fail (A/B/C plus what is wrong) moves it to `REJECTED`
  and opens its [rejection response path](matching.md#item-rejection-notice): the Agent may revise it once,
  concede it (refunded), or stand by it; the buyer then accepts it (`POST …/items/{n}/decision`).
  Windows are the D20 ones counted in business days.
- **A sample is judged once every sampled Item has settled** (D17′: judgements as they stand after the
  response paths). An Item counts as a defect when it ended refunded for being wrong (conceded, or a
  revision that failed the checks).
- **Only the random sample feeds the error rate** (D19). The reviewer may judge any other Item while the
  window is open (a full check is allowed); those are handled one by one and never counted.
- **A first sample that does not accept the lot always draws the second** (D19 step 1; the spec's wording wins
  over ISO 2859-1's early rejection at d1 ≥ Re1). Double plan: d1 ≤ Ac1 accepts the lot; otherwise the second
  sample is drawn, and d1 + d2 ≤ Ac2 accepts, else it fails. Single plan: d1 ≤ Ac accepts; otherwise a second
  sample of the same size is drawn and judged on its own (d2 ≤ Ac accepts, else it fails). When the first
  sample was the whole lot there is nothing left to draw, and it is judged once more on its own. Each draw
  restarts the review window.

## What a decision does (D19)

| Decision | Effect |
|---|---|
| Lot accepted | every lot Item still awaiting review is accepted and paid (a flagged Item waits for its own judgement or the window) |
| Second sample | drawn from the rest of the lot |
| Fails in round 1 | the whole Task is reworked once: every Item still awaiting review goes back (`AWAITING_RESULT`) in a rework WorkOrder carrying the failed check `SAMPLE_REJECTED`; when it returns, round 2 samples the new lot |
| Fails in round 2 | each Item still awaiting review settles **pro rata**: paid `price × (1 − error rate)`, the rest refunded, with error rate = defects ÷ Items sampled in round 2 (4 dp) |

Items judged correct were already paid in full, and Items found wrong refunded, so pro rata applies to
the unverified Items only. If the Agent can no longer take work, a failed round 1 settles pro rata at
once. Nothing is clawed back: a judgement after the review window closed is a 409, and the window's
close accepts whatever is still awaiting review (the inspection reads `WAIVED`, as it does when the buyer
accepts everything awaiting review).

## Money

Every Item settles once, through `SettlementWriteAppService` (per-Item ledger entries, correlation
`settle-<task>-item-<n>`): `ACCEPT`, `REJECT`, or `SPLIT` for pro rata (`SettlementDomainService.settlePartial`,
the refund by subtraction, so `paid + commission + refund = price`). A whole-Task refund (timeout,
failure) returns only what is still in escrow. The Task's one reputation event credits pro-rata Items by
what they were paid of their price.
