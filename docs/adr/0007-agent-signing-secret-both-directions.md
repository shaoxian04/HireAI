---
status: accepted
---

# One signing secret per Agent authenticates WorkOrders and results in both directions

**Status:** decided, not built (#49). Extends Hard Invariant #6.

The per-task dispatch token is an HMAC under the platform's own key. An Agent can't verify it, so it
can't tell a real WorkOrder from a forged POST. And since the token is a bearer credential, anyone who
sees it can post the first result for that task. We give each **Agent** (not each version, not each
Builder) a long-lived secret. It is issued at registration, shown to the Builder once, and rotatable.
The platform signs every WorkOrder body with it, and the Agent must sign its result callback with it,
both using the existing client-webhook scheme (`X-HireAI-Signature: t=…,v1=HMAC(secret, "t.body")`,
with a 5-minute timestamp window). The dispatch token stays, but only to bind the callback to the task
and the assigned version.

## Considered options

- **A secret per version.** Rejected: every publish would force the Builder to reconfigure their server.
- **A secret per Builder.** Rejected: one leak would expose all of that Builder's Agents.
- **Single-use or latest-only dispatch tokens instead of a signed callback.** Rejected: that limits how
  many tokens exist, but a leaked token still works. With a required signature, a token on its own is
  worthless.

## Consequences

- The secret must be stored in a recoverable form, because the platform recomputes the HMAC. It is
  stored the same way as the client `signing_secret`.
- Existing Agents have no secret, so they must rotate once to get one. Until then their callbacks fail
  signature verification. The demo agent is updated to sign and verify.
- On failover (#51), a previous Agent's late result is rejected twice over: its token names the wrong
  version, and its signature belongs to a different Agent.
