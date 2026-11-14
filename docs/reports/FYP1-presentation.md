# HireAI — FYP1 Proposal Defense (follows faculty template, 23 slides, ~5 min)

**Presenter:** Tang Shao Xian (23004913) · **Supervisor:** Dr. Mohamad Hazim bin Md Hanif
**Structure:** matches `FYP1_Proposal_Slide_Template` exactly — same slide order, same rubric mappings. Slide 13 (Technical Implementation, **P7 = 20%, the largest rubric weight**) is expanded into five sub-slides **13a–13e**, one per template component.
**Timing:** ~5:10 total. Airtime follows rubric weight: the P7 block gets ~80s (~26% of the talk), P6 Analysis & Design (10%) gets 28s. Several low-weight slides are *flash slides* (≤10s).

> **Design note:** keep the faculty template's master styling — don't fight it. Apply the HireAI brand (graphite `#0E1116`, acid-lime `#C6F211`, Archivo/JetBrains Mono) only **inside the diagrams and screenshots you paste**, so visuals match the live product without breaking template consistency. ≤5 bullets per slide; the script carries the detail.

> **Diagrams you need to paste manually** are marked `📌 PASTE`. Full list at the bottom.

---

## Slide 1 — Title (0:00–0:10)

**On slide**
> # HireAI — A Task-Driven AI Agent Distribution & Execution Platform
> FYP1 — Proposal Defense
>
> Presented by: Tang Shao Xian · Matric No.: 23004913
> Supervisor: Dr. Mohamad Hazim bin Md Hanif
> Faculty of Computer Science and Information Technology, Universiti Malaya
> Session 2025/2026 — Semester 2

**Speaker notes (10s)**
> Good morning to the panel and everyone present. I'm Tang Shao Xian, supervised by Dr. Mohamad Hazim. My project is HireAI — a task-driven distribution and execution platform for AI agents.

---

## Slide 2 — Outline (0:10–0:18)

**On slide**
> 1. Introduction & Background
> 2. Problem Statement
> 3. Research Questions & Objectives
> 4. Scope & Significance
> 5. Literature Review & Research Gap
> 6. Proposed Methodology
> 7. Requirements, Analysis & Design
> 8. Technical Implementation Plan *(5 parts)*
> 9. Outcomes, Timeline & Conclusion

*Addresses: Panel P9; Supervisor S10*

**Speaker notes (8s)**
> I'll move from the problem through the literature gap, methodology, requirements and design, then spend the largest block on the technical implementation plan, closing with outcomes and timeline.

---

## Slide 3 — Introduction & Background (0:18–0:32)

**On slide**
> - **Domain:** AI Agent marketplaces — the economic infrastructure for autonomous AI services
> - **Why now:** 2023–2026 shift from AI-as-feature to autonomous agents (planning, tool use, multi-step execution); AI agents market projected to scale rapidly to 2030 (MarketsandMarkets, 2025)
> - **Stakeholders:** independent agent builders, task clients, enterprises adopting agents
> - **Motivation:** agents proliferate, but the infrastructure to discover, hire, and hold them accountable has not kept pace

*Addresses: Supervisor S2 (Introduction — LO1, LO5)*

**Speaker notes (14s)**
> Between 2023 and 2026, AI moved from embedded features to autonomous agents that plan, reason, and execute tasks end-to-end. Supply of capable agents is exploding — but the economic and trust infrastructure around them hasn't kept pace. HireAI proposes to build that missing infrastructure.

---

## Slide 4 — Problem Statement (0:32–0:52)

**On slide**
> **Core problem:** capable AI agents exist, but no neutral infrastructure makes them *discoverable, accountable, and monetisable*.
>
> - **Ideal:** clients find, evaluate, and hire specialist agents with recourse when work fails
> - **Current reality:**
>   - **P1 — No neutral discovery:** agents scattered across GitHub, locked marketplaces, 19k+ unvetted directory listings (Glama.ai, MCP.so)
>   - **P2 — No accountability:** bad output → no fault-finding, no recourse, no consequence
>   - **P3 — No monetisation:** independent builders have no standard channel to earn
> - **Solution direction:** a neutral broker marketplace with escrow, validation, disputes, and reputation
> - **Why now:** the three problems are mutually reinforcing — the gap widens as agents multiply

*Addresses: Panel P2 (2%); Supervisor S5 (LO1, LO5)*

