# Capability Re-division — Slice 3: Ledger / Settlement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Move the settlement classes into a `ledger.settlement` package, promote Settlement to a persisted aggregate (`SettlementModel` + `settlements` table `V14`), and move the accept/reject money orchestration out of `TaskReviewAppService` into a `SettlementWriteAppService` — keeping the suite green.

**Architecture:** Slice 3 of the incremental-strangler refactor (spec: `docs/superpowers/specs/2026-06-29-backend-capability-redivision-design.md`). The money movement stays in the append-only ledger (the source of truth); the new `SettlementModel` adds an auditable per-task settlement record. `SettlementPolicy` stays the pure 85/15 source. `TaskReviewAppService` keeps the task-state + ownership/lock concern and delegates the money concern to the new ledger app service (one transaction, REQUIRED propagation).

**Tech Stack:** Java 21, Spring Boot 3.x, COLA reactor, JPA/Hibernate, Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (auto-skip without Docker).

## Global Constraints

- **Suite green at every commit:** `mvn -f backend/pom.xml -q -B test` → BUILD SUCCESS, 0 failures. Docker is unavailable locally, so Testcontainers `*IntegrationTest`s auto-skip; the settlement money path is end-to-end validated by CI (`TaskSettlementIntegrationTest` + the new settlement persistence test).
- **COLA layering compiler-enforced;** `hireai-domain`/`hireai-utility` carry no Spring. `SettlementModel`, `SettlementType`, `SettlementRepository` are plain Java.
- **Money is `NUMERIC`; settlement amounts persisted but the ledger remains the money truth.** Earnings stay derived (slice-out of scope).
- **`task_id` is a soft reference (no cross-context FK)** — consistent with `ledger_entries.related_task_id` and `tasks.agent_version_id`, keeping Ledger independent of Task at the schema level.
- **Additive migration only:** new `V14`; `V1`–`V13` immutable.
- **Stage ONLY `backend/`** — never `git add -A`. No `Co-Authored-By`. Windows / Git Bash.

## File Structure

After this slice, the Ledger/settlement half owns:

```
hireai-domain      com.hireai.domain.biz.ledger.settlement
                     model/      SettlementModel (root)
                     enums/      SettlementType (ACCEPT/REJECT/SPLIT)
                     repository/ SettlementRepository
                     service/    SettlementPolicy, SettlementDomainService(+impl/)   [moved from ledger.wallet]
                     info/       SettlementBreakdown                                  [moved from ledger.wallet]
hireai-application com.hireai.application.biz.ledger.settlement
                     SettlementWriteAppService(+impl/)   [owns the money orchestration]
hireai-repository  com.hireai.infrastructure.repository.ledger.settlement
                     SettlementDO, SettlementJpaRepository, SettlementRepositoryImpl
```

New migration: `backend/hireai-main/src/main/resources/db/migration/V14__settlements.sql`.
Refactored: `application.biz.task.impl.TaskReviewAppServiceImpl` (delegates money to the new service); `application.config.DomainServiceConfig` (settlement bean import path).

---

### Task 1: Move settlement classes `ledger.wallet` → `ledger.settlement`

Targeted move of four classes (and their test) out of `ledger.wallet.{service,info}` into `ledger.settlement.{service,info}`, leaving the wallet domain services (`WalletTopUp`/`WalletFreeze`) where they are. Behavior-identical; the suite is the test.

**Files moved:**
- `domain/.../ledger/wallet/service/SettlementPolicy.java` → `.../ledger/settlement/service/SettlementPolicy.java`
- `domain/.../ledger/wallet/service/SettlementDomainService.java` → `.../ledger/settlement/service/SettlementDomainService.java`
- `domain/.../ledger/wallet/service/impl/SettlementDomainServiceImpl.java` → `.../ledger/settlement/service/impl/SettlementDomainServiceImpl.java`
- `domain/.../ledger/wallet/info/SettlementBreakdown.java` → `.../ledger/settlement/info/SettlementBreakdown.java`
- test `hireai-main/.../domain/biz/ledger/wallet/service/SettlementDomainServiceImplTest.java` → `.../ledger/settlement/service/SettlementDomainServiceImplTest.java`

