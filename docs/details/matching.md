# Matching and selection

How the platform decides which Agent runs a Task, and how a Task avoids getting stranded. Values live in
`MatchingPolicy` and `hireai.matching.*` / `hireai.execution.*` in `application.yml`; bad values crash
startup. Open defects: **Known gaps** in [`build-status.md`](build-status.md).

## One ranking engine, three uses

`RoutingMatchDomainService` (framework-free) exposes `rank` and `selectOne`, which adds a random draw on
top of `rank`.

| Flow | Who decides | Path |
|---|---|---|
| Web submit | Client, from a shortlist | `GET /api/tasks/match-preview` runs `rank` (minus any `exclude`d Agents), then the pick calls `POST /api/tasks/direct` |
| API / MCP routed submit | Platform | `POST /api/tasks`, then `route()` runs `selectOne` |
| Re-match of a held routed task | Platform | Sweeper calls `route()` again over fresh candidates |
| Failover of a routed task whose Agent couldn't take it | Platform | `failOver()` runs `selectOne` over the candidates not yet tried ([routed failover](#routed-failover)) |
| Direct booking, or re-match of one | Client already chose | `dispatchDirect()`, no matching |

The web never calls `POST /api/tasks`, so every web task is a pinned direct booking.

**Shortlist.** Match-preview is read-only: it creates no task and freezes nothing. It returns the top 5 of
`rank` plus the 3 cheapest agents above budget (near-misses). The typed budget is only a ceiling for
ranking. The client pays the chosen agent's price, even for a near-miss above that budget.

**One eligibility, one score.** The shortlist, routed selection and failover consider the same Agents
and score them the same way; only the platform's own picks (`selectOne`) add the ε lottery. An Agent is
eligible when:

- the agent is `ACTIVE` and the version is its current one;
- its storefront is **listed** (`agent_profiles.is_listed`). An unlisted Agent is never shortlisted,
  routed or failed over to, so it receives no work; the Builder dashboard warns about it (`AgentDTO.listed`);
