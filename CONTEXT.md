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

**SourceSnapshot** _(planned, not built; see ADR 0004)_:
The frozen copy of the client-supplied input a Task is judged against — fetched once at submission, content-hashed, and immutable thereafter. It is what the Agent executes on and what an arbitrator later reads, so both sides are held to provably identical evidence even if the original source is amended or disappears.
_Avoid_: attachment, upload, file, source URL (the URL is only where it came from; the SourceSnapshot is the frozen thing itself)

**IdempotencyRecord**:
A dedup record of one submit attempt, keyed by (owner, idempotency key). Belongs to the submission act, not to ApiKey — it applies to any submitter, human JWT included, whenever a request carries an `Idempotency-Key`.
_Avoid_: idempotency key (that's the header/input; this is the stored record)

**WebhookDelivery**:
One outbound notification of a single Task's terminal event (`task.completed`/`task.failed`), enqueued as an outbox row in the same transaction as settlement and retried until delivered or dead. Cannot exist without the Task event that produced it.
_Avoid_: webhook (ambiguous with WebhookSubscription — say "delivery" for the per-event half)

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