**Interfaces produced:** the four classes now live under `com.hireai.domain.biz.ledger.settlement.*`.

- [ ] **Step 1: Move the files**

```bash
cd backend
D=hireai-domain/src/main/java/com/hireai/domain/biz/ledger
mkdir -p $D/settlement/service/impl $D/settlement/info
git mv $D/wallet/service/SettlementPolicy.java            $D/settlement/service/SettlementPolicy.java
git mv $D/wallet/service/SettlementDomainService.java     $D/settlement/service/SettlementDomainService.java
git mv $D/wallet/service/impl/SettlementDomainServiceImpl.java $D/settlement/service/impl/SettlementDomainServiceImpl.java
git mv $D/wallet/info/SettlementBreakdown.java            $D/settlement/info/SettlementBreakdown.java
T=hireai-main/src/test/java/com/hireai/domain/biz/ledger
mkdir -p $T/settlement/service
git mv $T/wallet/service/SettlementDomainServiceImplTest.java $T/settlement/service/SettlementDomainServiceImplTest.java
```

- [ ] **Step 2: Rewrite the four class FQNs everywhere (imports + references)**

```bash
grep -rl --include='*.java' 'ledger\.wallet\.\(service\.SettlementPolicy\|service\.SettlementDomainService\|service\.impl\.SettlementDomainServiceImpl\|info\.SettlementBreakdown\)' backend \
  | xargs sed -i \
      -e 's/ledger\.wallet\.service\.impl\.SettlementDomainServiceImpl/ledger.settlement.service.impl.SettlementDomainServiceImpl/g' \
      -e 's/ledger\.wallet\.service\.SettlementDomainService/ledger.settlement.service.SettlementDomainService/g' \
      -e 's/ledger\.wallet\.service\.SettlementPolicy/ledger.settlement.service.SettlementPolicy/g' \
      -e 's/ledger\.wallet\.info\.SettlementBreakdown/ledger.settlement.info.SettlementBreakdown/g'
```

- [ ] **Step 3: Fix the package declarations of the four moved files (+ test)**

Each moved file still declares its old `ledger.wallet.*` package. Set them to the settlement package — apply to exactly these files:

```bash
cd backend
sed -i 's/^package com\.hireai\.domain\.biz\.ledger\.wallet\.service;/package com.hireai.domain.biz.ledger.settlement.service;/' \
  hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/service/SettlementPolicy.java \
  hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/service/SettlementDomainService.java
sed -i 's/^package com\.hireai\.domain\.biz\.ledger\.wallet\.service\.impl;/package com.hireai.domain.biz.ledger.settlement.service.impl;/' \
  hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/service/impl/SettlementDomainServiceImpl.java
sed -i 's/^package com\.hireai\.domain\.biz\.ledger\.wallet\.info;/package com.hireai.domain.biz.ledger.settlement.info;/' \
  hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/info/SettlementBreakdown.java
sed -i 's/^package com\.hireai\.domain\.biz\.ledger\.wallet\.service;/package com.hireai.domain.biz.ledger.settlement.service;/' \
  hireai-main/src/test/java/com/hireai/domain/biz/ledger/settlement/service/SettlementDomainServiceImplTest.java
```

- [ ] **Step 4: Verify + build + test**

```bash
grep -rn --include='*.java' -e 'ledger\.wallet\.service\.SettlementPolicy' -e 'ledger\.wallet\.service\.SettlementDomainService' -e 'ledger\.wallet\.info\.SettlementBreakdown' backend && echo "STALE REFS!" || echo "(clean)"
mvn -f backend/pom.xml -q -B test
```

