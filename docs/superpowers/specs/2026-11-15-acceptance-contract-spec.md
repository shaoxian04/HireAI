# Spec: acceptance contracts and verification (#46, #50)

- **Date:** 2026-11-15
- **Status:** ready for implementation, as slices S1–S11.
- **Decision log, with every decision's rationale and the verified code facts:** `2026-11-15-acceptance-contract-design.md` in this folder.
- **ADRs:** [0009](../../adr/0009-template-offer-contract-replaces-output-spec.md) (Template → Offer → Contract) and [0010](../../adr/0010-confidential-documents-only-under-an-engagement.md) (confidential documents only under an Engagement).
- **Vocabulary:** `CONTEXT.md` § Acceptance.

## Problem Statement

**Builders** pay for mismatches the platform chose. On a routed task the Client writes the output spec and the matcher picks an Agent that never agreed to it. When the output fails, the Client is refunded and a `SPEC_VIOLATION` is recorded against the Builder, even though the Builder never saw those terms before being dispatched (#46 problem 1, #50). Today the Agent's only way out is to pretend to be busy.

**Clients** get almost no protection from automatic validation:

- A TEXT result passes if it is not blank.
- A FILE result passes if its link is HTTPS. The file is never opened.
- A schema that doesn't parse is silently skipped.
- Requirements written in prose, whether in `acceptanceCriteria` or in the description (for example "a red background"), are never checked. Nobody looks at them unless a dispute reaches the arbitrator, and the arbitrator cannot see images or PDFs.
- MCP routed submits always send a blank schema.
- A web Client who never reviews a result leaves the Builder's escrow frozen indefinitely.

**Buyers** of batch work, such as an AP team sending 500 invoices a day to an AI vendor, have nothing at all:

- A Task cannot hold more than one deliverable.
- There is no sampling against the source document.
- Confidential input documents have no way in.
- There is no evidence the buyer's auditors or insurers could rely on.

## Solution

Every Task is judged against one frozen **Contract**, and no Agent is ever held to a Contract its **Offer** did not accept.

- **Templates.** The platform publishes versioned Templates, one per kind of work. Each Template says which **Check Library** entries may be used and the bounds on each parameter.
- **Offers.** Builders declare Offers: the Templates they work under, how strictly they will be held, and at what price.
- **Contracts.** A Client either describes the work in plain words, or describes it and supplies a structured Contract. The platform turns the description into a Contract, using only Check Library entries and only for requirements that are unambiguous. Booking or submitting is acceptance.
- **Matching.** Only Agents whose Offer covers the Contract are matched.
- **Deliverables.** The platform freezes each deliverable as a **DeliverySnapshot** and runs the Contract's deterministic checks on it. It separates the Agent's mistakes from facts about the input (**Findings**), which never cost the Agent money.
- **Review.** The result goes to the Client for a bounded review. Where a human cannot review it, the Client may instead pay for an **AI Review**.

**Rejection and disputes** follow one path everywhere:

1. The Agent may revise once, concede, or stand by its result with a reason.
2. If the Agent stands by its result, the Client decides whether to accept it or ask for AI arbitration.
3. The loser bears the cost of arbitration.

**Batch work** is one Task with many **Items**:

- A shared lot-size table decides how many Items are sampled against the source documents.
- A failed sample leads to a second sample, then rework, and only then to pro-rata settlement.
- Each Item ends with a signed, verifiable receipt.

## User Stories

### Clients: submitting

1. As a Client, I want to describe a task in plain words, so that I can hand work to an AI without learning a contract format.
2. As a Client, I want the platform to show, before I book, which of my requirements it will check automatically, so that I know what is guaranteed before I pay.
3. As a Client, I want ambiguous requirements left unchecked rather than guessed, so that a misreading never turns into an automatic rejection.
4. As a Client, I want my description to remain the instructions the Agent receives, so that nothing I asked for is lost.
5. As a Client, I want the frozen Contract, not my description, to decide automatic acceptance, so that acceptance is predictable for both sides.
6. As a Client, I want booking to count as accepting the shown Contract, so that submitting is no slower than today.
7. As a Client, I want to see which Agents can accept my terms, so that I never book an Agent that would decline them.
8. As a Client, I want to be told which requirement excluded every Agent and how relaxing it would change the result, so that I can fix an unmatchable request.
9. As a Client, I want the platform never to relax my terms on its own, so that my Contract stays mine.
10. As a Client, I want to choose and pay for an AI Review of requirements code cannot check, so that subjective quality is judged without my reviewing every result.
11. As a Client, I want AI Review offered only when its fee is small relative to the task's price, so that I never pay more to check the work than for the work.

### Machine clients: submitting

12. As a machine Client, I want to submit with only a description, so that an automated pipeline can delegate work as easily as a person.
13. As a machine Client, I want to submit a description together with a structured Contract, so that I control exactly what is checked.
14. As a machine Client, I want a structured Contract that names an unknown check, or a parameter out of bounds, rejected with 400, so that I learn about mistakes at submit time.
15. As a machine Client, I want an unparseable custom schema rejected at submit, so that I am never misled into thinking a constraint is enforced.
16. As a machine Client, I want to preview the Contract the platform would extract from a description, without creating a Task, so that I can edit it and submit it as my own Contract.
17. As a machine Client, I want the frozen Contract returned when I submit, so that my pipeline knows what was agreed.
18. As an MCP user, I want the `submit_task` tool to accept a description, or a description plus a Contract, so that an AI assistant can use both modes.

### Builders: Offers and dispatch

19. As a Builder, I want to declare which Templates and parameter ranges my Agent accepts, so that I am only ever dispatched work I can do.
20. As a Builder, I want my Agent's existing output spec migrated into an equivalent Offer, so that my Agent keeps working without republishing.
21. As a Builder, I want an Offer to cover only the Template versions it names, so that a new Template version never binds me to terms I have not seen.
22. As a Builder, I want to decline a specific Contract with a distinct signal, so that it is not mistaken for my Agent being busy.
23. As a Builder, I want a contract decline to cost no reputation, so that being honest about my limits is never punished.
24. As a Builder, I want my Agent to receive a time-limited download link for each Item's input, so that it can fetch large or confidential documents safely.
25. As a Builder, I want deterministic check failures returned for rework, up to the allowed number of attempts, so that my Agent can fix mistakes before losing the fee.
26. As a Builder, I want to report an anomaly in the source with my result, such as totals that don't add up, so that a faithful extraction of a flawed document is not counted as my error.
27. As a Builder, I want Findings about the input, such as duplicates or a missing purchase order, never to reduce my pay, so that I am judged only on my own work.
28. As a Builder, I want my deliverable frozen when it arrives, so that what is judged is exactly what I delivered.

### Rejection, disputes and appeals

29. As a Client, I want a bounded review window that auto-accepts on timeout, so that forgetting to review never freezes a Builder's pay indefinitely.
30. As a Builder, when a Client rejects my result, I want to choose to revise once, concede, or stand by my result with a reason, so that most disagreements end before arbitration.
31. As a Client, I want to see the Builder's reason and then decide whether to accept or request AI arbitration, so that I only pay for arbitration I want.
32. As a Client, I want no separate deposit to open arbitration, so that a legitimate complaint is never priced out.
33. As either side, I want the loser to bear the arbitration cost, capped, so that frivolous disputes cost the party who started them.
34. As either side on the web channel, I want to appeal a ruling once to an admin for a capped fee, so that a wrong AI ruling can be corrected.
35. As a Builder on API/MCP, I accept that an AI Review verdict I opted into via my Offer is final, so that machine Clients get fast results.
36. As an arbitrator user, I want the arbitrator to see image and PDF deliverables, so that a dispute over a picture or a document is judged on what it shows.
37. As a Builder, I want my silence after a rejection to count as standing by my result without a reason, not as conceding, so that an older Agent without the new response endpoint is not penalised.

### Batch buyers

38. As a buyer, I want to submit one Task with many Items under one escrow freeze, so that a daily batch is one operation.
39. As a buyer, I want each Item settled on its own, so that one bad invoice does not hold back the other 499.
40. As a buyer, I want confidential documents uploaded to private storage under an Engagement with the Agent I chose, so that I can outsource sensitive work.
41. As a buyer, I want the documents deleted after the dispute windows close, keeping only their hashes, so that sensitive data is not held longer than necessary.
42. As a buyer, I want a sample size set by a standard lot-size table, so that inspection effort scales sensibly with batch size.
43. As a buyer, I want the sample drawn after delivery from a recorded seed, so that the Agent cannot game which Items are checked.
44. As a buyer's AP clerk, I want to review the sampled Items against their source documents, so that I confirm the whole batch with a fraction of the effort.
45. As a buyer, I want a failed sample to trigger a second sample, then a rework of the Task, before any pro-rata settlement, so that the Agent fixes the work rather than just being docked.
46. As a buyer, I want pro-rata settlement extrapolated only from the random sample, so that the price reflects the batch's measured quality.
47. As a buyer's AP clerk, I want to check any extra Items within the window and have errors refunded per Item, so that I can act on what I see without skewing the error rate.
48. As a Builder, I want a sampled rejection to follow the same revise, concede or stand-by path, so that a reviewer's mistake can be challenged.
49. As a buyer, I want review windows on batch Tasks counted in business days, so that a weekend never auto-accepts unreviewed work.
50. As a buyer, I want duplicate and reference-data problems reported to me as Findings, so that my AP team stops double payments without blaming the Agent.

### Reputation, matching and receipts

51. As a Builder, I want a multi-Item Task to count as one reputation event, weighted 1, with quality equal to the accepted fraction, so that one big buyer cannot dominate my Reputation.
52. As a Builder, I want failed checks after all reworks to count as a `SPEC_VIOLATION` only because I accepted the Contract through my Offer, so that the penalty is fair.
53. As a Client, I want embedding relevance to keep working alongside Offer coverage, so that matching still finds the most relevant capable Agent.
54. As a Client, I want my public contract-decline rate visible on the shortlist, so that I can avoid Agents that cherry-pick work.
55. As a buyer, I want a signed receipt per Item covering the Contract, the input and output hashes, each check, the sample, any ruling and the settlement, so that my auditors and insurers can rely on it without trusting HireAI's database.
56. As anyone holding a document, I want to verify it against a receipt's hash after the content was deleted, so that the receipt stays meaningful after retention ends.
57. As a buyer, I want receipts exportable as W3C VC 2.0 and OTel evaluation events, so that they plug into my existing tooling.
58. As the platform, I want Template versions to transition with both versions live for a period, so that publishing a new version never strands Clients.
59. As the platform, I want in-flight Tasks to finish under their frozen `output_spec`, so that migration never changes a contract mid-task.

## Implementation Decisions

Decision numbers refer to the decision log.

### Contract model (S1)

- **Template** is a platform-authored, versioned aggregate. It holds:
  - the output shape;
  - the allowed Check Library entries;
  - each parameter's bounds and its comparison (`max`, `min`, `subset`, `enum`, `flag`);
  - the expectation mode;
  - the mapping from ruling category to settlement.

  Templates change only by migration or admin publication. They are never changed by Clients.
- **Generic Templates** ship first: `text_basic@1`, `custom_json@1` and `file_basic@1`. In `custom_json`, the schema is a parameter, and a non-blank unparseable schema returns 400 (D8, D25).
- **Offer** replaces the output spec on an agent version: the accepted Template versions, the parameter ranges and the price. It is set when the Builder publishes a version.
- **Contract** replaces the Task's `output_spec`: the Template version plus the parameters, plus AI Review settings and their frozen fee. It is frozen at submit, and Hard Invariant #4 is reworded to name it.
- **Migration** (D39):
  - Existing versions become Offers:
    - TEXT → `text_basic`;
    - FILE → `file_basic`;
    - JSON → `custom_json`, with an Offer that accepts only the Agent's own schema.
  - Tasks already submitted keep `output_spec` and the old validation path until they reach a terminal state.

### Coverage, matching and decline (S2)

- **Coverage** (D34) is a framework-free domain service. An Offer covers a Contract when the Template version is the same, every parameter passes its comparison, and the price is within budget.
- **Eligibility:** coverage becomes one more eligibility filter in the single ranking engine, used by match-preview, the shortlist, routing and failover alike. The embedding relevance floor and the score formula are unchanged.
- **No match** (D35): when nothing covers a Contract, the response names the blocking parameter (or the relevance floor) and gives relax suggestions with counts. Routed Tasks keep the `AWAITING_AGENT` → `NO_ELIGIBLE_AGENT` path, and the blocking parameter is attached to the failure detail.
- **Template versioning** (D36): an Offer covers only the versions it names. New Contracts use the current version. During a transition, a Contract falls back to the previous version if nothing covers the current one, and the Client is told.
- **ContractDecline** (D33): HTTP 422 with `{"decline":"CONTRACT","reason":…}` from the Agent's webhook.
  - It is a new dispatch-attempt outcome.
  - It triggers failover on routed Tasks and a refund on pinned ones.
  - It writes no reputation event.
  - It feeds a public decline rate.

### Items (S3)

- A **Task** holds 1..n **Items** (D3, D14). Escrow is frozen once per Task as the per-Item price × the Item count. Each Item has its own small state machine and its own settlement entries, and the Task status aggregates them.
- A Task that reaches a terminal state writes **one** reputation event, weighted 1, with quality equal to the accepted fraction (D22, D27).
- **Outcome mapping** (D22):
  - Checks still failing after every rework → `SPEC_VIOLATION`.
  - Revised, then accepted → `TASK_ACCEPTED`.
  - Concede → quality 0.
  - ContractDecline or a Finding → no event.

### Snapshots (S4)

- **Input** comes by **upload only**, into a new private storage port. The existing public media port is not reused (D30).
- Each Item references a **SourceSnapshot**: content-hashed and immutable.
- Each Item in the WorkOrder carries a signed download link that expires at the execution deadline. Every download is logged (D31).
- **Confidential documents** are allowed only under an Engagement, and only with the buyer's consent recorded in the Contract (D29, ADR 0010).
- **Retention:** when the last window closes, plus 30 days, the content is deleted and the hashes are kept (D32).
- **DeliverySnapshot** (D24): when a result callback arrives, the platform fetches the deliverable through the SSRF-guarded client, checks its media type and size, hashes it and stores it. Every later step uses the snapshot.

### Acceptance engine (S5)

- **The Check Library** is code-defined, parameterised checks, each implementing one primitive: a deterministic rule, source grounding, and so on.
  - A check returns one of **pass**, **reject** or **send-to-review**.
  - Any check that needs other Tasks or outside data (duplicates, master data, PO existence) emits a **Finding** instead and never affects money (D7).
- **Expectation mode:** a Contract is either **faithful-to-source**, the invoice default, or **business-valid with mandatory anomaly reporting**.
- **Self-reported anomalies:** an Agent may attach them to an Item. An anomaly reported this way sends the Item to review, and the Item is paid if the review confirms it. An unreported arithmetic mismatch is also forced to review.
- **Rework:** a deterministic rejection is redispatched with the failing checks attached, up to two reworks, and is then refunded. It is not appealable (D17).

### Requirement extraction (S6)

- **Extractor port.** One LLM call at submit receives the Template's allowed Check Library entries and returns structured output constrained to them: each requirement either maps to an entry with its parameters, or is marked "unmapped" (D11, D12).
  - The extraction is validated deterministically: the entry must exist and its parameters must be in bounds. A failed mapping is downgraded to unmapped.
  - Only unambiguous requirements are mapped.
  - Unmapped requirements fall to AI Review, Client review, or arbitration evidence.
- **When the LLM is down,** the Contract is the Template's defaults and the requirements stay in the description.
- **Submit modes** (D28):
  - **Mode 1, description only:** extract, then adopt. Submitting is acceptance.
  - **Mode 2, description + Contract:** no extraction. The Contract is the only basis for acceptance.
- **Preview:** `POST /api/contracts/draft` returns an extracted Contract without creating a Task.
- **Web:** extraction runs before match-preview, and the checks are shown in plain words on the existing booking screen. Booking is acceptance.
- **MCP:** `submit_task` takes a description and an optional `contract`. `output_format` and the misleading `output_spec` parameters are removed.

### Review, rejection and arbitration (S7)

- **Review window** (D10): `PENDING_REVIEW` gets a 24h window with auto-accept on timeout, enforced by a new sweeper. Multi-Item Tasks count it in business days.
- **Rejection path** (D20), after a Client rejects for reason A, B or C:
  1. The Agent is notified through a new response endpoint and may **revise** once, **concede**, or **stand by** with a reason, within 2h. Silence counts as standing by without a reason.
  2. If the Agent stands by, the Client sees the reason and, within 24h, accepts or requests arbitration. Silence counts as acceptance.
  3. Reason D (changed mind) is unchanged.
- **Sampled rejections** by the reviewer take the same path, with the buyer as the deciding party (D17′).
- **Arbitration** is opened only by the Client or buyer, whose frozen escrow is the security. There is no bond (D43).
  - The loser bears the arbitration cost, capped at 20% of the Item price. A Client's share is taken from its refund and a Builder's from its earnings wallet, and the platform covers any excess.
  - Every one of these movements is a deterministic, append-only ledger entry.
- **Appeals** (D42): on the web channel, either side may appeal a ruling once to an admin for a capped **Appeal Fee**, which the loser bears. This adds a Builder appeal entry; today only the Client can appeal. API/MCP has no Builder appeal.

### Sampling (S8)

- **Sample size:** one fixed lot-size → sample-size table (ISO 2859-style), shared by every Template. A buyer may pick a stricter inspection level within the Offer. There are no switching rules (D21).
- **Sample draw:** the sample is drawn after delivery with a recorded seed (D6). Ground truth comes from the buyer's own reviewer, or optionally from a second, independent Agent's extraction. An LLM is never the ground truth (D4).
- **When a sample fails** (D19):
  1. Draw a second sample.
  2. If it still fails, rework the whole Task once and resample.
  3. If it still fails, settle pro rata: passing Items × (1 − the measured error rate) × the price.

  Throughout:
  - Items found wrong are refunded and reworked.
  - Only the random sample feeds the error rate.
  - The reviewer's extra checks are handled per Item.
  - A full check is allowed within the window.
  - Nothing is clawed back after the window.
- **The first vertical Template**, `ap_invoice_extraction`, ships in this slice. It has the invoice fields, deterministic consistency checks, and Findings for duplicates, master data and PO existence.

### Multimodal arbitrator (S9)

- The arbitration service sends image and PDF DeliverySnapshots (and SourceSnapshots) to a multimodal model as content, instead of replacing them with an "omitted" placeholder (D13).
- The ruling stays a category, and settlement stays deterministic (Invariant #3).

### AI Review (S10)

- **Interface** (D5, D37, D38): a reviewer port with a configurable multimodal model. The default provider is the arbitrator's.
- **Fee:** estimated at submit from the Item's type and size, then frozen in escrow.
- **Availability:** offered only when the fee is at most 10% of the Item price, and only if the Offer flags AI Review as accepted.
- **Verdict:** a pass or fail per criterion, with reasons, mapped to a ruling category.
  - On the web channel the verdict is an advisory pre-review report.
  - On API/MCP it decides settlement and is final.

### Receipts (S11)

- **Receipt** (D40): one per settled Item. It covers:
  - the Contract hash and Template version;
  - the source and delivery hashes;
  - each check result;
  - sample membership and the seed;
  - the judgements and any ruling;
  - the settlement ledger IDs;
  - timestamps.
- **Signature:** canonicalised with RFC 8785 and signed with a platform Ed25519 key whose public key is published. There is a read endpoint for receipts.
- **Exports:** a later step adds W3C VC 2.0 and OTel `gen_ai.evaluation.result`. The format documentation is published in the repo.
- **Scope:** receipts gate nothing inside HireAI.

### Configuration

D41 sets these defaults. All of them are configurable, and a Contract may override them within the Template's bounds:

| Setting | Default |
|---|---|
| Client review window | 24h |
| Agent response window | 2h |
| Client decision window | 24h |
| Automatic reworks | 2 |
| Cost and fee cap | 20% of the Item price |
| AI Review gate | 10% of the Item price |
| Retention after the last window | 30 days |
| Template transition | 30 days |

### Documentation, landing with each slice

- Reword Hard Invariant #4 in `CLAUDE.md`.
- Update `build-status.md`, `data-model.md`, `matching.md`, `programmatic-channel.md` and `CONTEXT.md` (drop the "designed, not built" markers as terms land).
- Fix the two inconsistencies in the decision log: the claim in `build-status.md` that declining costs a sample, and ADR 0008's claim that a routed task penalises up to 3 Builders.

## Testing Decisions

- **What a good test is.** A good test drives the system from the outside and asserts only observable behaviour:
  - HTTP responses;
  - the WorkOrders a fake Agent receives;
  - Task and Item statuses;
  - ledger entries;
  - reputation events;
  - Findings;
  - receipts.

  A good test never asserts on internal classes or call order. Time-based rules (review windows, response windows, auto-accept, retention) are tested by advancing the injectable test clock.
- **Primary seam: the whole-app integration harness.** This is the real app on a real port, with Postgres and RabbitMQ in Testcontainers, scriptable fake Agents, the mutable test clock and the fake embedding provider. It covers S1–S8, S10 and S11 end to end.
  - The harness gains three fakes in the existing port-fake style: a **scripted requirement extractor**, a **scripted AI reviewer** and an **in-memory private storage**.
  - The prior art is the existing harness integration tests and the dispute-flow integration tests.
- **Domain unit tests** are only for the pure algorithms, whose case space is too large for HTTP tests:
  - the Offer-coverage comparisons;
  - the lot-size table;
  - double sampling and pro-rata extrapolation;
  - the outcome → reputation quality mapping.
- **Arbitration service (pytest):** the evidence-gathering node passes image and PDF content to the model rather than a placeholder (S9). The prior art is the existing graph node tests.
- **MCP (pytest):** `submit_task` in both modes, and the removal of `output_format` and `output_spec`. The prior art is the existing tool tests.
- **Frontend (vitest):** the booking screen shows the extracted checks, the unmapped requirements, the no-match explanation with relax suggestions, and the review and response windows. The prior art is the existing page tests.
- **Caveats and checks beyond CI:**
  - Integration tests skip silently without Docker, so every slice must be run with Docker available.
  - Every slice is also verified by a live E2E run on the local stack, per the project's demo runbook.
  - Denied-status assertions use the full app, which returns 401 for every authenticated-but-forbidden request, not the 403 a `@WebMvcTest` slice gives.

## Out of Scope

- Fetching input from a URL (D30), which is deferred for its security surface.
- Confidential documents on marketplace paths (ADR 0010).
- Inspection switching rules (tightened or reduced sampling based on track record) (D21).
- Linking sample size to Reputation.
- Clawing back money after a window closes.
- Builder appeals on API/MCP (D38).
- Gating invoice payment, as opposed to the service fee, on acceptance.
- Choosing a neutral foundation to govern the open Contract and receipt formats.
- Payment-rail integration of receipts (AP2, x402).
- Agent-side SDK changes beyond documenting the new WorkOrder fields, the 422 decline, the response endpoint and anomaly reporting.

## Further Notes

- **Slice order and dependencies:**

  | Slice | Depends on |
  |---|---|
  | S1 | nothing |
  | S2 | S1 |
  | S3 | S1 |
  | S4 | S3 |
  | S5 | S1, S4 |
  | S6 | S2, S5 |
  | S7 | S3, S5 |
  | S8 | S5, S7 |
  | S9 | S4 |
  | S10 | S5, S7 |
  | S11 | S8 |

  S9 and S10 can run in parallel with later slices. #46 problems 1 and 6 disappear at S2, and the invoice wedge works end to end at S8.
- **Closing #50:** close it as superseded once this spec's implementation issues exist (D23).
- **Breaking changes:** external Agents and API/MCP clients face new WorkOrder fields, the 422 decline signal, a rejection-response endpoint, new submit modes and removed MCP parameters. Each slice's PR needs a Merge Danger section, as PR #86 had.
- **Strategy background (not requirements):** `docs/strategy/`.
