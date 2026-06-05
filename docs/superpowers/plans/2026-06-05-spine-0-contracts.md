# Marketplace Spine — Plan 0: Contracts-First Seam Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create and commit the shared seam types (records + application ports) that let Marketplace Spine Tracks A/B/C/Synthesis compile and build in isolation against stable interfaces.

**Architecture:** This plan is executed FIRST and sequentially. It adds only behavior-free DDD seam types — four framework-free domain records, four application-port interfaces, one claims record, and one exception — under `com.hireai`. No schema, no Spring wiring, no business logic. It is committed to the base branch `feat/marketplace-spine`; Plans 1–4 each branch (worktree) from this commit so every track has the cross-track types it depends on.

**Tech Stack:** Java 21, Spring Boot 3.x, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + Testcontainers.

---

## Conventions recap (from the existing codebase — follow exactly)

- DDD layering `controller → application → domain ← infrastructure`. The **domain layer has ZERO framework imports** (records `AgentCandidate`, `TaskRoutingView`, `TaskDispatchPayload`, `DispatchMessage` are plain Java).
- The **application layer** may import Spring/Jakarta, but the port interfaces in this plan are deliberately framework-free interfaces (they are pure seams; implementations in later plans add `@Service`/`@Component`).
- Records are the project idiom for immutable carriers (see `TaskSubmitInfo`, `TaskSubmittedDomainEvent`, `OutputSpec`). Each gets a short Javadoc block in the existing house style.
- Unit tests: JUnit 5 + AssertJ (`org.assertj.core.api.Assertions`), package-private `class`, package-private `@Test` methods, mirroring `MoneyTest` / `TaskModelTest`.
- Unit-test command: `mvn -f backend/pom.xml -B test`. This plan adds NO integration test (the ports are verified by compilation when Plans 1–4 implement them).
- Conventional-commit messages (`feat:`). Do **not** add `Co-Authored-By` lines.
- This plan runs **before** the fan-out. The base branch `feat/marketplace-spine` is already checked out (confirmed). All commits land directly on it.

### Package targets created by this plan

```
com.hireai.domain.biz.agent.info.AgentCandidate
com.hireai.domain.biz.task.info.TaskRoutingView
com.hireai.domain.biz.routing.info.TaskDispatchPayload
com.hireai.domain.biz.routing.info.DispatchMessage
com.hireai.application.port.messaging.TaskDispatchPublisher
com.hireai.application.port.security.DispatchTokenService
com.hireai.application.port.security.DispatchTokenClaims
com.hireai.application.port.security.DispatchTokenInvalidException
com.hireai.application.port.task.TaskExecutionPort
```

> Note on physical paths: source root is `backend/src/main/java/`, test root is `backend/src/test/java/`. So `com.hireai.domain.biz.agent.info.AgentCandidate` lives at `backend/src/main/java/com/hireai/domain/biz/agent/info/AgentCandidate.java`.

---

## Task 1 — `AgentCandidate` domain record (CONTRACTS item 1)

A framework-free read-model record used by routing to evaluate candidate agents.

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/info/AgentCandidate.java`
- Test (created here, extended by later tasks): `backend/src/test/java/com/hireai/contracts/SpineContractsTest.java`

### Steps

- [ ] **Write the failing test.** Create `backend/src/test/java/com/hireai/contracts/SpineContractsTest.java`:

```java
package com.hireai.contracts;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpineContractsTest {

    @Test
    void agentCandidateExposesAllAccessors() {
        UUID agentId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        AgentCandidate candidate = new AgentCandidate(
                agentId, agentVersionId, List.of("SUMMARISATION", "TRANSLATION"),
                new BigDecimal("12.50"), "https://agent.example.com/webhook", 120,
                new BigDecimal("87.25"));

        assertThat(candidate.agentId()).isEqualTo(agentId);
        assertThat(candidate.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(candidate.capabilityCategories()).containsExactly("SUMMARISATION", "TRANSLATION");
        assertThat(candidate.price()).isEqualByComparingTo("12.50");
        assertThat(candidate.webhookUrl()).isEqualTo("https://agent.example.com/webhook");
        assertThat(candidate.maxExecutionSeconds()).isEqualTo(120);
        assertThat(candidate.reputationScore()).isEqualByComparingTo("87.25");
    }
}
```

- [ ] **Run it — expect FAIL (does not compile: `AgentCandidate` missing).**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: compilation failure / `cannot find symbol: class AgentCandidate`.

- [ ] **Minimal implementation.** Create `backend/src/main/java/com/hireai/domain/biz/agent/info/AgentCandidate.java`:

```java
package com.hireai.domain.biz.agent.info;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-model carrier describing one ACTIVE agent version eligible to execute a task.
 * Framework-free (the domain layer has zero framework imports). Produced by
 * {@code AgentRepository.findActiveCandidates} (Plan 1) and consumed by the routing
 * matcher (Plan 4) to pick the best agent for a submitted task.
 */
public record AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                             BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                             BigDecimal reputationScore) {
}
```

- [ ] **Run it — expect PASS.**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/info/AgentCandidate.java backend/src/test/java/com/hireai/contracts/SpineContractsTest.java
git commit -m "feat: add AgentCandidate seam record for marketplace spine routing"
```

