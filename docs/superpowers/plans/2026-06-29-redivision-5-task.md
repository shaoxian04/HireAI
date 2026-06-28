# Capability Re-division — Slice 5: Task subdomain consolidation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fold the routing and agent-callback code into the Task subdomain package, fix stale "only SUBMITTED reachable" comments, and upgrade duplicate-callback handling from "throw on second" to graceful first-result-wins — the second callback returns 200 without re-processing. No migration; routes unchanged.

**Architecture:** Slice 5 of the incremental-strangler refactor (spec: `docs/superpowers/specs/2026-06-29-backend-capability-redivision-design.md`). Routing was never its own aggregate — it is a pure domain service + app-service orchestration over Task + Agent (spec §3, embedded decision). This slice promotes it physically: `domain.biz.routing` → `domain.biz.task.routing`; `application.biz.routing` → `application.biz.task.routing`; `application.biz.agentcallback` → `application.biz.task.callback`. Controllers (`controller.biz.agentcallback`) stay grouped by HTTP route, as in every prior slice. The callback-idempotency fix is the behavioral deepening called out in spec §4 Task bullet.

**Tech Stack:** Java 21, Spring Boot 3.x, COLA reactor, JPA/Hibernate, Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (auto-skip without Docker).

## Global Constraints

- **Suite green at every commit:** `mvn -f backend/pom.xml -q -B test` → BUILD SUCCESS, 0 failures. Docker unavailable locally → Testcontainers `*IntegrationTest`s auto-skip; the callback round-trip (including the idempotency change) is CI-validated by `AgentCallbackIntegrationTest` + `RoutingIntegrationTest`.
- **COLA layering compiler-enforced;** `hireai-domain` / `hireai-utility` carry no Spring. `RoutingMatchDomainService` + the info records remain framework-free after the move.
- **No migration.** The `task_results.task_id UNIQUE` constraint stays; first-result-wins never attempts a second insert, so the constraint is never triggered by a duplicate callback.
- **Routes unchanged.** `controller.biz.agentcallback` (`AgentCallbackController` + `dto/`) stays where it is; only its imports change.
- **OUT OF SCOPE (spec §7):** the validation gate (`RESULT_RECEIVED → PENDING_REVIEW / SPEC_VIOLATION`), `DISPUTED`, `AWAITING_SELECTION`, the sweeper / outbox. Do NOT add them.
- **Stage ONLY `backend/`** — never `git add -A`. No `Co-Authored-By`. Windows / Git Bash.

## File Structure

After this slice, the Task subdomain owns:

```
hireai-domain      com.hireai.domain.biz.task
                     model/      TaskModel, TaskResultModel, OutputSpec   [unchanged]
                     enums/      TaskStatus (stale comment fixed), TaskResolution, OutputFormat
                     repository/ TaskRepository                            [unchanged]
                     service/    TaskSubmitDomainService (+impl/)          [unchanged]
                     info/       TaskRoutingView, AgentResultInfo, ...     [unchanged]
                     routing/    [NEW — moved from domain.biz.routing]
                       service/  RoutingMatchDomainService (+impl/)
                       info/     DispatchMessage, TaskDispatchPayload

hireai-application com.hireai.application.biz.task
                     (existing task app services)                          [unchanged]
                     routing/    [NEW — moved from application.biz.routing]
                       RoutingAppService (+impl/)
                       RoutingEventListener
                     callback/   [NEW — moved from application.biz.agentcallback]
                       AgentCallbackAppService
                       impl/AgentCallbackAppServiceImpl   [idempotency guard added]
```

Unchanged locations (imports update only): `controller.biz.agentcallback.*`, `application.config.DomainServiceConfig`, `infrastructure.messaging.*` (TaskDispatchConsumer, RabbitTaskDispatchPublisher), `infrastructure.client.AgentDispatchClient`.

---

### Task 1: Relocate `routing` + `agentcallback` into the task subdomain

Mechanical prefix rename across four package trees. Behavior-identical; the existing suite is the test.

**Files moved:**

*Domain — 4 files:*
- `domain/.../biz/routing/service/RoutingMatchDomainService.java` → `biz/task/routing/service/`
- `domain/.../biz/routing/service/impl/RoutingMatchDomainServiceImpl.java` → `biz/task/routing/service/impl/`
- `domain/.../biz/routing/info/DispatchMessage.java` → `biz/task/routing/info/`
- `domain/.../biz/routing/info/TaskDispatchPayload.java` → `biz/task/routing/info/`