- its current version covers the category (a slug from the [category list](data-model.md#categories); an
  unknown one is rejected with a 400 before matching);
- `price <= budget` (checked in `rank`).

The SQL half of this lives once, in `MatchCandidateSql` (`ELIGIBLE_FROM` plus the load columns the score
needs), shared by the shortlist query (`JdbcMatchPreviewQueryDao`) and the routed/failover query
(`AgentRepository.findActiveCandidates`). The budget filter and the score live once, in
`RoutingMatchDomainService.rank`. So with `ε = 0` the shortlist's top Agent is the one a routed submit of
the same task gets. A direct booking is not a selection: it needs an `ACTIVE`, listed agent at booking
(see [`programmatic-channel.md`](programmatic-channel.md)) and is then dispatched to its pinned version.

## Scoring

```
score = w_rep     * reputation / 100
      + w_value   * (budget - price) / budget
      + w_load    * max(0, 1 - inFlight / maxConcurrent)
      + w_explore * 1 / (1 + sampleCount)
```

- `inFlight` counts `QUEUED`/`EXECUTING` tasks and `sampleCount` counts `RESOLVED`/`FAILED`/`TIMED_OUT`/
  `SPEC_VIOLATION` tasks, both per agent across all its versions. Failures count: `sampleCount` means
  "how often has the marketplace tried this agent".
- Load is a **soft** factor. A full agent scores 0 on it but stays eligible, so a busy marketplace degrades
  to best-available instead of no-match.
- `rank` is fully deterministic: score descending, then price, then `agentVersionId`.
- Reputation comes from Module 5, see [ADR 0003](../adr/0003-two-component-shrinkage-reputation.md).

## Why `selectOne` needs randomness

`selectOne` takes the top rank, except that with probability ε it runs a lottery over all eligible agents,
with each ticket weighted `1 / (1 + sampleCount)`. It is a fresh draw per match: no counter, so no shared
state and nothing to game by timing submissions. With one eligible agent there is no draw.

The score's exploration term alone isn't enough, because the score is deterministic and any gap repeats on
every match:

- **Starvation.** The bonus decays fast, so a newer agent can lose to the same veteran every time. At the
  default weights, a 5-for-5 agent (reputation 67.5, no ratings) still trails a 200-job, 90-reputation
  veteran by about 0.03, which is enough to lose on every match.
- **Tie-break monopoly.** Identical newcomers tie, and the deterministic tie-break always picks the same one.

So the term gives newcomers their first jobs and shortlist visibility, and ε gives sustained exploration on
the auto-route path. Humans on the shortlist path see no randomness.

Randomness affects selection only, never money (Hard Invariant #3). The RNG is injected (`SecureRandom` in
production), and `ε = 0` makes `selectOne` a pure argmax for tests.

## Flow and stranded-task protection

Task and escrow freeze commit together, then `TaskSubmittedDomainEvent` triggers routing `AFTER_COMMIT`.
On a match, `assignAndQueue` commits `QUEUED` and stamps `execution_deadline` (`now + maxExecutionSeconds +
grace`) **before** publishing, so the consumer never races an uncommitted state.

If routing throws after the submit commit, `RoutingEventListener` logs it and the submit still answers
with the normal success response and status `SUBMITTED`. The task and escrow exist, so an error would only
invite a second, paid submit.

| Trigger | Outcome | Refund | Webhook | Reputation event |
|---|---|---|---|---|
| Routing throws after submit | Stays `SUBMITTED`, escrow held | No | No | No |
| Still `SUBMITTED` after the routing attempts | `CANCELLED` | Yes | Yes | No |
| No eligible agent | `AWAITING_AGENT`, escrow held | No | No | No |
| Pinned agent not `ACTIVE`, or pinned version superseded, at dispatch | `CANCELLED` at once | Yes | Yes | No |
| Re-match attempts exhausted | `CANCELLED` | Yes | Yes | No |
| Past `execution_deadline` while `EXECUTING` | `TIMED_OUT` | Yes | Yes | `EXECUTION_TIMEOUT` |
| Past `execution_deadline` while still `QUEUED` | `TIMED_OUT` | Yes | Yes | No |
| Routed task: the Agent is busy, or every dispatch attempt failed, and an untried eligible Agent is left | Stays `QUEUED`, reassigned (see **Routed failover**) | No | No | `EXECUTION_FAILED` if unreachable, none if busy |
| Routed task: same, but no untried eligible Agent is left (or 3 Agents tried) | `FAILED` (`NO_ALTERNATIVE_AGENT`) | Yes | Yes | As above, for the last Agent |
| Pinned task: every dispatch attempt failed (Agent unreachable, timed out or non-2xx) | `FAILED` (`AGENT_UNREACHABLE`) | Yes | Yes | `EXECUTION_FAILED` |
| Pinned task: the Agent declined as busy (429/503) | `FAILED` (`AGENT_BUSY`) | Yes | Yes | No |

- **`AWAITING_AGENT`** means no eligible (`ACTIVE`, listed) agent covers the category within budget. Only routed tasks wait
  there. Load is soft, so a busy agent never causes it. The re-match sweeper
  (`AwaitingAgentRematchSweeper`) retries it up to `hireai.matching.rematch-max-attempts` times,
  then cancels with a refund.
- **Pinned tasks never substitute, and fail fast.** A direct booking is dispatched only to its exact version,
  because the client's spec and price are frozen to it (Invariant #4). If at dispatch the agent is not
  `ACTIVE` or the pinned version is no longer its current one, `dispatchDirect` cancels the task at once
  (`TaskWriteAppService.cancelPinnedAsUnavailable`, row-locked): `AGENT_UNAVAILABLE`, a full refund, a
  `task.failed` webhook and an `UNAVAILABLE` row in the dispatch history. Deactivating or publishing a new
  version never counts against the Builder, so no reputation event is written. The re-match sweeper never
  retries a pinned task: one it finds `AWAITING_AGENT` (parked there before this rule) is cancelled the
  same way on its first sweep, so its escrow is never stranded.
- <a id="routed-failover"></a>**Routed failover.** When the platform picked the Agent (no pinned version)
  and that Agent can't take the task before starting, the task stays `QUEUED` and goes to another Agent.
  The triggers are a busy decline (429/503) and an unreachable Agent (the DLQ, after the whole retry
  schedule). A failure after the Agent started (`EXECUTING`: a reported failure, a timeout, a spec
  violation) is never failed over. In one transaction under the task row lock
  (`TaskExecutionPortImpl.declineAsBusy` / `markFailed`, then `RoutingAppService.failOver`):
  - the failed attempt is recorded in the [dispatch history](data-model.md#dispatch-attempts) (`BUSY`, or
    `UNREACHABLE` plus `EXECUTION_FAILED` against that Agent);
  - the next candidate is picked from the same candidate query and matcher as initial routing (same budget
    and eligibility), excluding every Agent already in the task's history, under any version
    (`RoutedFailoverDomainService`);
  - `TaskModel.reassign` swaps the version (only from `QUEUED`, never to the same version) and a new
    `execution_deadline` is stamped from now.

  The new WorkOrder is published only after that transaction commits. At most **3 distinct Agents**
  (`RoutedFailoverDomainService.MAX_DISTINCT_AGENTS`) are tried per task. With no untried eligible Agent
  left, or 3 tried, the task ends `FAILED` with `NO_ALTERNATIVE_AGENT`, a full refund and a `task.failed`
  webhook. A late callback from a replaced Agent gets 401: its token names the old version, which is no
  longer the task's assigned one. A dead letter or busy reply for a replaced version is ignored. If the
  publish fails after the commit, the task stays `QUEUED` and its execution timeout refunds it without blame.
- **The deadline is stamped at assignment**, so one query catches both a silent agent and a lost publish.
  Only a silent agent is blamed: a task still `QUEUED` normally never reached the agent, so its timeout
  refunds without a reputation event (`TaskModel.timeoutIsAgentFault`). The rare task left `QUEUED` after a
  good send whose mark-executing step failed (see the consumer order below) is also not blamed.
- **SUBMITTED sweeper.** `SubmittedTaskSweeper` picks up tasks still `SUBMITTED` after
  `hireai.matching.submitted-min-age` (by the injected `Clock`) and re-runs routing, or the pinned dispatch
  for a direct booking. Each run counts against the same `match_attempts` counter as the re-match sweeper,
  but has its own limit, `hireai.matching.submitted-max-attempts`. A routing exception counts as
  a failed attempt. A task routed to `AWAITING_AGENT` carries its used attempts over to the re-match sweeper.
- **Sweeper transactions.** `timeoutOne` runs in one row-locked transaction. `rematchOne` and
  `rerouteSubmittedOne` are deliberately not transactional, because they publish to RabbitMQ and own their
  commit-before-publish ordering. The `SUBMITTED` cancel locks the task row before refunding.
- **Single-instance assumption.** The re-match and SUBMITTED paths read tasks unlocked, so overlapping sweepers on two
  instances could race. Fix that before running more than one replica. Money stays safe either way, through
  `settlements.task_id` UNIQUE.

Every exit above is a recorded settlement. Schema: `V24` in [`data-model.md`](data-model.md).

## The WorkOrder

What an Agent receives, and the Builder-facing contract. Routed and direct dispatch build it through the
same `RoutingAppServiceImpl.assignAndPublish`, so the two paths can't drift. The consumer POSTs it as JSON
to the version's webhook with `Authorization: Bearer <dispatch token>`, `X-Correlation-ID` and, once the
Agent has an AgentSigningSecret, `X-HireAI-Signature` (see **Signature** below).

| Field | Meaning |
|---|---|
| `taskId` | The task. Use it to correlate the order with your callback and to spot a repeated delivery |
| `agentVersionId` | The version the task was assigned to, so one endpoint can serve several versions |
| `title`, `description` | Exactly what the Client submitted |
| `category` | The task's category (a direct booking takes the version's first category) |
| `outputSpec` | The task's frozen output spec (Hard Invariant #4) as a JSON object: `format`, `schema`, `acceptanceCriteria`. Your result is validated, and any dispute judged, against it |
| `executionDeadline` | ISO-8601 instant: the `execution_deadline` stamped at assignment. After it the task times out |
| `callbackUrl` | Where to POST the result, with the same bearer token and your own signature (see **Result callback** below) |

