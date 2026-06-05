# Routing Synthesis (Module 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the marketplace spine's convergence point — match a submitted task to an ACTIVE Agent, atomically assign-and-queue it, then publish a dispatch message to RabbitMQ only after that transaction commits, falling back to AWAITING_CAPACITY when no Agent fits.

**Architecture:** A framework-free `RoutingMatchDomainService` (interface + `impl/`, registered in `DomainServiceConfig`) picks the best candidate by category, `price <= budget`, and highest `reputationScore`. A Spring-managed `RoutingAppService` orchestrates `TaskReadAppService.getRoutingView` → `AgentRepository.findActiveCandidates` → `selectAgentVersion` → on match `TaskWriteAppService.assignAndQueue` (its own committed transaction) then `TaskDispatchPublisher.publish`, or on no-match `TaskWriteAppService.markAwaitingCapacity`. A `RoutingEventListener` triggers `route` from `TaskSubmittedDomainEvent` via `@TransactionalEventListener(phase = AFTER_COMMIT)`, so routing only fires after the escrow freeze (Hard Invariant #1) has committed.

**Tech Stack:** Java 21, Spring Boot 3.x, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + Testcontainers (Postgres + RabbitMQ), Spring AMQP.

---

## Preconditions (this plan runs LAST, after Plans 1–3 are merged)

This is the **Synthesis** track. It consumes the real, merged classes produced by Plans 0–3 — there are no isolation ports here. Before starting, confirm these types exist on the base branch and compile:

- `com.hireai.domain.biz.agent.info.AgentCandidate` (Plan 0 record).
- `com.hireai.domain.biz.task.info.TaskRoutingView` (Plan 0 record).
- `com.hireai.domain.biz.routing.info.DispatchMessage` and `com.hireai.domain.biz.routing.info.TaskDispatchPayload` (Plan 0 records).
- `com.hireai.application.port.messaging.TaskDispatchPublisher` (Plan 0 port; Plan 2 supplies the RabbitMQ impl + `task.dispatch` queue/consumer/`AgentDispatchClient`).
- `com.hireai.domain.biz.agent.repository.AgentRepository#findActiveCandidates(String category, java.math.BigDecimal maxPrice)` returning `java.util.List<AgentCandidate>` (Plan 1 impl).
- `com.hireai.application.biz.task.TaskWriteAppService#assignAndQueue(UUID taskId, UUID agentVersionId)` and `#markAwaitingCapacity(UUID taskId)` (Plan 3 impl); `TaskModel.assignAndQueue(...)` → `QUEUED`, `markAwaitingCapacity()` → `AWAITING_CAPACITY` transitions (Plan 3).
- `com.hireai.application.biz.task.TaskReadAppService#getRoutingView(UUID taskId)` returning `TaskRoutingView` (Plan 3 impl).
- `com.hireai.application.biz.agentcallback.AgentCallbackAppService#recordResult(...)`, the `POST /api/agent-callbacks/{taskId}/result` controller, and `task_results` (Plan 3) — used by the end-to-end integration test only.
- `com.hireai.application.port.security.DispatchTokenService` + Plan 2 HMAC impl — used at runtime by Plan 2's consumer; this plan does not call it directly.

Run `mvn -f backend/pom.xml -B -q compile` first; if any of the above is missing, STOP — the merge of Plans 1–3 is incomplete and this plan cannot proceed.

**Verify command preamble (used in every task below):**
- Unit + all tests: `mvn -f backend/pom.xml -B test`
- A single test class: `mvn -f backend/pom.xml -B test -Dtest=ClassName`

`*IntegrationTest` classes use Testcontainers and **auto-skip** (via `@EnabledIf("dockerAvailable")`) when no Docker daemon is reachable — they never fail the build on a Docker-less machine.

---

## Why publish-after-commit (read before Task 4 and Task 5)

The single most important ordering rule in this plan:

> `RoutingAppService.route` calls `TaskWriteAppService.assignAndQueue(taskId, agentVersionId)` **first**, lets that transaction **commit**, and only **then** calls `TaskDispatchPublisher.publish(...)`.

Reasons:

1. **No race on uncommitted state.** Plan 2's `TaskDispatchConsumer` (`@RabbitListener task.dispatch`) reacts to the published message by issuing a token, dispatching the webhook, and flipping the task `QUEUED → EXECUTING`. RabbitMQ delivery is near-instant and runs on a *different* connection/transaction than the publisher. If we published while `assignAndQueue` were still uncommitted (or rolled back), the consumer could read a task that is still `SUBMITTED` (or never persisted), and its `markExecuting` would hit an illegal `SUBMITTED → EXECUTING` transition. Committing first guarantees the consumer always observes a durable `QUEUED` row.
2. **`assignAndQueue` is itself `@Transactional`.** Because `RoutingAppService.route` is invoked from a `@TransactionalEventListener(phase = AFTER_COMMIT)` callback (see Task 5), there is **no surrounding transaction** at that point — the task-submit transaction has already committed and closed. So the nested `TaskWriteAppService.assignAndQueue` call opens and commits its **own** transaction. By the time control returns to `route`, the `QUEUED` write is durable. `publish(...)` then runs outside any transaction, which is correct: we never want a RabbitMQ publish enrolled in a DB transaction (it cannot be rolled back).
3. **`route` must NOT be `@Transactional`.** If `RoutingAppServiceImpl.route` were annotated `@Transactional`, it would wrap `assignAndQueue` and `publish` in one transaction; `publish` would then fire before commit, reintroducing the race. Keep `route` non-transactional and let the two collaborators own their own boundaries (`assignAndQueue` is transactional; `publish` is not). This is asserted by an integration test.

The no-match branch (`markAwaitingCapacity`) publishes nothing, so ordering is moot there.

---

## Task 1 — `RoutingMatchDomainService` interface (framework-free)

Defines the matching contract in the domain layer. No Spring imports.

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/routing/service/RoutingMatchDomainService.java`

**Step 1 — write the interface (no test yet; it is consumed by Task 2's test):**

```java
package com.hireai.domain.biz.routing.service;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.TaskRoutingView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service for the routing MATCH decision. Given a task's routing view and the
 * ACTIVE agent candidates, selects the best-fitting agent version. Framework-free; the
 * bean is registered in DomainServiceConfig. Selection is pure and deterministic so it
 * can be unit-tested without Spring or a database.
 *
 * Selection rules:
 *   - candidate must advertise the task's category (in its capabilityCategories),
 *   - candidate price must be <= the task budget,
 *   - among the survivors, pick the highest reputationScore (tie-break),
 *   - returns the chosen agentVersionId, or empty when no candidate fits.
 */
public interface RoutingMatchDomainService {

    Optional<UUID> selectAgentVersion(TaskRoutingView criteria, List<AgentCandidate> candidates);
}
```

**Step 2 — verify it compiles:**

```
mvn -f backend/pom.xml -B -q compile
```

Expected: BUILD SUCCESS (the referenced `AgentCandidate` and `TaskRoutingView` come from the merged Plan 0).

**Step 3 — commit:**

```
git add backend/src/main/java/com/hireai/domain/biz/routing/service/RoutingMatchDomainService.java
git commit -m "feat: add RoutingMatchDomainService interface for agent matching"
```

---

## Task 2 — `RoutingMatchDomainServiceImpl` (TDD: selection logic)

The core matching algorithm. Pure unit-testable logic, no framework.

**Files:**
- Test (create): `backend/src/test/java/com/hireai/domain/biz/routing/service/RoutingMatchDomainServiceTest.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/routing/service/impl/RoutingMatchDomainServiceImpl.java`

**Step 1 — write the failing test:**

```java
package com.hireai.domain.biz.routing.service;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.routing.service.impl.RoutingMatchDomainServiceImpl;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingMatchDomainServiceTest {

    private final RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl();

    private TaskRoutingView task(String category, String budget) {
        return new TaskRoutingView(UUID.randomUUID(), category, new BigDecimal(budget), "SUBMITTED");
    }

    private AgentCandidate candidate(UUID versionId, List<String> categories, String price, String reputation) {
        return new AgentCandidate(
                UUID.randomUUID(), versionId, categories,
                new BigDecimal(price), "https://agent.example/hook", 60, new BigDecimal(reputation));
    }

    @Test
    void matchesCandidateAdvertisingTheCategory() {
        UUID wanted = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("translation"), "10.00", "50.00"),
                candidate(wanted, List.of("summarisation", "translation"), "10.00", "50.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).contains(wanted);
    }

    @Test
    void filtersOutCandidatesPricedAboveBudget() {
        UUID affordable = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("summarisation"), "40.00", "90.00"),
                candidate(affordable, List.of("summarisation"), "25.00", "50.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).contains(affordable);
    }

    @Test
    void breaksTiesByHighestReputation() {
        UUID best = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("summarisation"), "10.00", "60.00"),
                candidate(best, List.of("summarisation"), "10.00", "85.00"),
                candidate(UUID.randomUUID(), List.of("summarisation"), "10.00", "72.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).contains(best);
    }

    @Test
    void returnsEmptyWhenNoCandidateMatchesCategory() {
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("translation"), "10.00", "90.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).isEmpty();
    }

    @Test
    void returnsEmptyWhenCandidateListIsEmpty() {
        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), List.of());

        assertThat(chosen).isEmpty();
    }
}
```

**Step 2 — run it (expected FAIL — `RoutingMatchDomainServiceImpl` does not exist):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingMatchDomainServiceTest
```

