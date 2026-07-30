---
status: accepted
---

# ApiKey and Webhook nest under Identity and Task, not as their own subdomains

`domain/biz/apikey` and `domain/biz/webhook` (and their mirrors in `application`/`repository`) sat as
top-level siblings of the SAD's six capability subdomains (identity, ledger, offering, task,
reputation, adjudication). They were added later, during the programmatic-channel build, and never
folded back into that division. Neither is a business capability in its own right — the
programmatic-channel doc itself calls them "edge adapters" over the existing submit/escrow/routing/
validation core.

We split each into the half that's a credential/config concern and the half that's a task-event
concern, using one test: *can this exist with zero of the other thing present?*

- **ApiKey** → `identity/apikey`. It authenticates a machine principal and bounds what it may spend
  (`SpendCaps`) — a credential-like concept, same conceptual role as `Credential`/`OAuthIdentity`.
- **IdempotencyRecord** → `task/idempotency` (with `ApiKeyTaskRepository`, its attribution
  companion). A human JWT submit with an `Idempotency-Key` header dedupes with zero API key
  involved — this is a submission-act concern, not a credential concern.
- **WebhookSubscription** → `identity/webhooksubscription`. Registration (`callbackUrl` +
  `signingSecret`) is account-level config that can exist before any task is ever submitted;
  re-registering deactivates-and-creates (history-preserving, like `AgentVersion`), which is why it's
  its own aggregate root rather than a value nested on `ApiKeyModel`.
- **WebhookDelivery** → `task/webhookdelivery`. One outbox row per task terminal-state event
  (`task.completed`/`task.failed`) — cannot exist without the Task event that produced it.

`application/port/webhook` and `infrastructure/webhook` are unchanged: ports and infra adapters are
grouped by transport *kind* (`messaging`, `security`, `webhook` as an outbound-HTTP kind), not by
business subdomain, so they don't participate in this split. Controllers are unchanged for the same
reason — `controller/biz/apikey` and `controller/biz/webhook` are HTTP route groups, and route
grouping is orthogonal to domain subdomain.
