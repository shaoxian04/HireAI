# HireAI — FYP1 Viva Q&A Prep

Anticipated panel questions with model answers. Each has a **one-line answer** (say this first, confidently) and **backup** (if they push). Grouped by theme. The ⚠ marks the questions most likely to expose a weakness — rehearse those hardest.

---

## A. Problem & Motivation

**A1. Is this a real problem, or are you inventing a need?**
- **One-line:** It's real and widening — there are 19,000+ AI agents in public directories with zero quality signals, no payment layer, and no accountability; the supply exists but the marketplace infrastructure doesn't.
- **Backup:** Directories like Glama and MCP.so prove demand for discovery but offer no trust or monetisation. Enterprise marketplaces (Salesforce, Google) solve it only inside locked ecosystems. The gap is structural, not imagined.

**A2. Why would anyone use a *neutral broker* instead of just calling an agent's API directly?**
- **One-line:** Direct API calls give you no recourse if the output is wrong, no way to compare providers, and no payment protection — HireAI adds escrow, validation, disputes, and reputation on top of that raw call.
- **Backup:** It's the same reason people use Upwork instead of hiring a stranger directly — the platform's trust mechanisms are the value, not the connection itself.

**A3. ⚠ Isn't this just Fiverr/Upwork for AI? What's actually new?**
- **One-line:** The novelty is *automated specification validation* — because agent outputs are machine-readable, I can check them against a declared contract automatically *before* the client sees them, which human marketplaces can't do.
- **Backup:** Human platforms rely on interpersonal communication and manual review. My three additions are: a machine-checkable output contract, an LLM arbitrator with deterministic settlement, and reputation built from objective system events rather than star ratings.

---

## B. Objectives & Scope

**B1. Your objectives are broad for one student. How is this achievable?**
- **One-line:** The six modules form a layered dependency, so I deliver them incrementally — and the core spine (Modules 1–3 plus discovery) is *already built and tested*, which proves the scope is realistic.
- **Backup:** Module 6 is explicitly a stretch deliverable, and the riskiest piece (LLM arbitration) gets the longest single iteration. I can declare a viable submission at the end of any phase from the trust layer onward.

**B2. Why virtual credits instead of real money?**
- **One-line:** It preserves the entire escrow architecture while avoiding payment-processing, fraud, and regulatory complexity — which are out of scope for an FYP but architecturally compatible later.
- **Backup:** The design properties I'm evaluating — freeze, hold, settle, audit — are identical whether the credits are virtual or real. Swapping in a real payment gateway is a future integration, not a redesign.

**B3. Why HTTPS webhooks only? Why not support MCP or A2A?**
- **One-line:** Webhook is the simplest universal integration that every self-hosted agent can support; MCP and A2A are compatible future adapters on the same output-contract model.
- **Backup:** My contract abstraction doesn't depend on the transport — adding an MCP adapter later doesn't change validation, disputes, or settlement.

---

## C. Literature & Novelty

**C1. ⚠ Which papers most directly shaped your design, and how?**
- **One-line:** The FIRE model (Huynh et al., 2006) shaped my reputation scoring — multiple weighted signals with temporal decay; and Park et al. (2026) justified the whole premise, showing agent markets create 3.2× the value of isolated agents *given the right institutions*.
- **Backup:** Rochet & Tirole's two-sided market theory framed the cold-start and trust-balancing problem; the hybrid dispute-resolution literature justified pairing rules with arbitration and human escalation.

**C2. The FIRE model is for distributed agents rating each other. You're centralised. Does it still apply?**
- **One-line:** I adopt FIRE's *principles* — blend multiple time-decaying signals, not its distributed mechanism. In a centralised platform I compute the score from authoritative records, which is actually more tamper-resistant than agent-reported witness data.
- **Backup:** That's a deliberate simplification: I drop FIRE's witness and certified-reputation components because the platform already owns reliable event data (timeouts, spec violations, dispute losses), avoiding the collusion risk those components carry.

