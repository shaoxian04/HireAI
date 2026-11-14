---
status: accepted
---

# Who picked the Agent decides what happens when it can't take the task

**Status:** decided, not built (#51).

When the chosen Agent fails **before it starts** (unreachable after retries, declined as busy with
429/503, or unavailable at dispatch), the response depends on who chose it. If the **platform** chose
it (routed API/MCP submit), the platform re-routes automatically while the task stays QUEUED, to at most
3 different Agents within the same budget and deadline, recording every attempt. If the **client** chose
it (web shortlist, or API/MCP with `agent_id`), the platform never swaps Agents silently. The task ends
with a refund and a machine-readable FailureReason, and the client can rebook from a shortlist
prefilled with the same task and without the failed Agent. Failures after the Agent started (it reports
failure, times out, or breaks the spec) are never failed over.

## Considered options

- **Hold escrow and let the client re-pick.** This was the original direction for the web flow, and it
  was rejected. A direct booking freezes exactly the chosen Agent's price (ADR 0001), so picking a
  different Agent would need a top-up or partial-release step in the ledger, plus a new waiting state
  with its own timeout sweeper. Refund-and-rebook gives the same client experience with no new money-path
  logic.
- **Fail over after execution failures too.** Rejected: a timeout can be 24h, which makes latency
  unbounded. It would also chain penalties, because routed spec violations are still charged to the
  Builder until #46.
- **Auto re-route for `agent_id` callers.** Deferred: an opt-in flag can be added later, and callers get
  a machine-readable reason in the meantime.

## Consequences

- A "busy" decline (429/503) records no reputation event. An unreachable Agent records `EXECUTION_FAILED`
  once a fair retry window has passed.
- Agents must acknowledge a WorkOrder with 2xx within 15s and execute asynchronously. A slow
  acknowledgement looks like "unreachable".
- Until #46, a routed task whose spec no Agent can meet can still penalise up to 3 Builders in a row.
