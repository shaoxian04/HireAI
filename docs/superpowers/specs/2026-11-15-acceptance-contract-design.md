# Acceptance contract and verification: design in progress

- **Status:** grilling complete. The final spec is `2026-11-15-acceptance-contract-spec.md`, published as issue #87; the ADRs are 0009 and 0010. This file remains the rationale record. No code has been written. Started 2026-11-09 and continued 2026-11-15 via `/mattpocock-skills:grill-with-docs`.
- **Covers:** the generic acceptance mechanism, #46 (all six problems) and #50, plus the dependencies folded in by D26.
- **Supersedes:** the open questions in `2026-08-11-routed-spec-compatibility-design.md`. Its D1 ("filter by spec compatibility before scoring") survives as D1 below.
- **Strategy context, not requirements:** `docs/strategy/2026-11-09-ap-invoice-acceptance-wedge.md` and `docs/strategy/2026-11-09-acceptance-infrastructure-thesis.md`.
- **Vocabulary:** `CONTEXT.md` § Acceptance holds the terms Contract, Template, Offer, Item, Acceptance Check, Check Library, Finding, AI Review, Dispute Bond and DeliverySnapshot.

This file is the **decision log and handoff**. Once the open questions are resolved it gets rewritten into the final sliced spec.

## How to resume

```
/mattpocock-skills:grill-with-docs continue the design in
docs/superpowers/specs/2026-11-15-acceptance-contract-design.md —
pick up at the first open question
```