**Speaker notes (20s)**
> Three interlocking problems. Discovery: specialist agents are scattered, and directories list nineteen thousand MCP servers with zero quality signals. Accountability: when an agent delivers bad work, there's no process to determine fault or impose consequences. Monetisation: independent builders can't earn, so specialist supply stagnates. These reinforce each other — solving one alone cannot break the cycle.

---

## Slide 5 — Research Questions (0:52–1:02)

**On slide**
> - **RQ1:** Can a builder-declared output specification serve as a binding, *machine-checkable contract* for validating heterogeneous AI agent outputs?
> - **RQ2:** Can a hybrid pipeline — deterministic rules + LLM arbitration + human escalation — resolve task disputes *fairly, consistently, and at scale*?
> - **RQ3:** Do reputation-weighted routing and escrow settlement allow an agent marketplace to *self-optimise* toward higher-quality providers?
>
> Each RQ is answerable with measurable evidence (validation precision, ruling consistency, reputation validity).

*Supports: Panel P1 (2%); Supervisor S3 (LO1, LO5)*

**Speaker notes (10s)**
> Three research questions: can a declared output spec act as a machine-checkable contract; can hybrid rule-plus-LLM arbitration resolve disputes fairly at scale; and can reputation plus escrow make the marketplace self-optimising. Each maps to an objective and a metric.

---

## Slide 6 — Research Objectives (1:02–1:17)

**On slide**
> - **O1** *(→RQ1)*: Design task submission + agent registration where every agent declares a **binding output contract** (`M1`+`M2`)
> - **O2** *(→RQ3)*: Develop a **routing engine** — capability matching, queueing, timeout, retry (`M3`)
> - **O3** *(→RQ2)*: Implement **layered validation + 3-tier dispute resolution** — auto spec-check, LLM arbitration, human escalation (`M4`)
> - **O4** *(→RQ3)*: Construct **reputation scoring + virtual escrow settlement** on an append-only auditable ledger (`M5`)
> - **O5**: **Evaluate** routing accuracy, validation precision, arbitration consistency, settlement correctness under simulated + live usage

*Addresses: Panel P1 (2%); Supervisor S3 (LO1, LO5)*

**Speaker notes (15s)**
> Five SMART objectives, each tied to a research question and a module: the binding output contract, the routing engine, the trust layer with three-tier disputes, reputation and escrow settlement on an append-only ledger, and measurable evaluation of all of it.

---

## Slide 7 — Scope of Study (1:17–1:27)

**On slide**
> - **In-scope:** 6 modules (submission, registration, routing, validation+disputes, reputation+settlement, discovery); virtual credits; HTTPS webhook agent integration; English interface
> - **Out-of-scope:** real-money payment; platform-hosted agent execution; MCP / A2A protocol integration; advanced anti-abuse (sybil, collusion)
> - **Target environment:** cloud deployment (Railway) — Next.js + Spring Boot + Python services
> - **Assumptions:** builders self-host their agents; honest baseline behaviour

*Addresses: Supervisor S2 (scope — LO1, LO5)*

**Speaker notes (10s)**
> In scope: the six marketplace modules with virtual credits and webhook integration. Explicitly out: real money, hosted execution, and MCP or A2A protocols — those are architecturally compatible future extensions.

---

## Slide 8 — Significance of the Study (1:27–1:37)

**On slide**
> - **Builders:** first neutral monetisation channel for independent specialist agents
> - **Clients:** objective quality signals + structured recourse — prerequisites for enterprise adoption
> - **Knowledge contribution:** reframes agents from *capability tools* → *accountable service providers*; LLM-as-arbitrator with **deterministic settlement** (the LLM rules, but never moves credits)
> - **Closes the gap:** first platform combining open access, discovery, monetisation, reputation, auto-validation & disputes for AI agents

*Addresses: Supervisor S8 (LO5); supports Panel P3*

**Speaker notes (10s)**
> The significance is the reframing: agents become accountable contractors judged against declared contracts. The novel piece is LLM arbitration whose ruling maps deterministically to settlement — the LLM never touches the money.

---

## Slide 9 — Literature Review & Research Gap (1:37–1:59)

