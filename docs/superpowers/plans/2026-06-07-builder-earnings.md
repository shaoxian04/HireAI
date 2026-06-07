# Builder Earnings View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dedicated `/builder/earnings` page fed by `GET /api/builder/earnings` — lifetime earnings, pending escrow, per-agent breakdown, payout history — derived from the tasks table via `SettlementPolicy` (never by summing ledger rows).

**Architecture:** Read-side CQRS mirroring the existing builder-stats read: a query port (`application/port/query`) implemented by a `NamedParameterJdbcTemplate` DAO (`infrastructure/repository/catalogue`), folded through `SettlementPolicy` in a read app service (interface + `impl/`), exposed by a thin controller, rendered by a new Next.js page using the existing Mission Control kit.

**Tech Stack:** Spring Boot 3.3 / Java 21, NamedParameterJdbcTemplate, JUnit 5 + Mockito + Testcontainers; Next.js 16 + TypeScript + Tailwind, Vitest + RTL + MSW.

**Spec:** `docs/superpowers/specs/2026-06-07-builder-earnings-design.md` (approved). Key semantics:
- **lifetimeEarned** = Σ `SettlementPolicy.netOf(budget)` over tasks with `status=RESOLVED ∧ resolution=ACCEPTED` routed to an agent the caller owns.
- **pendingIfAccepted** = Σ net over the caller's agents' tasks in `QUEUED, EXECUTING, RESULT_RECEIVED, PENDING_REVIEW, AWAITING_CAPACITY` (the SQL join through `agent_version_id` already excludes unpinned tasks).
- **payouts** = the accepted tasks, newest `resolved_at` first, max 50, amount = net.
- Excluded everywhere: `RESOLVED+REJECTED`, `FAILED`, `TIMED_OUT`, `SPEC_VIOLATION`, `CANCELLED`.
- A CLIENT caller gets zeros + empty lists — not an error.

