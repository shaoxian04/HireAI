---
status: accepted
---

# The launch vertical is document-grounded extraction, chosen by where ground truth lives

**Status:** decided, not built. The free-form categories described below are superseded by
[ADR 0006](./0006-matching-category-filter-relevance-floor.md).

HireAI is horizontal by construction — `agent_versions.capability_categories` is a free-form lowercased `TEXT[]` and
`GET /catalogue/categories` merely aggregates whatever builders have registered, so nothing in the
schema prefers one kind of work. This ADR records the *go-to-market* decision to seed and sell one
vertical first, which vertical, and — more durably than the pick itself — the criterion that chose it.

**The vertical: document-grounded extraction.** A client supplies a source document (invoice,
receipt, contract, statement, form, résumé) plus a schema; an Agent returns structured data; the
declared `OutputSpec` is the contract, and correctness is checkable *against the supplied source*.

## The criterion: is the ground truth inside the box?

The platform's differentiator is neutral adjudication of whether work was delivered — the validation
gate, the arbitrator, the tiered dispute path, and settlement computed deterministically from a
ruling. That machinery is only worth anything in categories where a referee **can actually tell**.
So the selecting question is not market size, not demand growth, and not whether AI does the job
well. It is: *does the task submission itself contain enough evidence to verify the answer?*

| Candidate | Where the truth lives | Referee can check? |
|---|---|---|
| Document extraction | in the source document the client supplied | **yes** — read source, compare to output, field by field |
| Web / company enrichment | out in the world (is this email real?) | no — it would have to redo the work |
| Content writing | nowhere; it is taste | no |
| Code | in the tests | yes, but only with sandboxed execution |
| Video / media editing | in an artifact the arbitrator cannot read | no |

This criterion is the reusable part. Any future vertical question should be answered with it first.

## Considered options

**Video and media editing** was the initial candidate and has the strongest demand evidence of any
option: Upwork's *In-Demand Skills 2026* puts AI video generation and editing at **+329% YoY, the
fastest-growing skill on the platform**. It was rejected on three independent grounds. First, the
same report attributes the growth to work where *"humans are required to guide outputs into usable,
publication-ready assets"* — demand for human freelancers wielding AI tools, which is precisely the
part an unattended-webhook marketplace does not broker. Second, the price floor: Opus Clip is
$29/mo (\$14.50 annually) for 300 credits and Descript $16–50/mo, so there is no room for escrow, an
arbitration call, and a 15% commission — and Opus Clip's Business tier already sells API access, so
any "video agent" here would be reselling an API the buyer can call directly. Third and decisively,
it fails the criterion: `ValidationDomainServiceImpl.checkFile` asserts only that `resultUrl` is an
HTTPS URL, and the arbitrator's `gather_evidence` pastes a fetched file into the prompt **as text**.
Hand it an MP4 and Hard Invariant #4 degrades to a URL smoke test.

**Web enrichment and scraping** is the larger, louder market — Clay at a \$5B valuation with 10,000+
customers, AI-driven web scraping put at \$10.2B in 2026 — and it was rejected *by the criterion*,
which is why the criterion matters more than the pick. Enrichment's documented problem is accuracy:
one teardown calls data quality *"the single most-documented complaint in the B2B data industry"*,
with Clay at 88% US / 71% EU match (using a three-provider waterfall), Apollo ~73% email accuracy
and 20–30% bounce rates, Datanyze 59%. That complaint is **unresolvable by a referee** — no
arbitrator can verify a claim about the outside world without going out and testing it — which is
exactly why the industry lives with it. An earlier draft of this decision lumped enrichment and
document extraction together as "structured extraction"; under the criterion they are opposites.

**Code tasks** score highest on verifiability (tests are ground truth, fully in the box) and on
attention, and were rejected purely on feasibility: adjudicating them means *executing* code in a
sandbox that does not exist here, and `OutputFormat` (TEXT | JSON | FILE) cannot express it.

**Staying horizontal** — letting categories emerge from whoever registers — was rejected because a
two-sided market with no liquidity dies on an empty shelf, and thin-everywhere is how this becomes
a slower Fiverr.

## Consequences

- **The pitch changes shape.** An early framing — *"only pay when the output matches the schema you
  declared"* — was falsified by the research and must not be revived: IDP field-level extraction
  already runs at **95–99% accuracy**, so schema conformance guarantees something that is not the
  buyer's problem. The unsolved metric is **straight-through rate** (70–85% mid-market, 85–92%
  best-in-class), with the remainder routed to human exception review. The JSON-Schema gate is the
  cheapest check in the stack; the *adjudication* is the product.

- **This is the settlement layer for outcome-priced work.** Outcome-based pricing is an established
  and growing vendor trend whose stated blocker is the absence of neutral measurement — "clear
  validation processes to resolve discrepancies before they become billing issues." That gap, not
  matchmaking, is what the take rate is for. Note `SettlementPolicy.COMMISSION_RATE = 0.15` is
  priced like matchmaking; Mercor charges ~35% to broker vetted quality at \$2B annualised.

- **Task size stops needing negotiation.** A document is a self-defining unit, so one document = one
  task whether the client sends one or ten thousand — which sidesteps the batch-vs-record modelling
  problem entirely.

- **Two blockers are now on the critical path and neither exists today.** (1) There is no input-side
  upload: `TaskModel` carries `title`, `description`, `budget`, `outputSpec`, `category` — all text —
  so a client cannot supply a source document at all. (2) Adjudication must fetch and *read* that
  source, which for PDFs means extraction or vision; today `fetch_result_content` retrieves only the
  **result** URL, as text.

- **Deferred, explicitly.** The adjudication-cost problem is parked, not solved: `build.py` wires
  three gpt-4o calls per dispute (`deliberate → classify → critique`) with `fetch_max_bytes` at 5MB,
  against a 15% commission — so there is a floor task value below which the platform cannot afford
  to guarantee anything. Related open questions: routing adjudication by stake rather than by
  escalation, and what stops an automatic refund from becoming free work.

- **Unanswered by desk research, by nature.** Whether a buyer will pay a premium for a referee, and
  whether they will trust a third party to rule against their supplier. No review or report contains
  this; it needs conversations or a live pricing page.