---

## Task 2 — `TaskRoutingView` domain record (CONTRACTS item 2)

A framework-free read-model record carrying the routing-relevant projection of a task.

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java`
- Modify (test): `backend/src/test/java/com/hireai/contracts/SpineContractsTest.java`

### Steps

- [ ] **Write the failing test.** Add this import and method to `SpineContractsTest.java`.

Add import (alongside the existing imports):

```java
import com.hireai.domain.biz.task.info.TaskRoutingView;
```

Add this `@Test` method to the class body:

```java
    @Test
    void taskRoutingViewExposesAllAccessors() {
        UUID taskId = UUID.randomUUID();
        TaskRoutingView view = new TaskRoutingView(
                taskId, "SUMMARISATION", new BigDecimal("25.00"), "SUBMITTED");

        assertThat(view.taskId()).isEqualTo(taskId);
        assertThat(view.category()).isEqualTo("SUMMARISATION");
        assertThat(view.budget()).isEqualByComparingTo("25.00");
        assertThat(view.status()).isEqualTo("SUBMITTED");
    }
```

- [ ] **Run it — expect FAIL (does not compile: `TaskRoutingView` missing).**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: compilation failure / `cannot find symbol: class TaskRoutingView`.

- [ ] **Minimal implementation.** Create `backend/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java`:

```java
package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model projection of a Task carrying only what routing needs to match it
 * (category, budget, current status). Framework-free. Produced by
 * {@code TaskReadAppService.getRoutingView} (Plan 3) and passed to the routing
 * matcher (Plan 4); the routing trigger re-reads the task rather than enriching the
 * submitted event, so the event contract stays stable.
 */
public record TaskRoutingView(UUID taskId, String category, BigDecimal budget, String status) {
}
```

- [ ] **Run it — expect PASS.**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java backend/src/test/java/com/hireai/contracts/SpineContractsTest.java
git commit -m "feat: add TaskRoutingView seam record for marketplace spine routing"
```

---

## Task 3 — `TaskDispatchPayload` domain record (CONTRACTS item 3)

The body carried inside a dispatch message: the task content an agent needs to execute.

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/routing/info/TaskDispatchPayload.java`
- Modify (test): `backend/src/test/java/com/hireai/contracts/SpineContractsTest.java`

### Steps

- [ ] **Write the failing test.** Add this import and method to `SpineContractsTest.java`.

Add import:

```java
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
```

Add this `@Test` method to the class body:

```java
    @Test
    void taskDispatchPayloadExposesAllAccessors() {
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise doc", "Summarise the attached report", "SUMMARISATION",
                "{\"format\":\"TEXT\"}", "{\"format\":\"TEXT\",\"acceptanceCriteria\":\"concise\"}",
                "https://platform.example.com/api/agent-callbacks/abc/result");

        assertThat(payload.title()).isEqualTo("Summarise doc");
        assertThat(payload.description()).isEqualTo("Summarise the attached report");
        assertThat(payload.category()).isEqualTo("SUMMARISATION");
        assertThat(payload.expectedDeliverableJson()).isEqualTo("{\"format\":\"TEXT\"}");
        assertThat(payload.outputSpecJson()).isEqualTo("{\"format\":\"TEXT\",\"acceptanceCriteria\":\"concise\"}");
        assertThat(payload.callbackUrl()).isEqualTo("https://platform.example.com/api/agent-callbacks/abc/result");
    }
