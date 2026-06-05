# Task Aggregate Extensions (Module 3, Track C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing Task aggregate with guarded immutable routing/execution state transitions, a `TaskResult` child persisted through the root, a token-authenticated agent-callback endpoint, and a routing read view — so routing/dispatch (Tracks A/B + Synthesis) can drive a task SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED.

**Architecture:** Pure DDD layering `controller → application → domain ← infrastructure`; the domain `TaskModel` gains immutable guarded transitions (each returns a NEW copy; illegal transitions throw `DomainException`). A new `TaskResultModel` child is loaded/saved through the `TaskRepository` root only. App services stay CQRS (read/write) interface + `impl/`; a new `AgentCallbackAppService` verifies the dispatch token (via the `DispatchTokenService` port — mocked in tests, owned by Track B) then drives `TaskModel.recordResult`. Track C depends ONLY on contract port interfaces and never imports Track A/B concrete classes.

**Tech Stack:** Java 21, Spring Boot 3.x, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + Testcontainers + Mockito.

---

## Contracts this plan OWNS (authoritative — use verbatim)

Created/extended by this plan:
- `com.hireai.domain.biz.task.enums.TaskStatus` — already exists (full lifecycle). NO change.
- `com.hireai.domain.biz.task.model.TaskModel` — extend with `category`, `agentVersionId`, `result` fields + guarded transitions.
- `com.hireai.domain.biz.task.model.TaskResultModel` — NEW immutable child.
- `com.hireai.domain.biz.task.info.TaskRoutingView` — NEW framework-free record (shared contract): `record TaskRoutingView(UUID taskId, String category, java.math.BigDecimal budget, String status) {}`.
- `com.hireai.domain.biz.task.info.AgentResultInfo` — NEW framework-free record: `record AgentResultInfo(String agentStatus, String resultPayloadJson, String resultUrl, String message) {}`.
- `com.hireai.application.port.security.DispatchTokenService` + `DispatchTokenClaims` + `DispatchTokenInvalidException` — port interfaces this plan DEPENDS on (defined by contracts-first Plan 0; this plan creates local stub copies only if absent — see Task 0).
- `com.hireai.application.port.task.TaskExecutionPort` — port interface; this plan provides the @Service implementation.
- `com.hireai.application.biz.task.TaskWriteAppService` — extend: `assignAndQueue`, `markAwaitingCapacity`.
- `com.hireai.application.biz.task.TaskReadAppService` — extend: `getRoutingView`.
- `com.hireai.application.biz.agentcallback.AgentCallbackAppService` + `impl/` — NEW.
- `com.hireai.controller.biz.agentcallback.AgentCallbackController` + `dto.AgentResultCallbackRequest` — NEW.
- Flyway `V4` — `ALTER TABLE tasks` + `CREATE TABLE task_results`.

Owned-by-other-plans signatures this plan MUST MATCH (do not redefine, just call):
- `DispatchTokenService.verify(String token) → DispatchTokenClaims` (throws `DispatchTokenInvalidException`).
- `DispatchTokenClaims(UUID taskId, UUID agentVersionId, java.time.Instant expiresAt)`.

---

## Task 0 — Contract ports present (compile-in-isolation guard)

This track depends on `DispatchTokenService`, `DispatchTokenClaims`, `DispatchTokenInvalidException` and `TaskExecutionPort`. Plan 0 (contracts-first) commits these to the base branch. If executing this worktree BEFORE the contracts commit is present, create the port interfaces so the track compiles in isolation; if they already exist, SKIP creating them (do not duplicate). The `TaskExecutionPort` **implementation** is always created by this plan (Task 8).

**Files:**
- Verify/Create: `backend/src/main/java/com/hireai/application/port/security/DispatchTokenService.java`
- Verify/Create: `backend/src/main/java/com/hireai/application/port/security/DispatchTokenClaims.java`
- Verify/Create: `backend/src/main/java/com/hireai/application/port/security/DispatchTokenInvalidException.java`
- Verify/Create: `backend/src/main/java/com/hireai/application/port/task/TaskExecutionPort.java`

**Steps:**

- [ ] Check whether the four files above already exist on the branch (`git ls-files | grep application/port`). If ALL four exist, mark this task done and skip to Task 1.
- [ ] If absent, create `DispatchTokenClaims.java`:

```java
package com.hireai.application.port.security;

import java.time.Instant;
import java.util.UUID;

/** Claims carried by a dispatch token: which task/agent-version it authorises and when it expires. */
public record DispatchTokenClaims(UUID taskId, UUID agentVersionId, Instant expiresAt) {
}
```

- [ ] Create `DispatchTokenInvalidException.java`:

```java
package com.hireai.application.port.security;

/** Raised by DispatchTokenService.verify when a token is malformed, tampered, expired, or task-mismatched. */
public class DispatchTokenInvalidException extends RuntimeException {

    public DispatchTokenInvalidException(String message) {
        super(message);
    }
}
```

- [ ] Create `DispatchTokenService.java`:

```java
package com.hireai.application.port.security;

import java.time.Duration;
import java.util.UUID;

/**
 * Application-layer port for issuing and verifying short-lived signed dispatch tokens
 * (Hard Invariant #6). The HMAC implementation lives in infrastructure (Track B). Track C
 * depends only on this interface and mocks it in tests.
 */
public interface DispatchTokenService {

    String issue(UUID taskId, UUID agentVersionId, Duration ttl);

    DispatchTokenClaims verify(String token);
}
```

- [ ] Create `TaskExecutionPort.java`:

```java
package com.hireai.application.port.task;

import java.util.UUID;

/**
 * Application-layer port that lets the dispatch/consumer side (Track B) flip task execution
 * status without importing Track C concrete app-service classes. Implemented by Track C.
 */
public interface TaskExecutionPort {

    void markExecuting(UUID taskId);

    void markTimedOut(UUID taskId);

    void markFailed(UUID taskId);
}
```

- [ ] Compile to confirm the ports resolve:

```
mvn -f backend/pom.xml -B -q compile
```

Expected: BUILD SUCCESS.

- [ ] Commit (only if files were created here):

```
git add backend/src/main/java/com/hireai/application/port
git commit -m "chore: add dispatch-token + task-execution contract ports for Track C isolation"
```

---

## Task 1 — `TaskResultModel` child entity (immutable, framework-free)

