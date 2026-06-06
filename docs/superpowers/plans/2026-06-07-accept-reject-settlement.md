# Accept/Reject + Deterministic Settlement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a client Accept (escrow → 85% builder payout + 15% commission) or Reject (escrow → full refund) a `RESULT_RECEIVED` task; task ends `RESOLVED`.

**Architecture:** Synchronous settlement inside the accept/reject request transaction. New `TaskReviewAppService` orchestrates: ownership check → `TaskModel.accept()/reject()` transition (the exactly-once guard) → `SettlementDomainService` (framework-free, pure arithmetic — Invariant #3) mutating the existing `WalletModel` aggregate ops (`release`/`refund`/`credit`, which append the ledger entries) → save task + wallets in one transaction.

**Tech Stack:** Spring Boot 3 / Java 21 DDD backend (JPA + Flyway V9, Testcontainers ITs), Next.js 16 + Tailwind frontend (vitest + RTL + MSW).

**Spec:** `docs/superpowers/specs/2026-06-07-accept-reject-settlement-design.md` (approved). Branch: `feat/marketplace-spine`.

**Conventions you MUST follow** (from `docs/details/ddd-conventions.md` — read it first):
- Layering `controller → application → domain ← infrastructure`. Domain has ZERO framework imports.
- App services: interface in `application/biz/<ctx>/`, impl in `impl/` subpackage, `@Service @Slf4j @RequiredArgsConstructor @Transactional`.
- Domain services: framework-free interface + `impl/`, registered as beans in `application/config/DomainServiceConfig.java`.
- Domain models are immutable — transitions return NEW copies; illegal transitions throw `DomainException(ResultCode...)`.
- Commits: conventional format (`feat:`/`test:`/`docs:`), **NO attribution footer, NO Co-Authored-By** (user's global settings).
- Run backend commands from the repo root: `mvn -f backend/pom.xml -B test`. Frontend: `npx vitest run` inside `frontend/`.
- Without Docker, `*IntegrationTest` classes skip automatically — that is expected, not a failure.

---

### Task 1: `TaskResolution` enum + `TaskModel.accept()/reject()`

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/enums/TaskResolution.java`
- Modify: `backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`
- Test (create): `backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelReviewTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client review transitions: accept/reject from RESULT_RECEIVED only (the exactly-once settlement guard). */
class TaskModelReviewTest {

    private TaskModel resultReceivedTask() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "title", "desc",
                Money.of("20.00"), new OutputSpec(OutputFormat.TEXT, null, "short"), "summarisation");
        task = task.assignAndQueue(UUID.randomUUID()).markExecuting();
        return task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()));
    }

    @Test
    void acceptResolvesTheTask() {
        TaskModel resolved = resultReceivedTask().accept();
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.ACCEPTED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.rejectionReason()).isNull();
    }

    @Test
    void rejectResolvesWithTrimmedReason() {
        TaskModel resolved = resultReceivedTask().reject("  not what I asked for  ");
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(resolved.rejectionReason()).isEqualTo("not what I asked for");
    }

    @Test
    void rejectWithoutReasonStoresNull() {
        assertThat(resultReceivedTask().reject(null).rejectionReason()).isNull();
        assertThat(resultReceivedTask().reject("   ").rejectionReason()).isNull();
    }

    @Test
    void rejectReasonOver500CharsIsRejected() {
        assertThatThrownBy(() -> resultReceivedTask().reject("x".repeat(501)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void acceptFromNonResultReceivedThrows() {
        TaskModel executing = TaskModel.submit(UUID.randomUUID(), "t", "d",
                        Money.of("5.00"), new OutputSpec(OutputFormat.TEXT, null, null), "c")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(executing::accept)
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
    }

    @Test
    void resolvedTaskCannotBeResolvedAgain() {
        TaskModel resolved = resultReceivedTask().accept();
        assertThatThrownBy(resolved::accept).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> resolved.reject("again")).isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run to verify they fail to compile**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskModelReviewTest`
Expected: COMPILATION ERROR — `TaskResolution`, `accept()`, `reject()`, `resolution()` do not exist.

- [ ] **Step 3: Create the enum**

```java
package com.hireai.domain.biz.task.enums;

/** How the client resolved a RESULT_RECEIVED task. Null on the task until it is RESOLVED. */
public enum TaskResolution {
    ACCEPTED,
    REJECTED
}
```

- [ ] **Step 4: Extend `TaskModel`**

Add three fields, a new canonical constructor (keep the existing 11-arg constructor delegating to it so the repository impl and existing tests keep compiling until Task 2), the two transitions, and getters. Concretely:

```java
// new imports
import com.hireai.domain.biz.task.enums.TaskResolution;

// new fields (after `result`)
private final TaskResolution resolution;   // nullable until RESOLVED
private final Instant resolvedAt;          // nullable until RESOLVED
private final String rejectionReason;      // nullable; only set on REJECTED

private static final int MAX_REASON_LENGTH = 500;

// NEW canonical constructor (14 args) — rehydration path
public TaskModel(UUID id, UUID clientId, String title, String description,
                 Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                 UUID agentVersionId, TaskResultModel result, Instant createdAt,
                 TaskResolution resolution, Instant resolvedAt, String rejectionReason) {
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
    this.resolution = resolution;
    this.resolvedAt = resolvedAt;
    this.rejectionReason = rejectionReason;
}

// EXISTING 11-arg constructor becomes a delegating overload (pre-review rehydration)
public TaskModel(UUID id, UUID clientId, String title, String description,
                 Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                 UUID agentVersionId, TaskResultModel result, Instant createdAt) {
    this(id, clientId, title, description, budget, outputSpec, category, status,
            agentVersionId, result, createdAt, null, null, null);
}
```

The review transitions (place after `markFailed()`):

```java
/** RESULT_RECEIVED → RESOLVED (ACCEPTED): the client accepted the result. */
public TaskModel accept() {
    requireStatus(TaskStatus.RESULT_RECEIVED, "accept");
    return resolved(TaskResolution.ACCEPTED, null);
}

/** RESULT_RECEIVED → RESOLVED (REJECTED): the client rejected the result. Reason optional, ≤500 chars. */
public TaskModel reject(String reason) {
    requireStatus(TaskStatus.RESULT_RECEIVED, "reject");
    String trimmed = (reason == null || reason.isBlank()) ? null : reason.trim();
    if (trimmed != null && trimmed.length() > MAX_REASON_LENGTH) {
        throw new DomainException(ResultCode.VALIDATION_ERROR,
                "Rejection reason must be at most " + MAX_REASON_LENGTH + " characters");
    }
    return resolved(TaskResolution.REJECTED, trimmed);
}

private TaskModel resolved(TaskResolution resolution, String rejectionReason) {
    return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
            this.outputSpec, this.category, TaskStatus.RESOLVED, this.agentVersionId, this.result,
            this.createdAt, resolution, Instant.now(), rejectionReason);
}
```

Update the private `copyWith` to carry the new fields (they are always null pre-review, but carrying them is correct-by-construction):

```java
private TaskModel copyWith(TaskStatus newStatus, UUID newAgentVersionId, TaskResultModel newResult) {
    return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
            this.outputSpec, this.category, newStatus, newAgentVersionId, newResult, this.createdAt,
            this.resolution, this.resolvedAt, this.rejectionReason);
}
```

Getters (append to the accessor block):

```java
public TaskResolution resolution() { return resolution; }
public Instant resolvedAt() { return resolvedAt; }
public String rejectionReason() { return rejectionReason; }
```

- [ ] **Step 5: Run the new tests + the whole task-model suite**

Run: `mvn -f backend/pom.xml -B test -Dtest="TaskModel*"`
Expected: ALL PASS (new + existing `TaskModelTest`, `TaskModelTransitionsTest`).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/task backend/src/test/java/com/hireai/domain/biz/task
git commit -m "feat: TaskModel accept/reject review transitions with TaskResolution"
```

---

### Task 2: V9 migration + persistence of the resolution fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__task_resolution.sql`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskJpaEntity.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskRepositoryImpl.java`

- [ ] **Step 1: Write the migration**

V2's `tasks.status` CHECK already contains `'RESOLVED'` — do NOT touch the status constraint. Only add columns:

```sql
-- V9: Client review resolution. Set exactly once when a RESULT_RECEIVED task is
-- accepted or rejected (tasks.status -> RESOLVED). Settlement amounts are NOT stored
-- here -- the append-only ledger_entries (V1) is the money record (Invariant #2).
ALTER TABLE tasks
    ADD COLUMN resolution       TEXT NULL CHECK (resolution IN ('ACCEPTED', 'REJECTED')),
    ADD COLUMN resolved_at      TIMESTAMPTZ NULL,
    ADD COLUMN rejection_reason TEXT NULL CHECK (char_length(rejection_reason) <= 500);
```

- [ ] **Step 2: Extend `TaskJpaEntity`**

Add after the `gmtCreate` field:

```java
@Column(name = "resolution")
private String resolution;

@Column(name = "resolved_at")
private Instant resolvedAt;

@Column(name = "rejection_reason")
private String rejectionReason;
```

Extend the public constructor to 13 args (append `String resolution, Instant resolvedAt, String rejectionReason` and assign them) and add getters `getResolution()`, `getResolvedAt()`, `getRejectionReason()`.

- [ ] **Step 3: Update `TaskRepositoryImpl`**

`save(...)` — pass the new values:

```java
taskJpa.save(new TaskJpaEntity(
        task.id(), task.clientId(), task.title(), task.description(),
        task.budget().value(), outputSpecJsonMapper.toJson(task.outputSpec()),
        task.category(), task.status().name(), task.agentVersionId(), task.createdAt(),
        task.resolution() == null ? null : task.resolution().name(),
        task.resolvedAt(), task.rejectionReason()));
```

`toModel(...)` — use the 14-arg constructor:

```java
return new TaskModel(
        entity.getId(), entity.getClientId(), entity.getTitle(), entity.getDescription(),
        Money.of(entity.getBudget()), outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
        entity.getCategory(), TaskStatus.valueOf(entity.getStatus()),
        entity.getAgentVersionId(), result, entity.getGmtCreate(),
        entity.getResolution() == null ? null : TaskResolution.valueOf(entity.getResolution()),
        entity.getResolvedAt(), entity.getRejectionReason());
```

(add import `com.hireai.domain.biz.task.enums.TaskResolution`.)

- [ ] **Step 4: Full build + tests**

Run: `mvn -f backend/pom.xml -B test`
Expected: ALL PASS (persistence round-trip is asserted by the Task 7 IT; here we prove nothing regressed).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__task_resolution.sql backend/src/main/java/com/hireai/infrastructure/repository/task
git commit -m "feat: persist task resolution fields (V9)"
```

---

### Task 3: `SettlementPolicy` + `SettlementDomainService`

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/wallet/service/SettlementPolicy.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/wallet/info/SettlementBreakdown.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/wallet/service/SettlementDomainService.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/wallet/service/impl/SettlementDomainServiceImpl.java`
- Modify: `backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java`
- Test (create): `backend/src/test/java/com/hireai/domain/biz/wallet/service/SettlementDomainServiceImplTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.biz.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic settlement arithmetic (Invariant #3): accept -> 85/15 split out of the
 * client's escrow + payout credit to the builder; reject -> full refund. Every movement
 * appends a ledger entry through the aggregate (Invariant #2).
 */
class SettlementDomainServiceImplTest {

    private final SettlementDomainService service = new SettlementDomainServiceImpl();
    private final UUID taskId = UUID.randomUUID();

    /** Client wallet with `budget` already frozen in escrow, pending entries cleared. */
    private WalletModel clientWalletWithEscrow(String budget) {
        WalletModel w = WalletModel.openFor(UUID.randomUUID());
        w.topUp(Money.of("100.00"), "setup");
        w.freeze(Money.of(budget), taskId, "setup");
        w.clearPendingEntries();
        return w;
    }

    @Test
    void acceptanceSplitsEscrowEightyFifteen() {
        WalletModel client = clientWalletWithEscrow("20.00");
        WalletModel builder = WalletModel.openFor(UUID.randomUUID());

        SettlementBreakdown b = service.settleAcceptance(client, builder, Money.of("20.00"), taskId, "settle-x");

        assertThat(b.net()).isEqualTo(Money.of("17.00"));
        assertThat(b.commission()).isEqualTo(Money.of("3.00"));
        assertThat(client.escrow()).isEqualTo(Money.ZERO);
        assertThat(client.available()).isEqualTo(Money.of("80.00"));
        assertThat(builder.available()).isEqualTo(Money.of("17.00"));

        List<LedgerEntryType> clientTypes = client.pendingEntries().stream().map(LedgerEntryModel::type).toList();
        assertThat(clientTypes).containsExactly(LedgerEntryType.PAYOUT, LedgerEntryType.COMMISSION);
        List<LedgerEntryType> builderTypes = builder.pendingEntries().stream().map(LedgerEntryModel::type).toList();
        assertThat(builderTypes).containsExactly(LedgerEntryType.PAYOUT);
    }

    @Test
    void commissionRoundsHalfUpAndAlwaysReconciles() {
        // 10.01 * 0.15 = 1.5015 -> 1.50; net 8.51; 1.50 + 8.51 = 10.01
        WalletModel client = clientWalletWithEscrow("10.01");
        SettlementBreakdown b = service.settleAcceptance(client, WalletModel.openFor(UUID.randomUUID()),
                Money.of("10.01"), taskId, "c");
        assertThat(b.commission()).isEqualTo(Money.of("1.50"));
        assertThat(b.net()).isEqualTo(Money.of("8.51"));
        assertThat(b.net().add(b.commission())).isEqualTo(Money.of("10.01"));

        // 0.10 * 0.15 = 0.015 -> 0.02 (HALF_UP); net 0.08
        WalletModel client2 = clientWalletWithEscrow("0.10");
        SettlementBreakdown b2 = service.settleAcceptance(client2, WalletModel.openFor(UUID.randomUUID()),
                Money.of("0.10"), taskId, "c2");
        assertThat(b2.commission()).isEqualTo(Money.of("0.02"));
        assertThat(b2.net()).isEqualTo(Money.of("0.08"));
    }

    @Test
    void zeroCommissionSkipsTheCommissionEntry() {
        // 0.01 * 0.15 = 0.0015 -> 0.00; the COMMISSION ledger entry must be skipped (amounts must be positive)
        WalletModel client = clientWalletWithEscrow("0.01");
        WalletModel builder = WalletModel.openFor(UUID.randomUUID());
        SettlementBreakdown b = service.settleAcceptance(client, builder, Money.of("0.01"), taskId, "c");
        assertThat(b.commission()).isEqualTo(Money.ZERO);
        assertThat(b.net()).isEqualTo(Money.of("0.01"));
        assertThat(client.pendingEntries()).hasSize(1); // PAYOUT only
        assertThat(builder.available()).isEqualTo(Money.of("0.01"));
    }

    @Test
    void rejectionRefundsTheFullBudget() {
        WalletModel client = clientWalletWithEscrow("20.00");
        service.settleRejection(client, Money.of("20.00"), taskId, "settle-r");
        assertThat(client.escrow()).isEqualTo(Money.ZERO);
        assertThat(client.available()).isEqualTo(Money.of("100.00"));
        assertThat(client.pendingEntries()).hasSize(1);
        assertThat(client.pendingEntries().get(0).type()).isEqualTo(LedgerEntryType.REFUND);
    }
}
```

NOTE: if `LedgerEntryModel`'s accessor for the entry type is not named `type()`, check the record/class definition in `backend/src/main/java/com/hireai/domain/biz/wallet/model/LedgerEntryModel.java` and use its actual accessor — do not change `LedgerEntryModel`.

- [ ] **Step 2: Run to verify compile failure**

Run: `mvn -f backend/pom.xml -B test -Dtest=SettlementDomainServiceImplTest`
Expected: COMPILATION ERROR — types do not exist.

- [ ] **Step 3: Implement**

`SettlementPolicy.java`:

```java
package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;

/**
 * The platform's settlement constants (SAD: 15% commission, deducted on release).
 * The single place the rate lives — the DTO layer derives display amounts from here too.
 */
public final class SettlementPolicy {

    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.15");

    private SettlementPolicy() {
    }

    /** Commission on a budget, rounded half-up to 2dp (Money's fixed scale). */
    public static Money commissionOn(Money budget) {
        return budget.percentage(COMMISSION_RATE);
    }

    /** What the builder receives: budget minus commission (reconciles exactly). */
    public static Money netOf(Money budget) {
        return budget.subtract(commissionOn(budget));
    }
}
```

`SettlementBreakdown.java`:

```java
package com.hireai.domain.biz.wallet.info;

import com.hireai.domain.shared.model.Money;

/** Result carrier for an acceptance settlement: what the builder got and what the platform kept. */
public record SettlementBreakdown(Money net, Money commission) {
}
```

`SettlementDomainService.java`:

```java
package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Deterministic escrow settlement (Invariant #3: the LLM may produce rulings, but money
 * movement is pure domain arithmetic). Mutates the wallet aggregates, which append the
 * ledger entries; persistence is the caller's job. Framework-free; the bean is registered
 * in DomainServiceConfig.
 */
public interface SettlementDomainService {

    /**
     * Client accepted: release net (85%) + commission (15%) out of the client's escrow and
     * credit the net to the builder's available balance. {@code clientWallet} and
     * {@code builderWallet} MAY be the same instance (a client accepting their own agent's work).
     */
    SettlementBreakdown settleAcceptance(WalletModel clientWallet, WalletModel builderWallet,
                                         Money budget, UUID taskId, String correlationId);

    /** Client rejected: return the full budget from escrow to the client's available balance. */
    void settleRejection(WalletModel clientWallet, Money budget, UUID taskId, String correlationId);
}
```

`impl/SettlementDomainServiceImpl.java`:

```java
package com.hireai.domain.biz.wallet.service.impl;

import com.hireai.domain.biz.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.service.SettlementDomainService;
import com.hireai.domain.biz.wallet.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

public class SettlementDomainServiceImpl implements SettlementDomainService {

    @Override
    public SettlementBreakdown settleAcceptance(WalletModel clientWallet, WalletModel builderWallet,
                                                Money budget, UUID taskId, String correlationId) {
        Money commission = SettlementPolicy.commissionOn(budget);
        Money net = budget.subtract(commission);

        clientWallet.release(net, taskId, LedgerEntryType.PAYOUT, correlationId);
        // Tiny budgets can round the commission to zero; release() requires a positive amount.
        if (commission.isPositive()) {
            clientWallet.release(commission, taskId, LedgerEntryType.COMMISSION, correlationId);
        }
        builderWallet.credit(net, taskId, LedgerEntryType.PAYOUT, correlationId);
        return new SettlementBreakdown(net, commission);
    }

    @Override
    public void settleRejection(WalletModel clientWallet, Money budget, UUID taskId, String correlationId) {
        clientWallet.refund(budget, taskId, correlationId);
    }
}
```

Register the bean in `DomainServiceConfig` (add alongside the existing wallet beans):

```java
@Bean
public SettlementDomainService settlementDomainService() {
    return new SettlementDomainServiceImpl();
}
```

(with imports `com.hireai.domain.biz.wallet.service.SettlementDomainService` and `...impl.SettlementDomainServiceImpl`.)

- [ ] **Step 4: Run the tests**

Run: `mvn -f backend/pom.xml -B test -Dtest=SettlementDomainServiceImplTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/wallet backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java backend/src/test/java/com/hireai/domain/biz/wallet
git commit -m "feat: deterministic settlement domain service (15% commission policy)"
```

---

### Task 4: `AgentRepository.findOwnerByVersionId`

**Files:**
- Modify: `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentRepository.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentVersionJpaRepository.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentRepositoryImpl.java`

(Exercised end-to-end by the Task 7 integration test; no isolated unit test for a pure query method.)

- [ ] **Step 1: Add to the domain interface**

```java
/**
 * Owner (builder user id) of the agent that owns this version. Deliberately NO status
 * filter — settlement must resolve the payee even if the agent was deactivated after
 * executing the task.
 */
Optional<UUID> findOwnerByVersionId(UUID agentVersionId);
```

- [ ] **Step 2: Add the query to `AgentVersionJpaRepository`**

Follow the existing native-query style in that interface:

```java
@Query(value = """
        SELECT a.owner_id
        FROM agent_versions v
        JOIN agents a ON a.id = v.agent_id
        WHERE v.id = :versionId
        """, nativeQuery = true)
Optional<UUID> findOwnerByVersionId(@Param("versionId") UUID versionId);
```

- [ ] **Step 3: Delegate in `AgentRepositoryImpl`**

```java
@Override
public Optional<UUID> findOwnerByVersionId(UUID agentVersionId) {
    return versionJpa.findOwnerByVersionId(agentVersionId);
}
```

(Match the class's existing `Optional` import style — it uses `java.util.Optional` fully qualified in one spot; prefer adding the import.)

- [ ] **Step 4: Compile + run suite**

Run: `mvn -f backend/pom.xml -B test`
Expected: ALL PASS (no behaviour change yet).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentRepository.java backend/src/main/java/com/hireai/infrastructure/repository/agent
git commit -m "feat: resolve agent owner by version id for settlement payee lookup"
```

---

### Task 5: `TaskReviewAppService` (orchestration)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/task/TaskReviewAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/task/impl/TaskReviewAppServiceImpl.java`
- Test (create): `backend/src/test/java/com/hireai/application/biz/task/impl/TaskReviewAppServiceImplTest.java`

- [ ] **Step 1: Write the failing tests** (plain Mockito — no Spring context)

```java
package com.hireai.application.biz.task.impl;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.biz.wallet.service.SettlementDomainService;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReviewAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock AgentRepository agentRepository;
    @Mock WalletRepository walletRepository;
    @Mock SettlementDomainService settlementDomainService;

    TaskReviewAppServiceImpl service;

    final UUID clientId = UUID.randomUUID();
    final UUID builderId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskReviewAppServiceImpl(
                taskRepository, agentRepository, walletRepository, settlementDomainService);
    }

    private TaskModel resultReceivedTask() {
        TaskModel t = TaskModel.submit(clientId, "t", "d", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "c")
                .assignAndQueue(versionId).markExecuting();
        return t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()));
    }

    @Test
    void acceptSettlesAndSavesTaskAndBothWallets() {
        TaskModel task = resultReceivedTask();
        WalletModel clientWallet = WalletModel.openFor(clientId);
        WalletModel builderWallet = WalletModel.openFor(builderId);
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.findByUserId(builderId)).thenReturn(Optional.of(builderWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(eq(clientWallet), eq(builderWallet),
                eq(Money.of("20.00")), eq(task.id()), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.accept(task.id(), clientId);

        ArgumentCaptor<TaskModel> saved = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
        verify(walletRepository, times(2)).save(any());
    }

    @Test
    void acceptOpensBuilderWalletWhenAbsent() {
        TaskModel task = resultReceivedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(WalletModel.openFor(clientId)));
        when(walletRepository.findByUserId(builderId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(any(), any(), any(), any(), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.accept(task.id(), clientId);

        // three saves: opening the builder wallet, then client + builder after settlement
        verify(walletRepository, times(3)).save(any());
    }

    @Test
    void selfAcceptUsesOneWalletAndSavesItOnce() {
        TaskModel task = resultReceivedTask();
        WalletModel shared = WalletModel.openFor(clientId);
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(clientId));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(shared));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(eq(shared), eq(shared), any(), any(), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.accept(task.id(), clientId);

        verify(walletRepository, times(1)).save(any());
    }

    @Test
    void rejectRefundsAndNeverTouchesTheBuilder() {
        TaskModel task = resultReceivedTask();
        WalletModel clientWallet = WalletModel.openFor(clientId);
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(task.id(), clientId, "not good");

        verify(settlementDomainService).settleRejection(eq(clientWallet), eq(Money.of("20.00")),
                eq(task.id()), any());
        verify(agentRepository, never()).findOwnerByVersionId(any());
        ArgumentCaptor<TaskModel> saved = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().rejectionReason()).isEqualTo("not good");
    }

    @Test
    void nonOwnerGetsNotFound() {
        TaskModel task = resultReceivedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.accept(task.id(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskReviewAppServiceImplTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`TaskReviewAppService.java`:

```java
package com.hireai.application.biz.task;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Client review of a RESULT_RECEIVED task: accept (escrow -> builder payout net of
 * commission) or reject (escrow -> full refund). Settlement is synchronous and atomic
 * with the task transition; the TaskModel state guard makes it exactly-once.
 */
@Validated
public interface TaskReviewAppService {

    UUID accept(@NonNull UUID taskId, @NonNull UUID clientId);

    UUID reject(@NonNull UUID taskId, @NonNull UUID clientId, String reason);
}
```

`impl/TaskReviewAppServiceImpl.java`:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.biz.wallet.service.SettlementDomainService;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskReviewAppServiceImpl implements TaskReviewAppService {

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final WalletRepository walletRepository;
    private final SettlementDomainService settlementDomainService;

    @Override
    public UUID accept(UUID taskId, UUID clientId) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.accept(); // state guard: only RESULT_RECEIVED; exactly-once

        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));

        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        String correlationId = "settle-" + taskId;
        SettlementBreakdown breakdown = settlementDomainService.settleAcceptance(
                clientWallet, builderWallet, task.budget(), taskId, correlationId);

        taskRepository.save(resolved);
        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        log.info("Task {} accepted by client {}; payout {} to builder {}, commission {}",
                taskId, clientId, breakdown.net(), builderId, breakdown.commission());
        return taskId;
    }

    @Override
    public UUID reject(UUID taskId, UUID clientId, String reason) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.reject(reason); // state guard: only RESULT_RECEIVED

        WalletModel clientWallet = requireWallet(clientId);
        settlementDomainService.settleRejection(clientWallet, task.budget(), taskId, "settle-" + taskId);

        taskRepository.save(resolved);
        walletRepository.save(clientWallet);
        log.info("Task {} rejected by client {}; budget {} refunded", taskId, clientId, task.budget());
        return taskId;
    }

    /** Ownership check (Invariant #5): a foreign task is indistinguishable from a missing one. */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

    private WalletModel requireWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }

    private WalletModel loadOrOpen(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(WalletModel.openFor(userId)));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskReviewAppServiceImplTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/application/biz/task backend/src/test/java/com/hireai/application/biz/task
git commit -m "feat: TaskReviewAppService — atomic accept/reject orchestration"
```

---

### Task 6: HTTP surface — accept/reject endpoints + DTO settlement fields

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/task/dto/RejectTaskRequest.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/task/dto/TaskDTO.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/task/converter/TaskModel2DTOConverter.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/task/TaskController.java`
- Test (modify): `backend/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java`

- [ ] **Step 1: Write the failing slice tests** (append to `TaskControllerTest`; add `@MockBean TaskReviewAppService taskReviewAppService;` and import `com.hireai.application.biz.task.TaskReviewAppService`, `com.hireai.domain.biz.task.enums.OutputFormat` if missing)

```java
    private TaskModel resolvedTask(UUID clientId, boolean accepted) {
        TaskModel t = TaskModel.submit(clientId, "title", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
        t = t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()));
        return accepted ? t.accept() : t.reject("not what I asked");
    }

    @Test
    void acceptReturnsResolvedTaskWithSettlementAmounts() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.accept(eq(taskId), eq(clientId))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, true));

        mockMvc.perform(post("/api/tasks/{id}/accept", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolution").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.payoutAmount").value(17.00))
                .andExpect(jsonPath("$.data.commissionAmount").value(3.00))
                .andExpect(jsonPath("$.data.refundAmount").doesNotExist());
    }

    @Test
    void rejectPassesReasonAndReturnsRefundAmount() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.reject(eq(taskId), eq(clientId), eq("not what I asked"))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, false));

        mockMvc.perform(post("/api/tasks/{id}/reject", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not what I asked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("REJECTED"))
                .andExpect(jsonPath("$.data.refundAmount").value(20.00))
                .andExpect(jsonPath("$.data.rejectionReason").value("not what I asked"));
    }

    @Test
    void rejectWithoutBodyIsAccepted() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.reject(eq(taskId), eq(clientId), eq(null))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, false));

        mockMvc.perform(post("/api/tasks/{id}/reject", taskId))
                .andExpect(status().isOk());
    }

    @Test
    void acceptOnNonReviewableStateMapsTo409() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.accept(eq(taskId), eq(clientId)))
                .thenThrow(new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Illegal transition accept from RESOLVED"));

        mockMvc.perform(post("/api/tasks/{id}/accept", taskId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_RULE_VIOLATION"));
    }

    @Test
    void rejectReasonOver500CharsIs400() throws Exception {
        UUID clientId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);

        mockMvc.perform(post("/api/tasks/{id}/reject", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
```

NOTE: existing tests in this file construct `TaskModel` via the 11-arg constructor or factories — leave them untouched. If the slice test needs CSRF handling it would already show in the existing POST tests; mirror exactly what `returns200...` POST tests do (the existing `@WithMockUser` + SecurityConfig import covers it).

- [ ] **Step 2: Run to verify failure**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskControllerTest`
Expected: COMPILATION ERROR (`TaskReviewAppService`, `resolution` JSON path / DTO fields missing).

- [ ] **Step 3: Implement the DTO + converter changes**

`RejectTaskRequest.java`:

```java
package com.hireai.controller.biz.task.dto;

import jakarta.validation.constraints.Size;

/** Optional rejection context from the client. */
public record RejectTaskRequest(@Size(max = 500) String reason) {
}
```

`TaskDTO.java` — append six fields (all nullable; Jackson omits nothing by default, but the tests use `doesNotExist()` for absent amounts — so configure the record to leave them `null` and add `@JsonInclude`):

```java
package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbound HTTP DTO for a task. No domain types leak across the boundary. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDTO(
        UUID id,
        UUID clientId,
        String title,
        String description,
        BigDecimal budget,
        String status,
        OutputSpecDTO outputSpec,
        Instant createdAt,
        String resolution,
        Instant resolvedAt,
        String rejectionReason,
        BigDecimal payoutAmount,
        BigDecimal commissionAmount,
        BigDecimal refundAmount
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
```

`TaskModel2DTOConverter.java` — compute display amounts server-side from `SettlementPolicy` (the single home of the rate):

```java
package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.wallet.service.SettlementPolicy;

import java.math.BigDecimal;

/**
 * Explicit, hand-written converter from the Task domain model to its outbound DTO.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 * Settlement display amounts are derived from SettlementPolicy here so the commission
 * rate lives in exactly one place; the ledger remains the record of truth.
 */
public final class TaskModel2DTOConverter {

    private TaskModel2DTOConverter() {
    }

    public static TaskDTO toDTO(TaskModel task) {
        OutputSpec spec = task.outputSpec();
        BigDecimal payout = null;
        BigDecimal commission = null;
        BigDecimal refund = null;
        if (task.resolution() == TaskResolution.ACCEPTED) {
            payout = SettlementPolicy.netOf(task.budget()).value();
            commission = SettlementPolicy.commissionOn(task.budget()).value();
        } else if (task.resolution() == TaskResolution.REJECTED) {
            refund = task.budget().value();
        }
        return new TaskDTO(
                task.id(),
                task.clientId(),
                task.title(),
                task.description(),
                task.budget().value(),
                task.status().name(),
                new TaskDTO.OutputSpecDTO(spec.format().name(), spec.schema(), spec.acceptanceCriteria()),
                task.createdAt(),
                task.resolution() == null ? null : task.resolution().name(),
                task.resolvedAt(),
                task.rejectionReason(),
                payout,
                commission,
                refund);
    }
}
```

- [ ] **Step 4: Add the endpoints to `TaskController`**

Inject `TaskReviewAppService reviewAppService` (constructor + field, matching the existing style) and add:

```java
@PostMapping("/{id}/accept")
public WebResult<TaskDTO> accept(@PathVariable("id") UUID id) {
    UUID clientId = currentUser.currentUserId();
    reviewAppService.accept(id, clientId);
    return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId)));
}

@PostMapping("/{id}/reject")
public WebResult<TaskDTO> reject(@PathVariable("id") UUID id,
                                 @Valid @RequestBody(required = false) RejectTaskRequest request) {
    UUID clientId = currentUser.currentUserId();
    reviewAppService.reject(id, clientId, request == null ? null : request.reason());
    return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId)));
}
```

(new imports: `TaskReviewAppService`, `RejectTaskRequest`.)

- [ ] **Step 5: Run the slice tests, then the full suite**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskControllerTest`
Expected: ALL PASS.
Run: `mvn -f backend/pom.xml -B test`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/biz/task backend/src/test/java/com/hireai/controller/biz/task
git commit -m "feat: accept/reject endpoints with settlement display amounts"
```

---

### Task 7: Integration test — the full money journey

**Files:**
- Create: `backend/src/test/java/com/hireai/task/TaskSettlementIntegrationTest.java`

No RabbitMQ container needed: the test never calls the submit app service (no domain event → no routing → no publish). It builds the task via domain transitions and saves through the repository, exactly like settlement will find it in production.

- [ ] **Step 1: Write the integration test**

```java
package com.hireai.task;

import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.wallet.WalletReadAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletLedgerQuery;
import com.hireai.domain.shared.exception.DomainException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end settlement against real Postgres (Flyway V1–V9, append-only ledger triggers):
 * freeze → accept → 85/15 split with three ledger entries sharing one correlation id;
 * freeze → reject → full refund; the exactly-once and ownership guards.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class TaskSettlementIntegrationTest {

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

    @Autowired TaskReviewAppService reviewAppService;
    @Autowired TaskRepository taskRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired WalletReadAppService walletRead;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, ?)",
                id, id + "@test.local", role);
        return id;
    }

    /** Seed an agent + version owned by {@code builderId}; returns the version id. */
    private UUID newAgentVersion(UUID builderId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, 'IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url, max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return versionId;
    }

    /** A RESULT_RECEIVED task with its budget already frozen in the client's wallet. */
    private TaskModel seedReviewableTask(UUID clientId, UUID versionId, String budget) {
        TaskModel task = TaskModel.submit(clientId, "settle me", "desc",
                        Money.of(budget), new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(versionId).markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()));
        taskRepository.save(task);
        walletWrite.topUp(clientId, Money.of("100.00"), "setup-topup");
        walletWrite.freeze(clientId, Money.of(budget), task.id(), "setup-freeze");
        return task;
    }

    @Test
    void acceptSettlesEscrowToBuilderNetOfCommission() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder), "20.00");

        reviewAppService.accept(task.id(), client);

        WalletModel clientWallet = walletRead.getByUserId(client);
        assertThat(clientWallet.available()).isEqualTo(Money.of("80.00"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);

        WalletModel builderWallet = walletRead.getByUserId(builder); // opened on first payout
        assertThat(builderWallet.available()).isEqualTo(Money.of("17.00"));

        List<LedgerEntryModel> clientLedger = walletRead.getLedger(client, WalletLedgerQuery.firstPage());
        assertThat(clientLedger).extracting(LedgerEntryModel::type)
                .contains(LedgerEntryType.PAYOUT, LedgerEntryType.COMMISSION);
        List<LedgerEntryModel> builderLedger = walletRead.getLedger(builder, WalletLedgerQuery.firstPage());
        assertThat(builderLedger).extracting(LedgerEntryModel::type)
                .containsExactly(LedgerEntryType.PAYOUT);

        TaskModel resolved = taskRepository.findById(task.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.ACCEPTED);
        assertThat(resolved.resolvedAt()).isNotNull();
    }

    @Test
    void rejectRefundsTheFullBudgetAndStoresTheReason() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder), "20.00");

        reviewAppService.reject(task.id(), client, "wrong format");

        WalletModel clientWallet = walletRead.getByUserId(client);
        assertThat(clientWallet.available()).isEqualTo(Money.of("100.00"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);

        TaskModel resolved = taskRepository.findById(task.id()).orElseThrow();
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(resolved.rejectionReason()).isEqualTo("wrong format");
    }

    @Test
    void secondResolutionAttemptIsRejected() {
        UUID client = newUser("CLIENT");
        TaskModel task = seedReviewableTask(client, newAgentVersion(newUser("BUILDER")), "20.00");
        reviewAppService.accept(task.id(), client);

        assertThatThrownBy(() -> reviewAppService.accept(task.id(), client))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
        assertThatThrownBy(() -> reviewAppService.reject(task.id(), client, null))
                .isInstanceOf(DomainException.class);

        // money moved exactly once
        assertThat(walletRead.getByUserId(client).available()).isEqualTo(Money.of("80.00"));
    }

    @Test
    void nonOwnerCannotResolve() {
        UUID client = newUser("CLIENT");
        UUID stranger = newUser("CLIENT");
        walletWrite.topUp(stranger, Money.of("1.00"), "noop"); // stranger has a wallet; gate must trip first
        TaskModel task = seedReviewableTask(client, newAgentVersion(newUser("BUILDER")), "20.00");

        assertThatThrownBy(() -> reviewAppService.accept(task.id(), stranger))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }
}
```

- [ ] **Step 2: Run it (requires Docker)**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskSettlementIntegrationTest`
Expected: ALL PASS with Docker running (4 tests). Without Docker: SKIPPED — if so, say so explicitly in your report; do not claim green.

- [ ] **Step 3: Run the whole backend suite**

Run: `mvn -f backend/pom.xml -B test`
Expected: ALL PASS, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/hireai/task/TaskSettlementIntegrationTest.java
git commit -m "test: settlement integration journey — accept payout, reject refund, guards"
```

---

### Task 8: Frontend — review actions on the task page

**Files:**
- Modify: `frontend/lib/types.ts`
- Create: `frontend/components/ResultReviewBar.tsx`
- Modify: `frontend/app/client/tasks/[id]/page.tsx`
- Modify: `frontend/test/msw/handlers.ts`
- Test (create): `frontend/test/resultReview.test.tsx` (follow the naming convention of existing files in `frontend/test/`)

Read `docs/details/frontend.md` first. Tailwind tokens: lime accent = `text-accent`/`bg-accent`, red = `text-red`, borders `border-line`. UI kit: `Button`, `Card`, `Badge`, `Input` from `@/components/ui`. Check `frontend/components/ui/Button.tsx` for its actual variant prop API before using it.

- [ ] **Step 1: Extend the types**

In `frontend/lib/types.ts`, add next to `TaskStatus`:

```ts
/** Client review outcome (task/enums/TaskResolution.java). */
export type TaskResolution = "ACCEPTED" | "REJECTED";
```

and extend `TaskDTO` (fields are absent from JSON when null — type them optional):

```ts
export interface TaskDTO {
  id: string;
  clientId: string;
  title: string;
  description: string;
  budget: number;
  status: TaskStatus;
  outputSpec: OutputSpecDTO;
  createdAt: string;
  resolution?: TaskResolution | null;
  resolvedAt?: string | null;
  rejectionReason?: string | null;
  payoutAmount?: number | null;
  commissionAmount?: number | null;
  refundAmount?: number | null;
}
```

- [ ] **Step 2: Write the failing component test**

`frontend/test/resultReview.test.tsx` — mirror the setup style of the existing task-detail test in `frontend/test/` (same MSW server import, same render helpers; check an existing test file and copy its scaffolding exactly):

```tsx
import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ResultReviewBar } from "@/components/ResultReviewBar";
import type { TaskDTO } from "@/lib/types";

describe("ResultReviewBar", () => {
  it("accepts the result and reports the resolved task", async () => {
    const user = userEvent.setup();
    let resolved: TaskDTO | null = null;
    render(<ResultReviewBar taskId="t-1" onResolved={(t) => (resolved = t)} />);

    await user.click(screen.getByRole("button", { name: /accept result/i }));

    await waitFor(() => expect(resolved).not.toBeNull());
    expect(resolved!.resolution).toBe("ACCEPTED");
  });

  it("expands a reason field before rejecting", async () => {
    const user = userEvent.setup();
    let resolved: TaskDTO | null = null;
    render(<ResultReviewBar taskId="t-1" onResolved={(t) => (resolved = t)} />);

    await user.click(screen.getByRole("button", { name: /^reject$/i }));
    // the bar expands instead of firing immediately
    await user.type(screen.getByLabelText(/reason/i), "wrong format");
    await user.click(screen.getByRole("button", { name: /confirm reject/i }));

    await waitFor(() => expect(resolved).not.toBeNull());
    expect(resolved!.resolution).toBe("REJECTED");
    expect(resolved!.rejectionReason).toBe("wrong format");
  });
});
```

- [ ] **Step 3: Add MSW handlers**

In `frontend/test/msw/handlers.ts`, add to the handler array (reusing the file's `ok`/`fail` helpers; base the task object on the existing `/api/tasks/:id` fixture):

```ts
  http.post("*/api/tasks/:id/accept", ({ params }) =>
    ok({
      id: params.id,
      clientId: "u-1",
      title: "Summarise Q2 report",
      description: "Summarise it",
      budget: 30,
      status: "RESOLVED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
      resolution: "ACCEPTED",
      resolvedAt: "2026-06-06T10:10:00Z",
      payoutAmount: 25.5,
      commissionAmount: 4.5,
    }),
  ),
  http.post("*/api/tasks/:id/reject", async ({ params, request }) => {
    const body = (await request.json().catch(() => null)) as { reason?: string } | null;
    return ok({
      id: params.id,
      clientId: "u-1",
      title: "Summarise Q2 report",
      description: "Summarise it",
      budget: 30,
      status: "RESOLVED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
      resolution: "REJECTED",
      resolvedAt: "2026-06-06T10:10:00Z",
      rejectionReason: body?.reason ?? null,
      refundAmount: 30,
    });
  }),
```

- [ ] **Step 4: Run the test to verify it fails**

Run (in `frontend/`): `npx vitest run test/resultReview.test.tsx`
Expected: FAIL — `ResultReviewBar` does not exist.

- [ ] **Step 5: Implement `ResultReviewBar`**

`frontend/components/ResultReviewBar.tsx`:

```tsx
"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { TaskDTO } from "@/lib/types";
import { Button } from "@/components/ui";

interface Props {
  taskId: string;
  /** Called with the RESOLVED task returned by the backend. */
  onResolved: (task: TaskDTO) => void;
}

/**
 * Accept/Reject action bar shown under the result panel while the task is RESULT_RECEIVED.
 * Accept settles escrow to the builder (net of commission); Reject expands an optional
 * reason field first, then refunds the full budget.
 */
export function ResultReviewBar({ taskId, onResolved }: Props) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function resolve(path: "accept" | "reject") {
    setBusy(true);
    setError(null);
    try {
      const body = path === "reject" && reason.trim() ? { reason: reason.trim() } : undefined;
      const task = await api<TaskDTO>(`/tasks/${taskId}/${path}`, {
        method: "POST",
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      onResolved(task);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit your decision");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="Review result" className="space-y-3 border-t border-line pt-5">
      <p className="eyebrow">Your decision</p>
      <p className="text-sm text-muted">
        Accepting pays the builder out of escrow (15% platform commission). Rejecting refunds
        your full budget.
      </p>
      {error && (
        <p role="alert" className="font-mono text-xs text-red">
          {error}
        </p>
      )}
      {!rejecting ? (
        <div className="flex items-center gap-3">
          <Button onClick={() => resolve("accept")} disabled={busy}>
            Accept result ▸
          </Button>
          <Button variant="ghost" onClick={() => setRejecting(true)} disabled={busy}>
            Reject
          </Button>
        </div>
      ) : (
        <div className="space-y-3">
          <label htmlFor="reject-reason" className="font-mono text-xs uppercase tracking-wider text-dim">
            Reason (optional)
          </label>
          <textarea
            id="reject-reason"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            className="w-full rounded-md border border-line bg-base p-3 font-mono text-xs text-fg"
          />
          <div className="flex items-center gap-3">
            <Button variant="danger" onClick={() => resolve("reject")} disabled={busy}>
              Confirm reject
            </Button>
            <Button variant="ghost" onClick={() => setRejecting(false)} disabled={busy}>
              Back
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
```

IMPORTANT: check `frontend/components/ui/Button.tsx` for the real variant names (`ghost`/`danger` may differ — e.g. `secondary`/`subtle`). Use what exists; do NOT add new variants unless none fits, and if you must, follow the component's existing pattern.

- [ ] **Step 6: Run the component test**

Run (in `frontend/`): `npx vitest run test/resultReview.test.tsx`
Expected: PASS.

- [ ] **Step 7: Wire into the task page**

In `frontend/app/client/tasks/[id]/page.tsx`:

1. Import: `import { ResultReviewBar } from "@/components/ResultReviewBar";`
2. Inside the result `<section>` (after the `resultUrl` link, still within the section), render the bar while reviewable, and the settled summary once resolved:

```tsx
{task.status === "RESULT_RECEIVED" && (
  <ResultReviewBar taskId={task.id} onResolved={setTask} />
)}
```

3. Add a settled summary directly after the result section (sibling, inside the Card):

```tsx
{task.resolution && (
  <section aria-live="polite" className="space-y-1 border-t border-line pt-5">
    <p className="eyebrow">Settled</p>
    {task.resolution === "ACCEPTED" ? (
      <p className="font-mono text-sm text-accent">
        {task.payoutAmount} cr paid to the builder · {task.commissionAmount} cr platform commission
      </p>
    ) : (
      <p className="font-mono text-sm text-red">
        {task.refundAmount} cr refunded to your wallet
        {task.rejectionReason ? ` — “${task.rejectionReason}”` : ""}
      </p>
    )}
  </section>
)}
```

4. The escrow line in the header should stop saying "in escrow" once resolved. Replace the existing `cr in escrow` fragment with:

```tsx
<span className="tabular text-accent">{task.budget}</span>{" "}
cr {task.resolution ? "settled" : "in escrow"}
```

5. The badge should surface the resolution. Replace `<Badge status={task.status}>{task.status}</Badge>` with:

```tsx
<Badge status={task.status}>
  {task.resolution ? `RESOLVED · ${task.resolution}` : task.status}
</Badge>
```

Note on polling: the existing effect stops polling once the result is fetched, so `setTask` from `onResolved` is the only update after settling — no race.

6. A task loaded fresh in `RESOLVED` state must still show its result payload. In the effect, widen the result-fetch condition so it also fires for already-resolved tasks:

```tsx
if ((t.status === "RESULT_RECEIVED" || t.status === "RESOLVED") && !resultRef.current) {
```

(the existing 404-tolerant logic handles the rest; `TERMINAL` still stops the polling.)

7. **My-tasks list** (`frontend/app/client/tasks/page.tsx`): RESOLVED rows must show the resolution. Find where the list rows render their status `Badge` and apply the same label pattern as the detail page:

```tsx
<Badge status={t.status}>
  {t.resolution ? `RESOLVED · ${t.resolution}` : t.status}
</Badge>
```

(adjust the variable name to whatever the list's map callback uses; touch nothing else in that page.)

- [ ] **Step 8: Run the full frontend suite + typecheck**

Run (in `frontend/`): `npx vitest run` then `npx tsc --noEmit`
Expected: ALL PASS / no type errors. If other tests assert on the task page DOM, update their expectations only if the assertion text genuinely changed (e.g. "in escrow").

- [ ] **Step 9: Commit**

```bash
git add frontend/lib/types.ts frontend/components/ResultReviewBar.tsx "frontend/app/client/tasks/[id]/page.tsx" frontend/test
git commit -m "feat: accept/reject review bar + settled summary on the task page"
```

---

### Task 9: Docs + final verification

**Files:**
- Modify: `docs/details/data-model.md`
- Modify: `CLAUDE.md`
- Modify: `docs/details/frontend.md`
- Modify: `docs/details/demo-runbook.md`

- [ ] **Step 1: Update `docs/details/data-model.md`**

- In the tables section, extend the `tasks` bullet with: `resolution (ACCEPTED/REJECTED, V9), resolved_at, rejection_reason — set exactly once by client review`.
- In "Settlement rules", mark the 15% commission rule as implemented by `SettlementPolicy`/`SettlementDomainService` and note: accept → `PAYOUT`(net) + `COMMISSION` from client escrow + `PAYOUT` credit to builder; reject → `REFUND`. Migration range references change from V1–V8 to V1–V9 if mentioned.

- [ ] **Step 2: Update `CLAUDE.md`**

- Backend "Built" list: add "client review + settlement (accept → 85/15 payout, reject → refund, `V9`, `POST /api/tasks/{id}/accept|reject`)".
- Update Flyway range `V1`–`V8` → `V1`–`V9` and the test-count figures to the real numbers from the final suite run.
- "Pending": Module 4 narrows to "automated output-spec validation + disputes"; Module 5 narrows to "reputation events + earned reviews" (settlement core is now done).

- [ ] **Step 3: Update `docs/details/frontend.md`**

In the route map/page behaviour section for `/client/tasks/[id]`: add the accept/reject review bar at `RESULT_RECEIVED`, the settled summary, and the `ResultReviewBar` component.

- [ ] **Step 4: Update `docs/details/demo-runbook.md`**

Demo-flow step 3 gains: "→ **Accept** the result: the builder's wallet receives 85% of the budget (log in as the builder and check the wallet/stats), or **Reject** for a full refund."

- [ ] **Step 5: Full verification**

```bash
mvn -f backend/pom.xml -B test
```
Expected: ALL PASS (report the real total).

```bash
cd frontend; npx vitest run; npx tsc --noEmit; npm run build; cd ..
```
Expected: ALL PASS, no type errors, clean build. (Return to the repo root afterwards — the working directory persists.)

- [ ] **Step 6: Commit**

```bash
git add docs CLAUDE.md
git commit -m "docs: settlement slice — V9, accept/reject endpoints, demo flow"
```

---

## Out of scope (do NOT build)

Disputes/arbitration, automated output-spec validation, `PENDING_REVIEW` usage, auto-refund on `FAILED`/`TIMED_OUT`, auto-accept timeout, reputation events, earned reviews, builder-stats earned-amount rework, platform wallet. If you notice these gaps, they are known and deferred — do not "helpfully" add them.