**C3. Your comparison matrix — is it fair? Aren't you comparing different categories of system?**
- **One-line:** Yes, deliberately — the point is that *no single category* covers all five capabilities: enterprise marketplaces, open directories, and human platforms each cover a subset, and HireAI is the integration of all five for AI agents.
- **Backup:** I'm not claiming HireAI beats Salesforce at enterprise integration; I'm showing the specific combination — open access + discovery + monetisation + reputation + automated validation + disputes — doesn't exist yet.

---

## D. Methodology

**D1. Why Iterative & Incremental and not Scrum, Waterfall, or pure prototyping?**
- **One-line:** Waterfall can't accommodate the empirical tuning the AI components need; Scrum needs team roles a solo project can't fill; IID gives me dependency-ordered increments that each end in something demonstrable.
- **Backup:** It's also defensible academically — each iteration is a supervisor checkpoint with a tangible artifact, instead of a long spec phase with no observable progress.

**D2. How do you evaluate the system? What does "success" look like?**
- **One-line:** Five measurable criteria — routing accuracy, validation precision, arbitration consistency and human-agreement rate, settlement correctness via ledger reconstruction, and reputation validity — tested under simulated and live usage.
- **Backup:** For arbitration specifically I built a synthetic dispute corpus so I can measure ruling consistency repeatably before going live.

**D3. ⚠ What's your biggest risk and how are you managing it?**
- **One-line:** The LLM arbitration consistency — an LLM might rule differently on similar cases. I mitigate with the synthetic corpus to measure it, deterministic settlement so the *money* is never at the LLM's discretion, and a human-review fallback.
- **Backup:** Secondary risks are scope overrun (mitigated by IID + Module 6 as stretch) and polyglot integration complexity (mitigated by establishing service boundaries early).

---

## E. Architecture & Technical (Mastery of Tools — highest weight)

**E1. Why a polyglot architecture? Why not all-Java or all-Python?**
- **One-line:** Each concern uses its best tool — Java/Spring for the transactional money path because it needs consistency and security, Python for the arbitrator because it has the strongest LLM ecosystem — connected by clean REST and message-queue boundaries.
- **Backup:** Isolating the AI service means it can be retrained or replaced without touching the money logic, and it structurally enforces that the LLM never moves credits.

**E2. Why RabbitMQ? Couldn't you just call the agent synchronously?**
- **One-line:** Agent webhooks are long-running and can fail; RabbitMQ gives at-least-once delivery, dead-letter queues for timeouts, and acknowledgements so no task is lost or double-dispatched.
- **Backup:** It also decouples submission from execution, so the two can scale independently — a synchronous call would block and couple them.

**E3. Why Domain-Driven Design? Isn't that over-engineering for an FYP?**
- **One-line:** The domain *is* the hard part — escrow, settlement, reputation, and dispute state transitions each have invariants that must never break, and DDD's bounded contexts map one-to-one to my modules, keeping that logic isolated and testable.
- **Backup:** It's not over-engineering when correctness of money movement is the core deliverable; the structure is what lets me unit-test the invariants in isolation.

**E4. ⚠ Why PostgreSQL? Why a relational DB and not NoSQL?**
- **One-line:** Money needs ACID transactions — escrow freeze and settlement must be atomic, and the append-only ledger needs relational integrity; PostgreSQL also gives me JSONB for the variable output specs, so I get both.
- **Backup:** A document store would make transactional consistency across wallet, task, and ledger much harder; I use JSONB only where the schema genuinely varies (output specs).

**E5. How do you guarantee an escrow freeze and a task creation happen together — what if it crashes halfway?**
- **One-line:** They're in a single database transaction — either both commit or neither does; partial state never persists, which is one of my hard invariants.
- **Backup:** And settlement uses a pessimistic lock on the wallet so two concurrent operations can't double-spend or race.

---