Expected: `(clean)`, BUILD SUCCESS, 329 tests, 0 failures, 60 skipped.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor(ledger): move settlement classes into the ledger.settlement package"
```

---

### Task 2: Persisted `SettlementModel` aggregate + `settlements` table (V14)

Add the Settlement aggregate, its repository, JPA persistence, and the migration. Not wired into accept/reject yet — Task 3 does that. A Testcontainers test proves the save/load round-trip (CI-gated).

**Files:**
- Create: `domain/.../ledger/settlement/enums/SettlementType.java`
- Create: `domain/.../ledger/settlement/model/SettlementModel.java`
- Create: `domain/.../ledger/settlement/repository/SettlementRepository.java`
- Create: `hireai-repository/.../ledger/settlement/SettlementDO.java`
- Create: `hireai-repository/.../ledger/settlement/SettlementJpaRepository.java`
- Create: `hireai-repository/.../ledger/settlement/SettlementRepositoryImpl.java`
- Create: `backend/hireai-main/src/main/resources/db/migration/V14__settlements.sql`
- Create test: `hireai-main/src/test/java/com/hireai/ledger/SettlementRepositoryIntegrationTest.java` (mirror `WalletLedgerIntegrationTest` harness; CI-gated)

**Interfaces produced:**
- `SettlementType` enum: `ACCEPT`, `REJECT`, `SPLIT`.
- `SettlementModel` (record): `id, taskId, type, net, commission, createdAt`; factories `accepted(UUID taskId, Money net, Money commission)`, `rejected(UUID taskId)`.
- `SettlementRepository`: `SettlementModel save(SettlementModel)`, `Optional<SettlementModel> findByTaskId(UUID taskId)`.

- [ ] **Step 1: Migration**

Create `backend/hireai-main/src/main/resources/db/migration/V14__settlements.sql`:

```sql
-- V14: per-task settlement records. The append-only ledger remains the money truth;
-- this table is an auditable record of each settlement decision (accept / reject / split).
-- task_id is a soft reference (no cross-context FK) — Ledger stays independent of Task.
CREATE TABLE settlements (
    id           UUID PRIMARY KEY,
    task_id      UUID NOT NULL UNIQUE,
    type         TEXT NOT NULL CHECK (type IN ('ACCEPT', 'REJECT', 'SPLIT')),
    net          NUMERIC(14, 2) NOT NULL,
    commission   NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_create   TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: `SettlementType`**

```java
package com.hireai.domain.biz.ledger.settlement.enums;

/** How a task settled: full payout (ACCEPT), full refund (REJECT), or partial split (SPLIT, Module 4). */
public enum SettlementType {
    ACCEPT,
    REJECT,
    SPLIT
}
```

- [ ] **Step 3: `SettlementModel`**

```java
package com.hireai.domain.biz.ledger.settlement.model;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Settlement aggregate root: the auditable record of how one task settled. The money itself moves
 * through the append-only ledger (the source of truth); this records the decision + the split.
 * For REJECT, net and commission are zero (the client was fully refunded).
 */
public record SettlementModel(UUID id, UUID taskId, SettlementType type,
                              Money net, Money commission, Instant createdAt) {

    public static SettlementModel accepted(UUID taskId, Money net, Money commission) {
        return new SettlementModel(UUID.randomUUID(), taskId, SettlementType.ACCEPT, net, commission, Instant.now());
    }

    public static SettlementModel rejected(UUID taskId) {
        return new SettlementModel(UUID.randomUUID(), taskId, SettlementType.REJECT, Money.ZERO, Money.ZERO, Instant.now());
    }
}
```

- [ ] **Step 4: `SettlementRepository`**

```java
package com.hireai.domain.biz.ledger.settlement.repository;

import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the Settlement aggregate (one settlement per task). */
public interface SettlementRepository {

    SettlementModel save(SettlementModel settlement);

    Optional<SettlementModel> findByTaskId(UUID taskId);
}
```

- [ ] **Step 5: `SettlementDO` + `SettlementJpaRepository` + `SettlementRepositoryImpl`**

`SettlementDO.java`:

```java
package com.hireai.infrastructure.repository.ledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for a settlement row. */
@Entity
@Table(name = "settlements")
public class SettlementDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false, unique = true)
    private UUID taskId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "net", nullable = false)
    private BigDecimal net;

    @Column(name = "commission", nullable = false)
    private BigDecimal commission;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SettlementDO() {
    }

    public SettlementDO(UUID id, UUID taskId, String type, BigDecimal net,
                        BigDecimal commission, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.type = type;
        this.net = net;
        this.commission = commission;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getType() { return type; }
    public BigDecimal getNet() { return net; }
    public BigDecimal getCommission() { return commission; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`SettlementJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.ledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SettlementJpaRepository extends JpaRepository<SettlementDO, UUID> {
    Optional<SettlementDO> findByTaskId(UUID taskId);
}
```

`SettlementRepositoryImpl.java`:

```java
package com.hireai.infrastructure.repository.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link SettlementRepository}. */
@Repository
public class SettlementRepositoryImpl implements SettlementRepository {