*Application — 5 files:*
- `application/.../biz/routing/RoutingAppService.java` → `biz/task/routing/`
- `application/.../biz/routing/impl/RoutingAppServiceImpl.java` → `biz/task/routing/impl/`
- `application/.../biz/routing/RoutingEventListener.java` → `biz/task/routing/`
- `application/.../biz/agentcallback/AgentCallbackAppService.java` → `biz/task/callback/`
- `application/.../biz/agentcallback/impl/AgentCallbackAppServiceImpl.java` → `biz/task/callback/impl/`

*Tests — 6 files:*
- `test/.../domain/biz/routing/service/RoutingMatchDomainServiceTest.java` → `.../domain/biz/task/routing/service/`
- `test/.../application/biz/routing/RoutingAppServiceImplTest.java` → `.../application/biz/task/routing/`
- `test/.../application/biz/routing/RoutingEventListenerTest.java` → `.../application/biz/task/routing/`
- `test/.../application/biz/agentcallback/impl/AgentCallbackAppServiceImplTest.java` → `.../application/biz/task/callback/impl/`
- `test/.../routing/RoutingIntegrationTest.java` → `.../task/routing/`
- `test/.../routing/RoutingAppServiceDirectDispatchTest.java` → `.../task/routing/`

*Unchanged (imports update only):* `AgentCallbackController`, `AgentCallbackControllerTest`, `AgentCallbackIntegrationTest` (already in `com.hireai.task`), `DomainServiceConfig`, `AgentDispatchClient`, `TaskDispatchConsumer`, `RabbitTaskDispatchPublisher`.

- [ ] **Step 1: git mv all files**

Run from repo root (Git Bash):

```bash
cd backend

# Domain: routing → task.routing
D=hireai-domain/src/main/java/com/hireai/domain/biz
mkdir -p "$D/task/routing/service/impl" "$D/task/routing/info"
git mv "$D/routing/service/RoutingMatchDomainService.java"          "$D/task/routing/service/RoutingMatchDomainService.java"
git mv "$D/routing/service/impl/RoutingMatchDomainServiceImpl.java" "$D/task/routing/service/impl/RoutingMatchDomainServiceImpl.java"
git mv "$D/routing/info/DispatchMessage.java"                       "$D/task/routing/info/DispatchMessage.java"
git mv "$D/routing/info/TaskDispatchPayload.java"                   "$D/task/routing/info/TaskDispatchPayload.java"

# Application: routing → task.routing; agentcallback → task.callback
A=hireai-application/src/main/java/com/hireai/application/biz
mkdir -p "$A/task/routing/impl" "$A/task/callback/impl"
git mv "$A/routing/RoutingAppService.java"                          "$A/task/routing/RoutingAppService.java"
git mv "$A/routing/impl/RoutingAppServiceImpl.java"                 "$A/task/routing/impl/RoutingAppServiceImpl.java"
git mv "$A/routing/RoutingEventListener.java"                       "$A/task/routing/RoutingEventListener.java"
git mv "$A/agentcallback/AgentCallbackAppService.java"              "$A/task/callback/AgentCallbackAppService.java"
git mv "$A/agentcallback/impl/AgentCallbackAppServiceImpl.java"     "$A/task/callback/impl/AgentCallbackAppServiceImpl.java"

# Tests: unit — mirror source packages
T=hireai-main/src/test/java/com/hireai
mkdir -p "$T/domain/biz/task/routing/service"
mkdir -p "$T/application/biz/task/routing"
mkdir -p "$T/application/biz/task/callback/impl"
mkdir -p "$T/task/routing"
git mv "$T/domain/biz/routing/service/RoutingMatchDomainServiceTest.java" \
       "$T/domain/biz/task/routing/service/RoutingMatchDomainServiceTest.java"
git mv "$T/application/biz/routing/RoutingAppServiceImplTest.java"  "$T/application/biz/task/routing/RoutingAppServiceImplTest.java"
git mv "$T/application/biz/routing/RoutingEventListenerTest.java"   "$T/application/biz/task/routing/RoutingEventListenerTest.java"
git mv "$T/application/biz/agentcallback/impl/AgentCallbackAppServiceImplTest.java" \
       "$T/application/biz/task/callback/impl/AgentCallbackAppServiceImplTest.java"

# Tests: integration — flat test packages routing → task.routing
git mv "$T/routing/RoutingIntegrationTest.java"              "$T/task/routing/RoutingIntegrationTest.java"
git mv "$T/routing/RoutingAppServiceDirectDispatchTest.java" "$T/task/routing/RoutingAppServiceDirectDispatchTest.java"
```

- [ ] **Step 2: Rewrite all FQN prefixes across the codebase**