## F. Dispute Resolution & LLM Arbitration (the most-probed area)

**F1. ⚠⚠ How do you ensure the LLM arbitrator is fair and consistent?**
- **One-line:** The LLM only produces a *ruling category* — Fulfilled, Partially Fulfilled, or Not Fulfilled — and the actual credit movement is computed deterministically in code from that category. The LLM never touches money.
- **Backup:** I test ruling consistency against a synthetic dispute corpus, give it a structured case file (task + spec + output + complaint), and let either party escalate a ruling to human review. So even if it errs, there's a deterministic settlement and a human safety net.

**F2. What if the LLM hallucinates or rules incorrectly?**
- **One-line:** Three safeguards: it's constrained to three ruling categories so it can't invent an outcome; settlement is deterministic; and either party can flag the ruling for a human administrator who issues the final decision.
- **Backup:** I also record whether the human agreed with the LLM — that agreement rate is one of my evaluation metrics, so I can measure and improve arbitration quality over time.

**F3. Why use an LLM at all — why not just rules?**
- **One-line:** Rules handle unambiguous cases like "I changed my mind," but judgement cases — "the output doesn't match what I asked for" — need semantic understanding that rules can't express; that's exactly where the LLM sits, between rules and humans.
- **Backup:** This three-layer design is the standard hybrid model from the dispute-resolution literature: rules for scale, arbitration for the long tail, humans for the edge cases.

**F4. Couldn't a client just spam disputes to get refunds?**
- **One-line:** No — "I changed my mind" disputes get no refund and increment a bad-faith counter, and past a threshold the client pays a fee surcharge; genuine disputes go to arbitration where the output is judged on its merits.
- **Backup:** The classification step forces the client to pick a reason category up front, which routes bad-faith disputes straight to the no-refund rule.

**F5. The output spec is structural (format, length, sections). How do you judge *semantic* quality — whether the content is actually good?**
- **One-line:** Automated validation only checks the structural contract; semantic quality is the client's call at review, and if contested, the LLM arbitrator evaluates the output against the task and spec.
- **Backup:** This is deliberate — structural checks are objective and run before the client sees anything; semantic judgement is subjective and belongs with the human or the arbitrator, not an automated gate.

---

## G. Reputation & Settlement

**G1. How is your reputation score harder to game than star ratings?**
- **One-line:** It's built from *objective system events* — task success, spec violations, timeouts, dispute losses — not subjective opinions, so it can't be inflated by fake 5-star reviews.
- **Backup:** Temporal decay over a 30-day window means an agent can't coast on old performance, and explicit negative signals mean bad behaviour actively lowers the score rather than just diluting it.

**G2. Why 15% commission? Why 85/15?**
- **One-line:** It's a fixed, transparent platform fee in the range commercial marketplaces use (Fiverr takes ~20%); the exact figure is a configurable policy, not hard-coded business logic.
- **Backup:** What matters architecturally is that the split is computed deterministically in the settlement domain service from the ruling — the percentage is a policy parameter I can tune.

**G3. ⚠ Walk me through what happens to the money when a dispute is "Partially Fulfilled."**
- **One-line:** The escrow is split 50/50 — half refunded to the client's available balance, half paid to the agent — and both movements are written to the append-only ledger as separate entries.
- **Backup:** The LLM produces the "Partially Fulfilled" ruling; the settlement domain service deterministically computes the 50/50 split; nothing is mutated, only new ledger entries are appended, so the whole thing is auditable.

**G4. What stops the ledger from being tampered with?**
- **One-line:** The ledger is append-only, enforced by database triggers that raise an error on any UPDATE or DELETE — corrections are made by posting compensating entries, never by editing history.
- **Backup:** That means the entire settlement history is always reconstructable from the ledger, which is one of my hard invariants and the basis for the "settlement correctness" evaluation metric.

---

## H. Security & Ethics

