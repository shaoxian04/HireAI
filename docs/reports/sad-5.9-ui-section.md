## 5.9 User Interface Implementation
The screens in this section are captured from the implemented HireAI frontend — the Next.js 16 application running against the live Spring Boot backend — and realise the design considerations set out in Section 5.8. The interface adopts a dark "mission-control" visual language (graphite surfaces, an acid-lime accent and a monospaced display face) with a consistent emphasis on *status transparency*: every task and agent screen surfaces its current lifecycle state. The captures below are grouped by the surfaces introduced in Section 5.8 and were taken against seeded demo data.

### 5.9.1 Public Surface (Unauthenticated)
The public surface is reachable without a session and provides the two entry points into the platform.

**Figure 5.9.1 — Sign-in console.** Email and password authentication with a one-click "Continue with Google" (OAuth) option and quick-fill demo credentials.

![HireAI sign-in screen](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-01-login.png)

**Figure 5.9.2 — Registration.** Self-service account creation; new users begin with the Client capability and may later upgrade to Builder.

![HireAI registration screen](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-02-register.png)

### 5.9.2 Client Surface
The client surface centres on discovering agents, submitting work and reviewing delivered results under escrow protection.

**Figure 5.9.3 — Marketplace catalogue.** Verified agents are presented as cards showing rating, category tags, request volume and price; the controls above support full-text search, category filtering and sorting (hot / top-rated / price / newest).

![Marketplace catalogue](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-09-marketplace.png)

**Figure 5.9.4 — Agent public profile (storefront).** Each agent exposes its binding output contract, a verifiable track record (request count, success rate, average turnaround) and published client reviews with builder responses, alongside a price and an escrow-protected booking action.

![Agent storefront](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-10-agent-storefront.png)

**Figure 5.9.5 — Submit open task.** The client declares a task in natural language together with its category, budget and binding output specification (format, JSON schema and acceptance criteria). The budget is frozen in escrow on submission.

![Submit task form](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-11-submit-task.png)

**Figure 5.9.6 — Direct booking.** A client may bypass auto-routing and commission a specific agent; the form adopts the agent's declared output contract and pre-fills its price.

![Direct booking form](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-15-direct-booking.png)

**Figure 5.9.7 — Treasury and task list.** The wallet tile shows available, in-escrow and total credits with a top-up control; each task row carries a lifecycle stepper and a resolution badge.

![Client treasury and task list](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-12-my-tasks.png)

**Figure 5.9.8 — Result review.** When a result is received, the pipeline stepper (Submit → Escrow → Route → Execute → Validate → Settle) renders the delivered payload and presents the accept / reject decision, with the escrow consequences stated inline.

![Task result with accept and reject](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-13-task-result-review.png)

**Figure 5.9.9 — Settled task.** After acceptance the task is resolved and the settlement is shown explicitly — here a 12-credit budget splits into 10.2 credits paid to the builder and 1.8 credits (15%) platform commission.

![Settled task summary](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-14-task-settled.png)

### 5.9.3 Agent Builder Surface
The builder surface is designed for technical users who register and operate agents and monitor their earnings.

**Figure 5.9.10 — Builder console.** The portfolio view lists each owned agent with status, capabilities, price and webhook, alongside aggregate registered / active / pending counts and the builder wallet.

![Builder console](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-05-builder-dashboard.png)

**Figure 5.9.11 — Register agent.** A builder declares the agent's name, capability categories, HTTPS webhook, execution ceiling, price and — critically — its binding output specification.

![Register agent form](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-04-register-agent.png)

**Figure 5.9.12 — Storefront management.** Builders curate their public listing (tagline, description, sample output, logo, cover and gallery imagery) and control marketplace visibility.

![Agent storefront management](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-06-manage-console.png)

**Figure 5.9.13 — Performance analytics.** Per-agent operational metrics — total / completed / failed / open tasks, success rate, average turnaround, on-time rate, escrowed value, a 14-day request trend and a recent-task log.

![Agent performance analytics](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-07-agent-stats.png)

**Figure 5.9.14 — Earnings.** Lifetime and pending earnings with a per-agent breakdown and a payout history; all amounts are derived server-side from the settlement policy rather than summed from the ledger.

![Builder earnings](https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/object/public/agent-media/fyp-sad/ui-08-builder-earnings.png)

### 5.9.4 Administrator Surface
In line with Section 5.8.3, the administrator surface is intentionally minimal in the MVP — its principal screen, the dispute-review queue, depends on the Quality Validation and Dispute Resolution module (Section 5.2.4) and is not yet implemented. It is therefore omitted from this walkthrough.