**Conventions you must follow** (from `docs/details/ddd-conventions.md` + existing code):
- App services: interface annotated `@Validated`, params `@NonNull` (jspecify); impl in `impl/` with `@Service @Slf4j @RequiredArgsConstructor @Transactional(readOnly = true)`.
- Controllers extend `BaseController`, return `WebResult<T>` via `ok(...)`, identity ONLY from `CurrentUserProvider` (invariant #5).
- Money is `domain/shared/model/Money` (immutable, 2dp HALF_UP); fold with `Money.add`, expose `BigDecimal` via `.value()`.
- Frontend: data via `api()` (`lib/api.ts`), types in `lib/types.ts`, UI kit in `components/ui`, pages guarded by `RoleGuard`.

---

## File map

| File | Responsibility |
|---|---|
| `backend/.../application/port/query/BuilderEarningsQueryPort.java` | Create — port: raw routed-task rows + owned agents |
| `backend/.../application/biz/wallet/BuilderEarningsReadAppService.java` | Create — interface + result records |
| `backend/.../application/biz/wallet/impl/BuilderEarningsReadAppServiceImpl.java` | Create — the SettlementPolicy fold |
| `backend/.../infrastructure/repository/catalogue/JdbcBuilderEarningsQueryDao.java` | Create — two SQL queries |
| `backend/.../controller/biz/wallet/dto/BuilderEarningsDTO.java` | Create — HTTP shape + `from()` |
| `backend/.../controller/biz/wallet/BuilderEarningsController.java` | Create — `GET /api/builder/earnings` |
| `backend/src/test/java/com/hireai/wallet/BuilderEarningsReadAppServiceImplTest.java` | Create — fold unit tests |
| `backend/src/test/java/com/hireai/wallet/BuilderEarningsIntegrationTest.java` | Create — Testcontainers IT |
| `backend/src/test/java/com/hireai/controller/biz/wallet/BuilderEarningsControllerTest.java` | Create — web slice |
| `frontend/lib/types.ts` | Modify — earnings types |
| `frontend/test/msw/handlers.ts` | Modify — earnings stub |
| `frontend/app/builder/earnings/page.tsx` | Create — the page |
| `frontend/test/earnings.test.tsx` | Create — page tests |
| `frontend/components/Nav.tsx` | Modify — builder nav links |
| `frontend/app/builder/page.tsx` | Modify — tile relabel + link |
| `frontend/test/builder.test.tsx` | Modify — tile label + nav assertions |
| `CLAUDE.md`, `docs/details/frontend.md` | Modify — docs |

Backend Java root: `backend/src/main/java/com/hireai/`.

---

### Task 1: Query port + read app service (the SettlementPolicy fold)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/query/BuilderEarningsQueryPort.java`
- Create: `backend/src/main/java/com/hireai/application/biz/wallet/BuilderEarningsReadAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/wallet/impl/BuilderEarningsReadAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/wallet/BuilderEarningsReadAppServiceImplTest.java`

Look at `application/port/query/BuilderStatsQueryPort.java` + `application/biz/wallet/WalletReadAppService.java` first — you are mirroring them exactly.

- [ ] **Step 1: Write the port interface** (no test — it's a pure interface)

```java
package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-side rows for the builder earnings view. Returns EVERY task routed to any agent
 * the owner owns (the JOIN through agent_version_id excludes unrouted tasks naturally);
 * status/resolution filtering and all money arithmetic happen in the app service so the
 * semantics live in testable Java, not SQL.
 */
public interface BuilderEarningsQueryPort {

    List<RoutedTaskRow> routedTasks(UUID ownerId);

    /** Every agent the owner owns — so agents with no routed tasks still get a zero row. */
    List<OwnedAgentRow> ownedAgents(UUID ownerId);

    record RoutedTaskRow(UUID taskId, String title, BigDecimal budget, String status,
                         String resolution, Instant resolvedAt, UUID agentId, String agentName) {
    }

    record OwnedAgentRow(UUID agentId, String agentName) {
    }
}
```

- [ ] **Step 2: Write the app service interface**

```java
package com.hireai.application.biz.wallet;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builder earnings READ use case. Derives all amounts from the tasks table via
 * SettlementPolicy — never by summing ledger rows, because PAYOUT entries exist on both
 * wallets of a settlement and are ambiguous in the legal self-settle case (a client
 * accepting their own agent's work). Equal to the ledger credit by construction: settlement
 * computed the credit as netOf(budget) from the same task row. Display amounts only —
 * amounts of record live in the ledger.
 */
@Validated
public interface BuilderEarningsReadAppService {

    Earnings earningsFor(@NonNull UUID userId);

    record Earnings(BigDecimal lifetimeEarned, BigDecimal pendingIfAccepted, int paidTaskCount,
                    List<AgentEarnings> perAgent, List<Payout> payouts) {
    }

    record AgentEarnings(UUID agentId, String agentName, BigDecimal earned,
                         BigDecimal pendingIfAccepted, int paidTaskCount) {
    }

    record Payout(UUID taskId, String taskTitle, String agentName,
                  BigDecimal amount, Instant settledAt) {
    }
}
```

- [ ] **Step 3: Write the failing unit tests**

`backend/src/test/java/com/hireai/wallet/BuilderEarningsReadAppServiceImplTest.java`:

```java
package com.hireai.wallet;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.AgentEarnings;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.wallet.impl.BuilderEarningsReadAppServiceImpl;
import com.hireai.application.port.query.BuilderEarningsQueryPort;
import com.hireai.application.port.query.BuilderEarningsQueryPort.OwnedAgentRow;
import com.hireai.application.port.query.BuilderEarningsQueryPort.RoutedTaskRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The SettlementPolicy fold: which task rows count, how they round, how they group.
 * Wire-level concerns (SQL, JSON) are covered by the integration and slice tests.
 */
@ExtendWith(MockitoExtension.class)
class BuilderEarningsReadAppServiceImplTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID AGENT_A = UUID.randomUUID();
    private static final UUID AGENT_B = UUID.randomUUID();

    @Mock BuilderEarningsQueryPort queryPort;
    @InjectMocks BuilderEarningsReadAppServiceImpl service;

    private RoutedTaskRow row(UUID agentId, String agentName, String budget,
                              String status, String resolution, Instant resolvedAt) {
        return new RoutedTaskRow(UUID.randomUUID(), "task " + budget, new BigDecimal(budget),
                status, resolution, resolvedAt, agentId, agentName);
    }

    private void stub(List<RoutedTaskRow> rows, List<OwnedAgentRow> agents) {
        when(queryPort.routedTasks(OWNER)).thenReturn(rows);
        when(queryPort.ownedAgents(OWNER)).thenReturn(agents);
    }

    @Test
    void acceptedTasksFoldToLifetimeEarnedNetOfCommission() {
        stub(List.of(
                row(AGENT_A, "A", "12.00", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T10:00:00Z")),
                row(AGENT_A, "A", "20.00", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T11:00:00Z")),
                row(AGENT_A, "A", "30.00", "RESOLVED", "REJECTED", Instant.parse("2026-06-07T12:00:00Z")),
                row(AGENT_A, "A", "40.00", "FAILED", null, null)),
                List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        // 12.00 -> net 10.20, 20.00 -> net 17.00; rejected + failed contribute nothing
        assertThat(e.lifetimeEarned()).isEqualByComparingTo("27.20");
        assertThat(e.paidTaskCount()).isEqualTo(2);
        assertThat(e.payouts()).hasSize(2);
    }

    @Test
    void pendingSumsOpenStatusesOnly() {
        stub(List.of(
                row(AGENT_A, "A", "10.00", "QUEUED", null, null),
                row(AGENT_A, "A", "12.00", "EXECUTING", null, null),
                row(AGENT_A, "A", "20.00", "RESULT_RECEIVED", null, null),
                row(AGENT_A, "A", "4.00", "AWAITING_CAPACITY", null, null),
                row(AGENT_A, "A", "99.00", "RESOLVED", "ACCEPTED", Instant.now()),
                row(AGENT_A, "A", "99.00", "TIMED_OUT", null, null)),
                List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        // nets: 8.50 + 10.20 + 17.00 + 3.40 = 39.10 (accepted/timed-out are NOT pending)
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("39.10");
    }

    @Test
    void perAgentBreakdownIncludesZeroRowAgents() {
        stub(List.of(
                row(AGENT_A, "A", "12.00", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T10:00:00Z")),
                row(AGENT_A, "A", "10.00", "RESULT_RECEIVED", null, null)),
                List.of(new OwnedAgentRow(AGENT_A, "A"), new OwnedAgentRow(AGENT_B, "B")));

        Earnings e = service.earningsFor(OWNER);

        assertThat(e.perAgent()).containsExactly(
                new AgentEarnings(AGENT_A, "A", new BigDecimal("10.20"), new BigDecimal("8.50"), 1),
                new AgentEarnings(AGENT_B, "B", new BigDecimal("0.00"), new BigDecimal("0.00"), 0));
    }

    @Test
    void payoutsAreNewestFirstAndCappedAt50() {
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        List<RoutedTaskRow> rows = new ArrayList<>(IntStream.range(0, 60)
                .mapToObj(i -> row(AGENT_A, "A", "10.00", "RESOLVED", "ACCEPTED",
                        base.plusSeconds(i)))
                .toList());
        stub(rows, List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        assertThat(e.payouts()).hasSize(50);
        assertThat(e.payouts().get(0).settledAt()).isEqualTo(base.plusSeconds(59)); // newest first
        assertThat(e.payouts().get(49).settledAt()).isEqualTo(base.plusSeconds(10));
        assertThat(e.paidTaskCount()).isEqualTo(60); // the COUNT is not capped, only the list
    }

    @Test
    void zeroCommissionFlipPoint() {
        stub(List.of(
                row(AGENT_A, "A", "0.03", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T10:00:00Z")),
                row(AGENT_A, "A", "0.04", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T11:00:00Z"))),
                List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        // 0.03: commission rounds to 0.00 -> net 0.03; 0.04: commission 0.01 -> net 0.03
        assertThat(e.lifetimeEarned()).isEqualByComparingTo("0.06");
    }

    @Test
    void callerWithNoAgentsGetsEmptyEarnings() {
        stub(List.of(), List.of());

        Earnings e = service.earningsFor(OWNER);

        assertThat(e.lifetimeEarned()).isEqualByComparingTo("0.00");
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("0.00");
        assertThat(e.paidTaskCount()).isZero();
        assertThat(e.perAgent()).isEmpty();
        assertThat(e.payouts()).isEmpty();
    }
}
```

- [ ] **Step 4: Run the tests — expect compile failure** (impl class doesn't exist)

Run: `mvn -f backend/pom.xml test -Dtest=BuilderEarningsReadAppServiceImplTest`
Expected: COMPILATION ERROR (`BuilderEarningsReadAppServiceImpl` not found)

- [ ] **Step 5: Write the impl**

`backend/src/main/java/com/hireai/application/biz/wallet/impl/BuilderEarningsReadAppServiceImpl.java`:

```java
package com.hireai.application.biz.wallet.impl;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.application.port.query.BuilderEarningsQueryPort;
import com.hireai.application.port.query.BuilderEarningsQueryPort.OwnedAgentRow;
import com.hireai.application.port.query.BuilderEarningsQueryPort.RoutedTaskRow;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.wallet.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Folds routed-task rows through SettlementPolicy. O(agents x tasks) — fine at demo scale;
 * the payout list is capped at {@link #PAYOUT_HISTORY_LIMIT}, the totals are not.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BuilderEarningsReadAppServiceImpl implements BuilderEarningsReadAppService {

    /** Escrow still held and routed to one of the caller's agents — would pay out on accept. */
    private static final Set<String> PENDING_STATUSES = Set.of(
            TaskStatus.QUEUED.name(),
            TaskStatus.EXECUTING.name(),
            TaskStatus.RESULT_RECEIVED.name(),
            TaskStatus.PENDING_REVIEW.name(),
            TaskStatus.AWAITING_CAPACITY.name());

    private static final int PAYOUT_HISTORY_LIMIT = 50;

    private final BuilderEarningsQueryPort queryPort;

    @Override
    public Earnings earningsFor(UUID userId) {
        List<RoutedTaskRow> rows = queryPort.routedTasks(userId);
        List<OwnedAgentRow> agents = queryPort.ownedAgents(userId);

        Money lifetime = sumNet(rows.stream().filter(this::accepted).toList());
        Money pending = sumNet(rows.stream().filter(this::pending).toList());
        int paidCount = (int) rows.stream().filter(this::accepted).count();

        List<Payout> payouts = rows.stream()
                .filter(this::accepted)
                .sorted(Comparator.comparing(RoutedTaskRow::resolvedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PAYOUT_HISTORY_LIMIT)
                .map(r -> new Payout(r.taskId(), r.title(), r.agentName(), net(r).value(), r.resolvedAt()))
                .toList();

        List<AgentEarnings> perAgent = agents.stream()
                .map(a -> agentEarnings(a, rows))
                .toList();

        return new Earnings(lifetime.value(), pending.value(), paidCount, perAgent, payouts);
    }

    private AgentEarnings agentEarnings(OwnedAgentRow agent, List<RoutedTaskRow> allRows) {
        List<RoutedTaskRow> mine = allRows.stream()
                .filter(r -> agent.agentId().equals(r.agentId()))
                .toList();
        Money earned = sumNet(mine.stream().filter(this::accepted).toList());
        Money pending = sumNet(mine.stream().filter(this::pending).toList());
        int paid = (int) mine.stream().filter(this::accepted).count();
        return new AgentEarnings(agent.agentId(), agent.agentName(),
                earned.value(), pending.value(), paid);
    }

    private boolean accepted(RoutedTaskRow row) {
        return TaskStatus.RESOLVED.name().equals(row.status())
                && TaskResolution.ACCEPTED.name().equals(row.resolution());
    }

    private boolean pending(RoutedTaskRow row) {
        return PENDING_STATUSES.contains(row.status());
    }

    private Money net(RoutedTaskRow row) {
        return SettlementPolicy.netOf(Money.of(row.budget()));
    }

    private Money sumNet(List<RoutedTaskRow> rows) {
        return rows.stream().map(this::net).reduce(Money.ZERO, Money::add);
    }
}
```

- [ ] **Step 6: Run the tests — expect PASS**

Run: `mvn -f backend/pom.xml test -Dtest=BuilderEarningsReadAppServiceImplTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

Note: the Spring context will not start for this Mockito test, so the missing
`BuilderEarningsQueryPort` bean is NOT a problem yet — the DAO arrives in Task 2.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hireai/application/port/query/BuilderEarningsQueryPort.java backend/src/main/java/com/hireai/application/biz/wallet/BuilderEarningsReadAppService.java backend/src/main/java/com/hireai/application/biz/wallet/impl/BuilderEarningsReadAppServiceImpl.java backend/src/test/java/com/hireai/wallet/BuilderEarningsReadAppServiceImplTest.java
git commit -m "feat: builder earnings read service - SettlementPolicy fold over routed tasks"
```

---

### Task 2: JDBC DAO + integration test

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/catalogue/JdbcBuilderEarningsQueryDao.java`
- Test: `backend/src/test/java/com/hireai/wallet/BuilderEarningsIntegrationTest.java`

Read `infrastructure/repository/catalogue/JdbcBuilderStatsQueryDao.java` and
`backend/src/test/java/com/hireai/task/TaskSettlementIntegrationTest.java` first — the DAO
mirrors the former; the IT copies the latter's scaffolding (Testcontainers gate, `newUser`,
`newAgentVersion`, `seedReviewableTask`). Column names: tasks have `gmt_create`, `resolved_at`,
`resolution`, `budget`, `title`, `status`, `agent_version_id`; agents have `id`, `owner_id`,
`name`.

- [ ] **Step 1: Write the failing integration test**

`backend/src/test/java/com/hireai/wallet/BuilderEarningsIntegrationTest.java`:

```java
package com.hireai.wallet;

import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Earnings read against real Postgres + real settlement: accepted tasks earn net-of-commission,
 * rejected/open tasks don't, the self-settle case counts once, strangers see zeros. Settlement
 * is performed through the real TaskReviewAppService so the numbers this view derives from the
 * tasks table are provably consistent with what the wallets actually received.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class BuilderEarningsIntegrationTest {

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

    @Autowired BuilderEarningsReadAppService earningsService;
    @Autowired TaskReviewAppService reviewAppService;
    @Autowired TaskRepository taskRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, ?)",
                id, id + "@test.local", role);
        return id;
    }

    /** Seed an agent + version owned by {@code builderId} with a visible name. */
    private UUID newAgentVersion(UUID builderId, String name) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, ?, 'ACTIVE', ?)""", agentId, builderId, name, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url, max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return versionId;
    }

    /** A RESULT_RECEIVED task with its budget frozen — built from domain transitions (no broker). */
    private TaskModel seedReviewableTask(UUID clientId, UUID versionId, String budget) {
        TaskModel task = TaskModel.submit(clientId, "earn from me", "desc",
                        Money.of(budget), new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(versionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()));
        taskRepository.save(task);
        walletWrite.topUp(clientId, Money.of("100.00"), "setup-topup-" + task.id());
        walletWrite.freeze(clientId, Money.of(budget), task.id(), "setup-freeze-" + task.id());
        return task;
    }

    @Test
    void earningsAggregateAcrossAcceptedRejectedAndOpenTasks() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        UUID versionA = newAgentVersion(builder, "Agent A");
        UUID versionB = newAgentVersion(builder, "Agent B");

        TaskModel acceptedTask = seedReviewableTask(client, versionA, "12.00");
        TaskModel rejectedTask = seedReviewableTask(client, versionA, "20.00");
        seedReviewableTask(client, versionB, "20.00"); // stays RESULT_RECEIVED (pending)
        reviewAppService.accept(acceptedTask.id(), client);
        reviewAppService.reject(rejectedTask.id(), client, "not good enough");

        Earnings e = earningsService.earningsFor(builder);

        assertThat(e.lifetimeEarned()).isEqualByComparingTo("10.20");   // net of 12.00
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("17.00"); // net of 20.00
        assertThat(e.paidTaskCount()).isEqualTo(1);

        assertThat(e.perAgent()).hasSize(2);
        var agentA = e.perAgent().stream().filter(a -> a.agentName().equals("Agent A")).findFirst().orElseThrow();
        var agentB = e.perAgent().stream().filter(a -> a.agentName().equals("Agent B")).findFirst().orElseThrow();
        assertThat(agentA.earned()).isEqualByComparingTo("10.20");
        assertThat(agentA.pendingIfAccepted()).isEqualByComparingTo("0.00");
        assertThat(agentB.earned()).isEqualByComparingTo("0.00");
        assertThat(agentB.pendingIfAccepted()).isEqualByComparingTo("17.00");

        assertThat(e.payouts()).hasSize(1);
        assertThat(e.payouts().get(0).taskTitle()).isEqualTo("earn from me");
        assertThat(e.payouts().get(0).agentName()).isEqualTo("Agent A");
        assertThat(e.payouts().get(0).amount()).isEqualByComparingTo("10.20");
        assertThat(e.payouts().get(0).settledAt()).isNotNull();
    }

    @Test
    void selfSettleCountsThePayoutExactlyOnce() {
        UUID builderClient = newUser("BUILDER"); // same person on both sides — legal
        UUID version = newAgentVersion(builderClient, "My Own Agent");
        TaskModel task = seedReviewableTask(builderClient, version, "12.00");

        reviewAppService.accept(task.id(), builderClient);

        Earnings e = earningsService.earningsFor(builderClient);
        assertThat(e.lifetimeEarned()).isEqualByComparingTo("10.20"); // once, not 20.40
        assertThat(e.paidTaskCount()).isEqualTo(1);
        assertThat(e.payouts()).hasSize(1);
    }

    @Test
    void strangerSeesZerosAndEmptyLists() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder, "Agent A"), "12.00");
        reviewAppService.accept(task.id(), client);

        Earnings e = earningsService.earningsFor(newUser("CLIENT"));

        assertThat(e.lifetimeEarned()).isEqualByComparingTo("0.00");
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("0.00");
        assertThat(e.perAgent()).isEmpty();
        assertThat(e.payouts()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (no `BuilderEarningsQueryPort` bean → context fails)

Run: `mvn -f backend/pom.xml test -Dtest=BuilderEarningsIntegrationTest`
Expected: `NoSuchBeanDefinitionException: ... BuilderEarningsQueryPort` (requires Docker; if
Docker is down the test skips — start Docker, it must actually run here)

- [ ] **Step 3: Write the DAO**

`backend/src/main/java/com/hireai/infrastructure/repository/catalogue/JdbcBuilderEarningsQueryDao.java`:

```java
package com.hireai.infrastructure.repository.catalogue;

import com.hireai.application.port.query.BuilderEarningsQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Earnings rows for the builder console. Deliberately dumb SQL: just the ownership join —
 * status/resolution filtering and ALL money arithmetic live in the app service
 * (BuilderEarningsReadAppServiceImpl) so the semantics are unit-testable. The JOIN through
 * tasks.agent_version_id naturally excludes tasks never routed to an agent.
 */
@Repository
public class JdbcBuilderEarningsQueryDao implements BuilderEarningsQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBuilderEarningsQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoutedTaskRow> routedTasks(UUID ownerId) {
        String sql = """
                SELECT tk.id AS task_id, tk.title, tk.budget, tk.status, tk.resolution,
                       tk.resolved_at, a.id AS agent_id, a.name AS agent_name
                FROM tasks tk
                JOIN agent_versions v ON v.id = tk.agent_version_id
                JOIN agents a ON a.id = v.agent_id
                WHERE a.owner_id = :ownerId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("ownerId", ownerId), (rs, i) -> {
            Timestamp resolvedAt = rs.getTimestamp("resolved_at");
            return new RoutedTaskRow(
                    rs.getObject("task_id", UUID.class),
                    rs.getString("title"),
                    rs.getBigDecimal("budget"),
                    rs.getString("status"),
                    rs.getString("resolution"),
                    resolvedAt == null ? null : resolvedAt.toInstant(),
                    rs.getObject("agent_id", UUID.class),
                    rs.getString("agent_name"));
        });
    }

    @Override
    public List<OwnedAgentRow> ownedAgents(UUID ownerId) {
        String sql = "SELECT id, name FROM agents WHERE owner_id = :ownerId ORDER BY name";
        return jdbc.query(sql, new MapSqlParameterSource("ownerId", ownerId), (rs, i) ->
                new OwnedAgentRow(rs.getObject("id", UUID.class), rs.getString("name")));
    }
}
```

- [ ] **Step 4: Run the IT — expect PASS**

Run: `mvn -f backend/pom.xml test -Dtest=BuilderEarningsIntegrationTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/infrastructure/repository/catalogue/JdbcBuilderEarningsQueryDao.java backend/src/test/java/com/hireai/wallet/BuilderEarningsIntegrationTest.java
git commit -m "feat: earnings query DAO + integration test incl. self-settle exactly-once"
```

---

### Task 3: DTO + controller + web slice test

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/wallet/dto/BuilderEarningsDTO.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/wallet/BuilderEarningsController.java`
- Test: `backend/src/test/java/com/hireai/controller/biz/wallet/BuilderEarningsControllerTest.java`