    private final SettlementJpaRepository jpa;

    public SettlementRepositoryImpl(SettlementJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public SettlementModel save(SettlementModel s) {
        jpa.save(new SettlementDO(s.id(), s.taskId(), s.type().name(),
                s.net().value(), s.commission().value(), s.createdAt()));
        return s;
    }

    @Override
    public Optional<SettlementModel> findByTaskId(UUID taskId) {
        return jpa.findByTaskId(taskId).map(this::toModel);
    }

    private SettlementModel toModel(SettlementDO e) {
        return new SettlementModel(e.getId(), e.getTaskId(), SettlementType.valueOf(e.getType()),
                Money.of(e.getNet()), Money.of(e.getCommission()), e.getCreatedAt());
    }
}
```

- [ ] **Step 6: Persistence integration test (CI-gated)**

Read `hireai-main/src/test/java/com/hireai/ledger/WalletLedgerIntegrationTest.java` and match its Testcontainers harness. Create `hireai-main/src/test/java/com/hireai/ledger/SettlementRepositoryIntegrationTest.java`. The behavior under test (adapt boilerplate to the sibling):

```java
    @Test
    void savesAndFindsSettlementByTaskId() {
        UUID taskId = UUID.randomUUID();
        settlementRepository.save(SettlementModel.accepted(
                taskId, Money.of("17.00"), Money.of("3.00")));

        SettlementModel found = settlementRepository.findByTaskId(taskId).orElseThrow();
        assertThat(found.type()).isEqualTo(SettlementType.ACCEPT);
        assertThat(found.net()).isEqualTo(Money.of("17.00"));
        assertThat(found.commission()).isEqualTo(Money.of("3.00"));
    }
```

- [ ] **Step 7: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures. New integration test SKIPPED locally (no Docker).

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat(ledger): add the persisted Settlement aggregate (settlements, V14)"
```

---

### Task 3: Move accept/reject money orchestration into `SettlementWriteAppService`

Introduce a ledger app service that owns the wallet loading + settlement domain call + settlement-record persistence. `TaskReviewAppService` keeps the task-state + ownership/lock and delegates the money to it. Behavior preserved; one new behavior: a `SettlementModel` is persisted per settlement.

**Files:**
- Create: `application/.../ledger/settlement/SettlementWriteAppService.java`
- Create: `application/.../ledger/settlement/impl/SettlementWriteAppServiceImpl.java`
- Modify: `application/.../task/impl/TaskReviewAppServiceImpl.java` (delegate money; drop `WalletRepository`/`SettlementDomainService` deps)
- Modify test: `application/biz/task/impl/TaskReviewAppServiceImplTest.java` (mock `SettlementWriteAppService` instead of wallet/settlement)
- Create test: `hireai-main/src/test/java/com/hireai/application/biz/ledger/settlement/impl/SettlementWriteAppServiceImplTest.java`

**Interfaces produced:**
- `SettlementWriteAppService`: `SettlementBreakdown settleAccepted(UUID taskId, UUID clientId, UUID builderId, Money budget)`; `void settleRejected(UUID taskId, UUID clientId, Money budget)`.

- [ ] **Step 1: `SettlementWriteAppService` interface**

```java
package com.hireai.application.biz.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/** Owns the money side of a task review: moves escrowed credits and records the settlement. */
public interface SettlementWriteAppService {

    /** Accept: payout 85% to the builder + 15% commission; records an ACCEPT settlement. */
    SettlementBreakdown settleAccepted(UUID taskId, UUID clientId, UUID builderId, Money budget);

    /** Reject: full refund to the client; records a REJECT settlement. */
    void settleRejected(UUID taskId, UUID clientId, Money budget);
}
```

- [ ] **Step 2: `SettlementWriteAppServiceImpl`**

```java
package com.hireai.application.biz.ledger.settlement.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SettlementWriteAppServiceImpl implements SettlementWriteAppService {

    private final WalletRepository walletRepository;
    private final SettlementDomainService settlementDomainService;
    private final SettlementRepository settlementRepository;

    @Override
    public SettlementBreakdown settleAccepted(UUID taskId, UUID clientId, UUID builderId, Money budget) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        String correlationId = "settle-" + taskId;
        SettlementBreakdown breakdown = settlementDomainService.settleAcceptance(
                clientWallet, builderWallet, budget, taskId, correlationId);

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        settlementRepository.save(SettlementModel.accepted(taskId, breakdown.net(), breakdown.commission()));
        return breakdown;
    }

    @Override
    public void settleRejected(UUID taskId, UUID clientId, Money budget) {
        WalletModel clientWallet = requireWallet(clientId);
        settlementDomainService.settleRejection(clientWallet, budget, taskId, "settle-" + taskId);
        walletRepository.save(clientWallet);
        settlementRepository.save(SettlementModel.rejected(taskId));
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

- [ ] **Step 3: Refactor `TaskReviewAppServiceImpl` to delegate**

Replace the whole class body of `application/.../task/impl/TaskReviewAppServiceImpl.java` with:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.utility.exception.DomainException;
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
    private final SettlementWriteAppService settlementWriteAppService;

    @Override
    public UUID accept(UUID taskId, UUID clientId) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.accept(); // state guard: only RESULT_RECEIVED; exactly-once

        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));

        SettlementBreakdown breakdown =
                settlementWriteAppService.settleAccepted(taskId, clientId, builderId, task.budget());

        taskRepository.save(resolved);
        log.info("Task {} accepted by client {}; payout {} to builder {}, commission {}",
                taskId, clientId, breakdown.net(), builderId, breakdown.commission());
        return taskId;
    }

    @Override
    public UUID reject(UUID taskId, UUID clientId, String reason) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.reject(reason); // state guard: only RESULT_RECEIVED

        settlementWriteAppService.settleRejected(taskId, clientId, task.budget());

        taskRepository.save(resolved);
        log.info("Task {} rejected by client {}; budget {} refunded", taskId, clientId, task.budget());
        return taskId;
    }

    /**
     * Ownership check (Invariant #5): a foreign task is indistinguishable from a missing one.
     * Row-level lock so concurrent resolution attempts serialize (the loser sees RESOLVED and the
     * state guard throws).
     */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }
}
```

- [ ] **Step 4: Rework `TaskReviewAppServiceImplTest`**

The test currently mocks `WalletRepository` + `SettlementDomainService` and asserts wallet-save counts. Those concerns moved to `SettlementWriteAppService`. Rework it to mock `SettlementWriteAppService` and assert the delegation + task-state. Read the existing test first; replace its mocks/wiring so the constructor matches `new TaskReviewAppServiceImpl(taskRepository, agentRepository, settlementWriteAppService)` and the assertions become:
- `acceptSettlesAndSavesTask`: stub `settlementWriteAppService.settleAccepted(eq(task.id()), eq(clientId), eq(builderId), eq(Money.of("20.00")))` → `new SettlementBreakdown(Money.of("17.00"), Money.of("3.00"))` (import `SettlementBreakdown` from its NEW `ledger.settlement.info` package); assert the saved task status is `RESOLVED`; `verify(settlementWriteAppService).settleAccepted(...)`.
- `rejectRefundsAndNeverTouchesTheBuilder`: `verify(settlementWriteAppService).settleRejected(eq(task.id()), eq(clientId), eq(Money.of("20.00")))`; `verify(agentRepository, never()).findOwnerByVersionId(any())`; assert saved task `rejectionReason` == reason.
- `nonOwnerGetsNotFound`: unchanged (no settlement deps needed).
- Drop the wallet-open / wallet-save-count tests (`acceptOpensBuilderWalletWhenAbsent`, `selfAcceptUsesOneWalletAndSavesItOnce`) — that behavior is now tested in `SettlementWriteAppServiceImplTest` (Step 5).

- [ ] **Step 5: Add `SettlementWriteAppServiceImplTest`**

Cover the money orchestration that moved here (mocking `WalletRepository`, `SettlementDomainService`, `SettlementRepository`):
- `settleAcceptedSavesBothWalletsAndRecordsSettlement`: client + builder wallets present; stub `settleAcceptance(...)` → breakdown; `verify(walletRepository, times(2)).save(any())`; `verify(settlementRepository).save(argThat(s -> s.type() == SettlementType.ACCEPT))`; assert returned breakdown.
- `settleAcceptedOpensBuilderWalletWhenAbsent`: builder wallet empty → `verify(walletRepository, times(3)).save(any())` (open builder, then client + builder).
- `selfSettleSavesOneWallet`: clientId == builderId → `verify(walletRepository, times(1)).save(any())`.
- `settleRejectedRefundsAndRecordsRejectSettlement`: `verify(settlementDomainService).settleRejection(...)`; `verify(settlementRepository).save(argThat(s -> s.type() == SettlementType.REJECT))`.
- `requireWalletMissingThrowsNotFound`: client wallet empty → `DomainException` NOT_FOUND.

(Mirror the construction + `Money.of(...)` style from the old `TaskReviewAppServiceImplTest`.)

- [ ] **Step 6: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures. Net unit-test count changes (2 tests move from the task test into the new settlement test). Integration `TaskSettlementIntegrationTest` validates the end-to-end money path in CI.

- [ ] **Step 7: Commit**

```bash
git add backend/
git commit -m "refactor(ledger): own accept/reject settlement in SettlementWriteAppService"
```

> NOTE FOR CONTROLLER: the money path was re-wired (orchestration moved task→ledger app service). Unit tests cover the delegation + the moved orchestration with mocks; the real end-to-end money movement is validated by `TaskSettlementIntegrationTest` in CI. Flag for the final review / CI run.

---

### Task 4: Slice gate + tag

- [ ] **Step 1: Full suite green**

```bash
mvn -f backend/pom.xml -B test 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors|BUILD SUCCESS|BUILD FAILURE" | tail -3
```

Expected: BUILD SUCCESS; `Failures: 0, Errors: 0`.

- [ ] **Step 2: Tag**

```bash
git tag redivision-3-ledger-settlement
```

---

## Self-Review

**Spec coverage (spec §4 Ledger + §6 step 3):**
- "promote Settlement to a persisted aggregate: `SettlementModel(root)` (taskId + breakdown + type) + `SettlementRepository` + a new `settlements` table" → Task 2. ✓
- "`SettlementPolicy` stays the pure 85/15 source" → moved untouched (Task 1); still the only place the rate lives. ✓
- "Move accept/reject orchestration out of `TaskReviewAppService` into a `SettlementWriteAppService`" → Task 3. ✓
- "Settlement is shaped to support `SPLIT`, but the SPLIT producer lands later" → `SettlementType.SPLIT` exists; no producer. ✓
- Additive migration `V14`; `task_id` soft reference → Task 2. ✓

**Placeholder scan:** the two integration tests defer harness boilerplate to "match `WalletLedgerIntegrationTest`" (the sibling sets up Testcontainers/Postgres) — the behavioral assertions are fully specified. Task 3 Steps 4–5 specify the test rework by named test + exact verify/assert; the implementer adapts construction style from the existing test. No TBDs.

**Type consistency:** `SettlementBreakdown` import path moves to `ledger.settlement.info` (Task 1) and every consumer (`SettlementDomainService(Impl)`, `SettlementWriteAppServiceImpl`, `TaskReviewAppServiceImpl`, both tests) uses that path. `SettlementModel.accepted/rejected`, `SettlementRepository.save/findByTaskId`, `SettlementWriteAppService.settleAccepted/settleRejected` signatures are consistent across producer, consumer, and tests. `Money.value()`/`Money.of(...)`/`Money.ZERO` match the existing `WalletRepositoryImpl`/`WalletModel` usage.

**Risk note:** Task 3 re-wires the invariant-critical money path; validated locally only by mocked unit tests. CI's `TaskSettlementIntegrationTest` is the end-to-end gate. Flagged for the final whole-branch review.
