# Matching and selection

How the platform decides which Agent runs a Task, and how a Task avoids getting stranded. Values live in
`MatchingPolicy` and `hireai.matching.*` / `hireai.execution.*` in `application.yml`; bad values crash
startup. Open defects: **Known gaps** in [`build-status.md`](build-status.md).

## One ranking engine, three uses

`RoutingMatchDomainService` (framework-free) exposes `rank` and `selectOne`, which adds a random draw on
top of `rank`.

| Flow | Who decides | Path |
|---|---|---|
| Web submit | Client, from a shortlist | `GET /api/tasks/match-preview` runs `rank`, then the pick calls `POST /api/tasks/direct` |
| API / MCP routed submit | Platform | `POST /api/tasks`, then `route()` runs `selectOne` |
| Re-match of a held routed task | Platform | Sweeper calls `route()` again over fresh candidates |
| Direct booking, or re-match of one | Client already chose | `dispatchDirect()`, no matching |

The web never calls `POST /api/tasks`, so every web task is a pinned direct booking.

**Shortlist.** Match-preview is read-only: it creates no task and freezes nothing. It returns the top 5 of
`rank` plus the 3 cheapest agents above budget (near-misses). The typed budget is only a ceiling for
ranking. The client pays the chosen agent's price, even for a near-miss above that budget.

**Eligibility differs by path.** Both need an `ACTIVE` agent whose current version covers the category.
`rank` also needs `price <= budget`. Match-preview additionally requires a listed storefront (`is_listed`);
`route()` does not, so an unlisted agent can win a routed task.

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
| Re-match attempts exhausted | `CANCELLED` | Yes | Yes | No |
| Past `execution_deadline` while `EXECUTING` | `TIMED_OUT` | Yes | Yes | `EXECUTION_TIMEOUT` |
| Past `execution_deadline` while still `QUEUED` | `TIMED_OUT` | Yes | Yes | No |
| Dispatch dead-letters | `FAILED` | Yes | No | No |

- **`AWAITING_AGENT`** means no `ACTIVE` agent covers the category within budget, or a pinned agent went
  inactive or was superseded. Load is soft, so a busy agent never causes it. The re-match sweeper
  (`AwaitingAgentRematchSweeper`) retries it up to `hireai.matching.rematch-max-attempts` times,
  then cancels with a refund.
- **Pinned tasks never substitute.** A direct booking retries only its exact version, because the client's
  spec and price are frozen to it (Invariant #4).
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
to the version's webhook with `Authorization: Bearer <dispatch token>` and `X-Correlation-ID`.

| Field | Meaning |
|---|---|
| `taskId` | The task. Use it to correlate the order with your callback and to spot a repeated delivery |
| `agentVersionId` | The version the task was assigned to, so one endpoint can serve several versions |
| `title`, `description` | Exactly what the Client submitted |
| `category` | The task's category (a direct booking takes the version's first category) |
| `outputSpec` | The task's frozen output spec (Hard Invariant #4) as a JSON object: `format`, `schema`, `acceptanceCriteria`. Your result is validated, and any dispute judged, against it |
| `executionDeadline` | ISO-8601 instant: the `execution_deadline` stamped at assignment. After it the task times out |
| `callbackUrl` | Where to POST the result, with the same bearer token |

Nothing else is sent: no Client identity and no budget.

**Token lifetime.** The bearer token expires at `executionDeadline` + 5 minutes
(`DispatchTokenService.CALLBACK_MARGIN`), fixed when the consumer issues it. An Agent that calls back within
its declared execution time is never refused; a callback after that margin gets 401. There is no separate
TTL setting.

**Duplicate deliveries.** An Agent may receive the same WorkOrder more than once and must treat a repeat
`taskId` as the same job, not a new one. Only the first result for a task is recorded.

The consumer keeps repeats to the minimum, in this order:

1. It loads the task under a row lock and drops the message unless the task is still `QUEUED` for that
   version, so a WorkOrder for a refunded, timed-out or already-answered task never goes out.
2. It issues the token and sends. A failed send is retried, so an Agent may see one WorkOrder per send the
   platform considered failed (for example a 5xx or a read timeout after the Agent had already received it).
3. It marks the task `EXECUTING` under a row lock. A failure here, after a successful send, is logged and the
   message is acknowledged, never redelivered.

The callback loads the task under the same row lock and accepts a result while the task is `QUEUED` or
`EXECUTING`, so calling back before the platform has recorded the start is safe.