**On slide**
> **Systems compared (7):**
>
> | | Open access | Reputation | Auto validation | Disputes | Monetisation |
> |---|:-:|:-:|:-:|:-:|:-:|
> | Salesforce AgentExchange / Google Agent Marketplace | ✗ locked | ✗ | ✗ | SLA only | partial |
> | Glama.ai / MCP.so (18k–19k listings) | ✓ | ✗ | ✗ | ✗ | ✗ |
> | Fiverr / Upwork *(humans)* | ✓ | ✓ | manual | mediated | ✓ |
> | **HireAI (proposed)** | **✓** | **✓** | **✓** | **✓ 3-layer** | **✓** |
>
> **Theories/frameworks:** FIRE trust model (Huynh et al., 2006) · R-D-C disrepute model (2016) · two-sided markets (Rochet & Tirole) · hybrid dispute resolution
> **Recent agent-economy research:** Park et al. (2026) — market exchange yields 3.2× wealth vs self-sufficient agents, *given the right institutions*; Casper et al. (2025, ICML) — agent advocates vs platform-controlled agents
> **Gap:** no platform integrates the full trust + economic stack for AI agents — APA citations in references

*Addresses: Panel P3 (3%); Supervisor S4 (LO3, LO5)*

**Speaker notes (22s)**
> I reviewed seven systems and four research streams. Enterprise marketplaces solve monetisation but are closed. Directories solve discovery with zero trust infrastructure. Fiverr and Upwork prove the full stack works — but for humans, relying on interpersonal communication agents don't have. On theory, my reputation design follows the FIRE model — weighted signals with temporal decay — plus explicit negative signals. Recent research shows agent markets create three times the wealth, but only with the right institutions. That institutional structure is the gap HireAI fills.

---

## Slide 10 — Proposed Methodology / Research Framework (1:59–2:17)

**On slide**
> - **Approach:** design science — build and evaluate an artifact; **IID + Agile practices**
> - **Why IID:** modules form a layered dependency → natural increments; AI components (arbitration prompts, reputation tuning) need empirical iteration; scales to solo developer; every iteration ends demonstrable
> - **Phases:** `P1` Inception & requirements → `P2` Core construction `M1–M3` → `P3` Trust layer `M4` → `P4` Settlement & reputation `M5` → `P5` Discovery `M6` + evaluation
> - **Data gathering:** synthetic dispute corpus (built in P1) to empirically test LLM arbitration consistency
> - **Tools:** Next.js/TypeScript · Spring Boot (Java 21, DDD) · PostgreSQL · RabbitMQ · Python LangGraph + Claude API · Railway
> - Each phase ends with a supervisor checkpoint; risks managed structurally (M6 = stretch scope)

*Addresses: Panel P4 (3%); Supervisor S6 (LO2, LO5)*

📌 PASTE (optional): 5-phase timeline strip graphic

**Speaker notes (18s)**
> Iterative and Incremental Development with Agile practices, for four reasons: the modules form a natural layered dependency; the AI components need empirical tuning, not upfront spec; it scales to a solo developer; and every iteration produces a demonstrable increment. Five phases spanning FYP1 and FYP2, with a synthetic dispute corpus built early to de-risk arbitration.

---

## Slide 11 — Requirements (Project / Module) (2:17–2:37)

**On slide**
> **Functional — by module:**
> - `M1` Submission: cost estimate, **escrow freeze on submit** (FR1.4)
> - `M2` Registration: declared **output spec**, HTTPS webhook verification
> - `M3` Routing: match → dispatch → timeout → 1 retry → refund
> - `M4` Validation & disputes: auto spec-check **before client sees result**
> - `M5` Reputation & settlement: append-only ledger, 15% commission
> - `M6` Discovery: public catalogue, agent profiles, builder dashboard
>
> **Non-functional:** dispatch <2s · arbitration ruling <60s · 100 concurrent tasks · 99% uptime · JWT + bcrypt · signed webhook tokens · transactional credits — *no partial state, ever*
> **Actors:** Client · Agent Builder · AI Agent · LLM Arbitrator · Administrator · System
> **Elicitation:** comparative analysis · literature derivation · scenario walkthroughs · supervisor consultation

*Addresses: Panel P5 (5%); supports Supervisor S7 (LO2, LO5)*

**Speaker notes (20s)**
> Requirements were elicited through comparative analysis, literature derivation, scenario walkthroughs, and supervisor consultation. The defining ones: credits freeze into escrow at submission; every agent declares a machine-checkable output spec; every output is validated against it before the client sees the result; and every credit movement lands in an append-only log. Non-functionally: two-second dispatch, sixty-second rulings, strict transactional consistency on money.