Trust **Decisions** as settled. Re-check **Verified facts** only if the code has moved past `origin/main` `de7bc7a` (the PR #86 merge).

## Design goal

Build one acceptance mechanism that serves as many kinds of work as possible, in three layers: a shared engine, verification primitives, and per-kind Templates. It **must** fit the first wedge, AP invoice extraction batches. Today's marketplace TEXT, JSON and FILE tasks must map into the same mechanism, not into a parallel one.

## Verified facts (origin/main after PR #86)

- **The WorkOrder** carries `title`, `description`, `category`, the frozen `outputSpec` (`format`, `schema`, `acceptanceCriteria`) and `executionDeadline`. #45 is closed.
- **Busy decline:** an Agent may reply 429 or 503 before starting. This records a `BUSY` dispatch row and **no** reputation event. A routed task then fails over (up to 3 Agents); a pinned task ends `FAILED`/`AGENT_BUSY` with a refund. A failure after the Agent started (a reported failure, a timeout, a spec violation) never fails over (ADR 0008).
- **Eligibility** is: ACTIVE, listed, category slug, price ≤ budget, fits the client deadline, and cosine relevance ≥ τ. **There is no spec compatibility check.**
- **Validation** (`ValidationDomainServiceImpl`) is unchanged:
  - TEXT passes if the output is non-blank.
  - JSON passes if it parses; a blank or unparseable schema is recorded as `SCHEMA_SKIPPED`.
  - FILE passes if the URL is HTTPS; the file is never fetched.
  - `acceptanceCriteria` is never checked automatically.
- **Spec violations:** `ValidationAppServiceImpl` records `SPEC_VIOLATION` against the agent version unconditionally. #50 is not implemented; its comment on 2026-11-10 says it is deferred into #46.
- **MCP routed submit** still sends `schema=""` (`mcp/app/client.py`).
- **Web channel:** validation passes, then `PENDING_REVIEW`, then the client accepts or rejects. Reject reasons A, B and C open a dispute; reason D (changed mind) still pays in full. **`PENDING_REVIEW` has no timeout.** Only proposed rulings auto-accept (`RulingAcceptSweeper`).
- **API/MCP channel:** validation is the settlement trigger. There is no review and no dispute path (`programmatic-channel.md` §5).
- **Arbitrator:** for FILE results it fetches the content, but binary content becomes `[binary content omitted …]` (`arbitration/app/graph/tools.py`). It cannot see images or PDFs.
- **Reputation:** each component is a shrinkage average, and volume enters as confidence (ADR 0003). The matcher adds `1/(1+sampleCount)` and an ε lottery. Event types are `TASK_ACCEPTED` 1, `SPEC_VIOLATION` 0, `EXECUTION_FAILED` 0, `EXECUTION_TIMEOUT` 0, `DISPUTE_WON` 1, `DISPUTE_PARTIAL` 0.5, `DISPUTE_LOST` 0, and `RATING`.
- **Doc inconsistencies found:**
  - `build-status.md`, Known gaps on #46, says that declining "costs the same zero-quality sample". This contradicts the free 429 decline.
  - ADR 0008 says one routed task can penalise "up to 3 Builders", but a spec violation never fails over.

## Decisions

The numbers follow the grilling questions; gaps in the sequence are questions that were merged.

**Contract model**

- **D1 (Q1, Q1′).** Every dispatch must point to a Contract that the Agent accepted beforehand, through three layers:
  - **Template:** platform-authored and versioned. It defines the allowed checks, the bounds on each parameter, and how each ruling settles.
  - **Offer:** the Builder declares, at publish time, which Template versions it accepts, the parameter ranges it will be held to, and its price. It replaces the agent version's `outputSpec`.
  - **Contract:** the Template plus the client's parameters within the Offer, frozen at submit.
  - Matching filters to Agents whose Offer covers the Contract, before scoring. This closes #46 problems 1 and 6 structurally.
  - An Engagement (a buyer's custom Template variant) needs the Agent's explicit acceptance of that version.
- **D1″ (Q1″).** `outputSpec` grows into the **Contract**, the single binding contract (Invariant #4). Automated acceptance looks only at the Contract. The description is intent evidence for arbitration only, and on a conflict the Contract wins; the confirm screen says so.
- **D2.** Refusing a contract is a decline reason distinct from busy. It writes no reputation event but counts in a public decline rate. Its name is still open (O7).
- **D3, D14.** A Task contains 1..n **Items**, with escrow frozen once per Task and settlement per Item. There is no Batch concept: a marketplace task is a 1-Item Task.

**Verification**

- **D4.** Ground truth for sampling comes from the buyer's own reviewer (the AP clerk) by default. A second, independent Agent's extraction is optional. An LLM is never the ground truth.
- **D5 (Q5″).** No LLM runs in deterministic checks. **AI Review** is an optional, client-paid Contract option.
  - It is chosen and priced at submit and is never switched on mid-task.
  - It is offered only when its cost is at most X% of the commission (X is open, O1).
  - It is advisory on the web channel and decisive on API/MCP.
  - It is never used per Item by default.
- **D6.** The full Contract is visible to the Agent before it accepts. The sample is drawn after delivery from a seed that is recorded in the receipt.
- **D7 (Q7″).** Acceptance judges only whether the delivery conforms to *this* Task's Contract.
  - Anything needing other Tasks or outside data (duplicates, master data, PO existence) is a **Finding**: it is reported to the client and never moves money.
  - Each Contract states its expectation: **A** faithful to source, or **B** business-valid with mandatory anomaly reporting. The invoice default is A.
  - An anomaly the Agent self-reports goes to review and is paid if confirmed.
  - An unreported arithmetic mismatch is also forced to review.
- **D8.** A JSON Contract with a non-blank, unparseable schema returns 400 at submit.
- **D11 (NOT yet confirmed by the user; the 2026-11-15 challenge reopened it as O-Q28).** At submit, requirement extraction sorts each natural-language requirement into one of three classes, shown to the client in plain words: an automatic check, an AI Review criterion, or client review only (arbitration evidence only on API/MCP).
- **D12.** Automatic checks come only from the platform **Check Library**.
  - The extraction LLM gets the Template's allowed entries and uses structured output constrained to them, so it can only map to an entry or mark "unmapped".
  - Its mapping is then validated deterministically: the entry must exist and its parameters must be in bounds.
  - No check code is generated at submit.
  - If the LLM is down, everything stays in the description for client review.
- **D13.** The arbitrator receives image and PDF deliverables multimodally.
- **D24.** The **DeliverySnapshot**: the platform fetches the deliverable when it arrives, checks its type and size, hashes it and stores a frozen copy. Every later step judges the copy. This closes #46 problem 3.
- **D25.** Client-authored JSON Schema survives only as a parameter of a `custom_json` Template. That Template matches only Agents whose Offer accepts any schema. D8 applies to it.

**Review, rejection and disputes**

- **D10.**
  - **Review window:** `PENDING_REVIEW` gets one (about 72h), and the Task is auto-accepted on timeout.
  - **Multi-Item Tasks:** the client confirms them by reviewing the sample, and the Contract sets the default outcome on timeout.
- **D15 (Q15).** Machine clients can submit a structured Contract directly (Template, Check Library entries and parameters, validated, 400 when out of bounds). A `POST /api/contracts/draft` endpoint returns the extracted Contract for confirmation. Silent auto-adoption is rejected. **This is being amended by O-Q28.**
- **D16, D18 (amended by D43: there is no Dispute Bond; only the loser-pays rule below and the Appeal Fee remain).** Originally a **Dispute Bond** with loser pays.
  - The bond is sized to the expected cost of judging, capped at about 20% of the Item price, and the platform covers the gap.
  - The arbitration cost is borne by the loser, capped the same way. For a client it comes out of the refund; for a Builder, out of its earnings wallet; any excess is covered by the platform.
  - An appeal to an admin has its own fee.
  - All of these movements are deterministic ledger entries.
- **D17.** A rejection by a deterministic check is **not** appealable. The remedies are:
  - self-reporting an anomaly at delivery,
  - declining the Contract before starting (D2),
  - a platform fix of a buggy check, applied through compensating entries.

  A sample judgement **is** appealable by the Builder, using the bond.
- **D19.** When a sample fails:
  1. Draw a second sample (double sampling).
  2. If it still fails, the whole Task is reworked once and resampled.
  3. If it fails again, the Task settles pro rata by extrapolation: `passing Items × (1 − measured error rate) × price`.

  Rules that apply throughout:
  - Items found wrong are refunded and reworked.
  - **Only the random sample feeds the error rate.**
  - Extra checks by the clerk are handled per Item and do not count toward the rate.
  - A full check is allowed within the window, and nothing is clawed back after it.
- **D20 (Q20′, the user's design).** When a client rejects with reason A, B or C:
  1. The Agent may revise once, concede, or stand by its result with a reason.
  2. If it stands by, the client chooses: accept, or request AI arbitration. The client's frozen escrow is the security, so the client posts no bond.
  3. Timeouts: an Agent's silence counts as standing by without a reason; a client's silence counts as acceptance.
  4. Reason D is unchanged.

**Sampling, reputation, scope**

- **D21.** There is one fixed lot-size → sample-size table (ISO 2859-style), **shared by all Templates** and not linked to Reputation. A buyer may choose a stricter level within the Offer. The sample size used is recorded in the receipt. There are no switching rules.
- **D22.** Reputation mapping:

  | Outcome | Event |
  |---|---|
  | Checks fail after all reworks | `SPEC_VIOLATION` (fair now that the Agent accepted the Contract through its Offer) |
  | Revised, then accepted | `TASK_ACCEPTED` |
  | Concede | Quality 0, like a lost dispute; a loss additionally costs the fee |
  | Contract decline | No event |
  | Finding | No event |
  | A multi-Item Task | **One event per Task**, with quality equal to the final accepted fraction |
- **D27.** A multi-Item Task's event has weight 1: one buyer, one vote.
- **D23.** #50 is closed as superseded once this spec and its implementation issues exist.
- **D28 (2026-11-15, the user's design; amends D15).** Machine clients get exactly two submit modes:
  1. **Description only.** The platform extracts the Contract and adopts it, and submitting is acceptance.
  2. **Description plus a structured Contract.**

  There is no `confirm` mode.
  - **Mode 2 (Q28a):** the description is **not** extracted. The structured Contract is the only acceptance basis; the description goes to the Agent as instructions and to arbitration as intent evidence.
  - **Q28b:** `POST /api/contracts/draft` stays, as a preview helper that creates no Task and freezes nothing. A client can edit a draft and submit it in mode 2.
  - **Web (Q28c):** booking is acceptance. There is no new step: the derived checks are shown on the existing booking screen.
  - **D11 is settled in this form.** Extraction maps a requirement only when it is unambiguous; ambiguous ones stay unmapped and fall to AI Review, client review or arbitration evidence. Extraction runs on the description *before* match-preview, so the shortlist can be filtered by Offer coverage (see O6).
- **D29 (Q29).** A new ADR partly supersedes ADR 0005.
  - **Engagement:** confidential input documents are allowed. The buyer chose the Agent and consents in the Contract to sending documents to it; confidentiality is between the buyer and the Builder. The platform provides private storage, time-limited access and retention.
  - **Marketplace (routed or shortlist):** public documents only, unchanged.
- **D30 (Q30).** Input arrives by **upload only**, into private storage; each upload is frozen as a SourceSnapshot (content-hashed). URL fetch is deferred because of its security surface.
- **D31.** Each Item in the WorkOrder carries a signed, time-limited download link that expires at the execution deadline. Every download is logged.
- **D32.** Snapshots are kept until every dispute and appeal window has closed, plus N days. Then the content is deleted and only the hash is kept, so receipts stay verifiable. A buyer may contract a shorter retention.
- **D33.** **ContractDecline** is signalled by HTTP 422 with `{"decline":"CONTRACT","reason":"…"}`. It behaves like busy (routed tasks fail over; pinned tasks end with a refund) and writes no reputation event, but counts in the public decline rate.
- **D34 (Q34′).** **Offer coverage** is decidable by construction.
  - Each Template parameter declares one comparison: `max`, `min`, `subset`, `enum` or `flag`.
  - An Offer covers a Contract when the Template version is the same, every parameter passes its comparison, and the price is within budget.
  - Coverage is one more **eligibility filter**, alongside ACTIVE, listed, category, price, deadline and the **embedding relevance floor τ (kept unchanged)**. The score formula is unchanged.
  - The shortlist, match-preview, routing and failover all use the same filter.
- **D35.** When nothing covers a Contract, the client is told which parameter (or the relevance floor) excluded the Agents, with a relax suggestion such as "drop X → 3 Agents" or "use AI Review instead".
  - The platform never relaxes a Contract by itself.
  - A routed Task follows the existing `AWAITING_AGENT` path to `NO_ELIGIBLE_AGENT`, and the blocking parameter is attached to the failure detail.
- **D36.** An Offer is valid only for the Template versions it names.
  - New Contracts use the current version.
  - During a transition both versions are live: if no Offer covers the new version, the old one is used and the client is told.
  - After the transition, the old version takes no new Contracts.
  - A frozen Contract is always judged under its own version.
- **D37.** **AI Review.**
  - The spec fixes the interface, not the model. The default is a configurable multimodal model from the arbitrator's provider.
  - The fee is estimated at submit from the Item's type and size (cost plus margin) and frozen.
  - Each criterion gets a pass or fail with a reason. The overall result maps as: all pass → FULFILLED, some pass → PARTIALLY_FULFILLED, none pass → NOT_FULFILLED.
  - It is advisory on the web channel and decisive on API/MCP.
  - Both of the user's rules hold: the client opts in and pays, and the option appears only past a threshold. The threshold is measured against the **Item price**: the fee may be at most 10% of it. The client pays the fee, so the gate protects the client rather than the commission.
- **D38.** On API/MCP an AI Review verdict is **final**, with no Builder appeal, so machine clients get results quickly. The Builder consented by flagging AI Review in its Offer.
- **D17′ (2026-11-15; replaces the sample half of D17).** When the clerk rejects a sampled Item, the Agent takes the **D20 path**: revise once, concede, or stand by with a reason. The buyer then accepts or requests AI arbitration.
  - D19's error rate uses the sample judgements as they stand after these rounds.
  - A deterministic-check rejection is still not appealable; the remedies of D17 still apply.
  - **Consequence:** only the client or buyer ever opens arbitration, and their frozen escrow is the security. Whether the Dispute Bond term survives is open (O-Q43).
- **D40.** Receipts are the **last slice**.
  - First, a minimal per-Item signed JSON receipt: canonicalised with RFC 8785, signed with an Ed25519 platform key that has a published public key. It covers the Contract hash and Template version, the source and delivery hashes, each check result, sample membership and seed, the judgements and ruling, the ledger IDs and timestamps.
  - Then W3C VC 2.0 and OTel `gen_ai.evaluation.result` exports.
  - The open format docs live in the repo.
  - A receipt gates nothing inside HireAI. It is external evidence for auditors, insurers, Builders, disputes and future payment rails.
- **D41.** Defaults, all configurable and overridable per Contract within Template bounds:

  | Window or limit | Default |
  |---|---|
  | Client review window | 24h |
  | Agent response to a rejection | 2h |
  | Client decision after the Agent's reason | 24h |
  | Automatic reworks | 2 |
  | Bond and fee cap | 20% of the Item price |
  | AI Review gate | fee ≤ 10% of the Item price |
  | Sample-rejection response | the D20 windows above |
  | Retention after the windows close | 30 days |
  | Template transition | 30 days |

  For multi-Item Tasks the windows count **business days**.
- **D39.** Migration.
  - **New generic Templates:** `text_basic@1`, `custom_json@1` and `file_basic@1`.
  - **Existing agent versions:** each `outputSpec` becomes an Offer. TEXT maps to `text_basic`, FILE to `file_basic`, and JSON with the Agent's own schema to `custom_json`, with the Offer accepting **only that schema**.
  - **In-flight tasks:** finish under their frozen `output_spec` and the old rules.
  - **MCP:** `submit_task` gets two modes: a description, or a description plus a `contract`. The `output_format` and misleading `output_spec` parameters are removed, which closes #46 problem 5.
- **D42.** On the **web** channel either side may appeal a ruling once to an admin.
  - The appealing side pays the **Appeal Fee**, which the loser bears, capped.
  - This adds a Builder appeal entry; today only `clientId` may appeal.
  - API/MCP has no Builder appeal (D38).
- **D43.** The Dispute Bond is dropped. The remaining rules:
  - Only the client or buyer opens arbitration, and their frozen escrow is the security.
  - The arbitration cost is borne by the loser, capped at 20% of the Item price, and the platform covers any excess. A client's share comes out of the refund; a Builder's, out of its earnings wallet.
  - The Appeal Fee follows the same rule.
- **D44 (2026-11-15, settled during ticketing).** An **Engagement** is a standing agreement between a buyer and an Agent the buyer chose.
  - It records the buyer's consent to send confidential documents, and an optional Template variant, which the Agent must accept.
  - It is built in ticket #104.
- **D45 (2026-11-15, settled during ticketing).** The first invoice Template emits only duplicate and arithmetic Findings.
  - Findings that need the buyer's reference data (supplier master data, PO existence) wait for a later ticket that designs how that data gets in.
- **Tickets:** #88–#110 are sub-issues of #87, with native blocking edges. The first frontier is #88 and #94.
- **D9, D26.** There is **one** spec for acceptance, #46 and #50.
  - **Dependencies folded in:**
    - SourceSnapshot (input documents, ADR 0004),
    - verifiable receipts and the open spec,
    - AI Review (the model and the pricing).
  - **Shape of the final spec:** **ordered independently deliverable slices**, each becoming one implementation issue.
  - **Implementation tooling:** `/mattpocock-skills:implement-spec` is not installed here; the fallback is `superpowers:writing-plans` plus `superpowers:subagent-driven-development`.

## Open questions

- **O-Q28.** Resolved by D28 and D11.
- **O9.** ADR 0005 launches on public documents only, because self-hosted Agents are outside the platform's control. The invoice wedge sends confidential documents. This needs a decision (asked as Q29).
- **Verified for O2:** `MediaStoragePort` exists and is used for agent storefront media in a public bucket. There is no private storage for client input yet.
- **O1.** The thresholds: X% for AI Review, the bond cap, the review window lengths, the Agent response window, and the rework count N.
- **O2.** SourceSnapshot: upload versus URL fetch, size limits, retention, and how Items reference it.
- **O3.** Verifiable receipts: the fields, the signature scheme, the W3C VC and OTel `gen_ai.evaluation.result` mapping, and the governance of the open spec.
- **O4.** AI Review: the model or provider, the pricing formula, and how its verdict maps to ruling categories.
- **O5.** Migration:
  - existing agent versions' `outputSpec` to an Offer under generic TEXT, JSON and FILE Templates,
  - the MCP tools, including fixing the blank schema (#46 problem 5),
  - existing in-flight tasks.
- **O6.** Match-preview and the web shortlist: which Contract (the category's default Template with default parameters?) they filter Offers against.
- **O7.** The name and wire signal (status code or body field) of the contract-decline reason.
- **O8.** Doc updates on landing: Invariant #4's wording in `CLAUDE.md`, `build-status.md`, `data-model.md`, `matching.md` and `programmatic-channel.md`, plus the two inconsistencies listed above.