Nothing else is sent: no Client identity and no budget.

**Acknowledgement contract.** The Builder-facing rules for answering a WorkOrder (also shown on the
registration form, `frontend/components/AckContract.tsx`):

- **Accept:** reply any 2xx (202 recommended) within the 15s read timeout, then do the work asynchronously.
  A slower reply, a refused connection or any other non-2xx is a failed send and is retried (see
  **Send retries**); after the last attempt `EXECUTION_FAILED` is recorded against the Agent and the task
  fails over (routed) or fails (pinned).
- **Decline as busy:** reply 429 or 503. The platform does not retry, records a `BUSY` row in the
  [dispatch history](data-model.md#dispatch-attempts) and writes **no** reputation event
  (`TaskExecutionPort.declineAsBusy`). A routed task then goes to another Agent
  ([routed failover](#routed-failover)); a pinned task ends `FAILED` with FailureReason `AGENT_BUSY`, a
  refund and a `task.failed` webhook.
- **After a failover, the task is no longer yours:** a result you post for it afterwards gets 401.
- **Deliver only through the callback:** the result is accepted only as a POST to `callbackUrl`. Anything
  in the WorkOrder's HTTP response body is ignored.

**Signature.** Every WorkOrder to an Agent with a signing secret carries

```
X-HireAI-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>
```

The format is the client-webhook one (`WebhookSignature`). The platform signs the exact body bytes it
sends (`AgentDispatchClient`) and reads the Agent's current secret before every send attempt, so after a
rotation the next WorkOrder is signed with the new secret and the old one stops verifying. An Agent that
has no secret yet (registered before secrets existed and never rotated) gets the WorkOrder unsigned; the
bearer token and `X-Correlation-ID` are sent either way.

To verify, the Agent:

1. Reads the **raw request body bytes**, before any JSON parsing; a re-serialised body won't match.
2. Splits the header on `,` into `t` and `v1`. A missing header or part is a reject.
3. Rejects the request if `t` is more than 5 minutes from its own clock (replay window).
4. Computes `HMAC-SHA256(secret, t + "." + body)` as lowercase hex.
5. Compares it with `v1` in **constant time** (`hmac.compare_digest`, `MessageDigest.isEqual`), and answers
   401 on a mismatch. A 401 is a failed send and is retried like any non-2xx.

`demo-agent/app.py` (`verify_signature`) is a working example.

**Result callback.** POST to `callbackUrl` with `Content-Type: application/json`, `Authorization: Bearer <the WorkOrder's
token>` and an `X-HireAI-Signature` over the exact body bytes you send, in the same format as above, made
with your **current** signing secret and the current time. Body:
`{ taskId, agentStatus: "COMPLETED"|"FAILED", resultPayloadJson, resultUrl, message }`. `taskId` is
required and must be the WorkOrder's `taskId`: the signature covers only the timestamp and body, so the body
naming its task is what stops a signed result from being replayed onto another task's callback.

The platform reads the raw bytes, verifies the token, then verifies the signature with the secret of the
token's Agent version (which must be the task's assigned one), and only then parses the JSON. It answers
401 when the token is invalid or expired, the body's `taskId` is another task's, or the signature is
missing, malformed, made with any other secret
(an old one after a rotation, another Agent's), over different bytes, or timestamped more than 5 minutes from
the platform's clock. A malformed body that is correctly signed is 400.

An Agent with no signing secret (registered before secrets existed) **can't post results at all**: its owner
must rotate the secret on the Agent manager (`POST /api/agents/{id}/rotate-secret`) and configure the new
value first. `demo-agent/app.py` (`sign_body`) signs its callback this way.

**Token lifetime.** The bearer token expires at `executionDeadline` + 5 minutes
(`DispatchTokenService.CALLBACK_MARGIN`), fixed when the consumer issues it. An Agent that calls back within
its declared execution time is never refused; a callback after that margin gets 401. There is no separate
TTL setting.

**Duplicate deliveries.** An Agent may receive the same WorkOrder more than once and must treat a repeat
`taskId` as the same job, not a new one. Only the first result for a task is recorded.

The consumer keeps repeats to the minimum, in this order:

1. It loads the task under a row lock and drops the message unless the task is still `QUEUED` for that
   version, so a WorkOrder for a refunded, timed-out or already-answered task never goes out.
2. It issues the token and sends. A failed send is retried (see the schedule below), so an Agent may see one
   WorkOrder per send the platform considered failed (for example a 5xx or a read timeout after the Agent had
   already received it).
3. It records an `ACCEPTED` row in the task's [dispatch history](data-model.md#dispatch-attempts), then
   marks the task `EXECUTING` under a row lock. A failure of either, after a successful send, is logged and
   the message is acknowledged, never redelivered.

The callback loads the task under the same row lock and accepts a result while the task is `QUEUED` or
`EXECUTING`, so calling back before the platform has recorded the start is safe.

**Webhook URL guard.** The version's webhook goes through the same SSRF guard as client webhooks
(`WebhookUrlValidatorPort.assertAgentWebhookDeliverable`, rules in
[`programmatic-channel.md`](programmatic-channel.md)) at registration, at version publish (the URL carries
over, so publishing re-checks today's DNS answer) and before every send. Registration and publish reject an
internal address with the client-webhook error (400 `VALIDATION_ERROR`). The dev opt-in for
`http://localhost` is `hireai.dispatch.allow-insecure-localhost`.

**Send retries.** Only a failure the Agent is accountable for counts as a failed attempt: its endpoint
refused or was unreachable, did not answer within the read timeout (the contract is a 2xx within 15s),
replied with a non-2xx status other than 429/503 (`AgentSendFailedException`, which carries the status),
or its host now resolves to an internal address (the send-time guard; nothing is sent). A 3xx counts as a
non-2xx reply: the dispatch client (`AgentDispatchRestClientConfig`) never follows redirects and never
resends a POST by itself, so the Agent gets exactly one WorkOrder per attempt. A 429/503 is a busy decline
(`AgentBusyException`), never a failed attempt: see **Acknowledgement contract**.
The work-order connect and read timeouts are 5s and 15s. The consumer never waits in place: it republishes
the message to a retry queue and acknowledges it, so other dispatches keep flowing.

A platform-side failure (the row-locked task check, issuing the token, an invalid webhook URL, or scheduling
the retry) never counts against the Agent. The consumer logs it and acknowledges the message, so it never
reaches the DLQ or `EXECUTION_FAILED`. The task stays `QUEUED` and its execution timeout refunds it without a
reputation event.

- `hireai.dispatch.retry-delays` (default `5s,10s,15s`) are the waits between attempts, so three delays mean
  four attempts in total. Startup fails if they add up to more than `hireai.execution.grace-seconds`, so
  retrying never eats into the Agent's execution time. Tests use millisecond delays.
- Each delay has its own queue, `task.dispatch.retry.<millis>ms`. The message carries that delay as its
  per-message TTL, and expiry dead-letters it back to the dispatch exchange. The attempt number travels in
  the `x-hireai-dispatch-attempt` header.
- When the last attempt fails, the consumer rejects the message and it dead-letters to `task.dispatch.dlq`.
  The DLQ listener then records an `UNREACHABLE` row in the dispatch history and `EXECUTION_FAILED`, and
  either fails the task over ([routed failover](#routed-failover)) or, for a pinned task, fails it
  (`FAILED`, refund, `task.failed` webhook), all in one transaction. A dead letter for a version the task
  no longer names (it already failed over) is ignored.
- `hireai.dispatch.consumers` (default 4) consumers each take one message at a time, so a hanging Agent
  holds only the consumer that is waiting on it.
- Every attempt re-runs step 1, so a task that ended while its message waited is dropped, not sent.