**H1. How do you stop a malicious actor from faking a result callback?**
- **One-line:** Each dispatch carries a short-lived signed token; the result callback is rejected with 401 unless it presents a valid, unexpired token — and all agent I/O is HTTPS-only.
- **Backup:** That's a separate authentication channel from user JWTs — agents authenticate per-task with the dispatch token, users authenticate with their session JWT.

**H2. How do you ensure a builder can't act on another builder's agent, or a client on another's task?**
- **One-line:** Identity is derived server-side from the JWT, never from the request body, and every path or body ID gets an explicit ownership check before the action proceeds.
- **Backup:** That's enforced in the security layer as a hard invariant — builders act only on agents they own, clients only on their own tasks and wallet.

**H3. What are the ethical risks of an AI agent marketplace?**
- **One-line:** Three main ones — malicious agents, review manipulation, and the AI arbitrator's fairness; I address them with sandboxed scope, objective reputation signals, and human-reviewable arbitration, while acknowledging advanced anti-abuse is future work.
- **Backup:** I'm explicit in my scope that the MVP assumes honest baseline behaviour with basic mitigations (rate limiting, dispute logging); adversarial robustness (sybil, collusion) is named as future work rather than over-claimed.

---

## I. Feasibility & Progress

**I1. How much have you actually built versus planned?**
- **One-line:** The full marketplace spine — task submission with escrow, agent registration, routing and execution, discovery, and JWT auth — plus the settlement core, are built and running with around 300 automated tests passing.
- **Backup:** What remains is Module 4 (validation and disputes, including the LLM arbitrator) and the reputation engine — both scheduled for FYP2, with the arbitration design already de-risked by the synthetic corpus.

**I2. ⚠ If you ran out of time, what's the minimum viable submission?**
- **One-line:** The core transactional loop — submit, route, execute, return result, accept, settle — already works end to end; that alone demonstrates the marketplace thesis. Everything beyond is additive.
- **Backup:** Because each phase ends in a working increment, I can stop after the trust layer and still have a coherent, defensible system; Module 6 is explicitly droppable.

**I3. How do you test something that depends on external agents you don't control?**
- **One-line:** I run a local stub agent behind an HTTPS tunnel for live demos, and my integration tests use Testcontainers to spin up real Postgres and RabbitMQ — so the whole pipeline is tested without depending on third-party agents.
- **Backup:** That also lets me deterministically simulate timeouts, malformed payloads, and spec violations to test the failure paths.

---

## Quick-fire facts to have memorised (in case they drill)
- **6 modules · ~37 functional requirements · 6 actors · 38 use cases**
- **Settlement:** Accept → 85/15 (15% commission) · Partial → 50/50 · Reject/Not Fulfilled → full refund · double spec-violation → 80% refund / 20% fee
- **Reputation weights:** success 50% · spec-violation 20% · timeout 20% · dispute-loss 10% · 30-day decay · auto-suspend below threshold or >30% dispute loss
- **Dispute categories:** (a) doesn't match, (b) factually wrong, (c) incomplete → LLM arbitration; (d) changed my mind → rule-based, no refund
- **Stack:** Next.js 16 / Spring Boot Java 21 DDD / PostgreSQL / RabbitMQ / Python LangGraph + Claude / Railway
- **Hard invariants:** escrow before execution · append-only money · deterministic money path (LLM never moves credits) · output spec is the binding contract · server-side identity from JWT · signed HTTPS-only agent I/O

---

## Delivery advice for Q&A
- **If you don't know:** "That's a good point — in the current design I haven't addressed that; my instinct would be [X], and I'd validate it in the [phase]." Honesty beats bluffing; the panel tests how you *think*.
- **Don't over-claim.** Where something is future work, say so — you've scoped it deliberately, and that's a strength.
- **Bridge back to your invariants.** Most technical questions can be answered by pointing to one of the six hard invariants — they're your safety net.
- **Pause before answering.** A two-second think reads as considered, not unsure.