```

- [ ] **Run it — expect FAIL (does not compile: `TaskDispatchPayload` missing).**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: compilation failure / `cannot find symbol: class TaskDispatchPayload`.

- [ ] **Minimal implementation.** Create `backend/src/main/java/com/hireai/domain/biz/routing/info/TaskDispatchPayload.java`:

```java
package com.hireai.domain.biz.routing.info;

/**
 * The task content carried to an agent for execution. Framework-free. JSON-bearing
 * fields ({@code expectedDeliverableJson}, {@code outputSpecJson}) are passed through
 * as opaque strings so the domain never parses agent-facing JSON. Built by the routing
 * orchestration (Plan 4) and serialised into the webhook request by the dispatch client
 * (Plan 2); {@code callbackUrl} is where the agent posts its result back.
 */
public record TaskDispatchPayload(String title, String description, String category,
                                  String expectedDeliverableJson, String outputSpecJson,
                                  String callbackUrl) {
}
```

- [ ] **Run it — expect PASS.**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/routing/info/TaskDispatchPayload.java backend/src/test/java/com/hireai/contracts/SpineContractsTest.java
git commit -m "feat: add TaskDispatchPayload seam record for marketplace spine dispatch"
```

---

## Task 4 — `DispatchMessage` domain record (CONTRACTS item 4)

The RabbitMQ message envelope (serialised as JSON by Jackson on the messaging side).

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/routing/info/DispatchMessage.java`
- Modify (test): `backend/src/test/java/com/hireai/contracts/SpineContractsTest.java`

### Steps

- [ ] **Write the failing test.** Add this import and method to `SpineContractsTest.java`.

Add import:

```java
import com.hireai.domain.biz.routing.info.DispatchMessage;
```

Add this `@Test` method to the class body:

```java
    @Test
    void dispatchMessageExposesAllAccessors() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise doc", "Summarise the attached report", "SUMMARISATION",
                "{\"format\":\"TEXT\"}", "{\"format\":\"TEXT\"}",
                "https://platform.example.com/api/agent-callbacks/abc/result");
        DispatchMessage message = new DispatchMessage(
                taskId, agentVersionId, "https://agent.example.com/webhook", "corr-123", payload);

        assertThat(message.taskId()).isEqualTo(taskId);
        assertThat(message.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(message.webhookUrl()).isEqualTo("https://agent.example.com/webhook");
        assertThat(message.correlationId()).isEqualTo("corr-123");
        assertThat(message.payload()).isSameAs(payload);
    }
```

- [ ] **Run it — expect FAIL (does not compile: `DispatchMessage` missing).**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: compilation failure / `cannot find symbol: class DispatchMessage`.

- [ ] **Minimal implementation.** Create `backend/src/main/java/com/hireai/domain/biz/routing/info/DispatchMessage.java`:

```java
package com.hireai.domain.biz.routing.info;

import java.util.UUID;

/**
 * The dispatch envelope published to RabbitMQ (serialised as a JSON payload via Jackson
 * by the messaging adapter, Plan 2). Framework-free. Carries the routing identity
 * ({@code taskId}, {@code agentVersionId}), the target {@code webhookUrl}, a tracing
 * {@code correlationId}, and the {@link TaskDispatchPayload} the agent executes.
 */
public record DispatchMessage(UUID taskId, UUID agentVersionId, String webhookUrl,
                              String correlationId, TaskDispatchPayload payload) {
}
```

- [ ] **Run it — expect PASS.**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/routing/info/DispatchMessage.java backend/src/test/java/com/hireai/contracts/SpineContractsTest.java
git commit -m "feat: add DispatchMessage seam record for marketplace spine dispatch"
```

---

## Task 5 — `TaskDispatchPublisher` application port (CONTRACTS item 5)