---

## Slide 12 — Analysis & Design (2:37–3:05)

**On slide**
> - **Architecture:** polyglot 3-service design — Next.js frontend · Spring Boot DDD core · Python LangGraph arbitrator · PostgreSQL + RabbitMQ
> - **Use case model:** 6 actors · **38 use cases** across 6 modules
> - **The money path (escrow lifecycle):** `submit → freeze → execute → validate → accept/dispute → settle (release / refund / 50-50 split)`
> - **3-layer dispute resolution:** rules ("changed my mind" → no refund) → **LLM arbitration** (Fulfilled / Partial / Not Fulfilled) → human review
> - **Trust/threat model:** server-side identity from JWT · signed HTTPS-only agent I/O · append-only ledger via DB triggers · LLM never moves credits
> - Every design decision traceable to an FR

*Addresses: Panel P6 (10%); Supervisor S7 (LO2, LO5)*

📌 PASTE: **use case diagram** (report §5.3) + **system architecture diagram** (SAD). Optional: escrow-lifecycle flow.

**Speaker notes (28s)**
> The analysis produced six actors and thirty-eight documented use cases. Two designs define the system. First, the money path: credits freeze on submission and leave escrow only through an explicit settlement — release, refund, or split — recorded on an append-only ledger enforced by database triggers. Second, dispute resolution in three layers: deterministic rules for clear-cut cases; an LLM arbitrator judging contested cases against the declared spec, with each ruling mapping deterministically to a settlement — the LLM never moves credits; and human review as the final escalation. The threat model enforces server-side identity from JWT and signed, HTTPS-only agent communication.

---

# ★ Technical Implementation Plan — Slides 13a–13e (P7, 20% — biggest rubric weight)

*Five sub-slides, one per template component. Use a small "Technical Implementation Plan (1/5 … 5/5)" kicker on each so the panel sees deliberate coverage of every rubric item.*

---

## Slide 13a — Core Modules Planned (1/5) (3:05–3:20)

**On slide**
> **Six core modules — four beyond access control & registration** *(template minimum: 2)*
>
> | Module | What it does | Status |
> |---|---|---|
> | `M1` Task Submission | NL task + binding deliverable spec + **atomic escrow freeze** | ✅ Built |
> | `M2` Agent Registration | output contract, versioning, HTTPS webhook verify | ✅ Built |
> | `M3` Routing & Execution | match → queue → dispatch → timeout → retry | ✅ Built |
> | `M4` Validation & Disputes | auto spec-check · 3-tier dispute resolution | 🔜 FYP2 |
> | `M5` Reputation & Settlement | weighted score + escrow release/refund/split | ◐ Settlement core built |
> | `M6` Discovery & Dashboard | public catalogue, storefronts, builder console | ✅ Built |

*Addresses: Panel P7 (20%) — component 1/5*

**Speaker notes (15s)**
> Six core modules — four of them go well beyond the template's minimum of access control and registration. The construction order follows the dependency chain: the transactional spine first — submission, registration, routing — then the trust layer, then settlement and reputation. Four modules plus the settlement core are already built and working.

---

## Slide 13b — Technology Stack (2/5) (3:20–3:35)

**On slide**
> | Layer | Technology | Why |
> |---|---|---|
> | Frontend | **Next.js 16 · TypeScript · Tailwind** | SSR/SEO for public catalogue; type-safe specs across the wire |
> | Backend core | **Spring Boot · Java 21 · DDD** | bounded contexts map 1:1 to modules; transactional money logic |
> | Data | **PostgreSQL** (+ JSONB for output specs) | ACID for escrow; relational integrity; JSON for variable specs |
> | Async | **RabbitMQ** (Spring AMQP) | reliable dispatch, dead-letter queues for timeouts, no double-dispatch |
> | AI service | **Python FastAPI · LangGraph · Claude API** | isolated arbitration state machine — AI evolves independently of money path |
> | Hosting | **Railway** | managed multi-service deploy for the polyglot stack |
>
> **Datasets:** seeded demo users & reviews (Flyway) · **synthetic dispute corpus** for arbitration evaluation

*Addresses: Panel P7 (20%) — component 2/5*

📌 PASTE (optional): stack pipeline diagram (frontend → core → DB/queue → arbitrator)