Run from repo root (Git Bash). The four patterns are non-overlapping: `com.hireai.routing` does NOT appear as a substring of `com.hireai.application.biz.routing` (the latter has `.application.biz.` in between), so no collision in the sed pass.

```bash
grep -rl --include='*.java' \
  -e 'com\.hireai\.domain\.biz\.routing' \
  -e 'com\.hireai\.application\.biz\.routing' \
  -e 'com\.hireai\.application\.biz\.agentcallback' \
  -e 'com\.hireai\.routing' \
  backend \
  | xargs sed -i \
      -e 's/com\.hireai\.domain\.biz\.routing/com.hireai.domain.biz.task.routing/g' \
      -e 's/com\.hireai\.application\.biz\.routing/com.hireai.application.biz.task.routing/g' \
      -e 's/com\.hireai\.application\.biz\.agentcallback/com.hireai.application.biz.task.callback/g' \
      -e 's/com\.hireai\.routing/com.hireai.task.routing/g'
```

- [ ] **Step 3: Verify no stale references remain; confirm controller and infrastructure updated**

```bash
grep -rn --include='*.java' \
  -e 'com\.hireai\.domain\.biz\.routing' \
  -e 'com\.hireai\.application\.biz\.routing' \
  -e 'com\.hireai\.application\.biz\.agentcallback' \
  backend && echo "STALE REFS!" || echo "(clean)"

# Controller import updated to the new path:
grep -rn 'task\.callback\.AgentCallbackAppService' backend/hireai-controller

# Infrastructure imports updated (DispatchMessage + TaskDispatchPayload):
grep -rn 'task\.routing\.info' backend/hireai-infrastructure
```

Expected: `(clean)` on stale-ref check; controller grep shows 2 hits (AgentCallbackController + AgentCallbackControllerTest); infrastructure grep shows 2 hits (AgentDispatchClient + TaskDispatchConsumer) and 1 hit (RabbitTaskDispatchPublisher).

- [ ] **Step 4: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures, Testcontainers tests auto-skipped (no Docker locally). Test count identical to the slice-4 baseline.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor(task): relocate routing and agentcallback packages into the task subdomain"
```

---

### Task 2: Callback first-result-wins + stale comment fixes

**Design decision — where the idempotency check lives and how duplicates are detected:**

The check is an **app-service pre-check on task state** in `AgentCallbackAppServiceImpl.recordResult()`, placed AFTER token verification and agent-version confirmation but BEFORE calling `task.recordResult()`. The sequence:

1. Token is verified first. Invalid/expired token → `DispatchTokenInvalidException` → 401. (Unchanged.)
2. Task is loaded; agent-version match is confirmed. Mismatch → `DispatchTokenInvalidException` → 401. (Unchanged.)
3. **New guard:** `if (task.status() != TaskStatus.EXECUTING)` → log + return. A duplicate callback finds a non-EXECUTING status because the first callback already advanced the task to `RESULT_RECEIVED`. The duplicate exits with no state mutation.
4. Only when the task IS `EXECUTING` does the original path proceed (`task.recordResult()` → `taskRepository.save()`).

**Guarantee the first result is never overwritten:** the guard returns before any call to `task.recordResult()` or `taskRepository.save()`. No second `TaskResultModel` is created and no insert into `task_results` is attempted, so the `task_results.task_id UNIQUE` constraint is never triggered.

**HTTP status for a duplicate:** 200. The controller calls `agentCallbackAppService.recordResult()` and wraps exceptions as non-200. The duplicate takes the early return — no exception — so the controller returns its normal `ok()`.

**Files modified:**
- `hireai-application/.../biz/task/callback/impl/AgentCallbackAppServiceImpl.java` (guard + `TaskStatus` import + class javadoc update)
- `hireai-domain/.../biz/task/enums/TaskStatus.java` (stale class javadoc)
- `hireai-main/src/main/resources/db/migration/V2__tasks.sql` (stale SQL comment)
- `hireai-main/test/.../biz/task/callback/impl/AgentCallbackAppServiceImplTest.java` (new test method)

- [ ] **Step 1: Add idempotency guard to `AgentCallbackAppServiceImpl`**

In `backend/hireai-application/src/main/java/com/hireai/application/biz/task/callback/impl/AgentCallbackAppServiceImpl.java`:

Add the import after the existing task imports:

```java
import com.hireai.domain.biz.task.enums.TaskStatus;
```

Replace the class-level javadoc with:

```java
/**
 * Verifies the dispatch token, confirms it authorises THIS task, then records the agent's
 * result through the Task aggregate ({@code EXECUTING → RESULT_RECEIVED}) and persists the
 * task_results child via the repository root. A duplicate callback (task already past
 * EXECUTING) is treated as a first-result-wins no-op: the service returns without
 * re-processing and the caller receives 200 — the first result is never overwritten.
 */