Expected: compilation failure / test failure (`cannot find symbol RoutingMatchDomainServiceImpl`).

**Step 3 — minimal implementation:**

```java
package com.hireai.domain.biz.routing.service.impl;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless, deterministic matcher. Keeps candidates that advertise the task category
 * and whose price does not exceed the budget, then picks the highest reputationScore
 * (tie-break). Returns the chosen agentVersionId, or empty when nothing fits. No
 * framework imports — wired as a bean in DomainServiceConfig.
 */
public class RoutingMatchDomainServiceImpl implements RoutingMatchDomainService {

    @Override
    public Optional<UUID> selectAgentVersion(TaskRoutingView criteria, List<AgentCandidate> candidates) {
        if (criteria == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(c -> coversCategory(c, criteria.category()))
                .filter(c -> withinBudget(c, criteria))
                .max(Comparator.comparing(AgentCandidate::reputationScore))
                .map(AgentCandidate::agentVersionId);
    }

    private boolean coversCategory(AgentCandidate candidate, String category) {
        return category != null
                && candidate.capabilityCategories() != null
                && candidate.capabilityCategories().contains(category);
    }

    private boolean withinBudget(AgentCandidate candidate, TaskRoutingView criteria) {
        return criteria.budget() != null
                && candidate.price() != null
                && candidate.price().compareTo(criteria.budget()) <= 0;
    }
}
```