**Speaker notes (15s)**
> The stack is deliberately polyglot — each concern gets the best-suited tool. Spring Boot with domain-driven design carries the transactional core, where bounded contexts map one-to-one to modules. Postgres gives ACID guarantees for escrow, RabbitMQ gives reliable asynchronous dispatch with dead-letter queues, and the Claude-based arbitrator is a separate Python service so the AI layer evolves independently of the money path.

---

## Slide 13c — Database / Storage Plan (3/5) (3:35–3:51)

**On slide**
> - **Schema ownership:** Flyway-versioned migrations (`V1–V9` already applied) · Hibernate validates, never generates · 3NF
> - **Aggregates:** Wallet · Task · Agent/AgentVersion · TaskResult · LedgerEntry — one repository per aggregate root
> - **CRUD flows:**
>   - **Create** — tasks, agents, results, ledger entries (every state change writes)
>   - **Read** — catalogue, task tracking, earnings & transaction history (DTO projections)
>   - **Update** — guarded status transitions only (e.g. `PENDING_REVIEW → ACCEPTED`), race-safe via **pessimistic locking**
>   - **Delete** — ⛔ **forbidden on money**: `ledger_entries` is append-only, UPDATE/DELETE blocked by **DB triggers**; corrections = compensating entries
> - **File storage:** Supabase Storage for agent storefront media; task attachments planned

*Addresses: Panel P7 (20%) — component 3/5*

**Speaker notes (16s)**
> Flyway owns the schema — nine migration versions are already applied, and Hibernate only validates. Each DDD aggregate gets one repository. The CRUD design is deliberate: every state change creates records, updates are guarded status transitions protected by pessimistic locks, and on the money path there is no update or delete at all — the ledger is append-only, enforced by database triggers, so settlement is always reconstructable.

---

## Slide 13d — Integration Points & APIs (4/5) (3:51–4:07)

**On slide**
> - **REST API** (consistent `WebResult` envelope): `POST /api/auth/login` · `POST /api/tasks` · `GET /api/tasks/{id}/result` · `POST /api/tasks/{id}/accept|reject` · `POST /api/tasks/direct` · agent register/activate · wallet top-up
> - **Outbound → Agent:** signed **HTTPS-only webhook** dispatch (short-lived signed token in payload)
> - **Inbound ← Agent:** result callback **token-authenticated** (401 if invalid/expired) — *not* JWT
> - **Internal:** RabbitMQ event-driven dispatch (task-submitted → match → dispatch) · REST+queue to Python arbitrator (FYP2)
> - **Frontend ↔ Backend:** Next.js `/api/*` proxy with JWT-bearing client
> - **AuthZ:** identity derived **server-side from JWT**; explicit ownership checks on every path/body ID

*Addresses: Panel P7 (20%) — component 4/5*

**Speaker notes (16s)**
> Four integration boundaries. The REST API uses a consistent response envelope. Outbound, tasks dispatch to agents over signed, HTTPS-only webhooks carrying a short-lived token; inbound, result callbacks must present that token or they're rejected with 401 — that's a separate authentication channel from user JWTs. Internally, RabbitMQ decouples submission from execution, and the arbitrator will sit behind the same queue. Every user-facing call derives identity server-side from the JWT with explicit ownership checks.

---

## Slide 13e — Dev Environment, Version Control & Evidence (5/5) (4:07–4:25)

**On slide**
> - **Environment:** JDK 21 + Maven · Node 20 · Docker (Postgres + RabbitMQ) · local stub agent + HTTPS tunnel for live webhook demos
> - **Version control:** Git/GitHub · feature branches (`feat/...`) · conventional commits · PR review before merge
> - **CI & testing:** tests on every push · unit + integration via **Testcontainers** (real Postgres + RabbitMQ in Docker)
>
> **Mastery evidence — already running:**
> - **~284 backend + ~46 frontend tests green**
> - Full happy path live: login → top-up → submit → **escrow freeze** → dispatch → result → **accept → 85/15 settlement**
>
> 📌 PASTE: 2 product screenshots (e.g. marketplace catalogue + task detail with settled summary)

*Addresses: Panel P7 (20%) — component 5/5*

**Speaker notes (18s)**
> Development runs on JDK 21 with Maven and Docker; integration tests use Testcontainers against real Postgres and RabbitMQ instances, and CI runs the suite on every push. Work flows through feature branches with conventional commits. And this plan is already evidenced in code: roughly two hundred eighty backend and forty-six frontend tests are green, and the full happy path — from login and escrow freeze through dispatch to an accepted result settling at eighty-five fifteen — runs live end-to-end, as shown in these screenshots.