```

Replace the `recordResult` method body with:

```java
@Override
public void recordResult(UUID taskId, String bearerToken, AgentResultInfo result) {
    DispatchTokenClaims claims = dispatchTokenService.verify(bearerToken);
    if (!claims.taskId().equals(taskId)) {
        throw new DispatchTokenInvalidException(
                "Dispatch token task " + claims.taskId() + " does not match callback task " + taskId);
    }
    TaskModel task = taskRepository.findById(taskId)
            .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    if (!claims.agentVersionId().equals(task.agentVersionId())) {
        throw new DispatchTokenInvalidException(
                "Dispatch token agent version " + claims.agentVersionId()
                        + " does not match task " + taskId + " assignment " + task.agentVersionId());
    }
    // First-result-wins idempotency: if the task is no longer EXECUTING, the first callback
    // has already been processed (task is RESULT_RECEIVED or beyond). Return without
    // re-processing — no second insert into task_results, so the UNIQUE constraint is never
    // triggered. The first result is preserved unchanged.
    if (task.status() != TaskStatus.EXECUTING) {
        log.info("Task {} is already in status {} (not EXECUTING); treating duplicate callback as " +
                 "no-op — first result wins", taskId, task.status());
        return;
    }
    TaskResultModel resultModel = TaskResultModel.record(
            taskId, result.agentStatus(), result.resultPayloadJson(), result.resultUrl());
    taskRepository.save(task.recordResult(resultModel));
    log.info("Task {} recorded result with agent status {}", taskId, result.agentStatus());
}
```

- [ ] **Step 2: Fix stale javadoc in `TaskStatus.java`**

In `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/enums/TaskStatus.java`, replace the class-level javadoc block:

Old:
```java
/**
 * Full task lifecycle, per the SAD (see docs/details/data-model.md). Only
 * {@link #SUBMITTED} is reachable in the current slice; the remaining states are
 * declared for schema forward-compatibility and land with the routing, validation,
 * dispute, and settlement modules. The happy path is
 * SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → PENDING_REVIEW → RESOLVED;
 * the rest are off-path terminal/holding states.
 */
```

New:
```java
/**
 * Full task lifecycle, per the SAD (see docs/details/data-model.md).
 * All routing and settlement states are now live. The implemented happy path is
 * SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED → RESOLVED (client accept/reject);
 * off-path outcomes: AWAITING_CAPACITY (no eligible agent matched), TIMED_OUT, FAILED.
 * Deferred to future modules: PENDING_REVIEW and SPEC_VIOLATION (Module 4 validation
 * gate); CANCELLED is reserved. PENDING_REVIEW is included in {@link #PENDING_ESCROW}
 * for forward-compatibility with the planned validation gate.
 */
