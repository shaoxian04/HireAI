# HireAI — FYP1 Proposal Defense · Speaker Script (6 minutes)

**Presenter:** Tang Shao Xian (23004913) · **Supervisor:** Dr. Mohamad Hazim bin Md Hanif
**Deck:** `fyp_viva_slide.pptx` (29 slides) · **Target:** 6:00 · ~850 words · speak at a calm ~140–150 wpm

> **How to use this:** each block is one slide. The **[time]** is how long to spend before advancing. Lines are written to be *spoken*, not read word-for-word — say them naturally. The three Functional-Requirements slides and the diagram slides are deliberately quick "flip-through" lines: point, summarise, move on. Don't read the FR lists aloud.

---

### Slide 1 — Title · **[0:08]**
Good morning. I'm Tang Shao Xian, and my project is **HireAI** — a task-driven distribution and execution platform for AI agents. My supervisor is Dr. Mohamad Hazim.

### Slide 2 — Outline · **[0:07]**
I'll move from the problem and background, through my objectives and literature, into the methodology, requirements and design, and finally the technical implementation.

### Slide 3 — Introduction & Background · **[0:20]**
Between 2023 and 2026, AI shifted from being a simple feature inside apps to **autonomous agents** that can plan, use tools, and complete multi-step tasks on their own. The supply of these agents is growing very fast — but the infrastructure to discover them, hire them, and hold them accountable hasn't kept up. That gap is what HireAI addresses.

### Slide 4 — Problem Statement · **[0:25]**
There are three connected problems. **First, no neutral discovery** — agents are scattered across GitHub and locked marketplaces, with nineteen thousand-plus unvetted listings. **Second, no accountability** — when an agent delivers bad work, there's no way to determine fault or impose consequences. **Third, no monetisation** — independent builders have no standard way to earn. These reinforce each other, so HireAI solves all three together.

### Slide 5 — Research Questions · **[0:15]**
This gives three research questions: can a declared output spec act as a **machine-checkable contract**; can a hybrid of rules, LLM arbitration, and human review resolve disputes **fairly at scale**; and can reputation and escrow make the marketplace **self-improving**.

### Slide 6 — Research Objectives · **[0:17]**
From those, five objectives — each tied to a module: a **binding output contract** in submission and registration; a **routing engine**; **layered validation with three-tier disputes**; **reputation scoring with escrow settlement** on an append-only ledger; and finally, **measurable evaluation** of all of it.

### Slide 7 — Scope of Study · **[0:12]**
In scope: all six modules, a virtual credit economy with escrow, and webhook-based agent integration. Out of scope: real-money payment, platform-hosted execution, and the MCP and A2A protocols — those are compatible future extensions.

### Slide 8 — Significance · **[0:12]**
The core contribution is reframing agents from passive **tools** into accountable **service providers** — judged against a declared contract, with an LLM arbitrator whose ruling maps to a **deterministic** settlement. No existing platform combines all of this.

### Slide 9 — Literature Review & Research Gap · **[0:25]**
This matrix compares the field. Enterprise marketplaces are closed; open directories give no trust or payment layer; and human platforms like Fiverr don't fit autonomous agents. None combine all five capabilities. My reputation design follows the **FIRE model** — several weighted signals that decay over time, so recent behaviour counts most. And research shows well-run agent markets create over **three times** the value of isolated agents — exactly what HireAI targets.

### Slide 10 — Proposed Methodology · **[0:22]**
My approach is **Design Science Research** — I build and evaluate the platform as a working artifact, using **Iterative and Incremental Development**. The five phases map onto the modules, and we are currently here, in core construction. I chose this because the modules build on each other, the AI parts need empirical tuning rather than fixed upfront design, and every iteration ends in something I can demonstrate to my supervisor.

### Slide 11 — Requirements in Modules · **[0:16]**
The requirements are organised into the six modules — **37 functional requirements** in total, gathered through comparative analysis, the literature, scenario walkthroughs, and supervisor consultation. The defining ones: **escrow freeze on submit**, a declared **output spec**, and an **append-only ledger** for every credit movement.