The result an Agent posts back. One per task. Framework-free, immutable, lives under the Task aggregate.

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/model/TaskResultModel.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/task/model/TaskResultModelTest.java`

**Steps:**

- [ ] Write the failing test `TaskResultModelTest.java`:

```java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskResultModelTest {

    @Test
    void recordBuildsResultWithGeneratedIdAndTimestamp() {
        UUID taskId = UUID.randomUUID();
        TaskResultModel result = TaskResultModel.record(taskId, "COMPLETED", "{\"k\":\"v\"}", "https://x/y");

        assertThat(result.id()).isNotNull();
        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.agentStatus()).isEqualTo("COMPLETED");
        assertThat(result.resultPayloadJson()).isEqualTo("{\"k\":\"v\"}");
        assertThat(result.resultUrl()).isEqualTo("https://x/y");
        assertThat(result.receivedAt()).isNotNull();
    }

    @Test
    void allowsNullResultUrl() {
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "FAILED", "{}", null);
        assertThat(result.resultUrl()).isNull();
    }

    @Test
    void rejectsBlankAgentStatus() {
        assertThatThrownBy(() -> TaskResultModel.record(UUID.randomUUID(), "  ", "{}", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> TaskResultModel.record(UUID.randomUUID(), "COMPLETED", null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rehydrateRebuildsFromPersistedState() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant at = Instant.now();
        TaskResultModel result = TaskResultModel.rehydrate(id, taskId, "COMPLETED", "{}", null, at);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.receivedAt()).isEqualTo(at);
    }
}
```

- [ ] Run it (expected FAIL — `TaskResultModel` does not exist):

```
mvn -f backend/pom.xml -B test -Dtest=TaskResultModelTest
```

Expected: compilation/test FAILURE (cannot find symbol `TaskResultModel`).

- [ ] Create `TaskResultModel.java`:

```java
package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable child entity of the {@link TaskModel} aggregate: the result an Agent posts back
 * for a task. One row per task (task_results.task_id is UNIQUE). Loaded and saved only through
 * the Task aggregate root. Framework-free.
 */
public final class TaskResultModel {

    private final UUID id;
    private final UUID taskId;
    private final String agentStatus;
    private final String resultPayloadJson;
    private final String resultUrl; // nullable
    private final Instant receivedAt;

    private TaskResultModel(UUID id, UUID taskId, String agentStatus, String resultPayloadJson,
                            String resultUrl, Instant receivedAt) {
        this.id = id;
        this.taskId = taskId;
        this.agentStatus = agentStatus;
        this.resultPayloadJson = resultPayloadJson;
        this.resultUrl = resultUrl;
        this.receivedAt = receivedAt;
    }

    /** Factory for a freshly received result: validates and stamps id + receivedAt. */
    public static TaskResultModel record(UUID taskId, String agentStatus,
                                         String resultPayloadJson, String resultUrl) {
        requirePresent(taskId, "task id");
        requireText(agentStatus, "agent status");
        requirePresent(resultPayloadJson, "result payload");
        return new TaskResultModel(UUID.randomUUID(), taskId, agentStatus.trim(),
                resultPayloadJson, resultUrl, Instant.now());
    }

    /** Rebuild from persisted state (no validation; the row was already valid when written). */
    public static TaskResultModel rehydrate(UUID id, UUID taskId, String agentStatus,
                                            String resultPayloadJson, String resultUrl, Instant receivedAt) {
        return new TaskResultModel(id, taskId, agentStatus, resultPayloadJson, resultUrl, receivedAt);
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public String agentStatus() { return agentStatus; }
    public String resultPayloadJson() { return resultPayloadJson; }
    public String resultUrl() { return resultUrl; }
    public Instant receivedAt() { return receivedAt; }
}
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=TaskResultModelTest
```

Expected: BUILD SUCCESS, 5 tests pass.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/domain/biz/task/model/TaskResultModel.java backend/src/test/java/com/hireai/domain/biz/task/model/TaskResultModelTest.java
git commit -m "feat: add TaskResultModel child entity for the Task aggregate"
```

---

## Task 2 — Extend `TaskModel`: `category` + `agentVersionId` + `result` fields and `submit(category)`

Add the new fields to the immutable aggregate, thread `category` through the `submit` factory, and add accessors. Guarded transitions land in Task 3 (kept separate so each test stays focused). This task ONLY widens the constructor + factory; existing transitions unchanged.

**Files:**
- Modify: `backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`
- Modify: `backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelTest.java`

**Steps:**

- [ ] Add the failing test cases to `TaskModelTest.java` — append these two methods inside the class (after `submitBuildsSubmittedTask`):

```java
    @Test
    void submitCarriesCategoryAndNullRoutingFields() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "Summarise doc", "Summarise the report",
                Money.of("25.00"), spec(), "summarisation");

        assertThat(task.category()).isEqualTo("summarisation");
        assertThat(task.agentVersionId()).isNull();
        assertThat(task.result()).isNull();
    }

    @Test
    void submitRejectsBlankCategory() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc",
                Money.of("5.00"), spec(), "  "))
                .isInstanceOf(DomainException.class);
    }
```

- [ ] Update the existing five `TaskModel.submit(...)` calls already in `TaskModelTest.java` (in `submitBuildsSubmittedTask`, `trimsTitleAndDescription`, `rejectsBlankTitle`, `rejectsBlankDescription`, `rejectsNonPositiveBudget`, `rejectsNullOutputSpec`) to pass a trailing `"general"` category argument. For example change:

```java
        TaskModel task = TaskModel.submit(clientId, "Summarise doc", "Summarise the attached report",
                Money.of("25.00"), spec());
```

to:

```java
        TaskModel task = TaskModel.submit(clientId, "Summarise doc", "Summarise the attached report",
                Money.of("25.00"), spec(), "general");
```

Apply the same trailing `, "general"` argument to every other `TaskModel.submit(...)` call in the file.

- [ ] Run it (expected FAIL — `submit` has no 6-arg form; `category()`/`agentVersionId()`/`result()` missing):

```
mvn -f backend/pom.xml -B test -Dtest=TaskModelTest
```

Expected: compilation FAILURE.

- [ ] Replace the entire body of `TaskModel.java` with the extended version (new fields, widened constructor, 6-arg `submit`, accessors; transitions are added in Task 3 so leave room — this version compiles and passes Task 2 tests):

```java
package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Task aggregate root. A task is the unit of work a client submits; its budget is
 * frozen in escrow at submission. Behaviour (the submit factory + the routing/execution
 * transitions) lives here, not in setters. The aggregate is framework-free and IMMUTABLE:
 * every transition returns a NEW copy and an illegal transition throws a {@link DomainException}.
 */
public final class TaskModel {

    private final UUID id;
    private final UUID clientId;
    private final String title;
    private final String description;
    private final Money budget;
    private final OutputSpec outputSpec;
    private final String category;
    private final TaskStatus status;
    private final UUID agentVersionId; // nullable until assigned
    private final TaskResultModel result; // nullable until a result is recorded
    private final Instant createdAt;

    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.category = category;
        this.status = status;
        this.agentVersionId = agentVersionId;
        this.result = result;
        this.createdAt = createdAt;
    }

    /** Factory for the SUBMIT transition: enforces invariants and creates a SUBMITTED task. */
    public static TaskModel submit(UUID clientId, String title, String description,
                                   Money budget, OutputSpec outputSpec, String category) {
        requirePresent(clientId, "client id");
        requireText(title, "title");
        requireText(description, "description");
        requirePositive(budget);
        requirePresent(outputSpec, "output spec");
        requireText(category, "category");
        return new TaskModel(UUID.randomUUID(), clientId, title.trim(), description.trim(),
                budget, outputSpec, category.trim(), TaskStatus.SUBMITTED, null, null, Instant.now());
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    private static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Budget must be positive");
        }
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public String title() { return title; }
    public String description() { return description; }
    public Money budget() { return budget; }
    public OutputSpec outputSpec() { return outputSpec; }
    public String category() { return category; }
    public TaskStatus status() { return status; }
    public UUID agentVersionId() { return agentVersionId; }
    public TaskResultModel result() { return result; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=TaskModelTest
```

Expected: BUILD SUCCESS (the existing module-level `submit` callers in app/infra still compile because they are updated in Tasks 4–5; if a compile error surfaces in `TaskSubmitDomainServiceImpl`/`TaskRepositoryImpl`/`TaskController` at this point, that is expected and resolved in Tasks 4, 6, 9 — to keep THIS task green, run with the focused `-Dtest` flag which still compiles the whole module, so proceed to those tasks; see note below).

> NOTE: `mvn test` compiles the whole module, so callers of the old 5-arg `submit` and the old 8-arg `TaskModel` constructor (in `TaskSubmitDomainServiceImpl`, `TaskRepositoryImpl`, `TaskController`) will FAIL to compile until Tasks 4, 6 and 9 update them. To keep the wave green, perform Task 2 → Task 3 → Task 4 → Task 6 → Task 9 as one logical compile unit and run the full `mvn -f backend/pom.xml -B test` only after Task 9. The per-task "expected PASS" for Tasks 2 and 3 refers to the new domain tests once the module compiles. Commit after each task regardless (commits need not individually compile in this internal wave; the wave ends green).

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelTest.java
git commit -m "feat: thread category + nullable agentVersionId/result through TaskModel.submit"
```

---

## Task 3 — `TaskModel` guarded immutable transitions

Add the six routing/execution transitions. Each validates the current status, throws `DomainException(DOMAIN_RULE_VIOLATION, ...)` on an illegal source state, and returns a NEW `TaskModel` (never mutates).

**Files:**
- Modify: `backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelTransitionsTest.java`

**Steps:**

- [ ] Write the failing test `TaskModelTransitionsTest.java`:

```java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelTransitionsTest {

    private TaskModel submitted() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
    }

    @Test
    void assignAndQueueMovesSubmittedToQueuedAndSetsAgentVersion() {
        UUID agentVersionId = UUID.randomUUID();
        TaskModel queued = submitted().assignAndQueue(agentVersionId);

        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.agentVersionId()).isEqualTo(agentVersionId);
    }

    @Test
    void assignAndQueueReturnsNewInstanceLeavingOriginalUnchanged() {
        TaskModel original = submitted();
        TaskModel queued = original.assignAndQueue(UUID.randomUUID());

        assertThat(queued).isNotSameAs(original);
        assertThat(original.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(original.agentVersionId()).isNull();
    }

    @Test
    void assignAndQueueRejectsNullAgentVersion() {
        assertThatThrownBy(() -> submitted().assignAndQueue(null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void assignAndQueueRejectsNonSubmittedSource() {
        TaskModel queued = submitted().assignAndQueue(UUID.randomUUID());
        assertThatThrownBy(() -> queued.assignAndQueue(UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markExecutingMovesQueuedToExecuting() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThat(executing.status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void markExecutingRejectsNonQueuedSource() {
        assertThatThrownBy(() -> submitted().markExecuting())
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recordResultMovesExecutingToResultReceivedAndAttachesResult() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        TaskResultModel result = TaskResultModel.record(executing.id(), "COMPLETED", "{}", null);

        TaskModel received = executing.recordResult(result);

        assertThat(received.status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(received.result()).isSameAs(result);
    }

    @Test
    void recordResultRejectsNonExecutingSource() {
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", null);
        assertThatThrownBy(() -> submitted().recordResult(result))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recordResultRejectsNullResult() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(() -> executing.recordResult(null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markAwaitingCapacityMovesSubmittedToAwaitingCapacity() {
        TaskModel awaiting = submitted().markAwaitingCapacity();
        assertThat(awaiting.status()).isEqualTo(TaskStatus.AWAITING_CAPACITY);
    }

    @Test
    void markAwaitingCapacityRejectsNonSubmittedSource() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(executing::markAwaitingCapacity)
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markTimedOutFromQueuedOrExecuting() {
        TaskModel fromQueued = submitted().assignAndQueue(UUID.randomUUID()).markTimedOut();
        assertThat(fromQueued.status()).isEqualTo(TaskStatus.TIMED_OUT);

        TaskModel fromExecuting = submitted().assignAndQueue(UUID.randomUUID()).markExecuting().markTimedOut();
        assertThat(fromExecuting.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test
    void markTimedOutRejectsSubmittedSource() {
        assertThatThrownBy(() -> submitted().markTimedOut())
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markFailedFromQueuedOrExecuting() {
        TaskModel fromQueued = submitted().assignAndQueue(UUID.randomUUID()).markFailed();
        assertThat(fromQueued.status()).isEqualTo(TaskStatus.FAILED);

        TaskModel fromExecuting = submitted().assignAndQueue(UUID.randomUUID()).markExecuting().markFailed();
        assertThat(fromExecuting.status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void markFailedRejectsSubmittedSource() {
        assertThatThrownBy(() -> submitted().markFailed())
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] Run it (expected FAIL — transition methods do not exist):

```
mvn -f backend/pom.xml -B test -Dtest=TaskModelTransitionsTest
```

Expected: compilation FAILURE (cannot find symbol `assignAndQueue`, etc.).

- [ ] Add the six transition methods + a private `copyWith` helper to `TaskModel.java`, inserted after the `requirePositive(Money)` helper and before the accessors block:

```java
    // --- Routing & execution transitions (immutable: each returns a NEW TaskModel) ---

    /** SUBMITTED → QUEUED: a matching agent version was selected. */
    public TaskModel assignAndQueue(UUID agentVersionId) {
        requireStatus(TaskStatus.SUBMITTED, "assignAndQueue");
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, agentVersionId, this.result);
    }

    /** QUEUED → EXECUTING: the task was dispatched to the agent. */
    public TaskModel markExecuting() {
        requireStatus(TaskStatus.QUEUED, "markExecuting");
        return copyWith(TaskStatus.EXECUTING, this.agentVersionId, this.result);
    }

    /** EXECUTING → RESULT_RECEIVED: the agent posted a result back. */
    public TaskModel recordResult(TaskResultModel result) {
        requireStatus(TaskStatus.EXECUTING, "recordResult");
        requirePresent(result, "result");
        return copyWith(TaskStatus.RESULT_RECEIVED, this.agentVersionId, result);
    }

    /** SUBMITTED → AWAITING_CAPACITY: no eligible active agent was found. */
    public TaskModel markAwaitingCapacity() {
        requireStatus(TaskStatus.SUBMITTED, "markAwaitingCapacity");
        return copyWith(TaskStatus.AWAITING_CAPACITY, this.agentVersionId, this.result);
    }

    /** QUEUED/EXECUTING → TIMED_OUT: the agent did not respond in time. */
    public TaskModel markTimedOut() {
        requireStatusIn("markTimedOut", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return copyWith(TaskStatus.TIMED_OUT, this.agentVersionId, this.result);
    }

    /** QUEUED/EXECUTING → FAILED: dispatch/execution failed terminally. */
    public TaskModel markFailed() {
        requireStatusIn("markFailed", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return copyWith(TaskStatus.FAILED, this.agentVersionId, this.result);
    }

    private TaskModel copyWith(TaskStatus newStatus, UUID newAgentVersionId, TaskResultModel newResult) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.category, newStatus, newAgentVersionId, newResult, this.createdAt);
    }

    private void requireStatus(TaskStatus expected, String transition) {
        if (this.status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Illegal transition " + transition + " from " + this.status + "; expected " + expected);
        }
    }

    private void requireStatusIn(String transition, TaskStatus... allowed) {
        for (TaskStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                "Illegal transition " + transition + " from " + this.status);
    }
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=TaskModelTransitionsTest
```

Expected: BUILD SUCCESS, all transition tests pass.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelTransitionsTest.java
git commit -m "feat: add guarded immutable routing/execution transitions to TaskModel"
```

---

## Task 4 — Thread `category` through `TaskSubmitInfo` + submit domain service

`TaskSubmitInfo` gains `category`; the submit domain service passes it to the widened `TaskModel.submit`.

**Files:**
- Modify: `backend/src/main/java/com/hireai/domain/biz/task/info/TaskSubmitInfo.java`
- Modify: `backend/src/main/java/com/hireai/domain/biz/task/service/impl/TaskSubmitDomainServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/task/service/TaskSubmitDomainServiceImplTest.java`

**Steps:**

- [ ] Write the failing test `TaskSubmitDomainServiceImplTest.java`:

```java
package com.hireai.domain.biz.task.service;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.service.impl.TaskSubmitDomainServiceImpl;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSubmitDomainServiceImplTest {

    private final TaskSubmitDomainService service = new TaskSubmitDomainServiceImpl();

    @Test
    void submitBuildsSubmittedTaskCarryingCategory() {
        TaskSubmitInfo info = new TaskSubmitInfo(UUID.randomUUID(), "title", "desc",
                Money.of("10.00"), new OutputSpec(OutputFormat.TEXT, null, "summary"), "translation");

        TaskModel task = service.submit(info);

        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.category()).isEqualTo("translation");
    }
}
```

- [ ] Run it (expected FAIL — `TaskSubmitInfo` has no `category` component):

```
mvn -f backend/pom.xml -B test -Dtest=TaskSubmitDomainServiceImplTest
```

Expected: compilation FAILURE.

- [ ] Replace `TaskSubmitInfo.java`:

```java
package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Domain-layer carrier for the submit use case. Assembled by the controller from a
 * validated request plus the server-side client id; passed to the application layer.
 * {@code category} drives routing to a capability-matching agent.
 */
public record TaskSubmitInfo(UUID clientId, String title, String description,
                             Money budget, OutputSpec outputSpec, String category) {
}
```

- [ ] Replace `TaskSubmitDomainServiceImpl.java`:

```java
package com.hireai.domain.biz.task.service.impl;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;

/** Stateless implementation of the submit transition; delegates to the aggregate factory. */
public class TaskSubmitDomainServiceImpl implements TaskSubmitDomainService {

    @Override
    public TaskModel submit(TaskSubmitInfo info) {
        return TaskModel.submit(info.clientId(), info.title(), info.description(),
                info.budget(), info.outputSpec(), info.category());
    }
}
```

- [ ] Run it (expected PASS once the module compiles — see the compile-unit note in Task 2):

```
mvn -f backend/pom.xml -B test -Dtest=TaskSubmitDomainServiceImplTest
```

Expected: BUILD SUCCESS.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/domain/biz/task/info/TaskSubmitInfo.java backend/src/main/java/com/hireai/domain/biz/task/service/impl/TaskSubmitDomainServiceImpl.java backend/src/test/java/com/hireai/domain/biz/task/service/TaskSubmitDomainServiceImplTest.java
git commit -m "feat: thread category through TaskSubmitInfo and submit domain service"
```

---

## Task 5 — Flyway `V4`: tasks columns + `task_results` table

Add `agent_version_id` (unconstrained UUID, no FK) and `category` to `tasks`; create `task_results` with a UNIQUE FK to `tasks`. Backfill existing rows' `category` (none exist in fresh DBs; the column is nullable to avoid breaking historical rows, but app writes always set it).

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__task_routing_and_results.sql`

**Steps:**

- [ ] Create `V4__task_routing_and_results.sql`:

```sql
-- V4: Routing & execution extensions for the Task aggregate (Module 3).
-- tasks gains the selected agent version (unconstrained UUID — no cross-context FK to
-- agent_versions, keeping the Agent and Task contexts independently deployable) and a
-- routing category. task_results stores the single result an Agent posts back per task;
-- result_payload is JSONB so the result shape can evolve without a migration.

ALTER TABLE tasks ADD COLUMN agent_version_id UUID;
ALTER TABLE tasks ADD COLUMN category TEXT;

CREATE TABLE task_results (
    id             UUID PRIMARY KEY,
    task_id        UUID NOT NULL UNIQUE REFERENCES tasks (id),
    result_payload JSONB NOT NULL,
    result_url     TEXT,
    agent_status   TEXT NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_create     TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_results_task ON task_results (task_id);
```

- [ ] There is no isolated unit test for SQL; it is exercised by the integration test in Task 11. Sanity-check the migration parses by running the existing integration suite if Docker is available (it auto-skips otherwise):

```
mvn -f backend/pom.xml -B test -Dtest=TaskSubmissionIntegrationTest
```

Expected: PASS if Docker present (Flyway applies V1–V4 cleanly), or SKIPPED if no Docker.

- [ ] Commit:

```
git add backend/src/main/resources/db/migration/V4__task_routing_and_results.sql
git commit -m "feat: Flyway V4 adds tasks.agent_version_id/category and task_results table"
```

---

## Task 6 — Persistence: extend `TaskJpaEntity`, add `TaskResultJpaEntity`, update `TaskRepositoryImpl`

The JPA entity gains `agentVersionId` + `category`; a new `TaskResultJpaEntity` + `TaskResultJpaRepository` persist the child; `TaskRepositoryImpl.save` writes the child through the root and `findById`/`toModel` rehydrate it.

**Files:**
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskResultJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskResultJpaRepository.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskRepositoryImpl.java`

**Steps:**

- [ ] Replace `TaskJpaEntity.java` (adds `category` + `agentVersionId` columns and accessors; note `category` is written non-null by the app even though the column is nullable for historical rows):

```java
package com.hireai.infrastructure.repository.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for a task. Separate from the domain {@code TaskModel} so the
 * domain stays framework-free. {@code output_spec} is stored as JSONB; the repository
 * impl serialises the {@code OutputSpec} value object to/from JSON. {@code agentVersionId}
 * is a plain UUID (no FK — the Agent context is independent).
 */
@Entity
@Table(name = "tasks")
public class TaskJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "budget", nullable = false)
    private BigDecimal budget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_spec", columnDefinition = "jsonb", nullable = false)
    private String outputSpec;

    @Column(name = "category")
    private String category;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected TaskJpaEntity() {
    }

    public TaskJpaEntity(UUID id, UUID clientId, String title, String description,
                         BigDecimal budget, String outputSpec, String category, String status,
                         UUID agentVersionId, Instant gmtCreate) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.category = category;
        this.status = status;
        this.agentVersionId = agentVersionId;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public BigDecimal getBudget() { return budget; }
    public String getOutputSpec() { return outputSpec; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public UUID getAgentVersionId() { return agentVersionId; }
    public Instant getGmtCreate() { return gmtCreate; }
}
```

- [ ] Create `TaskResultJpaEntity.java`:

```java
package com.hireai.infrastructure.repository.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for the single result an Agent posts back for a task. Written and
 * read only through the Task aggregate root. {@code result_payload} is stored as JSONB.
 */
@Entity
@Table(name = "task_results")
public class TaskResultJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload", columnDefinition = "jsonb", nullable = false)
    private String resultPayload;

    @Column(name = "result_url")
    private String resultUrl;

    @Column(name = "agent_status", nullable = false)
    private String agentStatus;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected TaskResultJpaEntity() {
    }

    public TaskResultJpaEntity(UUID id, UUID taskId, String resultPayload, String resultUrl,
                               String agentStatus, Instant receivedAt) {
        this.id = id;
        this.taskId = taskId;
        this.resultPayload = resultPayload;
        this.resultUrl = resultUrl;
        this.agentStatus = agentStatus;
        this.receivedAt = receivedAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getResultPayload() { return resultPayload; }
    public String getResultUrl() { return resultUrl; }
    public String getAgentStatus() { return agentStatus; }
    public Instant getReceivedAt() { return receivedAt; }
}
```

- [ ] Create `TaskResultJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for task_results rows. Internal to infrastructure. */
public interface TaskResultJpaRepository extends JpaRepository<TaskResultJpaEntity, UUID> {

    Optional<TaskResultJpaEntity> findByTaskId(UUID taskId);
}
```

- [ ] Replace `TaskRepositoryImpl.java` (writes the result child through the root; rehydrates `category`, `agentVersionId`, and the result):

```java
package com.hireai.infrastructure.repository.task;

import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link TaskRepository}. Maps
 * {@code TaskModel} &lt;-&gt; JPA entity, serialises the output spec via
 * {@link OutputSpecJsonMapper}, and persists/loads the {@link TaskResultModel} child
 * through the root only.
 */
@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskJpaRepository taskJpa;
    private final TaskResultJpaRepository taskResultJpa;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public TaskRepositoryImpl(TaskJpaRepository taskJpa, TaskResultJpaRepository taskResultJpa,
                              OutputSpecJsonMapper outputSpecJsonMapper) {
        this.taskJpa = taskJpa;
        this.taskResultJpa = taskResultJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @Override
    public TaskModel save(TaskModel task) {
        taskJpa.save(new TaskJpaEntity(
                task.id(), task.clientId(), task.title(), task.description(),
                task.budget().value(), outputSpecJsonMapper.toJson(task.outputSpec()),
                task.category(), task.status().name(), task.agentVersionId(), task.createdAt()));

        TaskResultModel result = task.result();
        if (result != null && taskResultJpa.findByTaskId(result.taskId()).isEmpty()) {
            taskResultJpa.save(new TaskResultJpaEntity(
                    result.id(), result.taskId(), result.resultPayloadJson(),
                    result.resultUrl(), result.agentStatus(), result.receivedAt()));
        }
        return task;
    }

    @Override
    public Optional<TaskModel> findById(UUID taskId) {
        return taskJpa.findById(taskId).map(this::toModel);
    }

    @Override
    public List<TaskModel> findByClientId(UUID clientId, TaskQuery query) {
        return taskJpa.findByClientIdOrderByGmtCreateDesc(
                        clientId, PageRequest.of(query.page(), query.size()))
                .stream()
                .map(this::toModel)
                .toList();
    }

    private TaskModel toModel(TaskJpaEntity entity) {
        TaskResultModel result = taskResultJpa.findByTaskId(entity.getId())
                .map(this::toResultModel)
                .orElse(null);
        return new TaskModel(
                entity.getId(), entity.getClientId(), entity.getTitle(), entity.getDescription(),
                Money.of(entity.getBudget()), outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getCategory(), TaskStatus.valueOf(entity.getStatus()),
                entity.getAgentVersionId(), result, entity.getGmtCreate());
    }

    private TaskResultModel toResultModel(TaskResultJpaEntity entity) {
        return TaskResultModel.rehydrate(entity.getId(), entity.getTaskId(), entity.getAgentStatus(),
                entity.getResultPayload(), entity.getResultUrl(), entity.getReceivedAt());
    }
}
```

- [ ] Run the repository round-trip via the existing integration test (auto-skips without Docker):

```
mvn -f backend/pom.xml -B test -Dtest=TaskSubmissionIntegrationTest
```

Expected: PASS if Docker present (V4 applied; tasks persist with the new columns), SKIPPED otherwise.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/infrastructure/repository/task/TaskJpaEntity.java backend/src/main/java/com/hireai/infrastructure/repository/task/TaskResultJpaEntity.java backend/src/main/java/com/hireai/infrastructure/repository/task/TaskResultJpaRepository.java backend/src/main/java/com/hireai/infrastructure/repository/task/TaskRepositoryImpl.java
git commit -m "feat: persist task category/agentVersionId and the task_results child through the root"
```

---

## Task 7 — `TaskWriteAppService` new methods: `assignAndQueue` + `markAwaitingCapacity`

The write app service gains two routing-driven methods: load the task, apply the transition, save. (`markExecuting`/`markTimedOut`/`markFailed` are exposed via `TaskExecutionPort` in Task 8.)

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/biz/task/TaskWriteAppService.java`
- Modify: `backend/src/main/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImplTest.java`

**Steps:**

- [ ] Write the failing test `TaskWriteAppServiceImplTest.java` (Mockito; verifies the loaded task is transitioned then saved, and NOT_FOUND propagates):

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskWriteAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock TaskSubmitDomainService taskSubmitDomainService;
    @Mock WalletWriteAppService walletWriteAppService;
    @Mock ApplicationEventPublisher eventPublisher;

    private TaskWriteAppServiceImpl service() {
        return new TaskWriteAppServiceImpl(taskRepository, taskSubmitDomainService,
                walletWriteAppService, eventPublisher);
    }

    private TaskModel submittedTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
    }

    @Test
    void assignAndQueueTransitionsAndSaves() {
        TaskModel task = submittedTask();
        UUID agentVersionId = UUID.randomUUID();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().assignAndQueue(task.id(), agentVersionId);

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(captor.getValue().agentVersionId()).isEqualTo(agentVersionId);
    }

    @Test
    void markAwaitingCapacityTransitionsAndSaves() {
        TaskModel task = submittedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().markAwaitingCapacity(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.AWAITING_CAPACITY);
    }

    @Test
    void assignAndQueueThrowsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignAndQueue(taskId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] Run it (expected FAIL — methods do not exist):

```
mvn -f backend/pom.xml -B test -Dtest=TaskWriteAppServiceImplTest
```

Expected: compilation FAILURE.

- [ ] Replace `TaskWriteAppService.java` (add the two new methods):

```java
package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates task WRITE use cases. The submit use case enforces Hard Invariant #1
 * (escrow before execution): the task is persisted and its budget frozen in the SAME
 * transaction, so a failed freeze rolls the task back — there is no task without a
 * successful escrow freeze. Returns only the task id; callers re-read via the read service.
 * The routing transitions ({@link #assignAndQueue}, {@link #markAwaitingCapacity}) are
 * driven by the routing module after a match decision.
 */
@Validated
public interface TaskWriteAppService {

    UUID submit(@NonNull TaskSubmitInfo taskSubmitInfo);

    void assignAndQueue(@NonNull UUID taskId, @NonNull UUID agentVersionId);

    void markAwaitingCapacity(@NonNull UUID taskId);
}
```

- [ ] Replace `TaskWriteAppServiceImpl.java` (add the two methods + a private loader; keep `submit` unchanged in behaviour):

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskWriteAppServiceImpl implements TaskWriteAppService {

    private final TaskRepository taskRepository;
    private final TaskSubmitDomainService taskSubmitDomainService;
    private final WalletWriteAppService walletWriteAppService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public UUID submit(TaskSubmitInfo taskSubmitInfo) {
        String correlationId = UUID.randomUUID().toString();
        TaskModel task = taskSubmitDomainService.submit(taskSubmitInfo);
        UUID taskId = taskRepository.save(task).id();
        walletWriteAppService.freeze(taskSubmitInfo.clientId(), taskSubmitInfo.budget(), taskId, correlationId);
        eventPublisher.publishEvent(new TaskSubmittedDomainEvent(
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(), task.createdAt()));
        log.info("Task {} submitted by client {}; budget {} frozen in escrow",
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget());
        return taskId;
    }

    @Override
    public void assignAndQueue(UUID taskId, UUID agentVersionId) {
        TaskModel task = load(taskId);
        taskRepository.save(task.assignAndQueue(agentVersionId));
        log.info("Task {} assigned to agent version {} and queued", taskId, agentVersionId);
    }

    @Override
    public void markAwaitingCapacity(UUID taskId) {
        TaskModel task = load(taskId);
        taskRepository.save(task.markAwaitingCapacity());
        log.info("Task {} marked AWAITING_CAPACITY (no eligible agent)", taskId);
    }

    private TaskModel load(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=TaskWriteAppServiceImplTest
```

Expected: BUILD SUCCESS.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/application/biz/task/TaskWriteAppService.java backend/src/main/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java backend/src/test/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImplTest.java
git commit -m "feat: add assignAndQueue + markAwaitingCapacity to TaskWriteAppService"
```

---

## Task 8 — Implement `TaskExecutionPort` (markExecuting/markTimedOut/markFailed)

Provide the @Service adapter that lets Track B flip execution status without importing Task concrete classes. It loads the task, applies the transition, saves.

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/task/impl/TaskExecutionPortImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/task/impl/TaskExecutionPortImplTest.java`

**Steps:**

- [ ] Write the failing test `TaskExecutionPortImplTest.java`:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutionPortImplTest {

    @Mock TaskRepository taskRepository;

    private TaskExecutionPortImpl port() {
        return new TaskExecutionPortImpl(taskRepository);
    }

    private TaskModel queuedTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID());
    }

    @Test
    void markExecutingTransitionsQueuedToExecuting() {
        TaskModel task = queuedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markExecuting(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void markTimedOutTransitionsQueuedToTimedOut() {
        TaskModel task = queuedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markTimedOut(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test
    void markFailedTransitionsQueuedToFailed() {
        TaskModel task = queuedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markFailed(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void throwsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port().markExecuting(taskId))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] Run it (expected FAIL — `TaskExecutionPortImpl` does not exist):

```
mvn -f backend/pom.xml -B test -Dtest=TaskExecutionPortImplTest
```

Expected: compilation FAILURE.

- [ ] Create `TaskExecutionPortImpl.java`:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application adapter implementing {@link TaskExecutionPort}. Lets the dispatch consumer
 * (Track B) flip task execution status without importing Task concrete app-service classes.
 * Each method loads the task, applies the guarded transition, and saves the new copy.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskExecutionPortImpl implements TaskExecutionPort {

    private final TaskRepository taskRepository;

    @Override
    public void markExecuting(UUID taskId) {
        taskRepository.save(load(taskId).markExecuting());
        log.info("Task {} marked EXECUTING", taskId);
    }

    @Override
    public void markTimedOut(UUID taskId) {
        taskRepository.save(load(taskId).markTimedOut());
        log.info("Task {} marked TIMED_OUT", taskId);
    }

    @Override
    public void markFailed(UUID taskId) {
        taskRepository.save(load(taskId).markFailed());
        log.info("Task {} marked FAILED", taskId);
    }

    private TaskModel load(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=TaskExecutionPortImplTest
```

Expected: BUILD SUCCESS.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/application/biz/task/impl/TaskExecutionPortImpl.java backend/src/test/java/com/hireai/application/biz/task/impl/TaskExecutionPortImplTest.java
git commit -m "feat: implement TaskExecutionPort (markExecuting/markTimedOut/markFailed)"
```

---

## Task 9 — `TaskReadAppService.getRoutingView` + update `SubmitTaskRequest`/`TaskController` for category

Add the routing read view and finish threading `category` to the HTTP boundary so the whole module compiles and the existing submit endpoint sets a category.

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/biz/task/TaskReadAppService.java`
- Modify: `backend/src/main/java/com/hireai/application/biz/task/impl/TaskReadAppServiceImpl.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/task/dto/SubmitTaskRequest.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/task/TaskController.java`
- Test: `backend/src/test/java/com/hireai/application/biz/task/impl/TaskReadAppServiceGetRoutingViewTest.java`

**Steps:**

- [ ] Write the failing test `TaskReadAppServiceGetRoutingViewTest.java`:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReadAppServiceGetRoutingViewTest {

    @Mock TaskRepository taskRepository;

    private TaskReadAppServiceImpl service() {
        return new TaskReadAppServiceImpl(taskRepository);
    }

    @Test
    void getRoutingViewReturnsCategoryBudgetAndStatus() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("42.50"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "translation");
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        TaskRoutingView view = service().getRoutingView(task.id());

        assertThat(view.taskId()).isEqualTo(task.id());
        assertThat(view.category()).isEqualTo("translation");
        assertThat(view.budget()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(view.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void getRoutingViewThrowsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRoutingView(taskId))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] Run it (expected FAIL — `getRoutingView` + `TaskRoutingView` missing):

```
mvn -f backend/pom.xml -B test -Dtest=TaskReadAppServiceGetRoutingViewTest
```

Expected: compilation FAILURE.

- [ ] Create the shared contract record `backend/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java`:

```java
package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model the routing module uses to match a submitted task to an agent. Framework-free.
 * Shared contract: produced by {@code TaskReadAppService.getRoutingView}, consumed by the
 * routing matcher (Synthesis).
 */
public record TaskRoutingView(UUID taskId, String category, BigDecimal budget, String status) {
}
```

- [ ] Replace `TaskReadAppService.java` (add `getRoutingView`):

```java
package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates task READ use cases. Enforces Hard Invariant #5 (server-side identity +
 * ownership): a task is only returned to the client that owns it; otherwise NOT_FOUND,
 * so existence is not leaked across clients. {@link #getRoutingView} is an internal,
 * non-owner-scoped read used by the routing module (no client identity is involved in
 * routing).
 */
@Validated
public interface TaskReadAppService {

    TaskModel getForClient(@NonNull UUID taskId, @NonNull UUID clientId);

    List<TaskModel> listForClient(@NonNull UUID clientId, @NonNull TaskQuery query);

    TaskRoutingView getRoutingView(@NonNull UUID taskId);
}
```

- [ ] Replace `TaskReadAppServiceImpl.java` (add `getRoutingView`):

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskReadAppServiceImpl implements TaskReadAppService {

    private final TaskRepository taskRepository;

    @Override
    public TaskModel getForClient(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

    @Override
    public List<TaskModel> listForClient(UUID clientId, TaskQuery query) {
        return taskRepository.findByClientId(clientId, query);
    }

    @Override
    public TaskRoutingView getRoutingView(UUID taskId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        return new TaskRoutingView(task.id(), task.category(), task.budget().value(), task.status().name());
    }
}
```

- [ ] Replace `SubmitTaskRequest.java` (add a validated `category`):

```java
package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.enums.OutputFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Inbound HTTP DTO for submitting a task. Bean Validation at the boundary. */
public record SubmitTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotBlank @Size(max = 100) String category,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @NotNull @Valid OutputSpecRequest outputSpec
) {

    public record OutputSpecRequest(
            @NotNull OutputFormat format,
            @Size(max = 5000) String schema,
            @Size(max = 5000) String acceptanceCriteria
    ) {
    }
}
```

- [ ] Edit `TaskController.java` — update the `submit` method to pass `category` into `TaskSubmitInfo`. Replace the `TaskSubmitInfo info = new TaskSubmitInfo(...)` construction in `submit(...)`:

```java
        TaskSubmitInfo info = new TaskSubmitInfo(
                clientId,
                request.title(),
                request.description(),
                Money.of(request.budget()),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()),
                request.category());
```

- [ ] Run the new test + the existing domain tests now that the module compiles:

```
mvn -f backend/pom.xml -B test -Dtest=TaskReadAppServiceGetRoutingViewTest,TaskModelTest,TaskModelTransitionsTest,TaskResultModelTest,TaskSubmitDomainServiceImplTest,TaskWriteAppServiceImplTest,TaskExecutionPortImplTest
```

Expected: BUILD SUCCESS, all listed tests pass.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java backend/src/main/java/com/hireai/application/biz/task/TaskReadAppService.java backend/src/main/java/com/hireai/application/biz/task/impl/TaskReadAppServiceImpl.java backend/src/main/java/com/hireai/controller/biz/task/dto/SubmitTaskRequest.java backend/src/main/java/com/hireai/controller/biz/task/TaskController.java backend/src/test/java/com/hireai/application/biz/task/impl/TaskReadAppServiceGetRoutingViewTest.java
git commit -m "feat: add TaskReadAppService.getRoutingView and category at the HTTP boundary"
```

---

## Task 10 — `AgentCallbackAppService` + impl: token-verified result recording

The callback app service: derive `AgentResultInfo`, call `DispatchTokenService.verify(token)` (a bad/expired/mismatched token throws `DispatchTokenInvalidException`), confirm the token's `taskId` matches the path `taskId`, then load + `recordResult` + save. Define `AgentResultInfo`. `DispatchTokenService` is MOCKED in tests (Track B owns the real impl).

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/info/AgentResultInfo.java`
- Create: `backend/src/main/java/com/hireai/application/biz/agentcallback/AgentCallbackAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/agentcallback/impl/AgentCallbackAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/agentcallback/impl/AgentCallbackAppServiceImplTest.java`

**Steps:**

- [ ] Write the failing test `AgentCallbackAppServiceImplTest.java`:

```java
package com.hireai.application.biz.agentcallback.impl;

import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentCallbackAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock DispatchTokenService dispatchTokenService;

    private AgentCallbackAppServiceImpl service() {
        return new AgentCallbackAppServiceImpl(taskRepository, dispatchTokenService);
    }

    private TaskModel executingTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
    }

    private AgentResultInfo result() {
        return new AgentResultInfo("COMPLETED", "{\"k\":\"v\"}", "https://x/y", "done");
    }

    @Test
    void recordResultVerifiesTokenTransitionsAndSaves() {
        TaskModel task = executingTask();
        UUID agentVersionId = task.agentVersionId();
        when(dispatchTokenService.verify("good"))
                .thenReturn(new DispatchTokenClaims(task.id(), agentVersionId, Instant.now().plusSeconds(60)));
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().recordResult(task.id(), "good", result());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(captor.getValue().result()).isNotNull();
        assertThat(captor.getValue().result().agentStatus()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().result().resultPayloadJson()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void recordResultPropagatesInvalidTokenAndNeverSaves() {
        UUID taskId = UUID.randomUUID();
        when(dispatchTokenService.verify("bad"))
                .thenThrow(new DispatchTokenInvalidException("bad signature"));

        assertThatThrownBy(() -> service().recordResult(taskId, "bad", result()))
                .isInstanceOf(DispatchTokenInvalidException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void recordResultRejectsTaskIdMismatchAndNeverSaves() {
        UUID pathTaskId = UUID.randomUUID();
        UUID tokenTaskId = UUID.randomUUID();
        when(dispatchTokenService.verify("mismatch"))
                .thenReturn(new DispatchTokenClaims(tokenTaskId, UUID.randomUUID(), Instant.now().plusSeconds(60)));

        assertThatThrownBy(() -> service().recordResult(pathTaskId, "mismatch", result()))
                .isInstanceOf(DispatchTokenInvalidException.class);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void recordResultThrowsNotFoundWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();
        when(dispatchTokenService.verify("good"))
                .thenReturn(new DispatchTokenClaims(taskId, UUID.randomUUID(), Instant.now().plusSeconds(60)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().recordResult(taskId, "good", result()))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] Run it (expected FAIL — types do not exist):

```
mvn -f backend/pom.xml -B test -Dtest=AgentCallbackAppServiceImplTest
```

Expected: compilation FAILURE.

- [ ] Create `AgentResultInfo.java`:

```java
package com.hireai.domain.biz.task.info;

/**
 * Domain-layer carrier for an agent's result callback. Assembled by the callback controller
 * from the validated request body and passed to the application layer.
 * {@code agentStatus} is one of COMPLETED / FAILED.
 */
public record AgentResultInfo(String agentStatus, String resultPayloadJson,
                              String resultUrl, String message) {
}
```

- [ ] Create `AgentCallbackAppService.java`:

```java
package com.hireai.application.biz.agentcallback;

import com.hireai.domain.biz.task.info.AgentResultInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates the agent result-callback use case. This is the one non-JWT entry point:
 * the caller is the Agent, authenticated by the short-lived dispatch token (Hard Invariant #6).
 * The bearer token is verified before any state changes; an invalid/expired/mismatched token
 * throws a {@code DispatchTokenInvalidException} (mapped to HTTP 401 at the controller).
 */
@Validated
public interface AgentCallbackAppService {

    void recordResult(@NonNull UUID taskId, @NonNull String bearerToken, @NonNull AgentResultInfo result);
}
```

- [ ] Create `AgentCallbackAppServiceImpl.java`:

```java
package com.hireai.application.biz.agentcallback.impl;

import com.hireai.application.biz.agentcallback.AgentCallbackAppService;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Verifies the dispatch token, confirms it authorises THIS task, then records the agent's
 * result through the Task aggregate ({@code EXECUTING → RESULT_RECEIVED}) and persists the
 * task_results child via the repository root.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentCallbackAppServiceImpl implements AgentCallbackAppService {

    private final TaskRepository taskRepository;
    private final DispatchTokenService dispatchTokenService;

    @Override
    public void recordResult(UUID taskId, String bearerToken, AgentResultInfo result) {
        DispatchTokenClaims claims = dispatchTokenService.verify(bearerToken);
        if (!claims.taskId().equals(taskId)) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token task " + claims.taskId() + " does not match callback task " + taskId);
        }
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        TaskResultModel resultModel = TaskResultModel.record(
                taskId, result.agentStatus(), result.resultPayloadJson(), result.resultUrl());
        taskRepository.save(task.recordResult(resultModel));
        log.info("Task {} recorded result with agent status {}", taskId, result.agentStatus());
    }
}
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=AgentCallbackAppServiceImplTest
```

Expected: BUILD SUCCESS, 4 tests pass.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/domain/biz/task/info/AgentResultInfo.java backend/src/main/java/com/hireai/application/biz/agentcallback backend/src/test/java/com/hireai/application/biz/agentcallback
git commit -m "feat: add AgentCallbackAppService recording token-verified agent results"
```

---

## Task 11 — `AgentCallbackController` + `AgentResultCallbackRequest` DTO (401 on bad token)

Thin controller for `POST /api/agent-callbacks/{taskId}/result`. Reads the `Authorization: Bearer` header, maps the request DTO to `AgentResultInfo`, delegates, and maps `DispatchTokenInvalidException` → HTTP 401 via a controller-local `@ExceptionHandler` (so the shared `GlobalExceptionConfiguration`, owned outside this track, is untouched).

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/agentcallback/dto/AgentResultCallbackRequest.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/agentcallback/AgentCallbackController.java`
- Test: `backend/src/test/java/com/hireai/controller/biz/agentcallback/AgentCallbackControllerTest.java`

**Steps:**

- [ ] Write the failing test `AgentCallbackControllerTest.java` (`@WebMvcTest`, mocks the app service; asserts 200 on success and 401 when the app service throws `DispatchTokenInvalidException`):

```java
package com.hireai.controller.biz.agentcallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.agentcallback.AgentCallbackAppService;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.controller.biz.agentcallback.dto.AgentResultCallbackRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentCallbackController.class)
@WithMockUser
class AgentCallbackControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AgentCallbackAppService agentCallbackAppService;

    private String body() throws Exception {
        return objectMapper.writeValueAsString(
                new AgentResultCallbackRequest("COMPLETED", "{\"k\":\"v\"}", "https://x/y", "done"));
    }

    @Test
    void returns200AndDelegatesOnValidToken() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());

        verify(agentCallbackAppService).recordResult(eq(taskId), eq("good-token"), any());
    }

    @Test
    void returns401WhenTokenInvalid() throws Exception {
        UUID taskId = UUID.randomUUID();
        doThrow(new DispatchTokenInvalidException("bad token"))
                .when(agentCallbackAppService).recordResult(any(), any(), any());

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenAuthorizationHeaderMissing() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(agentCallbackAppService);
    }
}
```

- [ ] Run it (expected FAIL — controller + DTO do not exist):

```
mvn -f backend/pom.xml -B test -Dtest=AgentCallbackControllerTest
```

Expected: compilation FAILURE.

- [ ] Create `AgentResultCallbackRequest.java`:

```java
package com.hireai.controller.biz.agentcallback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound HTTP DTO for an agent result callback. {@code agentStatus} is COMPLETED or FAILED;
 * {@code resultPayloadJson} is the (string-encoded) result body; {@code resultUrl} and
 * {@code message} are optional.
 */
public record AgentResultCallbackRequest(
        @NotBlank String agentStatus,
        @NotBlank String resultPayloadJson,
        @Size(max = 2000) String resultUrl,
        @Size(max = 2000) String message
) {
}
```

- [ ] Create `AgentCallbackController.java`:

```java
package com.hireai.controller.biz.agentcallback;

import com.hireai.application.biz.agentcallback.AgentCallbackAppService;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.ResultCode;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agentcallback.dto.AgentResultCallbackRequest;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Agent result-callback surface. Unlike every other endpoint this is NOT JWT-authenticated —
 * the caller is the Agent, authenticated instead by the short-lived dispatch token carried in
 * the Authorization header (Hard Invariant #6). Thin: extract the bearer token, map the body to
 * {@link AgentResultInfo}, delegate. A bad/expired/mismatched token is mapped to HTTP 401 by the
 * controller-local handler below, so the shared global handler stays untouched.
 */
@RestController
@RequestMapping("/api/agent-callbacks")
public class AgentCallbackController extends BaseController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentCallbackAppService agentCallbackAppService;

    public AgentCallbackController(AgentCallbackAppService agentCallbackAppService) {
        this.agentCallbackAppService = agentCallbackAppService;
    }

    @PostMapping("/{taskId}/result")
    public WebResult<Void> recordResult(@PathVariable("taskId") UUID taskId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @Valid @RequestBody AgentResultCallbackRequest request) {
        String token = extractBearer(authorization);
        AgentResultInfo result = new AgentResultInfo(
                request.agentStatus(), request.resultPayloadJson(), request.resultUrl(), request.message());
        agentCallbackAppService.recordResult(taskId, token, result);
        return ok();
    }

    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new DispatchTokenInvalidException("Missing or malformed Authorization header");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new DispatchTokenInvalidException("Empty bearer token");
        }
        return token;
    }

    @ExceptionHandler(DispatchTokenInvalidException.class)
    public ResponseEntity<WebResult<Void>> handleInvalidToken(DispatchTokenInvalidException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, "Invalid dispatch token"));
    }
}
```

- [ ] Run it (expected PASS):

```
mvn -f backend/pom.xml -B test -Dtest=AgentCallbackControllerTest
```

Expected: BUILD SUCCESS, 3 tests pass.

> NOTE: `@WebMvcTest` loads `SecurityConfig` (it is a `@Configuration` picked up by the slice's filter chain via `@WithMockUser` + the project's permit-all chain). If the slice fails to find the security filter chain bean, add `@Import(com.hireai.controller.config.SecurityConfig.class)` to the test class. The permit-all chain means the 401s asserted here come from the controller's own `DispatchTokenInvalidException` handler, not from Spring Security.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/controller/biz/agentcallback backend/src/test/java/com/hireai/controller/biz/agentcallback
git commit -m "feat: add AgentCallbackController POST /api/agent-callbacks/{taskId}/result (401 on bad token)"
```

---

## Task 12 — Update `TaskSubmissionIntegrationTest` for `category` + add callback integration test

Fix the existing integration test's `TaskSubmitInfo` construction (now 6-arg) and add a new `*IntegrationTest` that drives the full Task-side flow through a real Postgres: submit → assignAndQueue → markExecuting → callback (valid token, mocked `DispatchTokenService`) → assert `RESULT_RECEIVED` + persisted `task_results`; and a bad-token callback path.

**Files:**
- Modify: `backend/src/test/java/com/hireai/task/TaskSubmissionIntegrationTest.java`
- Create: `backend/src/test/java/com/hireai/task/AgentCallbackIntegrationTest.java`

**Steps:**

- [ ] In `TaskSubmissionIntegrationTest.java`, update the `info(...)` helper to pass a category (6-arg constructor):

```java
    private TaskSubmitInfo info(UUID clientId, String budget) {
        return new TaskSubmitInfo(clientId, "Summarise report", "Summarise the attached quarterly report",
                Money.of(budget), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"),
                "summarisation");
    }
```

- [ ] Run the updated existing integration test (auto-skips without Docker):

```
mvn -f backend/pom.xml -B test -Dtest=TaskSubmissionIntegrationTest
```

Expected: PASS if Docker present, SKIPPED otherwise.

- [ ] Create `AgentCallbackIntegrationTest.java`. It boots Spring with a real Postgres and a `@MockBean DispatchTokenService` (Track B's real impl is not in this worktree; mocking the port keeps the track isolated). It drives the Task-side flow end-to-end and asserts persistence:

```java
package com.hireai.task;

import com.hireai.application.biz.agentcallback.AgentCallbackAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V4. Drives the
 * Task-side routing/execution flow: submit → assignAndQueue → markExecuting → token-verified
 * callback → RESULT_RECEIVED + persisted task_results; plus the bad-token rejection path.
 * The {@link DispatchTokenService} port is mocked (Track B owns the real HMAC impl).
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
class AgentCallbackIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReadAppService taskReadAppService;
    @Autowired TaskExecutionPort taskExecutionPort;
    @Autowired AgentCallbackAppService agentCallbackAppService;
    @Autowired WalletWriteAppService walletWriteAppService;
    @Autowired JdbcTemplate jdbc;

    @MockBean DispatchTokenService dispatchTokenService;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    private UUID submitExecutingTask(UUID client, UUID agentVersionId) {
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(
                client, "Summarise", "Summarise the attached report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"), "summarisation"));
        taskWriteAppService.assignAndQueue(taskId, agentVersionId);
        taskExecutionPort.markExecuting(taskId);
        return taskId;
    }

    @Test
    void validTokenCallbackRecordsResultAndPersistsTaskResults() {
        UUID client = newClient();
        UUID agentVersionId = UUID.randomUUID();
        UUID taskId = submitExecutingTask(client, agentVersionId);

        when(dispatchTokenService.verify(eq("good")))
                .thenReturn(new DispatchTokenClaims(taskId, agentVersionId, Instant.now().plusSeconds(120)));

        agentCallbackAppService.recordResult(taskId, "good",
                new AgentResultInfo("COMPLETED", "{\"summary\":\"ok\"}", "https://x/y", "done"));

        TaskModel task = taskReadAppService.getForClient(taskId, client);
        assertThat(task.status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(task.result()).isNotNull();
        assertThat(task.result().agentStatus()).isEqualTo("COMPLETED");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ? AND agent_status = 'COMPLETED'",
                Integer.class, taskId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void badTokenCallbackIsRejectedAndNoResultIsPersisted() {
        UUID client = newClient();
        UUID agentVersionId = UUID.randomUUID();
        UUID taskId = submitExecutingTask(client, agentVersionId);

        when(dispatchTokenService.verify(any())).thenThrow(new DispatchTokenInvalidException("bad"));

        assertThatThrownBy(() -> agentCallbackAppService.recordResult(taskId, "bad",
                new AgentResultInfo("COMPLETED", "{}", null, null)))
                .isInstanceOf(DispatchTokenInvalidException.class);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ?", Integer.class, taskId);
        assertThat(rows).isZero();
        assertThat(taskReadAppService.getForClient(taskId, client).status()).isEqualTo(TaskStatus.EXECUTING);
    }
}
```

- [ ] Run the full suite (integration tests auto-skip without Docker; unit tests always run):

```
mvn -f backend/pom.xml -B test
```

Expected: BUILD SUCCESS. With Docker: both integration classes pass (Flyway applies V1–V4). Without Docker: integration classes are SKIPPED, all unit tests pass.

- [ ] Commit:

```
git add backend/src/test/java/com/hireai/task/TaskSubmissionIntegrationTest.java backend/src/test/java/com/hireai/task/AgentCallbackIntegrationTest.java
git commit -m "test: integration coverage for category + token-verified agent callback flow"
```

---

## Final verification

- [ ] Run the entire backend test suite one last time and confirm green:

```
mvn -f backend/pom.xml -B test
```

Expected: BUILD SUCCESS. Unit tests: `TaskResultModelTest`, `TaskModelTest`, `TaskModelTransitionsTest`, `TaskSubmitDomainServiceImplTest`, `TaskWriteAppServiceImplTest`, `TaskExecutionPortImplTest`, `TaskReadAppServiceGetRoutingViewTest`, `AgentCallbackAppServiceImplTest`, `AgentCallbackControllerTest` all pass; integration tests pass (Docker) or skip (no Docker).

- [ ] Confirm no Track A/B concrete classes were imported (isolation check):

```
git grep -n "infrastructure.messaging\|infrastructure.client\|infrastructure.security\|domain.biz.agent\|domain.biz.routing" -- backend/src/main/java/com/hireai/domain/biz/task backend/src/main/java/com/hireai/application/biz/task backend/src/main/java/com/hireai/application/biz/agentcallback backend/src/main/java/com/hireai/controller/biz/agentcallback
```

Expected: NO matches (Track C depends only on the `application.port.*` contract interfaces).