```

- [ ] **Step 3: Fix stale comment in `V2__tasks.sql`**

In `backend/hireai-main/src/main/resources/db/migration/V2__tasks.sql`, replace the header comment block (first 5 lines):

Old:
```sql
-- V2: Tasks. A task is the unit of work a client submits; its budget is frozen in
-- escrow at submission (see Wallet/ledger in V1). output_spec is the binding output
-- contract used later by validation and arbitration; stored as JSONB so the contract
-- shape can evolve without a migration. Only SUBMITTED is reachable in the current
-- slice; the rest of the status set is declared for forward-compatibility.
```

New:
```sql
-- V2: Tasks. A task is the unit of work a client submits; its budget is frozen in
-- escrow at submission (see Wallet/ledger in V1). output_spec is the binding output
-- contract used later by validation and arbitration; stored as JSONB so the contract
-- shape can evolve without a migration. Routing, execution, and settlement states
-- (QUEUED through RESOLVED, AWAITING_CAPACITY, TIMED_OUT, FAILED) are all live.
-- PENDING_REVIEW and SPEC_VIOLATION are deferred to Module 4 (validation gate).
```

- [ ] **Step 4: Add idempotency unit test to `AgentCallbackAppServiceImplTest`**

In `backend/hireai-main/src/test/java/com/hireai/application/biz/task/callback/impl/AgentCallbackAppServiceImplTest.java`, add the following import (if not already present after Task 1's sed):

```java
import com.hireai.domain.biz.task.model.TaskResultModel;
```

Add this test method to the class body:

```java
@Test
void duplicateCallbackAfterResultReceivedIsNoOp() {
    // Build a task already in RESULT_RECEIVED — the first callback has already been processed.
    UUID agentVersionId = UUID.randomUUID();
    TaskModel executing = TaskModel.submit(
                    UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                    new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
            .assignAndQueue(agentVersionId)
            .markExecuting();
    TaskModel resultReceived = executing.recordResult(
            TaskResultModel.record(executing.id(), "COMPLETED", "{\"first\":true}", null));

    when(dispatchTokenService.verify("dup"))
            .thenReturn(new DispatchTokenClaims(resultReceived.id(), agentVersionId,
                    Instant.now().plusSeconds(60)));
    when(taskRepository.findById(resultReceived.id())).thenReturn(Optional.of(resultReceived));

    // Duplicate callback: must be a silent no-op (first result wins), not throw.
    service().recordResult(resultReceived.id(), "dup", result());

    // No state change: taskRepository.save() is never called; first result preserved.
    verify(taskRepository, never()).save(any());
}
```

- [ ] **Step 5: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures. Net change: +1 unit test over the Task 1 baseline. Testcontainers tests auto-skipped locally.

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "fix(task): first-result-wins callback idempotency; fix stale TaskStatus and V2 comments"
```

> NOTE FOR CONTROLLER: the idempotency change touches the callback path (Hard Invariant #6). It is validated locally by the new unit test (`duplicateCallbackAfterResultReceivedIsNoOp`). The end-to-end path — including a concurrent duplicate that would previously have hit the UNIQUE constraint — is validated in CI by `AgentCallbackIntegrationTest` (Testcontainers, auto-skips without Docker). Flag for the CI run / final whole-branch review.

---

### Task 3: Slice gate + tag

- [ ] **Step 1: Full suite green**

```bash
mvn -f backend/pom.xml -B test 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors|BUILD SUCCESS|BUILD FAILURE" | tail -3
```

Expected: BUILD SUCCESS; aggregate `Failures: 0, Errors: 0`.

- [ ] **Step 2: Tag**

```bash
git tag redivision-5-task
```

---

## Self-Review

**Spec coverage (spec §4 Task bullet + §6 step 5):**
- "routing folds into Task" → Task 1 moves `domain.biz.routing` and `application.biz.routing` under `task.routing`. ✓
- "agentcallback folds into Task" → Task 1 moves `application.biz.agentcallback` under `task.callback`. ✓
- "make duplicate callbacks first-result-wins (graceful) instead of throwing" → Task 2 Step 1: app-service pre-check after token + agent-version verification but before the domain guard. Duplicate returns 200 with no state mutation. ✓
- "fix stale comments" → Task 2 Steps 2–3: `TaskStatus.java` javadoc and `V2__tasks.sql` header. ✓
- "Routing is NOT its own subdomain — it is an app-service over Task + Agent, never an aggregate" → the relocation moves it under `task.*`, not a top-level `routing.*`. ✓
- "Controllers stay grouped by HTTP route" → `controller.biz.agentcallback` stays; only imports change. ✓
- "No migration" → confirmed; `task_results.task_id UNIQUE` retained and never triggered by the idempotency path. ✓
- OUT-OF-SCOPE items (spec §7) — validation gate, DISPUTED, AWAITING_SELECTION, sweeper: none added. ✓

**Placeholder scan:** Test in Task 2 Step 4 is fully specified — concrete task construction (`TaskModel.submit...assignAndQueue...markExecuting...recordResult`), concrete mock stubs, concrete assertion (`verify(taskRepository, never()).save(any())`). No TBDs.

**Type consistency:**
- `AgentCallbackAppServiceImpl.recordResult()` adds `import com.hireai.domain.biz.task.enums.TaskStatus;` and uses `TaskStatus.EXECUTING` (defined in the enum). No signature changes.
- The new unit test constructs `TaskResultModel.record(UUID taskId, String agentStatus, String resultPayloadJson, String resultUrl)` — matches the existing factory signature. `DispatchTokenClaims(UUID taskId, UUID agentVersionId, Instant expiry)` — matches the 3-arg record already used in the sibling tests.
- After Task 1's sed pass, `AgentCallbackAppServiceImpl`'s own class-import (`com.hireai.application.biz.task.callback.AgentCallbackAppService`) and the controller's import of the same are both rewritten correctly.

**Risk note:** The idempotency change touches the callback path (Hard Invariant #6). Locally validated by the new unit test; the full round-trip (real Postgres, real UNIQUE constraint, concurrent callback) is validated in CI by `AgentCallbackIntegrationTest`. Flagged for the CI run / final review.