Mirror `controller/biz/wallet/WalletController.java` (thin controller) and
`backend/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java` (slice harness).

- [ ] **Step 1: Write the failing slice test**

`backend/src/test/java/com/hireai/controller/biz/wallet/BuilderEarningsControllerTest.java`:

```java
package com.hireai.controller.biz.wallet;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.AgentEarnings;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Payout;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web slice: envelope, field mapping, identity from CurrentUserProvider only. */
@WebMvcTest(BuilderEarningsController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class BuilderEarningsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean BuilderEarningsReadAppService earningsService;
    @MockBean CurrentUserProvider currentUserProvider;

    @Test
    void returnsEarningsEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(userId);
        when(earningsService.earningsFor(userId)).thenReturn(new Earnings(
                new BigDecimal("27.20"), new BigDecimal("17.00"), 2,
                List.of(new AgentEarnings(agentId, "Summariser Bot",
                        new BigDecimal("27.20"), new BigDecimal("17.00"), 2)),
                List.of(new Payout(taskId, "Summarize the article", "Summariser Bot",
                        new BigDecimal("10.20"), Instant.parse("2026-06-07T04:28:39Z")))));

        mockMvc.perform(get("/api/builder/earnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.lifetimeEarned").value(27.20))
                .andExpect(jsonPath("$.data.pendingIfAccepted").value(17.00))
                .andExpect(jsonPath("$.data.paidTaskCount").value(2))
                .andExpect(jsonPath("$.data.perAgent[0].agentName").value("Summariser Bot"))
                .andExpect(jsonPath("$.data.perAgent[0].earned").value(27.20))
                .andExpect(jsonPath("$.data.payouts[0].taskTitle").value("Summarize the article"))
                .andExpect(jsonPath("$.data.payouts[0].amount").value(10.20));
    }

    @Test
    void emptyEarningsStillSucceed() throws Exception {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(userId);
        when(earningsService.earningsFor(userId)).thenReturn(new Earnings(
                new BigDecimal("0.00"), new BigDecimal("0.00"), 0, List.of(), List.of()));

        mockMvc.perform(get("/api/builder/earnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidTaskCount").value(0))
                .andExpect(jsonPath("$.data.perAgent").isEmpty())
                .andExpect(jsonPath("$.data.payouts").isEmpty());
    }
}
```

