# Thesis: acceptance as shared infrastructure for AI work

- **Date:** 2026-11-09
- **Status:** Long-term strategy thesis. This is the evidence and the reasoning behind it; nothing here is built or validated by buyers.
- **Companion:** the go-to-market wedge is [`2026-11-09-ap-invoice-acceptance-wedge.md`](2026-11-09-ap-invoice-acceptance-wedge.md).
- **Evidence base:** `reports/AIaaS 验证层缺口与潜力.md`, `reports/HireAI 创业竞争力与竞品调研.md` and their `research_notes/` (uncommitted).
- **Labels:** **(judgment)** marks reasoning, not findings, and **(assumption)** marks illustrative numbers. Sources are the ones cited in those notes. Several are second-hand, and they are flagged where it matters.

## 1. The thesis

> **Payment rails decide *how* money moves. HireAI decides *when* it should move.**

AI work is increasingly bought per outcome. Today, the party that is paid is also the party that counts the outcome. HireAI's long-term form is a **neutral, per-delivery acceptance layer**, built from five parts:

1. A frozen **acceptance contract** that both sides agree to before work starts.
2. **Verification of every delivery** against that contract.
3. **Escrow** that releases money only on acceptance.
4. **Adjudication** in which a model may rule, but deterministic code moves the money.
5. A **verifiable receipt** that payment rails, auditors and insurers can consume.

The thesis has two halves:

1. **The gap is real.** The neutral, per-delivery, money-touching, adjudicating slot is empty today (§2).
2. **The slot only becomes valuable if it is shared infrastructure,** meaning one open contract and receipt format that many vendors, buyers, rails and insurers reference, rather than a feature of one vendor (§3–§6).

The first half is well evidenced. The second half depends on conditions that are mostly outside the company's control (§7). §8 is the honest probability.

## 2. Evidence that the gap exists

### 2.1 Every outcome on every invoice is counted by the seller

