# HireAI

A task-driven AI Agent distribution and execution platform — a neutral marketplace broker between Clients (who submit tasks and pay in escrowed credits) and Agent Builders (who register self-hosted Agents that execute tasks for pay).

## Language

### Identity

**ApiKey**:
An access grant issued to a User for unattended machine access. It authenticates a machine principal (`ROLE_API_CLIENT`) *and* simultaneously bounds what that principal may spend without a human confirming each task (concurrent escrow cap, rolling 24h cap, held as a `SpendCaps` value on the grant itself) — authentication and authorization-limit in one concept, distinct from a pure `Credential`.
_Avoid_: token, key (as a standalone term), credential (reserve `Credential` for human-only password/OAuth proof-of-identity)

**WebhookSubscription**:
A client's registered notification target (`callbackUrl` + `signingSecret`) for one API key — account-level configuration, not tied to any particular Task. Re-registering deactivates the prior row and creates a new one (history-preserving, like AgentVersion), which is why it's its own aggregate root rather than a value nested on ApiKey.
_Avoid_: webhook (see WebhookDelivery — say "subscription" for the registration half)

### Task

**WorkOrder**:
The platform's instruction to one Agent to execute one Task: what the Client asked for, the binding output spec, the deadline, and where to return the result. It carries what the Agent needs to do the work and nothing about the Client beyond that. An Agent may receive the same WorkOrder more than once and must treat a repeat as the same job.
_Avoid_: dispatch (that's the act of sending it), job, request, payload

**AgentSigningSecret**:
A secret shared between the platform and one Agent, used to prove in both directions that a WorkOrder came from the platform and that a result came from that Agent. Issued when the Agent is registered, shown to the Builder once, and replaceable by rotation; it outlives the Agent's versions.
_Avoid_: dispatch token (a per-Task bearer credential, distinct from this long-lived secret), API key

**FailureReason**:
The single cause recorded when a Task ends without a delivered result: the Agent could not be reached, declined as busy, was unavailable, no alternative or eligible Agent remained, dispatch was abandoned, execution timed out, the Agent reported failure, or the output broke the spec. Set once at the end and shown to the Client, so the Client can tell *why* without reading the attempt history.
_Avoid_: error, status (the status says how the Task ended; the reason says why)

**Awaiting agent**:
A routed Task's holding condition when no eligible Agent exists yet. Escrow stays frozen while the platform looks again a bounded number of times, then the Task is cancelled and refunded. It is never caused by an Agent being busy: a busy Agent declines its WorkOrder and the Task moves on.
_Avoid_: awaiting capacity (the old status name; capacity is the Agent declining as busy, which is a different thing)

**Engagement** _(built, #104; ADR 0010)_:
A standing agreement between a buyer and an Agent the buyer chose, recording the buyer's consent to send that Agent confidential documents, and optionally a Template variant the Agent must accept. Confidential documents travel only under an Engagement; the marketplace paths take public input only.
_Avoid_: contract (that is the per-Task Contract), subscription

**SourceSnapshot** _(built for uploaded documents under an Engagement, #104; URL fetch deferred)_:
The frozen copy of the client-supplied input a Task is judged against — uploaded once, content-hashed, and immutable thereafter; its content is deleted after retention, its hash kept. It is what the Agent executes on and what an arbitrator later reads, so both sides are held to provably identical evidence even if the original source is amended or disappears.
_Avoid_: attachment, upload, file, source URL (the URL is only where it came from; the SourceSnapshot is the frozen thing itself)

**DeliverySnapshot** _(built for FILE results, #97; per-Item snapshots come with Items)_:
The frozen copy of what an Agent delivered for an Item — fetched once when the result arrives, content-hashed, and immutable thereafter. Every later step (Acceptance Checks, AI Review, the client's review, arbitration) judges this copy, never the Agent's link, so a deliverable cannot be swapped or withdrawn after the fact. The output-side twin of SourceSnapshot.
_Avoid_: result URL, attachment (the URL is only where the Agent put it)

**IdempotencyRecord**:
A dedup record of one submit attempt, keyed by (owner, idempotency key). Belongs to the submission act, not to ApiKey — it applies to any submitter, human JWT included, whenever a request carries an `Idempotency-Key`.
_Avoid_: idempotency key (that's the header/input; this is the stored record)

**WebhookDelivery**:
One outbound notification of a single Task's terminal event (`task.completed`/`task.failed`), enqueued as an outbox row in the same transaction as settlement and retried until delivered or dead. Cannot exist without the Task event that produced it.
_Avoid_: webhook (ambiguous with WebhookSubscription — say "delivery" for the per-event half)

### Acceptance _(Contract, Template and Offer are built as data derived from the output spec, the Offer editable by its Builder, and not yet enforced; the rest is designed, not built; see the #46 design spec)_

**Contract**:
The single binding agreement a Task is judged against, frozen at submission. It states the shape of the deliverable, how it will be accepted, and how money moves on each outcome. Automated acceptance looks only at the Contract; the Task's description is evidence of intent that an arbitrator may read, never a ground for automatic rejection. Where the two conflict, the Contract wins.
_Avoid_: output spec (the old, narrower name, which covered only the shape), agreement, terms

**Template**:
The platform-authored, versioned definition of one kind of work: which Acceptance Checks it may use, the bounds on each parameter, and how each ruling settles. Every Contract is built from exactly one Template version. New Contracts use the current version; for a transition period after a new version is published, the previous one is used when no Offer covers the new one yet, and the Client is told.
_Avoid_: category (a category is how work is found; a Template is how it is judged), schema

**Offer**:
An Agent's standing declaration of which Template versions it will work under, the parameter range it is willing to be held to, and its price. Only an Agent whose Offer covers a Task's Contract can be given that Task, so no Agent is ever held to terms it did not accept.
_Avoid_: output spec (the old single-shape declaration it replaces), listing, capability

**Acceptance Check**:
One test of whether the delivery conforms to the Contract — judged only against the current Task's input and Contract, never against other Tasks or outside data. Its outcome can move money.
_Avoid_: validation rule, business rule (a check that looks outside the Task is a Finding)

**Item**:
One deliverable unit inside a Task, accepted and settled on its own. A marketplace Task has one Item; a batch of 500 invoices is one Task with 500 Items, so one bad invoice never drags the other 499 down with it.
_Avoid_: batch (a batch is just a Task with many Items), document, line, sub-task

**Check Library**:
The platform-curated catalogue of Acceptance Checks a Contract may use, each with fixed meaning and adjustable parameters ("background colour is red, within this hue range"). A natural-language requirement either maps onto an entry or is not automatically checked — no check is invented at submission.
_Avoid_: rules engine, validators

**Finding**:
A fact about the Task's *input* noticed while accepting it — a duplicate invoice, a source document that does not add up, a supplier missing from the buyer's records. Reported to the client; it never changes what the Agent is paid.
_Avoid_: error, violation, exception (none of these are the Agent's fault)

**AI Review**:
An optional, client-paid acceptance step in which a model judges the criteria code cannot check. Chosen and priced at submission, never switched on mid-task. For a human client it is a pre-review report; for a machine client it decides settlement.
_Avoid_: LLM validation, AI check

**Review Window** _(built, #94)_:
The time a Client has to accept or reject a result that passed validation (24h by default). When it closes unreviewed, the Client's silence counts as acceptance: the platform accepts and settles on their behalf, so no Builder's pay is held hostage to a Client who never looks.
_Avoid_: review timeout, grace period

**Rejection Response** _(built, #95)_:
What follows a Client rejecting a result as mismatched, wrong or incomplete. The Agent may revise once, concede (the Client is refunded) or stand by its result with a reason, within 2h; silence counts as standing by. If it stands by, the Client sees the reason and, within 24h, accepts or asks for AI arbitration; silence counts as acceptance. Arbitration is therefore always the Client's choice, never the first step.
_Avoid_: appeal (that is a ruling taken to an admin), counter-dispute

**Appeal Fee** _(built, #96)_:
What a party pays to take an arbitrator's ruling to a human admin, once. Like the cost of arbitration itself, it is borne by whoever loses, up to a cap tied to the Item's price. Only a Client or buyer ever opens arbitration, and their escrowed payment already stands behind it, so no one posts a separate deposit.
_Avoid_: dispute bond, deposit (no one pays up front to open arbitration)

### Reputation

**Reputation**:
An Agent's single standing in the marketplace, blended from its two independent components — Reliability and Satisfaction. It is the marketplace's one consequential quality number: the matcher weights it above every other factor when choosing who gets routed work.
_Avoid_: score, rank, trust score, rating (a Rating is one *input* to Reputation, not a synonym for it)

**Reliability**:
The component of Reputation earned from outcomes the platform itself witnessed — a Client accepting, an arbitrator ruling, validation failing, execution timing out. Objective and dense: nearly every terminal outcome produces one. Changed-mind rejections, capacity cancellations and self-dealt Tasks produce none.
_Avoid_: uptime, success rate (it covers contract conformance, not just whether the Agent responded)

**Satisfaction**:
The component of Reputation earned from Ratings Clients chose to leave. Subjective and sparse — an Agent nobody rated sits at the neutral prior, never at the top, so silence reads as *unknown* rather than *perfect*.
_Avoid_: rating average, stars (those are the raw inputs; Satisfaction is the shrunk estimate over them)

**ReputationEvent**:
One append-only record of something that happened to an Agent bearing on its Reputation — either an outcome the platform itself witnessed (validation failed, execution timed out, an arbitrator ruled) or a Rating a Client left. The stream is the source of truth; Reputation is derived from it and never edited directly.
_Avoid_: reputation change, score delta, penalty

**Rating**:
A Client's 1–5 star judgment of one accepted, human-submitted Task of their own. Subjective and optional — distinct from the platform-witnessed outcomes, but *not* a separate axis: it enters the same ReputationEvent stream and therefore steers routing like any other event.
_Avoid_: review (a Review is the authored artifact — stars plus prose plus the Builder's response; the Rating is just the stars)

**Review**:
The client-authored artifact attached to an accepted Task — the Rating, optional prose, and the Builder's optional response. Public storefront content; only its Rating feeds Reputation.
_Avoid_: feedback, testimonial, comment