- [ ] **Step 2: Run it — expect compile failure** (controller doesn't exist)

Run: `mvn -f backend/pom.xml test -Dtest=BuilderEarningsControllerTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Write the DTO and controller**

`backend/src/main/java/com/hireai/controller/biz/wallet/dto/BuilderEarningsDTO.java`:

```java
package com.hireai.controller.biz.wallet.dto;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbound HTTP view of a builder's earnings. 1:1 with the app-service record. */
public record BuilderEarningsDTO(
        BigDecimal lifetimeEarned,
        BigDecimal pendingIfAccepted,
        int paidTaskCount,
        List<AgentEarningsDTO> perAgent,
        List<PayoutDTO> payouts
) {

    public record AgentEarningsDTO(UUID agentId, String agentName, BigDecimal earned,
                                   BigDecimal pendingIfAccepted, int paidTaskCount) {
    }

    public record PayoutDTO(UUID taskId, String taskTitle, String agentName,
                            BigDecimal amount, Instant settledAt) {
    }

    public static BuilderEarningsDTO from(BuilderEarningsReadAppService.Earnings e) {
        return new BuilderEarningsDTO(
                e.lifetimeEarned(),
                e.pendingIfAccepted(),
                e.paidTaskCount(),
                e.perAgent().stream().map(a -> new AgentEarningsDTO(
                        a.agentId(), a.agentName(), a.earned(), a.pendingIfAccepted(), a.paidTaskCount())).toList(),
                e.payouts().stream().map(p -> new PayoutDTO(
                        p.taskId(), p.taskTitle(), p.agentName(), p.amount(), p.settledAt())).toList());
    }
}
```

`backend/src/main/java/com/hireai/controller/biz/wallet/BuilderEarningsController.java`:

```java
package com.hireai.controller.biz.wallet;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.wallet.dto.BuilderEarningsDTO;
import com.hireai.controller.config.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Builder earnings HTTP surface. Identity comes from {@link CurrentUserProvider} only
 * (invariant #5) — no parameters to tamper with; a caller who owns no agents gets zeros.
 */
@RestController
@RequestMapping("/api/builder/earnings")
public class BuilderEarningsController extends BaseController {

    private final BuilderEarningsReadAppService earningsService;
    private final CurrentUserProvider currentUser;

    public BuilderEarningsController(BuilderEarningsReadAppService earningsService,
                                     CurrentUserProvider currentUser) {
        this.earningsService = earningsService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public WebResult<BuilderEarningsDTO> earnings() {
        return ok(BuilderEarningsDTO.from(earningsService.earningsFor(currentUser.currentUserId())));
    }
}
```

- [ ] **Step 4: Run the slice test — expect PASS**

Run: `mvn -f backend/pom.xml test -Dtest=BuilderEarningsControllerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Run the whole backend suite**

Run: `mvn -f backend/pom.xml test`
Expected: all green (≈295 tests incl. the 11 new ones; ITs skip without Docker but Docker is up)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/biz/wallet/dto/BuilderEarningsDTO.java backend/src/main/java/com/hireai/controller/biz/wallet/BuilderEarningsController.java backend/src/test/java/com/hireai/controller/biz/wallet/BuilderEarningsControllerTest.java
git commit -m "feat: GET /api/builder/earnings endpoint"
```

---

### Task 4: Frontend — types, MSW stub, earnings page

**Files:**
- Modify: `frontend/lib/types.ts` (append)
- Modify: `frontend/test/msw/handlers.ts` (append a handler)
- Create: `frontend/app/builder/earnings/page.tsx`
- Test: `frontend/test/earnings.test.tsx`

Read `docs/details/frontend.md` and `frontend/app/builder/page.tsx` first — the page mirrors
the builder dashboard's structure (RoleGuard + AppShell, `api()` fetch in `useEffect`, panel +
tile classes). All vitest commands run **from `frontend/`**.

- [ ] **Step 1: Add the types** (append to `frontend/lib/types.ts`)

```typescript
// ── Builder earnings ──────────────────────────────────────────────────────

export interface AgentEarningsDTO {
  agentId: string;
  agentName: string;
  earned: number;
  pendingIfAccepted: number;
  paidTaskCount: number;
}

export interface PayoutDTO {
  taskId: string;
  taskTitle: string;
  agentName: string;
  amount: number;
  settledAt: string;
}

export interface BuilderEarningsDTO {
  lifetimeEarned: number;
  pendingIfAccepted: number;
  paidTaskCount: number;
  perAgent: AgentEarningsDTO[];
  payouts: PayoutDTO[];
}
```

- [ ] **Step 2: Add the MSW handler** (append inside the `handlers` array in `frontend/test/msw/handlers.ts`, following the existing `ok(...)` helper style)

```typescript
  http.get("*/api/builder/earnings", () =>
    ok({
      lifetimeEarned: 27.2,
      pendingIfAccepted: 17.0,
      paidTaskCount: 2,
      perAgent: [
        { agentId: "a-1", agentName: "Summariser", earned: 27.2, pendingIfAccepted: 17.0, paidTaskCount: 2 },
        { agentId: "a-2", agentName: "Analyst", earned: 0, pendingIfAccepted: 0, paidTaskCount: 0 },
      ],
      payouts: [
        {
          taskId: "t-1",
          taskTitle: "Summarize the article",
          agentName: "Summariser",
          amount: 10.2,
          settledAt: "2026-06-07T04:28:39Z",
        },
        {
          taskId: "t-2",
          taskTitle: "Summarise Q2 report",
          agentName: "Summariser",
          amount: 17.0,
          settledAt: "2026-06-06T10:00:00Z",
        },
      ],
    }),
  ),
```

- [ ] **Step 3: Write the failing page test**

`frontend/test/earnings.test.tsx`:

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { http } from "msw";
import { server, ok } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import EarningsPage from "@/app/builder/earnings/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderEarnings() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "BUILDER" }));
  return render(
    <AuthProvider>
      <EarningsPage />
    </AuthProvider>,
  );
}

describe("builder earnings page", () => {
  it("renders totals, per-agent breakdown and payout history", async () => {
    renderEarnings();

    // summary tiles (from the MSW stub)
    expect(await screen.findByText("27.20")).toBeInTheDocument();
    expect(screen.getByText("17.00")).toBeInTheDocument();
    expect(screen.getByText("lifetime earned")).toBeInTheDocument();
    expect(screen.getByText("pending · if accepted")).toBeInTheDocument();

    // per-agent rows — zero-row agent included
    expect(screen.getByText("Analyst")).toBeInTheDocument();

    // payout history
    expect(screen.getByText("Summarize the article")).toBeInTheDocument();
    expect(screen.getByText("+10.20 cr")).toBeInTheDocument();
    expect(screen.getByText("+17.00 cr")).toBeInTheDocument();
  });

  it("shows the empty state when there are no payouts", async () => {
    server.use(
      http.get("*/api/builder/earnings", () =>
        ok({
          lifetimeEarned: 0,
          pendingIfAccepted: 0,
          paidTaskCount: 0,
          perAgent: [],
          payouts: [],
        }),
      ),
    );
    renderEarnings();

    expect(await screen.findByText(/No payouts yet/)).toBeInTheDocument();
  });
});
```

Note: `ok` is already exported from `frontend/test/msw/handlers.ts` (`export { ok, fail };`
at the bottom of the file) — import it as shown above.

- [ ] **Step 4: Run it — expect FAIL** (page module doesn't exist)

Run (from `frontend/`): `npx vitest run test/earnings.test.tsx`
Expected: FAIL — cannot resolve `@/app/builder/earnings/page`

- [ ] **Step 5: Write the page**

`frontend/app/builder/earnings/page.tsx`:

```tsx
"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { StatTile } from "@/components/StatTile";
import type { BuilderEarningsDTO } from "@/lib/types";

function EarningsView() {
  const [earnings, setEarnings] = useState<BuilderEarningsDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<BuilderEarningsDTO>("/builder/earnings")
      .then(setEarnings)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load earnings"));
  }, []);

  if (error) {
    return (
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
        {error}
      </p>
    );
  }

  if (!earnings) {
    return <p className="font-mono text-sm text-dim">Loading…</p>;
  }

  return (
    <div className="space-y-10">
      <header>
        <p className="eyebrow flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          Builder console
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Earnings</h1>
        <p className="mt-2 text-sm text-muted">
          Accepted work pays out 85% of the task budget; amounts of record live in the ledger.
        </p>
      </header>

      {/* ── summary ──────────────────────────────────────────────────── */}
      <div className="grid grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
        <StatTile value={earnings.lifetimeEarned.toFixed(2)} label="lifetime earned" tone="accent" />
        <StatTile value={earnings.pendingIfAccepted.toFixed(2)} label="pending · if accepted" tone="amber" />
        <StatTile value={earnings.paidTaskCount} label="paid tasks" />
      </div>

      {/* ── per-agent breakdown ──────────────────────────────────────── */}
      {earnings.perAgent.length > 0 && (
        <div>
          <p className="eyebrow mb-3">By agent</p>
          <ul className="space-y-2">
            {earnings.perAgent.map((a) => (
              <li
                key={a.agentId}
                className="flex items-center justify-between gap-3 rounded-md border border-line bg-surface-2 px-4 py-3"
              >
                <p className="truncate text-sm font-bold text-fg">{a.agentName}</p>
                <dl className="flex shrink-0 items-center gap-6 font-mono text-xs">
                  <div className="text-right">
                    <dt className="text-[0.6rem] uppercase tracking-wider text-dim">earned</dt>
                    <dd className="tabular mt-0.5 text-accent">{a.earned.toFixed(2)} cr</dd>
                  </div>
                  <div className="text-right">
                    <dt className="text-[0.6rem] uppercase tracking-wider text-dim">pending</dt>
                    <dd className="tabular mt-0.5 text-amber">{a.pendingIfAccepted.toFixed(2)} cr</dd>
                  </div>
                  <div className="text-right">
                    <dt className="text-[0.6rem] uppercase tracking-wider text-dim">paid tasks</dt>
                    <dd className="tabular mt-0.5 text-fg">{a.paidTaskCount}</dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ── payout history ───────────────────────────────────────────── */}
      <div>
        <p className="eyebrow mb-3">Payout history</p>
        {earnings.payouts.length === 0 ? (
          <div className="panel p-10 text-center">
            <p className="font-mono text-sm text-muted">
              No payouts yet — earnings land here when a client accepts your agent&apos;s work.
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {earnings.payouts.map((p) => (
              <li
                key={p.taskId}
                className="flex items-center justify-between gap-3 rounded-md border border-line bg-surface-2 px-4 py-3"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm text-fg">{p.taskTitle}</p>
                  <p className="mt-0.5 font-mono text-[0.65rem] text-dim">
                    {p.agentName} · {new Date(p.settledAt).toLocaleDateString()}
                  </p>
                </div>
                <span className="tabular shrink-0 font-mono text-sm font-semibold text-accent">
                  +{p.amount.toFixed(2)} cr
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <EarningsView />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 6: Run the page test — expect PASS**

Run (from `frontend/`): `npx vitest run test/earnings.test.tsx`
Expected: `2 passed`

- [ ] **Step 7: Commit**

```bash
git add frontend/lib/types.ts frontend/test/msw/handlers.ts frontend/app/builder/earnings/page.tsx frontend/test/earnings.test.tsx
git commit -m "feat: /builder/earnings page - totals, per-agent breakdown, payout history"
```

---

### Task 5: Builder nav links + console tile relabel

**Files:**
- Modify: `frontend/components/Nav.tsx` (builder nav block)
- Modify: `frontend/app/builder/page.tsx` (tile relabel + link)
- Test: `frontend/test/builder.test.tsx` (update label assertion, add nav assertion)

- [ ] **Step 1: Update the failing tests first**

In `frontend/test/builder.test.tsx`, replace the existing earnings-tile test (it currently
asserts the label `credits earned`) with:

```tsx
describe("builder earnings", () => {
  it("shows the wallet balance tile linking to the earnings page", async () => {
    renderBuilder();
    // From the MSW /api/wallet stub: availableBalance 950.
    expect(await screen.findByText("950.00")).toBeInTheDocument();
    expect(screen.getByText("wallet cr")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /950\.00/ })).toHaveAttribute(
      "href",
      "/builder/earnings",
    );
  });

  it("shows builder nav links", async () => {
    renderBuilder();
    expect(await screen.findByRole("link", { name: "Earnings" })).toHaveAttribute(
      "href",
      "/builder/earnings",
    );
    expect(screen.getByRole("link", { name: "My agents" })).toHaveAttribute("href", "/builder");
  });
});
```

- [ ] **Step 2: Run — expect FAIL** (label still `credits earned`, no nav links)

Run (from `frontend/`): `npx vitest run test/builder.test.tsx`
Expected: 2 failures (the original activate test still passes)

- [ ] **Step 3: Add the builder nav block**

In `frontend/components/Nav.tsx`, directly below the existing `{role === "CLIENT" && (...)}`
block (keep that block unchanged), add:

```tsx
            {role === "BUILDER" && (
              <div className="hidden items-center gap-1 md:flex">
                {[
                  { href: "/builder", label: "My agents" },
                  { href: "/builder/earnings", label: "Earnings" },
                ].map((l) => (
                  <Link
                    key={l.href}
                    href={l.href}
                    className="rounded-md px-3 py-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted transition hover:text-fg"
                  >
                    {l.label}
                  </Link>
                ))}
              </div>
            )}
```

Note the test renders the nav at jsdom width where `hidden md:flex` classes are present but
jsdom does not apply CSS media queries — `getByRole("link")` still finds them.

- [ ] **Step 4: Relabel + link the console tile**

In `frontend/app/builder/page.tsx`:

1. Add the import: `import Link from "next/link";` is already there — no change needed.
2. In the summary-tiles array, change the wallet tile label:

```tsx
          {
            v: (wallet?.availableBalance ?? 0).toFixed(2),
            l: "wallet cr",
            c: "text-accent",
          },
```

3. Wrap each tile so the wallet one links to the earnings page. Replace the `.map((s) => (...))`
body with:

```tsx
        ].map((s) =>
          s.l === "wallet cr" ? (
            <Link key={s.l} href="/builder/earnings" className="bg-surface px-5 py-5 transition hover:bg-surface-2">
              <p className={`tabular text-3xl font-extrabold ${s.c}`}>{s.v}</p>
              <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">
                {s.l} ▸
              </p>
            </Link>
          ) : (
            <div key={s.l} className="bg-surface px-5 py-5">
              <p className={`tabular text-3xl font-extrabold ${s.c}`}>{s.v}</p>
              <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">
                {s.l}
              </p>
            </div>
          ),
        )}