### Slides 12–14 — Functional Requirements (M1–M6) · **[0:25 total — flip through]**
These three slides list every functional requirement, module by module — I'll let them speak for themselves. The themes to notice: credits **freeze the moment a task is submitted**; outputs are **validated against the spec before the client ever sees them**; disputes **escalate from rules, to LLM arbitration, to human review**; and every credit movement is **logged immutably**.
> *(Advance through all three at roughly 8 seconds each — pause on each so the panel can scan, but don't read the lines.)*

### Slide 15 — Analysis & Design (Actors) · **[0:15]**
The system has **six actors**: the client who funds tasks, the builder who monetises agents, the third-party AI agent that executes them, an internal LLM arbitrator, a human administrator for escalations, and the platform itself — handling routing, escrow, and the ledger.

### Slide 16 — Analysis & Design (Architecture) · **[0:17]**
Architecturally it's a **polyglot three-service design**: a Next.js frontend, a Spring Boot core built with domain-driven design — where each bounded context maps to a module — and a separate Python arbitration service, over PostgreSQL and RabbitMQ. Importantly, the LLM is kept away from ever moving money.

### Slide 17 — Technical Implementation 1/5: Core Modules · **[0:18]**
Now the technical implementation — and this is already **real**, not just planned. Six core modules, four going well beyond the template minimum. The transactional spine — submission, registration, routing — and the discovery module are **built**; the settlement core is **partly done**; and Module 4, validation and disputes, is scheduled for **FYP2**.

### Slide 18 — Technical Implementation 2/5: Technology Stack · **[0:18]**
The stack is chosen per concern. **Spring Boot** with domain-driven design carries the transactional money logic. **PostgreSQL** gives ACID guarantees for escrow. **RabbitMQ** gives reliable dispatch with dead-letter queues, so no task is lost or double-sent. And the **Claude-based arbitrator** is isolated in its own Python service, so the AI can evolve independently of the money path.

### Slide 19 — Technical Implementation 3/5: Data Flow Diagram · **[0:12]**
This data-flow diagram traces a task end to end — from submission and escrow freeze, through routing and dispatch to the agent, to validation and final settlement.

### Slide 20 — Entity-Relationship Diagram · **[0:11]**
The ER diagram shows the schema in third normal form — wallet, task, agent, result, and the **append-only ledger** at the centre of settlement.

### Slide 21 — State Machines · **[0:11]**
These state machines define the status lifecycles — a task moves from submitted, through executing, to resolved; an agent from registration to active or suspended. Every transition is **guarded**.

### Slide 22 — Project Screenshot · **[0:15]**
And here's the platform actually **running**. The full happy path works end to end — login, top-up, task submission with escrow freeze, dispatch, result, and settlement — all live.

### Slide 23 — Stakeholder Collaboration · **[0:12]**
I'm also consulting with **Wei Bao Zheng**, a Team Lead on the Chargeback Team at **Ant International**. His experience with real payment disputes and settlement workflows directly shaped my dispute-resolution and escrow design.

### Slide 24 — Expected Outcomes & Contributions · **[0:12]**
The deliverables are the **working platform**, the **arbitration service** with its reusable dispute corpus, and an **evaluation report** — measured on routing accuracy, validation precision, arbitration consistency, and settlement correctness.

### Slide 25 — Project Timeline · **[0:10]**
This is the timeline across the phases — inception and core construction in FYP1, with the trust layer, settlement, and evaluation continuing into FYP2.

### Slide 26 — Project Logbook · **[0:07]**
And the logbook records my supervisor checkpoints and progress across each iteration.

### Slide 27 — Conclusion · **[0:18]**
To conclude: AI agents are everywhere, but the infrastructure to trust and pay them isn't. HireAI builds exactly that — binding contracts, escrow, three-layer disputes, and reputation, in one platform. And feasibility is already proven: the marketplace spine runs end to end with around **three hundred tests passing**.

### Slide 28 — References · **[0:03]**
My references are listed here in APA style.
> *(Advance — don't read.)*

### Slide 29 — Thank You / Q&A · **[0:07]**
Thank you for your time. HireAI brings discovery, accountability, and monetisation into one platform — and I'm happy to take your questions.

---

## Timing checkpoints (glance at the clock here)

| At this slide | Clock should read |
|---|---|
| Slide 5 (Research Questions) | ~1:25 |
| Slide 9 (Literature Review) | ~2:35 |
| Slide 14 (end of FR slides) | ~3:30 |
| Slide 18 (Tech Stack) | ~4:35 |
| Slide 24 (Outcomes) | ~5:25 |
| Slide 29 (Thank You) | ~6:00 |

**If you're running over at the Literature slide (2:35+):** trim Slides 7, 8, 19, 20, 21, 25, 26 to a single sentence each — they're low-rubric or transitional. **Never** rush Problem (4), Literature (9), Methodology (10), or the Technical Implementation block (17–22): those carry the marks.

---

## Delivery tips
- **Pause after each section heading**, not in the middle of a sentence. A one-second pause as you advance reads as confidence.
- The script is ~850 words. If your natural pace is slow, cut the diagram-slide lines (19–21) to one line total — that buys ~20 seconds.
- **Look up** on the bolded phrases — those are your emphasis words.
- End on time. Finishing at 5:55 with a clean "thank you" beats running to 6:30.

## Prepared Q&A (likely panel questions)
- **How do you ensure LLM arbitration is fair?** → The LLM only produces a *ruling category*; the actual credit movement is computed deterministically in code. A synthetic dispute corpus tests its consistency, and either party can escalate to human review. The LLM never moves money.
- **Why virtual credits instead of real money?** → It keeps the full escrow architecture while avoiding payment regulation and fraud handling, which are out of FYP scope but architecturally compatible later.
- **Why RabbitMQ?** → Reliable at-least-once delivery, dead-letter queues for timeouts — so no task is lost or dispatched twice under failure.
- **Why Java/Spring for the core, not Python end-to-end?** → The money path needs transactional consistency and mature security tooling; Python is reserved for the AI layer where its ecosystem is strongest — clean separation between the two.
- **How is the output spec enforced on free-form output?** → The spec is structural and machine-checkable — format, minimum length, required sections. Semantic quality goes to client review and, if contested, arbitration.

---

## ⚠ Two consistency notes before you present
1. **FR count:** Slide 11 says "37 functional requirements", but Slides 12–14 list 36 (6+5+7+8+7+3). Either fix Slide 11 to 36, or it's fine to say "thirty-seven" and trust the headline — just be aware if the panel counts.
2. **Timeline weeks:** Slide 10 (Methodology) shows an **18-week, 5-phase** plan with "we are here" in core construction. If the image on Slide 25 is the 14-week FYP1-only Gantt, the two slides imply different totals. The script wording on Slide 25 is neutral ("across the phases") so it works either way — but if the panel asks, explain that the 18 weeks span FYP1 + FYP2, and FYP1 itself is the first ~14-week block ending at core construction.
