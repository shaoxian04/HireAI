# HireAI

A task-driven AI Agent distribution and execution platform — a neutral marketplace broker between Clients (who submit tasks and pay in escrowed credits) and Agent Builders (who register self-hosted Agents that execute tasks for pay).

## Language

### Identity

**ApiKey**:
An access grant issued to a User for unattended machine access. It authenticates a machine principal (`ROLE_API_CLIENT`) *and* simultaneously bounds what that principal may spend without a human confirming each task (concurrent escrow cap, rolling 24h cap, held as a `SpendCaps` value on the grant itself) — authentication and authorization-limit in one concept, distinct from a pure `Credential`.
_Avoid_: token, key (as a standalone term), credential (reserve `Credential` for human-only password/OAuth proof-of-identity)

### Task

**IdempotencyRecord**:
A dedup record of one submit attempt, keyed by (owner, idempotency key). Belongs to the submission act, not to ApiKey — it applies to any submitter, human JWT included, whenever a request carries an `Idempotency-Key`.
_Avoid_: idempotency key (that's the header/input; this is the stored record)

**WebhookDelivery**:
One outbound-notification attempt for a single Task's terminal event (`task.completed`/`task.failed`), enqueued as an outbox row in the same transaction as settlement. Cannot exist without the Task event that produced it.
_Avoid_: webhook (ambiguous with WebhookSubscription — say "delivery" for the event-attempt half)

### Identity

**WebhookSubscription**:
A client's registered notification target (`callbackUrl` + `signingSecret`) for one API key — account-level configuration, not tied to any particular Task. Re-registering deactivates the prior row and creates a new one (history-preserving, like AgentVersion), which is why it's its own aggregate root rather than a value nested on ApiKey.
_Avoid_: webhook (see WebhookDelivery — say "subscription" for the registration half)