**Step 4 — run the test (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingMatchDomainServiceTest
```

Expected: BUILD SUCCESS, 5 tests pass.

**Step 5 — commit:**

```
git add backend/src/main/java/com/hireai/domain/biz/routing/service/impl/RoutingMatchDomainServiceImpl.java backend/src/test/java/com/hireai/domain/biz/routing/service/RoutingMatchDomainServiceTest.java
git commit -m "feat: implement RoutingMatchDomainService selection (category, budget, reputation tie-break)"
```

---

## Task 3 — register `RoutingMatchDomainService` in `DomainServiceConfig`

Expose the framework-free matcher as a Spring bean by its domain interface.

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java`

**Step 1 — add the import and bean method.** Add this import alongside the existing ones:

```java
import com.hireai.domain.biz.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.routing.service.impl.RoutingMatchDomainServiceImpl;
```

Add this `@Bean` method inside the class body (after `taskSubmitDomainService()`):

```java
    @Bean
    public RoutingMatchDomainService routingMatchDomainService() {
        return new RoutingMatchDomainServiceImpl();
    }
```

**Step 2 — verify the context still wires (compile + existing tests):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingMatchDomainServiceTest
```

Expected: BUILD SUCCESS (compile passes with the new bean; the unit test still passes — it instantiates the impl directly, but compilation of the config is what we verify here).

**Step 3 — commit:**

```
git add backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java
git commit -m "chore: register RoutingMatchDomainService bean in DomainServiceConfig"
```

---

## Task 4 — `RoutingAppService` interface + impl (orchestration, publish-after-commit)

The convergence point: read → match → assign/queue-or-await → publish. `route` is intentionally **not** `@Transactional` (see "Why publish-after-commit").

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/routing/RoutingAppService.java`
- Test (create): `backend/src/test/java/com/hireai/application/biz/routing/RoutingAppServiceImplTest.java`
- Create: `backend/src/main/java/com/hireai/application/biz/routing/impl/RoutingAppServiceImpl.java`

**Step 1 — write the interface:**

```java
package com.hireai.application.biz.routing;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates the routing decision for a submitted task. Reads the task routing view,
 * asks the AgentRepository for ACTIVE candidates, runs the match, and either
 * assign-and-queues the task (then publishes a dispatch message AFTER that write has
 * committed) or marks it AWAITING_CAPACITY. Invoked from RoutingEventListener after the
 * submit transaction commits (Hard Invariant #1: routing never precedes a committed escrow
 * freeze).
 */
@Validated
public interface RoutingAppService {

    void route(@NonNull UUID taskId);
}
```

**Step 2 — write the failing unit test (mock collaborators; assert ordering + branch behaviour).** This uses Mockito (bundled with `spring-boot-starter-test`), including `InOrder` to assert `assignAndQueue` happens before `publish`:

```java
package com.hireai.application.biz.routing;

import com.hireai.application.biz.routing.impl.RoutingAppServiceImpl;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingAppServiceImplTest {

    private final TaskReadAppService taskReadAppService = mock(TaskReadAppService.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RoutingMatchDomainService routingMatchDomainService = mock(RoutingMatchDomainService.class);
    private final TaskDispatchPublisher taskDispatchPublisher = mock(TaskDispatchPublisher.class);

    private final RoutingAppServiceImpl service = new RoutingAppServiceImpl(
            taskReadAppService, taskWriteAppService, agentRepository,
            routingMatchDomainService, taskDispatchPublisher);

    private TaskRoutingView view(UUID taskId) {
        return new TaskRoutingView(taskId, "summarisation", new BigDecimal("30.00"), "SUBMITTED");
    }

    private AgentCandidate candidate(UUID versionId) {
        return new AgentCandidate(
                UUID.randomUUID(), versionId, List.of("summarisation"),
                new BigDecimal("10.00"), "https://agent.example/hook", 60, new BigDecimal("80.00"));
    }

    @Test
    void onMatchAssignsAndQueuesThenPublishesInThatOrder() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates("summarisation", new BigDecimal("30.00")))
                .thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        InOrder inOrder = inOrder(taskWriteAppService, taskDispatchPublisher);
        inOrder.verify(taskWriteAppService).assignAndQueue(taskId, versionId);
        inOrder.verify(taskDispatchPublisher).publish(any(DispatchMessage.class));
        verify(taskWriteAppService, never()).markAwaitingCapacity(any());
    }

    @Test
    void onMatchPublishesDispatchMessageWithTaskAndVersionAndWebhook() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(eq("summarisation"), any())).thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        DispatchMessage message = captor.getValue();
        assertThat(message.taskId()).isEqualTo(taskId);
        assertThat(message.agentVersionId()).isEqualTo(versionId);
        assertThat(message.webhookUrl()).isEqualTo("https://agent.example/hook");
        assertThat(message.correlationId()).isNotBlank();
        assertThat(message.payload().category()).isEqualTo("summarisation");
    }

    @Test
    void onNoMatchMarksAwaitingCapacityAndPublishesNothing() {
        UUID taskId = UUID.randomUUID();
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(any(), any())).thenReturn(List.of());
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.empty());

        service.route(taskId);

        verify(taskWriteAppService).markAwaitingCapacity(taskId);
        verify(taskWriteAppService, never()).assignAndQueue(any(), any());
        verify(taskDispatchPublisher, never()).publish(any());
    }
}
```

**Step 3 — run it (expected FAIL — `RoutingAppServiceImpl` does not exist):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingAppServiceImplTest
```

Expected: compilation failure (`cannot find symbol RoutingAppServiceImpl`).

**Step 4 — minimal implementation.** Note: the matched candidate carries the `webhookUrl`, `agentId`, `maxExecutionSeconds`, etc.; the chosen `agentVersionId` is returned by the matcher, and we look the candidate back up to build the `DispatchMessage` and the per-version callback URL. The `callbackUrl` points at Plan 3's callback endpoint.

```java
package com.hireai.application.biz.routing.impl;

import com.hireai.application.biz.routing.RoutingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
import com.hireai.domain.biz.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates routing. Deliberately NOT @Transactional: assignAndQueue owns its own
 * transaction and must COMMIT before publish runs, so the RabbitMQ consumer never races
 * an uncommitted QUEUED state (see plan: "Why publish-after-commit"). publish runs outside
 * any DB transaction because a message send cannot be rolled back.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoutingAppServiceImpl implements RoutingAppService {

    private final TaskReadAppService taskReadAppService;
    private final TaskWriteAppService taskWriteAppService;
    private final AgentRepository agentRepository;
    private final RoutingMatchDomainService routingMatchDomainService;
    private final TaskDispatchPublisher taskDispatchPublisher;

    /**
     * Public base URL the Agent uses to call back. NON-final so @RequiredArgsConstructor stays
     * 5-arg (the unit test constructs the impl directly); the inline default applies in unit
     * tests (plain `new`, no injection), while Spring overrides it from application.yml at
     * runtime. The property `hireai.platform.public-base-url` is owned by Plan 2's application.yml.
     */
    @org.springframework.beans.factory.annotation.Value("${hireai.platform.public-base-url:http://localhost:8080}")
    private String publicBaseUrl = "http://localhost:8080";

    @Override
    public void route(UUID taskId) {
        TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
        List<AgentCandidate> candidates =
                agentRepository.findActiveCandidates(view.category(), view.budget());
        Optional<UUID> chosen = routingMatchDomainService.selectAgentVersion(view, candidates);

        if (chosen.isEmpty()) {
            log.info("No ACTIVE agent matched task {} (category={}, budget={}); marking AWAITING_CAPACITY",
                    taskId, view.category(), view.budget());
            taskWriteAppService.markAwaitingCapacity(taskId);
            return;
        }

        UUID agentVersionId = chosen.get();
        AgentCandidate winner = candidates.stream()
                .filter(c -> c.agentVersionId().equals(agentVersionId))
                .findFirst()
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Matcher returned an agentVersionId absent from candidates: " + agentVersionId));

        // Commit the QUEUED transition FIRST so the consumer always sees a durable QUEUED row.
        taskWriteAppService.assignAndQueue(taskId, agentVersionId);

        DispatchMessage message = buildDispatchMessage(taskId, agentVersionId, view, winner);
        taskDispatchPublisher.publish(message);
        log.info("Task {} assigned to agentVersion {} and dispatch published (correlationId={})",
                taskId, agentVersionId, message.correlationId());
    }

    private DispatchMessage buildDispatchMessage(UUID taskId, UUID agentVersionId,
                                                 TaskRoutingView view, AgentCandidate winner) {
        String correlationId = UUID.randomUUID().toString();
        String callbackUrl = publicBaseUrl + "/api/agent-callbacks/" + taskId + "/result";
        TaskDispatchPayload payload = new TaskDispatchPayload(
                view.category(),               // title placeholder uses category in this slice
                view.category(),               // description placeholder
                view.category(),
                null,                          // expectedDeliverableJson: not enriched in this slice
                null,                          // outputSpecJson: not enriched in this slice
                callbackUrl);
        return new DispatchMessage(taskId, agentVersionId, winner.webhookUrl(), correlationId, payload);
    }
}
```

> NOTE FOR THE IMPLEMENTER: `TaskDispatchPayload`'s fields are `(String title, String description, String category, String expectedDeliverableJson, String outputSpecJson, String callbackUrl)`. This slice does not carry the task's full title/description/output_spec into the payload (the routing view exposes only `taskId, category, budget, status`). If, at merge time, `TaskReadAppService` exposes a richer view, replace the `view.category()` placeholders with the real `title`/`description`/`expectedDeliverable`/`outputSpec` values — the `DispatchMessage`/`TaskDispatchPayload` shape does not change. The integration test (Task 6) asserts only on `taskId`, status outcome, and persisted `task_results`, so the placeholder values do not affect the end-to-end assertion.

**Step 5 — run the test (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingAppServiceImplTest
```

Expected: BUILD SUCCESS, 3 tests pass (including the `InOrder` ordering assertion).

**Step 6 — commit:**

```
git add backend/src/main/java/com/hireai/application/biz/routing/RoutingAppService.java backend/src/main/java/com/hireai/application/biz/routing/impl/RoutingAppServiceImpl.java backend/src/test/java/com/hireai/application/biz/routing/RoutingAppServiceImplTest.java
git commit -m "feat: add RoutingAppService orchestrating match, assign-and-queue, publish-after-commit"
```

---

## Task 5 — `RoutingEventListener` (`@TransactionalEventListener` AFTER_COMMIT)

Triggers routing from `TaskSubmittedDomainEvent`, but only after the submit/escrow-freeze transaction commits.

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/routing/RoutingEventListener.java`
- Test (create): `backend/src/test/java/com/hireai/application/biz/routing/RoutingEventListenerTest.java`

**Step 1 — write the failing unit test (verify the listener delegates the event's `taskId` to `route`):**

```java
package com.hireai.application.biz.routing;

import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoutingEventListenerTest {

    private final RoutingAppService routingAppService = mock(RoutingAppService.class);
    private final RoutingEventListener listener = new RoutingEventListener(routingAppService);

    @Test
    void delegatesTaskIdToRoutingAppService() {
        UUID taskId = UUID.randomUUID();
        TaskSubmittedDomainEvent event = new TaskSubmittedDomainEvent(
                taskId, UUID.randomUUID(), Money.of("30.00"), Instant.now());

        listener.onTaskSubmitted(event);

        verify(routingAppService).route(taskId);
    }
}
```

**Step 2 — run it (expected FAIL — `RoutingEventListener` does not exist):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingEventListenerTest
```

Expected: compilation failure (`cannot find symbol RoutingEventListener`).

**Step 3 — minimal implementation:**

```java
package com.hireai.application.biz.routing;

import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routing trigger seam. Listens for TaskSubmittedDomainEvent and starts routing ONLY
 * AFTER the submit transaction commits (Hard Invariant #1: routing never precedes a
 * committed escrow freeze). Because this fires AFTER_COMMIT, there is no surrounding
 * transaction, so RoutingAppService.route's call to assignAndQueue opens and commits its
 * own transaction before the dispatch message is published.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RoutingEventListener {

    private final RoutingAppService routingAppService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskSubmitted(TaskSubmittedDomainEvent event) {
        log.info("Task {} submit committed; starting routing", event.taskId());
        routingAppService.route(event.taskId());
    }
}
```

**Step 4 — run the test (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingEventListenerTest
```

Expected: BUILD SUCCESS, 1 test passes.

**Step 5 — commit:**

```
git add backend/src/main/java/com/hireai/application/biz/routing/RoutingEventListener.java backend/src/test/java/com/hireai/application/biz/routing/RoutingEventListenerTest.java
git commit -m "feat: trigger routing on TaskSubmittedDomainEvent after commit"
```

---

## Task 6 — End-to-end `RoutingIntegrationTest` (Testcontainers Postgres + RabbitMQ)

Boots Spring against a real Postgres (Flyway V1–V4) and a real RabbitMQ, seeds an ACTIVE agent, submits a task, and lets the live wiring (routing listener → publisher → Plan 2 consumer → `AgentDispatchClient` → stub agent → Plan 3 callback) drive the task to `RESULT_RECEIVED` with a persisted `task_results` row. Also covers the no-match → `AWAITING_CAPACITY` case. Auto-skips without Docker.

**Files:**
- Test (create): `backend/src/test/java/com/hireai/routing/RoutingIntegrationTest.java`

**Step 1 — verify the RabbitMQ Testcontainers module is already present (do NOT add it).** Plan 2 (Task 1) already added the `org.testcontainers:rabbitmq` test dependency to `backend/pom.xml`. This plan runs after Plans 1–3 are merged, so that dependency is already present — adding it again would create a duplicate `<dependency>` (Maven warning, and a hard failure under any duplicate-dependency enforcer rule). Confirm it is there and do **not** touch `pom.xml`:

```
mvn -f backend/pom.xml -B -q dependency:tree | findstr rabbitmq
```

Expected: a line showing `org.testcontainers:rabbitmq:...:test`. If it is missing, the merge of Plan 2 is incomplete — STOP and reconcile the merge rather than adding the dependency here.

**Step 2 — write the failing integration test.** This test stands up a minimal stub agent **inside the test JVM** as a `@RestController` bound to a random port (no external `demo-agent/` process needed for the hermetic test): it receives Plan 2's dispatch POST and immediately POSTs a spec-conforming result back to the `callbackUrl` carrying the same Bearer token. Because the stub URL is `http://127.0.0.1:<port>`, the test runs under the `dev` profile so `AgentDispatchClient`'s HTTPS enforcement allows localhost (per spec invariant #6 local-demo exception); the signed-token check stays enforced.

The test seeds an agent and an `agent_version` directly via JDBC (matching how Plan 1's `findActiveCandidates` reads `agents`/`agent_versions`), with the version's `webhook_url` pointing at the in-JVM stub.

```java
package com.hireai.routing;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end marketplace spine: submit a task, let routing match it to a seeded ACTIVE
 * agent, dispatch over RabbitMQ to an in-JVM stub agent, and observe the token-authenticated
 * callback drive the task to RESULT_RECEIVED with a persisted task_results row. Also asserts
 * the no-match path lands on AWAITING_CAPACITY. Boots a real Postgres (Flyway V1-V4) and a
 * real RabbitMQ; auto-skips without Docker. Runs under the 'dev' profile so the localhost
 * stub webhook is allowed (spec invariant #6 local-demo exception); the signed dispatch
 * token is still enforced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
@EnabledIf("dockerAvailable")
class RoutingIntegrationTest {

    /** Skip (do not fail) the whole class when no Docker daemon is reachable. */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        // Allow the localhost stub webhook over http (spec invariant #6 local-demo exception);
        // the signed dispatch token is still enforced. Set here (not via the dev profile) so the
        // test is self-contained and does not depend on a dev block in application.yml.
        registry.add("hireai.dispatch.allow-insecure-localhost", () -> "true");
    }

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubAgentController stubAgent;

    /**
     * Minimal in-JVM stub Agent. Receives Plan 2's dispatch POST and immediately calls the
     * callback endpoint with the same Bearer token and a spec-conforming COMPLETED result.
     * Lives in the test JVM so the integration test is hermetic (no external demo-agent process).
     */
    @TestConfiguration
    static class StubAgentConfig {
        @Bean
        StubAgentController stubAgentController(
                org.springframework.web.client.RestClient.Builder builder,
                @org.springframework.beans.factory.annotation.Value("${local.server.port}") int serverPort) {
            return new StubAgentController(builder.build(), serverPort);
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class StubAgentController {
        private final org.springframework.web.client.RestClient http;
        private final int serverPort;

        StubAgentController(org.springframework.web.client.RestClient http, int serverPort) {
            this.http = http;
            this.serverPort = serverPort;
        }

        @org.springframework.web.bind.annotation.PostMapping("/stub-agent/hook")
        void receive(
                @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization,
                @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
            String taskId = String.valueOf(body.get("taskId"));
            String callbackPath = "http://127.0.0.1:" + serverPort + "/api/agent-callbacks/" + taskId + "/result";
            http.post()
                    .uri(callbackPath)
                    .header("Authorization", authorization)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of(
                            "agentStatus", "COMPLETED",
                            "resultPayloadJson", "{\"summary\":\"done\"}",
                            "resultUrl", "https://results.example/r/1",
                            "message", "ok"))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", id, id + "@test.local");
        return id;
    }

    /** Seed one ACTIVE agent with a single version advertising {category} at {price}, webhook -> stub. */
    private UUID seedActiveAgent(String category, String price, String webhookUrl) {
        UUID ownerId = newBuilder();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, 80.00)",
                agentId, ownerId, "Stub Agent", versionId);
        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, max_execution_seconds, price) " +
                        "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}",
                new String[]{category}, webhookUrl, new java.math.BigDecimal(price));
        return versionId;
    }

    private TaskSubmitInfo info(UUID clientId, String category, String budget) {
        return new TaskSubmitInfo(clientId, "Summarise report", "Summarise the attached quarterly report",
                Money.of(budget), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"),
                category);
    }

    @Test
    void submitRoutesDispatchesAndRecordsResult(@Autowired
            @org.springframework.beans.factory.annotation.Value("${local.server.port}") int serverPort) {
        UUID client = newClient();
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), client);
        String stubWebhook = "http://127.0.0.1:" + serverPort + "/stub-agent/hook";
        UUID versionId = seedActiveAgent("summarisation", "10.00", stubWebhook);

        UUID taskId = taskWriteAppService.submit(info(client, "summarisation", "30.00"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isEqualTo("RESULT_RECEIVED");
        });

        UUID assignedVersion = jdbc.queryForObject(
                "SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
        assertThat(assignedVersion).isEqualTo(versionId);

        Integer results = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ? AND agent_status = 'COMPLETED'",
                Integer.class, taskId);
        assertThat(results).isEqualTo(1);
    }

    @Test
    void noMatchingAgentLeavesTaskAwaitingCapacity() {
        UUID client = newClient();
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), client);
        // An ACTIVE agent exists but advertises a different category, so no candidate fits.
        seedActiveAgent("translation", "10.00", "https://unused.example/hook");

        UUID taskId = taskWriteAppService.submit(info(client, "summarisation", "30.00"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isEqualTo("AWAITING_CAPACITY");
        });

        Integer results = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ?", Integer.class, taskId);
        assertThat(results).isZero();
    }
}
```

> NOTES FOR THE IMPLEMENTER:
> - **`TaskSubmitInfo` gains a `category` field in Plan 3** (the spec's Track C extends `SubmitTaskRequest`/`TaskSubmitInfo`/`TaskModel.submit` with `category`). The test's `info(...)` helper passes `category` as the 6th argument. If, at merge time, Plan 3 used a different constructor arrangement, adapt the call site only — the assertions are unchanged.
> - **Wallet seeding columns** (`wallets(id, user_id, available_balance, escrow_balance)`) match V1; if V1's column names differ, use `walletWriteAppService.topUp(client, Money.of("100.00"), "seed")` instead (mirror `TaskSubmissionIntegrationTest`). Prefer the app-service top-up to avoid coupling the test to wallet column names.
> - **`agent_versions.capability_categories TEXT[]`** is bound via a Java `String[]` (the Postgres JDBC driver maps `String[]` to `text[]`). If Plan 1 used a different array binding, mirror Plan 1's seed helper.
> - **Awaitility** is needed for the async RabbitMQ round-trip. Add it in Step 3 if not already present.

**Step 3 — ensure Awaitility is available (test scope).** `awaitility` is a transitive dependency of `spring-boot-starter-test` in Spring Boot 3.x, so `org.awaitility.Awaitility` is already on the test classpath — no `pom.xml` change is required. If the import does not resolve at compile time, add this block in `<dependencies>` after the RabbitMQ test dependency:

```xml
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

**Step 4 — run the integration test (expected: PASS with Docker, SKIP without).**

```
mvn -f backend/pom.xml -B test -Dtest=RoutingIntegrationTest
```

Expected with Docker running: BUILD SUCCESS, 2 tests pass — `submitRoutesDispatchesAndRecordsResult` drives the task to `RESULT_RECEIVED` with one `COMPLETED` `task_results` row; `noMatchingAgentLeavesTaskAwaitingCapacity` lands on `AWAITING_CAPACITY` with zero results.
Expected without Docker: the class is skipped (`dockerAvailable()` returns false), build still green.

**Step 5 — commit:**

```
git add backend/src/test/java/com/hireai/routing/RoutingIntegrationTest.java
git commit -m "test: add end-to-end routing integration test (submit -> dispatch -> callback -> RESULT_RECEIVED)"
```

---

## Task 7 — full suite green + final commit

Run the complete suite to confirm Synthesis integrates cleanly with the merged Plans 1–3 and that no existing test regressed.

**Files:** none (verification only).

**Step 1 — run the whole backend test suite:**

```
mvn -f backend/pom.xml -B test
```

Expected: BUILD SUCCESS. Unit tests (`RoutingMatchDomainServiceTest`, `RoutingAppServiceImplTest`, `RoutingEventListenerTest`, plus all pre-existing Task/Wallet/Money tests) pass; `*IntegrationTest` classes run with Docker or skip without it. No regressions.

**Step 2 — if anything fails**, fix the failing layer (not the test, unless the test encodes a wrong contract), re-run the single failing class with `-Dtest=ClassName`, and only then re-run the full suite. Do not weaken an assertion to make it pass.

**Step 3 — final commit (only if Step 2 required a fix; otherwise the work is already committed):**

```
git add -A
git commit -m "chore: routing synthesis suite green against merged spine"
```

---

## Done criteria

- [ ] `RoutingMatchDomainService` (interface + impl) selects by category, `price <= budget`, reputation tie-break; returns the chosen `agentVersionId` or empty; registered in `DomainServiceConfig`.
- [ ] `RoutingAppService.route` reads the routing view, matches, and on match `assignAndQueue` **then** `publish` (committed-before-publish, asserted by `InOrder`); on no-match `markAwaitingCapacity` and publishes nothing.
- [ ] `RoutingAppServiceImpl.route` is NOT `@Transactional` (so `assignAndQueue` commits before `publish`).
- [ ] `RoutingEventListener` reacts to `TaskSubmittedDomainEvent` with `@TransactionalEventListener(phase = AFTER_COMMIT)` and delegates `taskId` to `route`.
- [ ] End-to-end `RoutingIntegrationTest` (Testcontainers Postgres + RabbitMQ, `@EnabledIf` Docker) proves submit → routing → dispatch → stub callback → `RESULT_RECEIVED` + persisted `task_results`, plus a no-match → `AWAITING_CAPACITY` case.
- [ ] `mvn -f backend/pom.xml -B test` is green; no pre-existing test regressed.