```

Note the label test asserts `getByText("wallet cr")` — the rendered text is `wallet cr ▸`,
so use exact: false semantics; `getByText("wallet cr", { exact: false })` if the plain call
fails. The link-role assertion uses a name regex so the ▸ doesn't matter there.

- [ ] **Step 5: Run — expect PASS**

Run (from `frontend/`): `npx vitest run test/builder.test.tsx`
Expected: all pass (3 tests)

- [ ] **Step 6: Run the whole frontend suite + type check**

Run (from `frontend/`): `npx vitest run` then `npx tsc --noEmit`
Expected: all green (≈50 tests), tsc exit 0

- [ ] **Step 7: Commit**

```bash
git add frontend/components/Nav.tsx frontend/app/builder/page.tsx frontend/test/builder.test.tsx
git commit -m "feat: builder nav with earnings link; console wallet tile links to earnings"
```

---

### Task 6: Docs + full verification

**Files:**
- Modify: `docs/details/frontend.md` (route map: add `/builder/earnings`)
- Modify: `CLAUDE.md` (backend + frontend "Built" summaries, test counts)

- [ ] **Step 1: Update `docs/details/frontend.md`**

In the **Routes** section, extend the `builder/` line to mention the earnings page:

```markdown
- `builder/` — portfolio dashboard (wallet tile links to earnings); `builder/earnings` — earnings
  view (lifetime/pending totals from `GET /api/builder/earnings`, per-agent breakdown, payout
  history; amounts derived server-side from `SettlementPolicy`); `builder/agents/new` — register
  an agent; `builder/agents/[id]` — manage console (tabs: Storefront · Pricing & tags · Stats ·
  Reviews; image uploader via `apiUpload`).