---

## Slide 14 — Stakeholder Collaboration Initiative (4:25–4:35)

**On slide**
> - **Collaborating party:** Wei Bao Zheng — Team Lead, Chargeback Team, **Ant International**
> - **Role:** industry consultation on payment dispute systems, settlement workflows, and escrow-based trust mechanisms
> - **Influence on design:** directly informed `M4` (dispute resolution) and `M5` (settlement) — e.g. deterministic settlement rules, dispute classification, audit reconstructability
> - **Artefacts:** informal advisory collaboration; validation additionally grounded in synthetic dispute corpus + measurable evaluation criteria

*Addresses: Panel P8 (5%)*

**Speaker notes (10s)**
> I collaborate with Wei Bao Zheng, Team Lead of the Chargeback Team at Ant International. His experience in payment disputes directly shaped the dispute and escrow design — particularly keeping settlement deterministic and fully auditable, mirroring real chargeback systems.

---

## Slide 15 — Expected Outcomes & Contributions (4:35–4:45)

**On slide**
> - **D1:** Working MVP platform — all 6 modules deployed on Railway
> - **D2:** LLM arbitration microservice + **synthetic dispute corpus** (reusable evaluation asset)
> - **D3:** Evaluation report against measurable criteria
> - **Academic contribution:** integrated trust + economic stack for AI agent marketplaces; LLM-as-arbitrator with deterministic settlement
> - **Evaluation metrics:** routing accuracy · validation precision · arbitration consistency & human-agreement rate · settlement correctness (ledger reconstruction) · reputation validity

*Addresses: Supervisor S8 (LO5)*

**Speaker notes (10s)**
> Three deliverables: the platform, the arbitration service with its synthetic dispute corpus, and an evaluation report. Every outcome links to an objective and a metric — validation precision for RQ1, arbitration consistency for RQ2, settlement correctness and reputation validity for RQ3.

---

## Slide 16 — Project Timeline (Gantt Chart) (4:45–4:55)

**On slide**
> 📌 PASTE: **Gantt chart** — ✅ ready at `docs/FYP1-gantt.png` (regenerate by editing `docs/FYP1-gantt.html` and screenshotting it in a browser)
>
> - **FYP1 = 14 weeks, scoped through core construction:** `P1` inception (Wk 1–6) → `P2` core spine `M1–M3` + JWT auth (Wk 7–12) → ◆ E2E demo → report + proposal defense (Wk 13) → ◆ **FYP1 viva** (Wk 14)
> - **Carried into FYP2:** `M4` validation + disputes (LLM arbitration), `M5` reputation, evaluation, thesis, final viva
> - **Critical path (red):** P1 → M1→M2→M3 → E2E demo
> - **Ahead of plan = schedule buffer:** `M6` discovery and the settlement core are *already built* beyond FYP1 scope, de-risking FYP2

*Addresses: Supervisor S2 (schedule); supports S6*

**Speaker notes (10s)**
> FYP1 spans fourteen weeks, scoped through core construction: six weeks of inception, then the transactional spine — submission, registration, routing, with auth — demonstrated end-to-end by week twelve, closing with the report and this viva. We're actually ahead of this plan: discovery and the settlement core are already built, which banks schedule buffer for FYP2's trust layer and evaluation.

---

## Slide 17 — Conclusion (4:55–5:05)

**On slide**
> - **Problem:** AI agents proliferate without infrastructure for discovery, accountability, or monetisation
> - **Solution:** HireAI — a neutral broker with binding output contracts, escrow, 3-layer disputes, and reputation
> - **Methodology:** IID across 5 phases; design de-risked by industry consultation + synthetic corpus
> - **Feasibility proven:** marketplace spine + settlement core already running end-to-end, ~330 tests green
> - **Gap closed:** the first integrated trust + economic stack for independent AI agents

*Addresses: Supervisor S8 (LO5)*

**Speaker notes (10s)**
> To conclude: agents exist, infrastructure doesn't. HireAI builds that infrastructure — contracts, escrow, disputes, reputation — in one coherent platform, and feasibility is already demonstrated by a working core. Thank you.

---

## Slide 18 — References (5:05–5:07, flash)