A pure application-port interface; Plan 2 supplies the RabbitMQ-backed implementation. No test (verified by compilation when Plan 2 implements it). It depends on `DispatchMessage` (Task 4), so the package now compiles.

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/messaging/TaskDispatchPublisher.java`

### Steps

- [ ] **Implementation (interface only).** Create `backend/src/main/java/com/hireai/application/port/messaging/TaskDispatchPublisher.java`:

```java
package com.hireai.application.port.messaging;

import com.hireai.domain.biz.routing.info.DispatchMessage;

/**
 * Application port for publishing a task dispatch onto the messaging fabric. The
 * routing orchestration (Plan 4) depends on this interface only — it never imports the
 * RabbitMQ adapter. Plan 2 provides the implementation in
 * {@code infrastructure/messaging}, publishing to the {@code task.dispatch} exchange.
 */
public interface TaskDispatchPublisher {

    void publish(DispatchMessage message);
}
```

- [ ] **Run the build — expect PASS (compiles; existing tests unaffected).**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0` (the new interface compiles into the test classpath; no new test added).

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/application/port/messaging/TaskDispatchPublisher.java
git commit -m "feat: add TaskDispatchPublisher application port for marketplace spine"
```

---

## Task 6 — `DispatchTokenService` port + `DispatchTokenClaims` + `DispatchTokenInvalidException` (CONTRACTS item 6)

The signed-dispatch-token seam. Plan 2 supplies the HMAC implementation; Plan 3's callback app service depends on `verify`. Build claims and exception first (the service references both), then the service interface.

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/security/DispatchTokenClaims.java`
- Create: `backend/src/main/java/com/hireai/application/port/security/DispatchTokenInvalidException.java`
- Create: `backend/src/main/java/com/hireai/application/port/security/DispatchTokenService.java`
- Modify (test): `backend/src/test/java/com/hireai/contracts/SpineContractsTest.java`

### Steps

- [ ] **Write the failing test.** Add these imports and method to `SpineContractsTest.java`.

Add imports:

```java
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import java.time.Instant;
```

Add this `@Test` method to the class body:

```java
    @Test
    void dispatchTokenClaimsAndExceptionAreUsable() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-06-05T12:00:00Z");
        DispatchTokenClaims claims = new DispatchTokenClaims(taskId, agentVersionId, expiresAt);

        assertThat(claims.taskId()).isEqualTo(taskId);
        assertThat(claims.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(claims.expiresAt()).isEqualTo(expiresAt);

        DispatchTokenInvalidException ex = new DispatchTokenInvalidException("expired");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("expired");
    }
```

- [ ] **Run it — expect FAIL (does not compile: `DispatchTokenClaims` / `DispatchTokenInvalidException` missing).**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: compilation failure / `cannot find symbol: class DispatchTokenClaims`.

- [ ] **Minimal implementation — claims record.** Create `backend/src/main/java/com/hireai/application/port/security/DispatchTokenClaims.java`:

```java
package com.hireai.application.port.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims carried by a dispatch token: the task and agent version it authorises
 * and its expiry. Returned by {@link DispatchTokenService#verify(String)} once signature
 * and expiry pass. The callback app service (Plan 3) checks these against the path
 * {@code taskId} before recording a result (Hard Invariant #6).
 */
public record DispatchTokenClaims(UUID taskId, UUID agentVersionId, Instant expiresAt) {
}
```

- [ ] **Minimal implementation — exception.** Create `backend/src/main/java/com/hireai/application/port/security/DispatchTokenInvalidException.java`:

```java
package com.hireai.application.port.security;

/**
 * Thrown by {@link DispatchTokenService#verify(String)} when a dispatch token has a bad
 * signature, is expired, or does not match the expected task/agent version. The callback
 * controller (Plan 3) maps this to HTTP 401 (Hard Invariant #6).
 */
public class DispatchTokenInvalidException extends RuntimeException {

    public DispatchTokenInvalidException(String message) {
        super(message);
    }
}
```

- [ ] **Minimal implementation — service port.** Create `backend/src/main/java/com/hireai/application/port/security/DispatchTokenService.java`:

```java
package com.hireai.application.port.security;

import java.time.Duration;
import java.util.UUID;

/**
 * Application port for issuing and verifying short-lived signed dispatch tokens
 * (Hard Invariant #6). The dispatch consumer (Plan 2) issues a token bound to a task and
 * agent version with a bounded TTL; the agent returns it on the result callback, where the
 * callback app service (Plan 3) verifies it. Plan 2 provides the HMAC-backed implementation
 * in {@code infrastructure/security}.
 */
public interface DispatchTokenService {

    String issue(UUID taskId, UUID agentVersionId, Duration ttl);

    DispatchTokenClaims verify(String token);
}
```

- [ ] **Run it — expect PASS.**

```
mvn -f backend/pom.xml -B test -Dtest=SpineContractsTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/application/port/security/DispatchTokenClaims.java backend/src/main/java/com/hireai/application/port/security/DispatchTokenInvalidException.java backend/src/main/java/com/hireai/application/port/security/DispatchTokenService.java backend/src/test/java/com/hireai/contracts/SpineContractsTest.java
git commit -m "feat: add DispatchTokenService port, claims, and invalid-token exception for marketplace spine"
```

---

## Task 7 — `TaskExecutionPort` application port (CONTRACTS item 7)

The execution-status seam so Plan 2 can flip task status without importing Plan 3's concrete classes. Pure interface; Plan 3 implements it. No test (verified by compilation when Plan 3 implements it).

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/task/TaskExecutionPort.java`

### Steps

- [ ] **Implementation (interface only).** Create `backend/src/main/java/com/hireai/application/port/task/TaskExecutionPort.java`:

```java
package com.hireai.application.port.task;

import java.util.UUID;

/**
 * Application port exposing the execution-side task status transitions the dispatch
 * pipeline (Plan 2) drives, without coupling it to the Task aggregate's concrete app
 * services. Plan 3 implements this over {@code TaskWriteAppService}:
 * {@code markExecuting} on successful dispatch (QUEUED→EXECUTING), {@code markTimedOut}
 * and {@code markFailed} on DLQ/exhausted retries.
 */
public interface TaskExecutionPort {

    void markExecuting(UUID taskId);

    void markTimedOut(UUID taskId);

    void markFailed(UUID taskId);
}
```

- [ ] **Run the full unit-test suite — expect PASS (whole module still compiles and all unit tests green).**

```
mvn -f backend/pom.xml -B test
```

Expected: build success; existing suites (`MoneyTest`, `WalletModelTest`, `OutputSpecTest`, `TaskModelTest`) plus `SpineContractsTest` (5 tests) all pass.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/application/port/task/TaskExecutionPort.java
git commit -m "feat: add TaskExecutionPort application port for marketplace spine execution status"
```

---

## Task 8 — Final verification of the contracts base

Confirm the base branch builds cleanly end-to-end so Plans 1–4 can branch from a green commit. No code change.

**Files:** none.

### Steps

- [ ] **Run the full build (compile + unit tests).**

```
mvn -f backend/pom.xml -B test
```

Expected: `BUILD SUCCESS`. `SpineContractsTest` reports `Tests run: 5, Failures: 0, Errors: 0`; no other suite regresses. (Integration tests `*IntegrationTest` auto-skip without Docker — that is expected and does not fail the build.)

- [ ] **Confirm the working tree is clean and all seam types are committed.**

```
git status --short
git log --oneline -7
```

Expected: `git status --short` shows no staged/unstaged changes under `backend/`; the last seven commits are the seven `feat:` commits from Tasks 1–7. The base branch `feat/marketplace-spine` now carries every CONTRACTS seam type; Plans 1–4 each create their worktree branch FROM this commit.

---

## Done criteria

- [ ] All nine seam types exist at their CONTRACTS package paths:
  `AgentCandidate`, `TaskRoutingView`, `TaskDispatchPayload`, `DispatchMessage`,
  `TaskDispatchPublisher`, `DispatchTokenService`, `DispatchTokenClaims`,
  `DispatchTokenInvalidException`, `TaskExecutionPort`.
- [ ] `SpineContractsTest` constructs each record and asserts every accessor (5 tests, all green).
- [ ] `mvn -f backend/pom.xml -B test` is `BUILD SUCCESS` on `feat/marketplace-spine`.
- [ ] Seven `feat:` commits landed on `feat/marketplace-spine`; working tree clean.