```

(Replace the existing `- builder/ — ...` bullet; keep the rest of the section unchanged.)

- [ ] **Step 2: Update `CLAUDE.md`**

In the backend bullet, after the client review + settlement clause, add the earnings read:
`and a **builder earnings read** (GET /api/builder/earnings — derived from tasks via SettlementPolicy, never ledger sums)`.
In the frontend bullet, add `/builder/earnings` to the built list. Update the test counts to
the numbers observed in Step 3 (backend was ~284 + 11 new; frontend was 47 + 4 new).

- [ ] **Step 3: Full verification**

Run: `mvn -f backend/pom.xml test`
Expected: all green, 0 failures (note the final count)

Run (from `frontend/`): `npx vitest run` then `npx tsc --noEmit` then `npm run build`
Expected: all green / exit 0 / build succeeds (note the final count)

- [ ] **Step 4: Commit**

```bash
git add docs/details/frontend.md CLAUDE.md
git commit -m "docs: builder earnings - route map, module summary, test counts"
```

---

## Self-review notes (already applied)

- Spec coverage: semantics table → Task 1; endpoint/DTO → Task 3; SQL/self-settle → Task 2;
  page/empty state → Task 4; nav + tile relabel → Task 5; docs → Task 6. The spec's
  "client caller gets zeros" is covered three times (unit, IT, slice).
- `paidTaskCount` is intentionally NOT capped at 50 (only the payout list is) — asserted in
  `payoutsAreNewestFirstAndCappedAt50`.
- Type consistency: `Earnings/AgentEarnings/Payout` records (Task 1) are consumed by name in
  Tasks 2–3; `BuilderEarningsDTO` field names match the frontend types in Task 4.