**On slide** *(APA — ⚠ verify each against the report's bibliography before the defense)*
> - Casper, S., et al. (2025). *Build agent advocates, not platform agents.* ICML 2025.
> - Huynh, T. D., Jennings, N. R., & Shadbolt, N. R. (2006). An integrated trust and reputation model for open multi-agent systems. *Autonomous Agents and Multi-Agent Systems, 13*(2), 119–154.
> - Khosravifar, B. (2012). *Trust and reputation in multi-agent systems* (Doctoral dissertation, Concordia University).
> - MarketsandMarkets. (2025). *AI agents market — global forecast to 2030.*
> - Park, J., et al. (2026). *When agent markets arrive.* [verify venue]
> - Pinyol, I., & Sabater-Mir, J. (2013). Trust and reputation models for multi-agent systems. [verify venue — report cites ACM Computing Surveys]
> - Rochet, J.-C., & Tirole, J. (2003). Platform competition in two-sided markets. *Journal of the European Economic Association, 1*(4), 990–1029.
> - Upwork Inc. (2025). *Annual report (Form 10-K).*
> - Wang, L., et al. (2024). A survey on large language model based autonomous agents. *Frontiers of Computer Science, 18*(6).

*Addresses: Supervisor S9 (LO5)*

**Speaker notes (2s)** — flash slide; don't read. "Full references in APA style."

---

## Slide 19 — Thank You · Q&A (5:07–5:12)

**On slide**
> # Thank You — Q & A
> HireAI: Discovery · Accountability · Monetisation — one platform.

*Addresses: Panel P9 (5%), P10 (5%); Supervisor S10–S13*

**Speaker notes (5s)**
> Thank you for your attention — I welcome your questions.

**Prepared Q&A (P10 / S12):**
- *How do you ensure LLM arbitration fairness?* → synthetic dispute corpus measures consistency; rulings map **deterministically** to settlement; either party can escalate to human review; the LLM never moves credits.
- *Why virtual credits, not real money?* → preserves the full escrow architecture while avoiding payment regulation and fraud management — out of FYP scope, architecturally extensible.
- *Why RabbitMQ over simple polling?* → at-least-once delivery, dead-letter queues for timeouts, acknowledgement semantics — no lost or double-dispatched tasks under failure.
- *How is the output spec enforced on free-form agent output?* → spec is structural and machine-checkable (format, min length, required sections); semantic quality goes to client review and arbitration.
- *What if the agent ecosystem doesn't adopt webhooks?* → webhook is the MVP integration; MCP/A2A adapters are compatible future extensions on the same contract model.
- *Why Java/Spring for the core instead of Python end-to-end?* → the money path needs transactional consistency, type safety, and mature security tooling; Python is reserved for the AI layer where its LLM ecosystem is strongest — clean process boundary between the two.
- *How do you prevent a malicious agent from faking callbacks?* → callbacks must present the short-lived signed token issued at dispatch; invalid or expired → 401. Webhooks are HTTPS-only.

---

## Diagrams / images to paste manually (📌 summary)

| Slide | Asset | Source |
|---|---|---|
| 12 — Analysis & Design | **Use case diagram** (required) | FYP report §5.3 |
| 12 — Analysis & Design | **System architecture diagram** (required) | SAD / `docs/details/architecture.md` |
| 12 — Analysis & Design | Escrow-lifecycle flow (optional) | draw from §5.2.5 / data-model doc |
| 13b — Stack | Stack pipeline diagram (optional) | draw: frontend → core → DB/queue → arbitrator |
| 13e — Evidence | **2 product screenshots** (strongly recommended — live evidence for P7) | running frontend (marketplace catalogue, task detail with settled summary) |
| 16 — Timeline | **Gantt chart** (required — template expects it) | ✅ generated: `docs/FYP1-gantt.png` (source: `docs/FYP1-gantt.html`) |
| 10 — Methodology | 5-phase timeline strip (optional) | report §3.2 |

---

## Rehearsal checkpoints (5:12 target)

| Time | You should be starting |
|---|---|
| 1:00 | Slide 5→6 (RQ → Objectives) |
| 2:00 | Slide 10 (Methodology) |
| 3:00 | Slide 12 ending → **13a** |
| 4:00 | Slide 13d→13e |
| 4:45 | Slide 16 (Timeline) |

**If running over at 3:00:** flash slides 7, 8, 14, 15 down to one sentence each. **Never** cut from the 13a–13e block or slide 12 — together they carry 30% of the panel rubric.