| Vendor | Billable unit | Who counts | Buyer recourse |
|---|---|---|---|
| Intercom Fin | About $0.99 per resolution; "assumed" resolved after 24h of customer silence ([Intercom](https://www.intercom.com/help/en/articles/8205718-fin-resolutions-how-are-they-measured)) | Intercom | No formal per-resolution dispute process found ([aimdoc.ai](https://aimdoc.ai/blog/intercom-resolution-pricing-explained)) |
| Zendesk | Since May 2026, only "verified" resolutions bill; verification is "an LLM that evaluates the conversation", run by Zendesk ([Zendesk](https://support.zendesk.com/hc/en-us/articles/9570369117338-About-automated-resolution-tiers)) | Zendesk | Contact Zendesk support |
| Salesforce Agentforce | Pay per resolution; claims a 70% rate from 4.3M of its own inquiries ([Futurum](https://futurumgroup.com/insights/salesforces-agentforce-help-agent-bets-on-pay-per-resolution-will-enterprises-trust-the-model/)) | Salesforce | Analysts call governance and auditability "the gate to scale" |
| Agentforce Flex Credits | $0.10 per action ([Salesforce](https://www.salesforce.com/news/press-releases/2025/05/15/agentforce-flexible-pricing-news/)) | Salesforce | Input-metered, not outcome-metered |
| Devin | Compute units, billed whether or not the PR merges ([eesel.ai](https://eesel.ai/blog/cognition-ai-pricing)) | Cognition | n/a |

Zendesk's move to LLM-verified resolutions is itself evidence: the vendor admits the count is contestable. Yet the verifier is still the vendor.

A documented disagreement already exists. In June 2025 an Intercom employee wrote, "We didn't communicate the billing logic accurately", after customers understood that teammate replies would stop billing. The community thread is titled "Fin's flawed resolution assumption" ([Varley](https://varley.ai/blog/fin-76-percent-resolution-rate); the source is a competitor and discloses its interest).

Intercom does back Fin with money. Its official page offers a $1M payout if Fin falls below a 65% resolution rate for customers above about 250k conversations a month, plus a 90-day refund of Fin fees for new customers. Even then, the guarantee is measured by Intercom's own counter.

### 2.2 Contracts disclaim accuracy and cap liability at fees

- Mayer Brown (Feb 2026) notes that AI SaaS vendors often disclaim warranties entirely because AI is probabilistic, that standard SLAs measure only uptime, and that audit rights stop at shared SOC reports. It recommends BPO-style outcome SLAs and structured decision logs ([Mayer Brown](https://www.mayerbrown.com/en/insights/publications/2026/02/contracting-for-agentic-ai-solutions-shifting-the-model-from-saas-to-services)).
- Clifford Chance warns that agent losses "can easily exceed" the subscription fees that usually cap liability ([Clifford Chance](https://www.cliffordchance.com/content/dam/cliffordchance/briefings/2026/02/agentic-ai-theliability-gap-your-contracts-may-not-cover.pdf)).
- US OMB M-25-22 requires ongoing performance testing and monitoring clauses ([M-25-22](https://digitalgovernmenthub.org/wp-content/uploads/2025/04/M-25-22-Driving-Efficient-Acquisition-of-Artificial-Intelligence-in-Government.pdf)). The EU MCC-AI requires pre-delivery testing at probabilistic thresholds but leaves acceptance, liability and disputes to the buyer ([Covington](https://www.insideprivacy.com/artificial-intelligence/eus-community-of-practice-publishes-updated-ai-model-contractual-clauses/)).

**Reading (judgment):** the legal profession has already described this product almost clause by clause: outcome SLAs, preset thresholds, decision logs and audit rights. What none of them proposes is a **neutral party** to execute those clauses. They assume the vendor's own logs will do it.

### 2.3 Disputes are appearing

- Deloitte Australia partially refunded an approximately A$440k government report that contained fabricated citations. An outside academic caught the errors, not the buyer's acceptance process ([The Register](https://www.theregister.com/2025/10/06/deloitte_ai_report_australia/)).
- The AAA-ICDR's summary of AI arbitrations finds three recurring patterns. The most common are payment and billing disputes ([AAA-ICDR](https://www.adr.org/news-and-insights/ai-arbitrations-a-summary-of-recent-issues/)).
  1. A promised outcome is not delivered and a refund is refused.
  2. Defects are found after acceptance.
  3. A vendor sues for fees and the buyer counter-claims on quality.
- Visa's own commissioned research says agentic disputes are unsolved. Chargeback windows and evidence rules were built for human-speed commerce, and there is no settled way to unwind a payment that went wrong across a chain of agents ([Visa/Artemis](https://www.visa.com/en-us/thought-leadership/innovation/agentic-payments-from-the-ground-up)).

### 2.4 Every adjacent player sits in a different cell

| Area | Players | Whose side | When | Touches money | Gap left |
|---|---|---|---|---|---|
| Evals and observability | Braintrust, Arize, Galileo (acquired by Cisco/Splunk), Langfuse (acquired by ClickHouse) ([SiliconANGLE](https://siliconangle.com/2026/01/16/database-maker-clickhouse-raises-400m-acquires-ai-observability-startup-langfuse/)) | Producer | Pre-deploy and continuous | No | Producer's own evidence |
| Certification and assurance | AIUC-1, AICPA×AIUC, Schellman, CSA STAR ([AIUC-1](https://www.aiuc-1.com/); [Journal of Accountancy](https://www.journalofaccountancy.com/news/2026/oct/aicpa-partners-on-new-offering-that-assures-ai-systems-and-agents/)) | Producer-paid "independent" | Quarterly or annual | Only via insurance | Periodic, not per delivery |
| Outcome billing | Paid.ai, Stripe+Metronome, Orb ([Stripe](https://stripe.com/newsroom/news/stripe-completes-metronome-acquisition); [TechCrunch](https://techcrunch.com/2025/09/28/paid-the-ai-agent-results-based-billing-startup-from-manny-medina-raises-huge-21m-seed)) | Producer | Per event, vendor-reported | Invoices only | Meters reported events; does not verify them |
| Agent payments | AP2, x402, ACP, Catena ACK, Skyfire | Authorisation and rails | Before the transaction | Yes | Proves *who authorised*, not *whether work conforms* |
| Escrow plus verification | Nava ($8.3M seed) ([Fortune](https://fortune.com/2026/04/14/nava-seed-funding-ai-financial-agents/)), ERC-8183 (draft) ([EIP-8183](https://eips.ethereum.org/EIPS/eip-8183)) | Closer to neutral | Per transaction | Yes | Nava covers financial intent only; ERC-8183 has "no dispute resolution or arbitration; reject/expire is final" |
| Standards bodies | OTel GenAI, NIST CAISI, MLCommons | Neutral | Model-level or n/a | No | No "MRC for AI work"; OTel's `gen_ai.evaluation.result` records a result but does not produce one, and is still in Development ([Greptime](https://www.greptime.com/blogs/2026-05-09-opentelemetry-genai-semantic-conventions)) |

**Conclusion of §2:** the crowded cells are producer-side evaluation and periodic certification. The cell defined by **neutral × per delivery × in the money flow × with adjudication** is empty, or only partly filled. That proves a gap exists. It does not prove anyone will pay for it (§7.2).

## 3. Why it has to be shared infrastructure, not a feature

A per-vendor acceptance feature cannot do this job, for structural reasons (judgment, supported by the analogies in §5):

1. **Conflict of interest.** A vendor verifying its own work is the status quo in §2.1. Verification by the paid party is not credible to auditors or insurers. The credit-rating settlement ($1.375B, S&P 2015) is the canonical warning ([ABA Journal](https://www.abajournal.com/news/article/ratings_agency_to_pay_nearly_1.4b_to_resolve_allegations_it_inflated_rating)).
2. **Multi-vendor buyers need one yardstick.** A buyer that uses two AI vendors plus a BPO cannot compare three self-reported dashboards. A single contract format makes vendors comparable and switching cheap, which is the buyer's main lever.
3. **Insurers need comparable ground truth.**
   - Armilla pays out when accuracy falls "below a verified threshold" ([Armilla](https://www.armilla.ai/enterprise)).
   - Munich Re aiSure depends on the client establishing ground truth ([Munich Re](https://www.munichre.com/en/solutions/for-industry-clients/insure-ai/faq.html)).
   - Mainstream carriers are adding generative-AI exclusions: Verisk/ISO's optional exclusions, and WR Berkley's "absolute" AI exclusion ([Insurance Journal](https://www.insurancejournal.com/magazines/mag-features/2026/08/17/881424.htm)).
   - Specialist cover needs evidence that is consistent across insureds. One vendor's format cannot provide that.
4. **Payment rails need a portable signal.** AP2 provides a "non-repudiable, cryptographic audit trail" of *authorisation* and leaves accountability for an incorrect transaction as an open question ([ap2-protocol.org](https://ap2-protocol.org/)). The Agentic Payments Alliance (launched August 2026, 25+ firms including Visa and Mastercard) says key questions are "still being defined" ([Hangar75](https://hangar75.com/news-insights/visa-mastercard-join-neutral-standard-ai-agent-payments)). An acceptance receipt that any rail can read is the missing complement to an authorisation mandate.

## 4. How one mechanism covers many kinds of work

The obvious objection is: *"Does every new work type need its own acceptance process?"* The answer is no, if the mechanism is layered (design judgment):

| Layer | What it is | Changes per category? |
|---|---|---|
| **1. Shared engine** | Contract freezing and versioning, escrow, dispatch, the per-item state machine, deterministic settlement, the append-only ledger, the adjudication workflow, receipts | **No.** It is built once. |
| **2. Verification primitives** | Six reusable check types (below) | **No.** Each is a library. |
| **3. Category templates** | A configuration that picks primitives, fields, thresholds and ruling categories for one kind of work | **Yes**, but as data, not code |

The six primitives:

1. **Deterministic checks:** schema validation, arithmetic, referential lookups, format and range checks.
2. **Reproduction:** re-running tests or a build, or recomputing a result independently.
3. **Source grounding:** every claimed value must cite a location in the input (page and box, a URL, a line).
4. **Real-world outcome signals:** downstream facts such as an ERP posting succeeding, a PR merging, or a ticket staying closed for N days.
5. **Sampling plus judge:** a statistical sample checked by a human, an LLM, or a second independent producer.
6. **Buyer acceptance:** explicit sign-off within a window, with a default on timeout.

A new category such as claims intake reuses (1), (3), (5) and (6) and adds a template. The marginal cost of a category is therefore domain knowledge, not engineering. Without this layering the "universal" story collapses into a services company.

## 5. Historical analogies: when verification layers become currency

### 5.1 Precedents

| Precedent | Trust anchor | Who pays | Scale | Lesson for HireAI |
|---|---|---|---|---|
| **DoubleVerify / IAS** (ad verification) | MRC accreditation of a standard unit: a "viewable impression" is 50% of pixels visible for at least 1 second ([MRC](https://www.iab.com/wp-content/uploads/2015/06/MRC-Viewable-Ad-Impression-Measurement-Guideline.pdf)) | Advertiser (buyer) | DV: founded 2008, MRC-accredited 2013, $748.3M revenue in 2025 at a 33% adjusted EBITDA margin ([DV](https://s206.q4cdn.com/961864615/files/doc_financials/2025/q4/DoubleVerify-Q4-FY25-Earnings-Release.pdf)) | A countable unit plus an accreditor turns a vendor into a currency |
| **Cass** (freight audit and pay) | Sits in the payment flow | Shipper (buyer) | $38B of payables in 2023 ([Cass](https://www.sec.gov/Archives/edgar/data/708781/000070878124000004/investorpresentationq420.htm)) | Being in the money flow is stickier than issuing reports, and the float is revenue |
| **UL** (product safety) | Fire insurers funded it and relied on its mark from 1894 ([Wikipedia](https://en.wikipedia.org/wiki/UL_(safety_organization))) | Manufacturers, demanded by insurers | Global standard | A party with money at risk is the strongest anchor |
| **Credit ratings** | SEC NRSRO status, plus being written into rules and contracts | Issuer (seller) | Oligopoly | Being referenced in rules makes a verifier survive scandal; seller-pays is the most conflict-prone model |
| **Financial audit** | The 1933 Act, which codified existing practice: over 90% of NYSE firms were already audited ([CPA Journal](https://www.cpajournal.com/2020/11/25/history-of-the-auditing-world-part-1/)) | Issuer | Big Four | Mandates codify demand that already exists; they rarely create it |

**Walled gardens open late, and on their own terms.**

- Facebook admitted inflated video metrics in 2016 and accepted an MRC audit only after pressure from the ANA ([SiliconANGLE](https://siliconangle.com/2017/02/10/facebook-submits-ad-audit-prove-metrics-can-trusted)).
- YouTube added third-party brand-safety reporting only after a boycott by 250+ advertisers ([MarTech](https://martech.org/google-youtube-brand-safety-timeine/)), and even then GroupM complained the arrangements were "not always fully independent" ([Digiday](https://digiday.com/marketing/groupm-joe-barone-youtube-brand-safety/?is=1)).
- Today's outcome-priced agent vendors are where Facebook was before 2016.

**Theory adds a caution.** A monopoly certifier optimally reveals only pass or fail and captures most of the surplus, while competition pushes towards fuller disclosure ([Lizzeri 1999](https://ideas.repec.org/a/rje/randje/v30y1999isummerp214-231.html)). Acceptance is inherently pass or fail, which suits the business model. It also means buyers will eventually want competing verifiers or an open method.

### 5.2 Conditions that predict a verification layer, scored for AIaaS (late 2026)

| # | Condition (from the precedents) | AIaaS status | Judgment |
|---|---|---|---|
| 1 | A counterparty with money at risk demands verification: concentrated buyers, an insurer or a regulator | Insurers are emerging; there is no ANA-style buyer coalition | **Emerging.** This is the decisive condition. |
| 2 | A standardised, countable unit with an accepted definition of "conforming" | "Resolution" is defined by each vendor; frozen per-task specs are bespoke | **Missing** |
| 3 | Spend large enough that the fee is a small fraction | Large in aggregate, but mostly seats and direct contracts; about $1 per unit | **Partial** |
| 4 | A material defect rate the buyer cannot see | Agent-washing, predicted project cancellations ([Gartner](https://www.gartner.com/en/newsroom/press-releases/2025-06-25-gartner-predicts-over-40-percent-of-agentic-ai-projects-will-be-canceled-by-end-of-2027)); hallucination is now an insured peril | **Met** |
| 5 | An accreditor that verifies the verifier | No MRC for AI work; AIUC-1 is a privately owned commercial standard | **Missing** |
| 6 | The verifier sits in the money or contract flow | Escrow plus deterministic settlement makes this achievable | **Achievable.** This is HireAI's differentiator. |
| 7 | Weak or fragmented sellers | The top tier is integrated walled gardens; the long tail is fragmented | **Mixed** |

**Reading:** AIaaS meets the *pain* condition (4) and offers the *positioning* condition (6). It lacks the conditions that make verification a currency: a unit (2) and an accreditor (5). It also lacks the trigger, a public scandal. Historically, going from first product to currency took 5–15 years, and scandals compressed that.

## 6. Relationship to agentic payments

### 6.1 The two layers

The agentic payment stack is converging on **authorisation**, not **acceptance**:

| Protocol | What it proves | Dispute and acceptance handling |
|---|---|---|
| **AP2** (Google, now in FIDO working groups) | Who authorised what, via signed mandates | Supplies an audit trail "aiding in dispute resolution"; it describes no dispute process, escrow or delivery verification ([ap2-protocol.org](https://ap2-protocol.org/)) |
| **x402** (Linux Foundation foundation since July 2026; members include Visa, Mastercard, Stripe, Google, AWS) ([CoinDesk](https://www.coindesk.com/tech/2026/07/15/visa-mastercard-and-ripple-join-the-standard-letting-ai-agents-pay-in-stablecoins)) | Pay-per-request settlement | Has an `auth-capture` scheme. A `job-escrow` scheme that maps ERC-8183 into x402 was proposed in October 2026 and is unassigned and unanswered ([x402 #3694](https://github.com/x402-foundation/x402/issues/3694)) |
| **ACP** (OpenAI and Stripe) | Checkout for merchants | Instant Checkout was scaled back in March 2026 ([Rye](https://rye.com/blog/openai-chatgpt-checkout-agentic-commerce), a vendor) |
| **ERC-8183** | A job with escrow and a single evaluator | "No dispute resolution or arbitration; reject/expire is final" ([EIP-8183](https://eips.ethereum.org/EIPS/eip-8183)) |

**Where HireAI fits (judgment):** an auth-capture or escrow primitive on a rail needs an external signal that says *capture* or *void*. That signal is an acceptance decision. HireAI's receipt is designed to be that signal, attached next to the AP2 mandate or x402 payment it settles. HireAI therefore does not compete with the rails. It plugs into their hold-and-capture step.

### 6.2 Sizing caution

Agentic payment volume is tiny and mostly not outcome-priced work.

- a16z cut a widely quoted $24M figure for 30 days of x402 volume to about $1.6M after a wash filter, and wrote that "most of the numbers circulating are noise" ([a16z](https://a16zfrontier.com/posts/article/ai-agent-payments-honest-number)).
- TRM Labs put truly agentic x402 volume at only about $5–11k per month in 2026 (via a secondary summary, [Spotedcrypto](https://www.spotedcrypto.com/trm-labs-x402-ai-agent-payments-report-2026/)).

The near-term money is enterprise outcome-priced contracts paid on ordinary rails. On-chain agent payments are an integration target, not a revenue source.

## 7. What must become true, and what is missing

### 7.1 The open specification is the strategic asset

Being the party that first writes down the unit of accepted work is a structural advantage. MRC and IAB did this for the viewable impression. AP2 was reportedly handed to FIDO; this comes from a single source and is unconfirmed ([spark.money](https://www.spark.money/research/google-ap2-agent-payment-protocol)).

HireAI should publish two specifications:

- **Acceptance Contract spec:** the category, the output schema, the rules (each naming a primitive), the sampling plan, thresholds, rework and dispute windows, the ruling categories, and the mapping from ruling category to settlement. It is versioned and content-hashed.
- **Acceptance Receipt spec:** one per delivered item. It carries the contract hash, input and output hashes, the result of each check, sample membership with the seed, the ruling (if any), the settlement outcome and an issuer signature.
  - It is expressible as a W3C Verifiable Credential.
  - It is emittable as an OTel `gen_ai.evaluation.result` event, so it lands in the buyer's existing observability tooling.
  - It is attachable to an AP2 mandate or an x402 payment reference.

The specifications should be open. Eventually they should be donated to a neutral home, which could be a Linux Foundation project, FIDO, or an insurer and auditor consortium. The commercial moat is then **operating the reference implementation inside the money flow** and **the accumulated cross-vendor accuracy data**, not owning the format.

### 7.2 The demand gap, stated plainly

- **Today the money comes from sellers, and it buys periodic certification.** Sierra, Cursor, KPMG, Intercom Fin and UiPath hold AIUC-1, which runs for one year with quarterly retests ([AIUC-1](https://www.aiuc-1.com/)). The AICPA partnered with AIUC in October 2026 to pair SOC 2 with AIUC-1 ([Journal of Accountancy](https://www.journalofaccountancy.com/news/2026/oct/aicpa-partners-on-new-offering-that-assures-ai-systems-and-agents/)).
- **No named buyer pilot was found** for buyer-paid, per-delivery neutral acceptance. The closest are vendor marketing pieces, for example "tamper-evident receipts" ([HackerNoon](https://hackernoon.com/verifiable-ai-how-tamper-evident-receipts-can-protect-your-business-from-the-next-ai-failure)).
- **The buyer's pain is ROI more than counting.**
  - Only 14% of 200 US finance leaders see meaningful AI ROI ([CFO.com](https://www.cfo.com/news/so-far-few-cfos-see-substantial-roi-from-ai-spending-RGP/808249/)).
  - 76% of 600 finance leaders say hard-to-quantify ROI is blocking investment ([Coupa](https://www.coupa.com/resources/the-strategic-cfo-report/); vendor survey).
  - The counter-evidence: 83% of 1,000+ B2B decision makers are satisfied with agents in common use cases ([Techstrong.ai](https://techstrong.ai/features/ai-agents-drive-business-growth-as-adoption-accelerates-g2-report/)).
- **Outcome pricing is still small.** About 5% of AI SaaS companies use it as the primary model ([Orb](https://www.withorb.com/blog/outcome-based-pricing-statistics), compiling High Alpha data). 18.7% of enterprises use some outcome pricing tied to agreed metrics ([Futurum](https://futurumgroup.com/insights/salesforces-agentforce-help-agent-bets-on-pay-per-resolution-will-enterprises-trust-the-model/)).
- **No survey has asked buyers** whether they trust vendor-reported outcomes or would pay a third party to check. **This is the largest evidence gap in the thesis.**

### 7.3 Who pays

**Proposed structure (judgment):**

| Payer | Why they would pay | Conflict | Role in the structure |
|---|---|---|---|
| Buyer | Pay less for bad work; get dispute evidence | Will push acceptance standards up to pay less | **Main fee payer**, via escrow and settlement fees. This avoids the issuer-pays conflict. |
| Seller | Unlocks enterprise deals, insurance and a badge | Issuer-pays | Subscription for a shareable continuous-verification report |
| Insurer | Needs ground truth to price and settle claims | Wants to pay out less | Buys aggregated data; **never** takes part in individual rulings |

### 7.4 Absorption risk

The paths by which incumbents could absorb this layer are short:

- **AIUC** already bundles standard, testing and insurance. Moving from quarterly testing to continuous runtime monitoring is one step ([RITS](https://rits.shanghai.nyu.edu/ai/aiuc-1-the-ai-agent-certification-turns-to-coding-agents/)).
- **Stripe** owns metering (Metronome) and hold primitives.
- **Eval tools** can add a signed, shareable report cheaply.
- **Verification data is consolidating:** InfoWorld framed ClickHouse's purchase of Langfuse as data platforms racing to own "the AI feedback loop" (cited via [scour.ing](https://scour.ing/@hello/p/https://www.infoworld.com/article/4118621/clickhouse-buys-langfuse-as-data-platforms-race-to-own-the-ai-feedback-loop.html)).

**Defence (judgment):**

1. Be **in the money flow**, because report-only verifiers get absorbed. The ThousandEyes-to-Cisco exit at about $1B is the good version of that outcome ([Data Center Knowledge](https://www.datacenterknowledge.com/cisco/cisco-buys-startup-thousandeyes-add-software-services)).
2. **Own the open spec's reference implementation.**
3. **Stay neutral and focused.** Moat died not because the category failed but because its parent, Oracle, exited advertising ([Marketing Dive](https://www.marketingdive.com/news/oracle-exits-advertising-business-revenue-falloff/718832/)).

## 8. Path, economics and odds

### 8.1 Sequencing from wedge to standard (judgment)

1. **Year 0–1: wedge.** Acceptance-before-payment for AP invoice extraction with 3–5 buyers. Measure the gap between vendor-reported and accepted counts.
2. **Year 1: publish the specs.** Open-source the Acceptance Contract and Receipt specs, and their validators, with a design-partner insurer or auditor named in the working group.
3. **Year 1–2: second category.** Insurance claims intake, reusing the primitives, proves the three-layer claim in §4.
4. **Year 2–3: recognition.** At least one insurer prices a policy off receipts, and at least one auditor accepts them as control evidence.
5. **Year 3+: rails and neutral home.** Receipts drive capture and void on at least one payment rail's hold primitive, and the specs move to a neutral foundation.

### 8.2 A $100M ARR mix (all assumptions, years 7–9)

| Line | Arithmetic (assumption) | ARR |
|---|---|---|
| Buyer acceptance and settlement fees | 300 enterprises × $15M of outcome-priced agent spend through the layer = $4.5B × 1% | $45M |
| Seller continuous-verification reports | 1,200 vendors × $30k a year (comparable: Vanta passed $300M ARR in April 2026 ([Fortune](https://fortune.com/2026/04/29/exclusive-vanta-arr-300-million-sequoia-shadow-ai-claude-cursor/))) | $36M |
| Insurer and auditor data and evidence | 40 programmes × $250k | $10M |
| Adjudication fees | 50k disputes × $200 | $10M |
| **Total** | | **About $101M** |

No single line reaches $100M alone. The largest line needs about $4.5B of outcome-priced agent spend flowing through HireAI, and **no credible estimate of total outcome-priced agent spend was found**. That figure becomes plausible only if outcome pricing goes from about 5% to the forecast 25% by 2028 ([Growth Unhinged](https://growthunhinged.com/p/usage-based-pricing-for-ai); this is a forecast). Given the 5–13-year time-to-currency in the precedents, $100M ARR most likely lands outside a five-year window.

### 8.3 Odds over five years (subjective)

These assume a competent team and adequate funding.

| Outcome | Probability |
|---|---|
| Large ($100M+ ARR or $1B+ valuation) | About 3% (range 2–4%) |
| Mid ($10–100M, or a strategic exit above $100M) | About 10% |
| Small but real ($1–10M) | About 20% |
| Pivot or asset sale | About 27% |
| Shutdown | About 40% |

These odds are higher than for the marketplace form, because the thesis no longer needs heavy stranger-to-stranger procurement or specialists that keep beating generalists. The large outcome barely moves, because the unit, the accreditor and the scandal are all outside the company's control.

### 8.4 Signals that would move the odds

**Upward:**

- A public scandal over billed-but-unresolved outcomes.
- A buyer coalition, or a large procurer, demanding third-party outcome audits.
- Outcome pricing clearly moving off about 5%.
- An MRC-like body opening a definition of "accepted task".
- An insurer pricing on per-delivery evidence.

**Downward:**

- AIUC announcing continuous runtime monitoring.
- Stripe shipping outcome holds with pluggable evaluators.
- Salesforce or Intercom offering third-party-audited counts through someone else.
- ERC-8183-style cheap evaluators commoditising the role.

## 9. Evidence limits and disclosure

**Evidence limits:**

- Many outcome-pricing definitions come from third-party or competitor blogs.
- Sierra and Decagon pricing is under NDA.
- AIUC funding figures conflict between sources, and neither AIUC-1 certification nor AI insurance has public prices.
- Enterprise MSAs are private, so "no vendor offers an outcome warranty tied to the billing unit" was not exhaustively checked.
- Whether Microsoft, AWS, OpenAI or Anthropic offer native counterparty outcome verification was not established.
- Non-English markets were not researched.
- The two biggest gaps are on the demand side: no buyer willingness-to-pay survey, and no estimate of total outcome-priced agent spend.

**Disclosure:** this document was drafted by an Anthropic model, and the evidence has three Anthropic-related points:

1. Anthropic co-founder Ben Mann invested in AIUC's seed round ([Fortune](https://www.fortune.com/2025/07/23/ai-agent-insurance-startup-aiuc-stealth-15-million-seed-nat-friedman)). AIUC is both the key analogy and the most likely absorber.
2. Metronome, now owned by Stripe, meters billing for Anthropic, so Anthropic takes part in vendor-side metering.
3. Anthropic is one of the large vendors that could form a walled garden.

Removing those points does not change the conclusion. The core gap evidence comes from Intercom, Zendesk, Salesforce, law firms and arbitration bodies.
