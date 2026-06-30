# Module 4 Phase 2 — Dispute Core + Reason-Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a client rejection into a reason-gated decision — `D_CHANGED_MIND` deterministically charges the client (85/15, no dispute), while `A/B/C` open a `Dispute`, obtain a ruling through an `ArbitrationGateway` ACL port (a deterministic `StubArbitrationClient` in this phase), and settle deterministically by ruling category — finally wiring the dormant `SettlementType.SPLIT` for `PARTIALLY_FULFILLED`.

**Architecture:** Populate the reserved `adjudication` subdomain with a `Dispute` aggregate (+ `Ruling` VO and dispute enums) and a `DisputeAppService` that opens disputes, applies rulings, and resolves by fallback. The arbitration call sits behind an application **port** (`ArbitrationGateway.requestRuling → Optional<RulingInfo>`): a *present* Optional is a synchronous ruling (the Phase-2 stub / tests), an *empty* Optional means "ruling will arrive asynchronously later" (the Phase-3 RabbitMQ adapter). Ruling → money is computed deterministically in `ledger.settlement` (Inv #3); `PARTIALLY_FULFILLED` uses a new `settleSplit` that wires `SettlementType.SPLIT`. Reject becomes reason-gated at the existing `POST /api/tasks/{id}/reject`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, COLA multi-module reactor, PostgreSQL + Flyway, Hibernate (`ddl-auto: validate`), JUnit 5 + AssertJ + Mockito + Testcontainers.

## Global Constraints

- **Framework-free domain.** `hireai-domain` depends only on `hireai-utility` (+ jspecify). No Spring/Jackson/networknt in domain. The `Dispute`/`Ruling`/enums and `SettlementDomainService.settleSplit` are pure Java. Domain services are wired as `@Bean` in `com.hireai.application.config.DomainServiceConfig`.
- **Immutability.** Aggregates are immutable; transitions return new copies (`TaskModel`/`Dispute` build new instances). Never mutate in place.
- **Inv #3 — deterministic money path.** The arbitrator returns only a ruling *category* + rationale. All credit movement is computed in `ledger.settlement` from the category: `FULFILLED → settleAccepted` (85/15), `PARTIALLY_FULFILLED → settleSplit` (half refund + half 85/15), `NOT_FULFILLED → settleRejected` (full refund). The LLM never sees balances.
- **Inv #2 — append-only money.** Settlement still flows through `WalletModel` (which appends `ledger_entries`) + the `settlements` row. `disputes` is a mutable state record (no append-only trigger).
- **Inv #5 — server-side identity.** `raisedBy` / client id come from the JWT principal via `CurrentUserProvider`; the existing reject owner-check (`task.clientId().equals(clientId)`) is retained. Never trust path/body ids.
- **SPLIT reconciles exactly.** `clientRefund` is computed by **subtraction** (`budget − builderShare`), never as an independent percentage, so `net + commission + clientRefund == budget` always holds (commission half-up to 2dp).
- **Concurrency.** Settlement loads the task with `taskRepository.findByIdForUpdate` (pessimistic row lock, as accept/reject already do). The task state guard (`requireStatus`) is the exactly-once gate. The `disputes.task_id` UNIQUE constraint blocks a duplicate open.
- **Migrations.** Additive only; next free version is **`V17`**. Never edit an applied migration. Money is `NUMERIC(14,2)`. New adjudication tables follow `V16`'s convention: `gmt_create` / `gmt_modified TIMESTAMPTZ NOT NULL DEFAULT now()`. Cross-context ids are soft refs (`UUID`, no `REFERENCES`), like `settlements`/`validation_reports`.
- **Test placement (consistent with Phase 1).** Domain unit tests live in `hireai-domain`, app tests in `hireai-application`, infra tests in `hireai-infrastructure`, integration tests (`*IntegrationTest`, Testcontainers) in `hireai-main`. Testcontainers auto-skip without Docker — never fail the build. TDD per task; keep the suite green at every task.

**Out of scope for this plan (→ Phase 3 / later):** the RabbitMQ transport + Python LangGraph arbitrator (`RabbitArbitrationClient`, `ArbitrationCallbackController`, `ArbitrationDlqListener`, the `arbitration/` service) — Phase 2 uses the synchronous `StubArbitrationClient` only; the bounded same-agent validation retry + the auto-accept review sweeper (designed in the spec; a separate plan); reputation events (Module 5). The `Dispute` state machine still includes `ARBITRATING` + `ESCALATED` and `resolveByFallback` so Phase 3 wires onto them with no domain change.

---

## File structure

**New (domain — `hireai-domain/src/main/java/com/hireai/domain/`):**
- `biz/task/enums/RejectReason.java` — `A_MISMATCH` / `B_FACTUAL` / `C_INCOMPLETE` / `D_CHANGED_MIND` + `opensDispute()`. (Reject is a Task concept, so the enum lives in the task subdomain; adjudication — which already depends on task types — reuses it.)
- `biz/adjudication/enums/DisputeStatus.java` — `OPEN` / `ARBITRATING` / `RULED` / `RESOLVED` / `ESCALATED`.
- `biz/adjudication/enums/RulingCategory.java` — `FULFILLED` / `PARTIALLY_FULFILLED` / `NOT_FULFILLED`.
- `biz/adjudication/enums/RulingDecidedBy.java` — `ARBITRATOR` / `FALLBACK`.
- `biz/adjudication/model/Ruling.java` — VO `(int tier, RulingCategory category, String rationale, RulingDecidedBy decidedBy)`.
- `biz/adjudication/model/DisputeModel.java` — aggregate root; state machine.
- `biz/adjudication/repository/DisputeRepository.java` — repository interface.

**New (infrastructure):**
- `hireai-infrastructure/.../infrastructure/adjudication/StubArbitrationClient.java` — synchronous deterministic `ArbitrationGateway` (active when the RabbitMQ adapter is absent).
- `hireai-repository/.../infrastructure/repository/adjudication/DisputeDO.java`, `DisputeJpaRepository.java`, `DisputeRepositoryImpl.java`.

**New (application):**
- `hireai-application/.../application/biz/adjudication/port/ArbitrationGateway.java` — ACL port.
- `hireai-application/.../application/biz/adjudication/port/RulingInfo.java` — inbound ruling carrier `(RulingCategory category, String rationale)`.
- `hireai-application/.../application/biz/adjudication/dispute/DisputeAppService.java` + `impl/DisputeAppServiceImpl.java`.

**New (migration):**
- `hireai-main/src/main/resources/db/migration/V17__disputes.sql`.

**Modified:**
- `ledger/settlement/service/SettlementPolicy.java` — add the SPLIT-fraction constant + builder-share helper.
- `ledger/settlement/service/SettlementDomainService.java` + `impl/...Impl.java` — add `settleSplit`.
- `ledger/settlement/model/SettlementModel.java` — add `split(...)` factory.
- `application/biz/ledger/settlement/SettlementWriteAppService.java` + `impl/...Impl.java` — add `settleSplit`.
- `biz/task/model/TaskModel.java` — add `rejectReasonCategory` field + `dispute()` / `resolveDispute()` / `chargeChangedMind()` transitions; thread the new field through `copyWith`/`resolved`.
- `biz/task/enums/TaskStatus.java` — add `DISPUTED` (+ to `PENDING_ESCROW`).
- `hireai-repository/.../task/TaskDO.java` + `TaskRepositoryImpl.java` — map `reject_reason_category`.
- `application/biz/task/TaskReviewAppService.java` + `impl/...Impl.java` — reason-gated `reject`.
- `hireai-controller/.../biz/task/dto/RejectTaskRequest.java` — add required `reasonCategory`.
- `application/config/DomainServiceConfig.java` — no change (the `settlementDomainService` bean already returns the impl that gains `settleSplit`).

---

### Task 1: SPLIT settlement (wire `SettlementType.SPLIT`)

Add the half-refund/half-payout settlement to the money path. No dispute concepts yet — this is self-contained and unblocks Task 6.

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/service/SettlementPolicy.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/service/SettlementDomainService.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/service/impl/SettlementDomainServiceImpl.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/ledger/settlement/model/SettlementModel.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/ledger/settlement/SettlementWriteAppService.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/ledger/settlement/impl/SettlementWriteAppServiceImpl.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/ledger/settlement/SettlementSplitTest.java`

**Interfaces:**
- Produces: `SettlementDomainService.settleSplit(WalletModel clientWallet, WalletModel builderWallet, Money budget, UUID taskId, String correlationId) -> SettlementBreakdown`; `SettlementModel.split(UUID taskId, Money net, Money commission)`; `SettlementWriteAppService.settleSplit(UUID taskId, UUID clientId, UUID builderId, Money budget) -> SettlementBreakdown`.
- Consumes (existing): `SettlementPolicy.COMMISSION_RATE`, `Money.percentage/subtract/isPositive`, `WalletModel.release/refund/credit`, `LedgerEntryType.{SPLIT,COMMISSION}`.

- [ ] **Step 1: Write the failing test**

```java
// SettlementSplitTest.java
package com.hireai.domain.biz.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.biz.ledger.settlement.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementSplitTest {

    private final SettlementDomainServiceImpl service = new SettlementDomainServiceImpl();

    @Test
    void splitReconcilesToBudgetAndPaysHalfNet() {
        UUID client = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WalletModel clientWallet = WalletModel.openFor(client);
        WalletModel builderWallet = WalletModel.openFor(builder);
        Money budget = Money.of("100.00");
        // client must hold the budget in escrow before settlement
        clientWallet.topUp(budget, "seed");
        clientWallet.freeze(budget, taskId, "freeze");

        SettlementBreakdown b = service.settleSplit(clientWallet, builderWallet, budget, taskId, "corr");

        // builderShare = 50.00; commission = 7.50; net = 42.50; clientRefund = 50.00
        assertThat(b.net()).isEqualTo(Money.of("42.50"));
        assertThat(b.commission()).isEqualTo(Money.of("7.50"));
        assertThat(builderWallet.available()).isEqualTo(Money.of("42.50"));
        assertThat(clientWallet.available()).isEqualTo(Money.of("50.00")); // the refunded half
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);            // escrow fully drained
        // reconciliation: net + commission + clientRefund == budget
        assertThat(b.net().add(b.commission()).add(Money.of("50.00"))).isEqualTo(budget);
    }

    @Test
    void splitReconcilesOnOddBudget() {
        UUID client = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WalletModel clientWallet = WalletModel.openFor(client);
        WalletModel builderWallet = WalletModel.openFor(builder);
        Money budget = Money.of("5.01");
        clientWallet.topUp(budget, "seed");
        clientWallet.freeze(budget, taskId, "freeze");

        SettlementBreakdown b = service.settleSplit(clientWallet, builderWallet, budget, taskId, "corr");

        // builderShare = round(2.505) = 2.51; commission = round(0.3765) = 0.38; net = 2.13;
        // clientRefund = 5.01 - 2.51 = 2.50  → net+commission+refund = 2.13+0.38+2.50 = 5.01
        assertThat(b.net().add(b.commission())).isEqualTo(Money.of("2.51"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);
        assertThat(b.net().add(b.commission()).add(clientWallet.available())).isEqualTo(budget);
    }

    @Test
    void builderShareOnSplitIsHalfHalfUp() {
        assertThat(SettlementPolicy.builderShareOnSplit(Money.of("100.00"))).isEqualTo(Money.of("50.00"));
        assertThat(SettlementPolicy.builderShareOnSplit(Money.of("5.01"))).isEqualTo(Money.of("2.51"));
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=SettlementSplitTest`
Expected: COMPILATION FAILURE (`settleSplit` / `builderShareOnSplit` do not exist).

- [ ] **Step 3: Add the SPLIT policy helper**

In `SettlementPolicy.java`, after `netOf(...)`:

```java
    /** A PARTIALLY_FULFILLED ruling settles half the budget; the rest is refunded. */
    public static final BigDecimal PARTIAL_BUILDER_FRACTION = new BigDecimal("0.50");

    /** The builder's gross share under a partial-fulfilment split (half the budget, half-up to 2dp). */
    public static Money builderShareOnSplit(Money budget) {
        return budget.percentage(PARTIAL_BUILDER_FRACTION);
    }
```

- [ ] **Step 4: Add `settleSplit` to the domain service interface**

In `SettlementDomainService.java`, after `settleRejection(...)`:

```java
    /**
     * Partial fulfilment (Module 4 SPLIT): the builder is paid 85/15 on HALF the budget; the other
     * half is refunded to the client. clientRefund is computed by subtraction so net+commission+refund
     * reconciles to the budget exactly. clientWallet and builderWallet MAY be the same instance.
     */
    SettlementBreakdown settleSplit(WalletModel clientWallet, WalletModel builderWallet,
                                    Money budget, UUID taskId, String correlationId);
```

- [ ] **Step 5: Implement `settleSplit`**

In `SettlementDomainServiceImpl.java`, add:

```java
    @Override
    public SettlementBreakdown settleSplit(WalletModel clientWallet, WalletModel builderWallet,
                                           Money budget, UUID taskId, String correlationId) {
        Money builderShare = SettlementPolicy.builderShareOnSplit(budget);
        Money commission = SettlementPolicy.commissionOn(builderShare);
        Money net = builderShare.subtract(commission);
        Money clientRefund = budget.subtract(builderShare); // by subtraction → exact reconciliation

        clientWallet.release(net, taskId, LedgerEntryType.SPLIT, correlationId);
        if (commission.isPositive()) {
            clientWallet.release(commission, taskId, LedgerEntryType.COMMISSION, correlationId);
        }
        if (clientRefund.isPositive()) {
            clientWallet.refund(clientRefund, taskId, correlationId);
        }
        builderWallet.credit(net, taskId, LedgerEntryType.SPLIT, correlationId);
        return new SettlementBreakdown(net, commission);
    }
```

(Add the `import` for `BigDecimal` in `SettlementPolicy` if not present; `LedgerEntryType` is already imported in the impl.)

- [ ] **Step 6: Add the `SettlementModel.split` factory**

In `SettlementModel.java`, after `rejected(...)`:

```java
    public static SettlementModel split(UUID taskId, Money net, Money commission) {
        return new SettlementModel(UUID.randomUUID(), taskId, SettlementType.SPLIT, net, commission, Instant.now());
    }
```

- [ ] **Step 7: Run the domain test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=SettlementSplitTest`
Expected: PASS (3/3).

- [ ] **Step 8: Add `settleSplit` to the application write service**

In `SettlementWriteAppService.java`, after `settleRejected(...)`:

```java
    /** Partial fulfilment: pay the builder 85/15 on half the budget; refund the other half. Records a SPLIT settlement. */
    SettlementBreakdown settleSplit(UUID taskId, UUID clientId, UUID builderId, Money budget);
```

In `SettlementWriteAppServiceImpl.java`, add (mirrors `settleAccepted`):

```java
    @Override
    public SettlementBreakdown settleSplit(UUID taskId, UUID clientId, UUID builderId, Money budget) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        String correlationId = "settle-" + taskId;
        SettlementBreakdown breakdown = settlementDomainService.settleSplit(
                clientWallet, builderWallet, budget, taskId, correlationId);

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        settlementRepository.save(SettlementModel.split(taskId, breakdown.net(), breakdown.commission()));
        return breakdown;
    }
```

- [ ] **Step 9: Run the full build, verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS, no new failures (Docker-gated ITs skip).

- [ ] **Step 10: Commit**

```bash
git add backend/hireai-domain backend/hireai-application
git commit -m "feat(settlement): wire SettlementType.SPLIT (half refund / half 85-15 payout)"
```

---

### Task 2: Dispute aggregate + enums + `Ruling` VO

Pure-domain. The `DisputeModel` state machine + `RejectReason` enum that the reason-gate and arbitration build on.

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/enums/RejectReason.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/DisputeStatus.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/RulingCategory.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/RulingDecidedBy.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/Ruling.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/DisputeModelTest.java`

**Interfaces:**
- Produces:
  - `RejectReason { A_MISMATCH, B_FACTUAL, C_INCOMPLETE, D_CHANGED_MIND }` with `boolean opensDispute()` (true for A/B/C).
  - `Ruling(int tier, RulingCategory category, String rationale, RulingDecidedBy decidedBy)`.
  - `DisputeModel.open(UUID taskId, UUID raisedBy, RejectReason reasonCategory, String correlationId) -> DisputeModel` (status `OPEN`, ruling `null`; throws if `reasonCategory` is not a dispute reason).
  - `DisputeModel.rehydrate(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory, DisputeStatus status, Ruling rulingOrNull, String correlationId, Instant createdAt, Instant resolvedAtOrNull)`.
  - transitions `startArbitrating()` (`OPEN→ARBITRATING`), `recordRuling(Ruling)` (`OPEN|ARBITRATING→RULED`), `resolve()` (`RULED→RESOLVED`), `resolveByFallback(Ruling)` (`OPEN|ARBITRATING→RESOLVED`).
  - guard `isResolvable()` → `status == OPEN || status == ARBITRATING` (used by the app for first-ruling-wins).
  - accessors `id/taskId/raisedBy/reasonCategory/status/ruling/correlationId/createdAt/resolvedAt`.

- [ ] **Step 1: Write the failing test**

```java
// DisputeModelTest.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeModelTest {

    private DisputeModel openDispute() {
        return DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.A_MISMATCH, "corr-1");
    }

    @Test
    void openStartsInOpenWithNoRuling() {
        DisputeModel d = openDispute();
        assertThat(d.status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.ruling()).isNull();
        assertThat(d.isResolvable()).isTrue();
        assertThat(d.reasonCategory()).isEqualTo(RejectReason.A_MISMATCH);
    }

    @Test
    void openRejectsChangedMindReason() {
        assertThatThrownBy(() ->
                DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.D_CHANGED_MIND, "c"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rulingThenResolveDrivesToResolved() {
        Ruling ruling = new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half done", RulingDecidedBy.ARBITRATOR);
        DisputeModel resolved = openDispute().startArbitrating().recordRuling(ruling).resolve();
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.ruling()).isEqualTo(ruling);
        assertThat(resolved.isResolvable()).isFalse();
    }

    @Test
    void recordRulingAllowedDirectlyFromOpen() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR);
        DisputeModel ruled = openDispute().recordRuling(ruling);
        assertThat(ruled.status()).isEqualTo(DisputeStatus.RULED);
    }

    @Test
    void recordRulingRejectedOnceResolved() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR);
        DisputeModel resolved = openDispute().recordRuling(ruling).resolve();
        assertThatThrownBy(() -> resolved.recordRuling(ruling)).isInstanceOf(DomainException.class);
    }

    @Test
    void resolveByFallbackFromArbitratingGoesResolved() {
        Ruling fb = new Ruling(1, RulingCategory.NOT_FULFILLED, "arbitrator unavailable", RulingDecidedBy.FALLBACK);
        DisputeModel resolved = openDispute().startArbitrating().resolveByFallback(fb);
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.ruling().decidedBy()).isEqualTo(RulingDecidedBy.FALLBACK);
    }

    @Test
    void rulingVoRejectsNullCategory() {
        assertThatThrownBy(() -> new Ruling(1, null, "x", RulingDecidedBy.ARBITRATOR))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=DisputeModelTest`
Expected: COMPILATION FAILURE (types do not exist).

- [ ] **Step 3: Create the enums**

```java
// RejectReason.java
package com.hireai.domain.biz.task.enums;

/**
 * Why a client rejected a reviewed result. A/B/C are disputable (open arbitration);
 * D is buyer's remorse on conformant work — a deterministic charge, no dispute.
 */
public enum RejectReason {
    A_MISMATCH,     // output does not match the declared spec
    B_FACTUAL,      // output contains factual errors
    C_INCOMPLETE,   // output is incomplete
    D_CHANGED_MIND; // client changed their mind; work was conformant → charged in full

    /** A/B/C open a Dispute; D_CHANGED_MIND does not. */
    public boolean opensDispute() {
        return this != D_CHANGED_MIND;
    }
}
```

```java
// DisputeStatus.java
package com.hireai.domain.biz.adjudication.enums;

/** Dispute lifecycle. ESCALATED is reserved for tier-2 (a future spec) and unused in tier-1. */
public enum DisputeStatus {
    OPEN,
    ARBITRATING,
    RULED,
    RESOLVED,
    ESCALATED
}
```

```java
// RulingCategory.java
package com.hireai.domain.biz.adjudication.enums;

/** The arbitrator's verdict category; maps deterministically to settlement (Inv #3). */
public enum RulingCategory {
    FULFILLED,            // → settleAccepted (85/15)
    PARTIALLY_FULFILLED,  // → settleSplit (half refund / half 85-15)
    NOT_FULFILLED         // → settleRejected (full refund)
}
```

```java
// RulingDecidedBy.java
package com.hireai.domain.biz.adjudication.enums;

/** Who produced the ruling: the LLM arbitrator, or the platform's refund fallback (DLQ 兜底). */
public enum RulingDecidedBy {
    ARBITRATOR,
    FALLBACK
}
```

- [ ] **Step 4: Create the `Ruling` VO**

```java
// Ruling.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Immutable ruling VO. The arbitrator supplies only {@code category} + {@code rationale};
 * {@code tier} (1 in tier-1) and {@code decidedBy} are set platform-side. No money lives here (Inv #3).
 */
public record Ruling(int tier, RulingCategory category, String rationale, RulingDecidedBy decidedBy) {

    public Ruling {
        if (category == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling category is required");
        }
        if (decidedBy == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling decidedBy is required");
        }
    }
}
```

- [ ] **Step 5: Create the `DisputeModel` aggregate**

```java
// DisputeModel.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Dispute aggregate root (one per task). Immutable: each transition returns a new copy; an illegal
 * transition throws {@link DomainException}. State machine: OPEN → ARBITRATING → RULED → RESOLVED,
 * with a RESOLVED-via-fallback path from OPEN/ARBITRATING. ESCALATED is a reserved tier-2 seam.
 */
public final class DisputeModel {

    private final UUID id;
    private final UUID taskId;
    private final UUID raisedBy;
    private final RejectReason reasonCategory;  // always a dispute reason (A/B/C)
    private final DisputeStatus status;
    private final Ruling ruling;                // nullable until RULED/RESOLVED
    private final String correlationId;
    private final Instant createdAt;
    private final Instant resolvedAt;           // nullable until RESOLVED

    private DisputeModel(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                         DisputeStatus status, Ruling ruling, String correlationId,
                         Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.ruling = ruling;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    /** Open a new dispute for a disputable rejection (A/B/C only). */
    public static DisputeModel open(UUID taskId, UUID raisedBy, RejectReason reasonCategory, String correlationId) {
        if (taskId == null || raisedBy == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "taskId and raisedBy are required");
        }
        if (reasonCategory == null || !reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute reason must be A/B/C; got " + reasonCategory);
        }
        return new DisputeModel(UUID.randomUUID(), taskId, raisedBy, reasonCategory,
                DisputeStatus.OPEN, null, correlationId, Instant.now(), null);
    }

    /** Rebuild from a persisted row (no validation). */
    public static DisputeModel rehydrate(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                                         DisputeStatus status, Ruling ruling, String correlationId,
                                         Instant createdAt, Instant resolvedAt) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, status, ruling,
                correlationId, createdAt, resolvedAt);
    }

    /** OPEN → ARBITRATING: the request was handed to the arbitrator (async transport). */
    public DisputeModel startArbitrating() {
        requireStatusIn("startArbitrating", DisputeStatus.OPEN);
        return copyWith(DisputeStatus.ARBITRATING, this.ruling, this.resolvedAt);
    }

    /** OPEN|ARBITRATING → RULED: a ruling arrived (first-ruling-wins; later rulings are rejected by the guard). */
    public DisputeModel recordRuling(Ruling ruling) {
        requireStatusIn("recordRuling", DisputeStatus.OPEN, DisputeStatus.ARBITRATING);
        if (ruling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling is required");
        }
        return copyWith(DisputeStatus.RULED, ruling, this.resolvedAt);
    }

    /** RULED → RESOLVED: the ruling has been settled. */
    public DisputeModel resolve() {
        requireStatusIn("resolve", DisputeStatus.RULED);
        return copyWith(DisputeStatus.RESOLVED, this.ruling, Instant.now());
    }

    /** OPEN|ARBITRATING → RESOLVED via the platform refund fallback (DLQ 兜底). */
    public DisputeModel resolveByFallback(Ruling fallbackRuling) {
        requireStatusIn("resolveByFallback", DisputeStatus.OPEN, DisputeStatus.ARBITRATING);
        if (fallbackRuling == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "fallback ruling is required");
        }
        return copyWith(DisputeStatus.RESOLVED, fallbackRuling, Instant.now());
    }

    /** True while a ruling can still be applied (used for first-ruling-wins idempotency). */
    public boolean isResolvable() {
        return status == DisputeStatus.OPEN || status == DisputeStatus.ARBITRATING;
    }

    private DisputeModel copyWith(DisputeStatus newStatus, Ruling newRuling, Instant newResolvedAt) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, newStatus, newRuling,
                correlationId, createdAt, newResolvedAt);
    }

    private void requireStatusIn(String transition, DisputeStatus... allowed) {
        for (DisputeStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                "Illegal dispute transition " + transition + " from " + this.status);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID raisedBy() { return raisedBy; }
    public RejectReason reasonCategory() { return reasonCategory; }
    public DisputeStatus status() { return status; }
    public Ruling ruling() { return ruling; }
    public String correlationId() { return correlationId; }
    public Instant createdAt() { return createdAt; }
    public Instant resolvedAt() { return resolvedAt; }
}
```

- [ ] **Step 6: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=DisputeModelTest`
Expected: PASS (7/7).

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-domain
git commit -m "feat(adjudication): Dispute aggregate + Ruling VO + dispute/reject enums"
```

---

### Task 3: `V17` migration + Dispute persistence

The `disputes` table + `tasks.reject_reason_category` column, and the Dispute repository stack (mirrors the Settlement stack). The task column is added by the migration here but only *mapped* in Task 4 — Hibernate `validate` does not fail on an unmapped extra column.

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V17__disputes.sql`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/DisputeRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/main/adjudication/DisputeRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `DisputeRepository.save(DisputeModel) -> DisputeModel`; `findByTaskId(UUID) -> Optional<DisputeModel>`; `findById(UUID) -> Optional<DisputeModel>`.

- [ ] **Step 1: Write the migration**

```sql
-- V17__disputes.sql
-- Module 4 Phase 2: dispute records + the uniform reject-reason category on tasks.

CREATE TABLE disputes (
    id               UUID PRIMARY KEY,
    task_id          UUID NOT NULL UNIQUE,           -- soft ref to tasks (no FK; one dispute per task)
    raised_by        UUID NOT NULL,                  -- client user id
    reason_category  TEXT NOT NULL CHECK (reason_category IN ('A_MISMATCH','B_FACTUAL','C_INCOMPLETE')),
    status           TEXT NOT NULL CHECK (status IN ('OPEN','ARBITRATING','RULED','RESOLVED','ESCALATED')),
    correlation_id   TEXT NOT NULL,
    ruling_category  TEXT CHECK (ruling_category IN ('FULFILLED','PARTIALLY_FULFILLED','NOT_FULFILLED')),
    ruling_rationale TEXT,
    ruling_tier      INT,
    decided_by       TEXT CHECK (decided_by IN ('ARBITRATOR','FALLBACK')),
    resolved_at      TIMESTAMPTZ,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_disputes_status ON disputes (status);

-- Uniform reject-reason category (A/B/C open a dispute; D is captured here only — no dispute row).
ALTER TABLE tasks ADD COLUMN reject_reason_category TEXT
    CHECK (reject_reason_category IN ('A_MISMATCH','B_FACTUAL','C_INCOMPLETE','D_CHANGED_MIND'));
```

- [ ] **Step 2: Create the domain repository interface**

```java
// DisputeRepository.java
package com.hireai.domain.biz.adjudication.repository;

import com.hireai.domain.biz.adjudication.model.DisputeModel;

import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository {
    DisputeModel save(DisputeModel dispute);
    Optional<DisputeModel> findById(UUID id);
    Optional<DisputeModel> findByTaskId(UUID taskId);
}
```

- [ ] **Step 3: Create the JPA entity**

```java
// DisputeDO.java
package com.hireai.infrastructure.repository.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class DisputeDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "task_id", nullable = false, unique = true) private UUID taskId;
    @Column(name = "raised_by", nullable = false) private UUID raisedBy;
    @Column(name = "reason_category", nullable = false) private String reasonCategory;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "correlation_id", nullable = false) private String correlationId;
    @Column(name = "ruling_category") private String rulingCategory;     // nullable until ruled
    @Column(name = "ruling_rationale") private String rulingRationale;
    @Column(name = "ruling_tier") private Integer rulingTier;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "gmt_create", nullable = false) private Instant gmtCreate;

    protected DisputeDO() {}

    public DisputeDO(UUID id, UUID taskId, UUID raisedBy, String reasonCategory, String status,
                     String correlationId, String rulingCategory, String rulingRationale,
                     Integer rulingTier, String decidedBy, Instant resolvedAt, Instant gmtCreate) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.correlationId = correlationId;
        this.rulingCategory = rulingCategory;
        this.rulingRationale = rulingRationale;
        this.rulingTier = rulingTier;
        this.decidedBy = decidedBy;
        this.resolvedAt = resolvedAt;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getRaisedBy() { return raisedBy; }
    public String getReasonCategory() { return reasonCategory; }
    public String getStatus() { return status; }
    public String getCorrelationId() { return correlationId; }
    public String getRulingCategory() { return rulingCategory; }
    public String getRulingRationale() { return rulingRationale; }
    public Integer getRulingTier() { return rulingTier; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getGmtCreate() { return gmtCreate; }
}
```

- [ ] **Step 4: Create the JPA repository**

```java
// DisputeJpaRepository.java
package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DisputeJpaRepository extends JpaRepository<DisputeDO, UUID> {
    Optional<DisputeDO> findByTaskId(UUID taskId);
}
```

- [ ] **Step 5: Create the repository impl (Model ↔ DO mapping)**

```java
// DisputeRepositoryImpl.java
package com.hireai.infrastructure.repository.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DisputeRepositoryImpl implements DisputeRepository {

    private final DisputeJpaRepository jpa;

    @Override
    public DisputeModel save(DisputeModel d) {
        Ruling r = d.ruling();
        jpa.save(new DisputeDO(
                d.id(), d.taskId(), d.raisedBy(), d.reasonCategory().name(), d.status().name(),
                d.correlationId(),
                r == null ? null : r.category().name(),
                r == null ? null : r.rationale(),
                r == null ? null : r.tier(),
                r == null ? null : r.decidedBy().name(),
                d.resolvedAt(), d.createdAt()));
        return d;
    }

    @Override
    public Optional<DisputeModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }

    @Override
    public Optional<DisputeModel> findByTaskId(UUID taskId) {
        return jpa.findByTaskId(taskId).map(this::toModel);
    }

    private DisputeModel toModel(DisputeDO e) {
        Ruling ruling = e.getRulingCategory() == null ? null : new Ruling(
                e.getRulingTier() == null ? 1 : e.getRulingTier(),
                RulingCategory.valueOf(e.getRulingCategory()),
                e.getRulingRationale(),
                RulingDecidedBy.valueOf(e.getDecidedBy()));
        return DisputeModel.rehydrate(
                e.getId(), e.getTaskId(), e.getRaisedBy(),
                RejectReason.valueOf(e.getReasonCategory()),
                DisputeStatus.valueOf(e.getStatus()), ruling, e.getCorrelationId(),
                e.getGmtCreate(), e.getResolvedAt());
    }
}
```

- [ ] **Step 6: Write the round-trip integration test**

```java
// DisputeRepositoryIntegrationTest.java
package com.hireai.main.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisputeRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired DisputeRepository disputeRepository;

    @Test
    void persistsAndRehydratesRuledDispute() {
        UUID taskId = UUID.randomUUID();
        DisputeModel open = DisputeModel.open(taskId, UUID.randomUUID(), RejectReason.B_FACTUAL, "corr-x");
        disputeRepository.save(open);

        DisputeModel ruled = open
                .recordRuling(new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half", RulingDecidedBy.ARBITRATOR))
                .resolve();
        disputeRepository.save(ruled);

        DisputeModel found = disputeRepository.findByTaskId(taskId).orElseThrow();
        assertThat(found.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(found.reasonCategory()).isEqualTo(RejectReason.B_FACTUAL);
        assertThat(found.ruling().category()).isEqualTo(RulingCategory.PARTIALLY_FULFILLED);
        assertThat(found.ruling().rationale()).isEqualTo("half");
        assertThat(found.resolvedAt()).isNotNull();
    }
}
```

> NOTE for the implementer: confirm the existing Testcontainers base class name/package used by the Phase-1 `ValidationGateIntegrationTest` (e.g. `PostgresIntegrationTest` / `@SpringBootTest` + `@ActiveProfiles("test")` + `@Testcontainers`). Match it exactly — do not invent a new base. If the project uses a per-test `@Container` instead of a shared base, mirror that pattern.

- [ ] **Step 7: Run the integration test (skips without Docker)**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=DisputeRepositoryIntegrationTest`
Expected: PASS with Docker; SKIPPED without Docker (never a failure).

- [ ] **Step 8: Run the full build, verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS (Hibernate `validate` passes — `disputes` mapped; `tasks.reject_reason_category` unmapped-but-present is fine).

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-domain backend/hireai-repository backend/hireai-main
git commit -m "feat(adjudication): disputes table (V17) + Dispute persistence"
```

---

### Task 4: TaskModel dispute transitions + `DISPUTED` status

Add the task-side state for disputes + the `reject_reason_category` audit field, and map it in the task DO.

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/enums/TaskStatus.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/task/TaskDO.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/task/TaskRepositoryImpl.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/task/model/TaskModelDisputeTest.java`

**Interfaces:**
- Consumes: `RejectReason` (Task 2).
- Produces:
  - `TaskStatus.DISPUTED` (member of `PENDING_ESCROW`).
  - `TaskModel.dispute(RejectReason reasonCategory, String reason)` — `PENDING_REVIEW → DISPUTED`; requires `reasonCategory.opensDispute()`; stores `reason` (≤500, trimmed) + `reasonCategory`.
  - `TaskModel.resolveDispute(TaskResolution resolution)` — `DISPUTED → RESOLVED`.
  - `TaskModel.chargeChangedMind(String reason)` — `PENDING_REVIEW → RESOLVED` (REJECTED), sets `reject_reason_category = D_CHANGED_MIND`.
  - accessor `TaskModel.rejectReasonCategory()` (nullable).

> IMPLEMENTER NOTE — exact current source of `TaskModel.java` is in the plan; the field `category` (String, task domain) already exists, so name the new field `rejectReasonCategory` and the transition parameter `reasonCategory` to avoid shadowing. The canonical constructor grows from 14 → 15 args (new last arg `RejectReason rejectReasonCategory`); update the 11-arg overload to pass a 4th trailing `null`, and `copyWith`/`resolved` to thread `this.rejectReasonCategory`.

- [ ] **Step 1: Write the failing test**

```java
// TaskModelDisputeTest.java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelDisputeTest {

    private TaskModel pendingReview() {
        TaskModel submitted = TaskModel.submit(UUID.randomUUID(), "t", "d", Money.of("10.00"),
                new OutputSpec(com.hireai.domain.biz.task.enums.OutputFormat.TEXT, null, null), "cat");
        return submitted
                .assignAndQueue(UUID.randomUUID())
                .markExecuting()
                .recordResult(TaskResultModel.record(submitted.id(), "COMPLETED", "ok", null))
                .passValidation();
    }

    @Test
    void disputeMovesToDisputedAndRecordsCategory() {
        TaskModel d = pendingReview().dispute(RejectReason.A_MISMATCH, "  does not match  ");
        assertThat(d.status()).isEqualTo(TaskStatus.DISPUTED);
        assertThat(d.rejectReasonCategory()).isEqualTo(RejectReason.A_MISMATCH);
        assertThat(d.rejectionReason()).isEqualTo("does not match"); // trimmed
        assertThat(d.resolution()).isNull();
    }

    @Test
    void disputeRejectsChangedMindReason() {
        assertThatThrownBy(() -> pendingReview().dispute(RejectReason.D_CHANGED_MIND, "x"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void disputeRejectsFromNonPendingReview() {
        TaskModel disputed = pendingReview().dispute(RejectReason.A_MISMATCH, "x");
        assertThatThrownBy(() -> disputed.dispute(RejectReason.B_FACTUAL, "y"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resolveDisputeMovesToResolvedWithResolution() {
        TaskModel resolved = pendingReview().dispute(RejectReason.A_MISMATCH, "x")
                .resolveDispute(TaskResolution.REJECTED);
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.rejectReasonCategory()).isEqualTo(RejectReason.A_MISMATCH); // preserved
    }

    @Test
    void chargeChangedMindResolvesRejectedWithDCategory() {
        TaskModel charged = pendingReview().chargeChangedMind("not needed anymore");
        assertThat(charged.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(charged.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(charged.rejectReasonCategory()).isEqualTo(RejectReason.D_CHANGED_MIND);
    }

    @Test
    void disputedCountsAsPendingEscrow() {
        assertThat(TaskStatus.DISPUTED.isPendingEscrow()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=TaskModelDisputeTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Add `DISPUTED` to `TaskStatus`**

In `TaskStatus.java`: add `DISPUTED` to the enum (after `SPEC_VIOLATION`), and add `DISPUTED` to the `PENDING_ESCROW` `EnumSet.of(...)` (escrow is still frozen while a dispute arbitrates). Refresh the class Javadoc to mention the `PENDING_REVIEW → DISPUTED → RESOLVED` branch.

```java
public enum TaskStatus {
    SUBMITTED,
    QUEUED,
    EXECUTING,
    RESULT_RECEIVED,
    PENDING_REVIEW,
    DISPUTED,
    RESOLVED,
    AWAITING_CAPACITY,
    TIMED_OUT,
    SPEC_VIOLATION,
    FAILED,
    CANCELLED;

    private static final Set<TaskStatus> PENDING_ESCROW = EnumSet.of(
            QUEUED, EXECUTING, RESULT_RECEIVED, PENDING_REVIEW, DISPUTED, AWAITING_CAPACITY);

    public boolean isPendingEscrow() {
        return PENDING_ESCROW.contains(this);
    }
}
```

- [ ] **Step 4: Add the field + transitions to `TaskModel`**

In `TaskModel.java`:

1. Add the field after `rejectionReason`:
```java
    private final RejectReason rejectReasonCategory; // nullable; A/B/C/D once reviewed via reject
```
and `import com.hireai.domain.biz.task.enums.RejectReason;`.

2. Replace the 14-arg canonical constructor with a 15-arg one (new trailing param `RejectReason rejectReasonCategory`); assign it. Update the 11-arg overload's delegate call to pass **four** trailing `null`s (`..., null, null, null, null`).

3. Update `resolved(...)` and `copyWith(...)` to pass `this.rejectReasonCategory` as the final constructor arg.

4. Add a shared trim helper + the three transitions (place after `reject`):
```java
    /** PENDING_REVIEW → DISPUTED: client rejected with a disputable reason (A/B/C); arbitration opens. */
    public TaskModel dispute(RejectReason reasonCategory, String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "dispute");
        requirePresent(reasonCategory, "reject reason category");
        if (!reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "dispute() requires an A/B/C reason; got " + reasonCategory);
        }
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.DISPUTED, agentVersionId, result, createdAt,
                this.resolution, this.resolvedAt, trimReason(reason), reasonCategory);
    }

    /** DISPUTED → RESOLVED: the arbitration ruling (or fallback) has been settled. */
    public TaskModel resolveDispute(TaskResolution resolution) {
        requireStatus(TaskStatus.DISPUTED, "resolveDispute");
        requirePresent(resolution, "resolution");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                resolution, Instant.now(), this.rejectionReason, this.rejectReasonCategory);
    }

    /** PENDING_REVIEW → RESOLVED (REJECTED): client changed their mind (D); charged in full, no dispute. */
    public TaskModel chargeChangedMind(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "chargeChangedMind");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                TaskResolution.REJECTED, Instant.now(), trimReason(reason), RejectReason.D_CHANGED_MIND);
    }

    private static String trimReason(String reason) {
        String trimmed = (reason == null || reason.isBlank()) ? null : reason.trim();
        if (trimmed != null && trimmed.length() > MAX_REASON_LENGTH) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Rejection reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }
```

5. Refactor the existing `reject(String reason)` to reuse `trimReason` (its inline trim/length logic is now duplicated):
```java
    public TaskModel reject(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "reject");
        return resolved(TaskResolution.REJECTED, trimReason(reason));
    }
```

6. Add the accessor:
```java
    public RejectReason rejectReasonCategory() { return rejectReasonCategory; }
```

- [ ] **Step 5: Map `reject_reason_category` in `TaskDO` + repository**

In `TaskDO.java`: add a nullable column field
```java
    @Column(name = "reject_reason_category")
    private String rejectReasonCategory;
```
plus its getter/setter (match the file's existing style), and include it in the constructor used by `TaskRepositoryImpl` if the DO is built via constructor (otherwise via setter).

In `TaskRepositoryImpl.java`: on **save**, write `task.rejectReasonCategory() == null ? null : task.rejectReasonCategory().name()`; on **rehydrate (toModel)**, pass `e.getRejectReasonCategory() == null ? null : RejectReason.valueOf(e.getRejectReasonCategory())` as the new final `TaskModel` constructor arg.

> IMPLEMENTER NOTE — read the current `TaskDO.java` + `TaskRepositoryImpl.java` to match the exact mapping idiom (constructor-based vs setter-based, and how the existing nullable `rejection_reason`/`resolution` columns are mapped). Mirror `rejection_reason` exactly — it is the closest precedent (nullable text, enum-ish). Ensure the `TaskModel` rehydration call passes the new 15th arg.
> SAFETY — removing the 14-arg constructor breaks every direct caller. Before finishing, run `grep -rn "new TaskModel(" backend --include=*.java` and update each remaining 14-arg call site to pass the new final `RejectReason` arg (use `null` where no category applies). The full-build step (Step 7) must compile cleanly — a leftover 14-arg call is a compile error, not a silent bug.

- [ ] **Step 6: Run the unit test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=TaskModelDisputeTest`
Expected: PASS (6/6).

- [ ] **Step 7: Run the full build, verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS. Existing TaskModel/Task persistence tests still pass (the new field is nullable and preserved through transitions).

- [ ] **Step 8: Commit**

```bash
git add backend/hireai-domain backend/hireai-repository
git commit -m "feat(task): DISPUTED status + dispute/charge transitions + reject_reason_category"
```

---

### Task 5: `ArbitrationGateway` port + `RulingInfo` + `StubArbitrationClient`

The ACL port that the dispute flow calls, and a synchronous deterministic adapter so Phase 2 is fully testable before any Python/RabbitMQ exists.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/port/RulingInfo.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/port/ArbitrationGateway.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/adjudication/StubArbitrationClient.java`
- Test: `backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/adjudication/StubArbitrationClientTest.java`

**Interfaces:**
- Produces:
  - `RulingInfo(RulingCategory category, String rationale)` — the inbound ruling carrier (only the two fields the arbitrator supplies).
  - `ArbitrationGateway.requestRuling(DisputeModel dispute, TaskModel task) -> Optional<RulingInfo>` — *present* = synchronous ruling (stub/tests); *empty* = ruling will arrive asynchronously (Phase 3 Rabbit adapter).
  - `StubArbitrationClient` — deterministic mapping `A_MISMATCH→NOT_FULFILLED`, `B_FACTUAL→PARTIALLY_FULFILLED`, `C_INCOMPLETE→FULFILLED` (an arbitrary-but-fixed test fixture so all three settlement branches are exercised; replaced by `RabbitArbitrationClient` in Phase 3).

- [ ] **Step 1: Write the failing test**

```java
// StubArbitrationClientTest.java
package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StubArbitrationClientTest {

    private final StubArbitrationClient client = new StubArbitrationClient();

    private DisputeModel disputeWith(RejectReason reason) {
        return DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), reason, "corr");
    }

    @Test
    void mismatchRulesNotFulfilled() {
        Optional<RulingInfo> r = client.requestRuling(disputeWith(RejectReason.A_MISMATCH), null);
        assertThat(r).isPresent();
        assertThat(r.get().category()).isEqualTo(RulingCategory.NOT_FULFILLED);
        assertThat(r.get().rationale()).isNotBlank();
    }

    @Test
    void factualRulesPartiallyFulfilled() {
        assertThat(client.requestRuling(disputeWith(RejectReason.B_FACTUAL), null).orElseThrow().category())
                .isEqualTo(RulingCategory.PARTIALLY_FULFILLED);
    }

    @Test
    void incompleteRulesFulfilled() {
        assertThat(client.requestRuling(disputeWith(RejectReason.C_INCOMPLETE), null).orElseThrow().category())
                .isEqualTo(RulingCategory.FULFILLED);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am test -Dtest=StubArbitrationClientTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `RulingInfo`**

```java
// RulingInfo.java
package com.hireai.application.biz.adjudication.port;

import com.hireai.domain.biz.adjudication.enums.RulingCategory;

/** The raw ruling an arbitrator produces: only a category + rationale (Inv #3 — no money). */
public record RulingInfo(RulingCategory category, String rationale) {}
```

- [ ] **Step 4: Create the `ArbitrationGateway` port**

```java
// ArbitrationGateway.java
package com.hireai.application.biz.adjudication.port;

import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.TaskModel;

import java.util.Optional;

/**
 * ACL port to the arbitration capability. Returns a present {@link RulingInfo} when the ruling is
 * produced synchronously (the Phase-2 stub / tests), or empty when the request was handed off for
 * asynchronous arbitration (the Phase-3 RabbitMQ adapter), in which case the ruling arrives later
 * via the arbitration ruling callback.
 */
public interface ArbitrationGateway {
    Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task);
}
```

- [ ] **Step 5: Create the `StubArbitrationClient`**

```java
// StubArbitrationClient.java
package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Synchronous deterministic arbitration adapter for Phase 2 (before the Python service exists).
 * The reason→category mapping is an arbitrary-but-fixed test fixture chosen so all three settlement
 * branches are reachable; the real LLM ruling arrives via {@code RabbitArbitrationClient} in Phase 3.
 * Active only when no other {@link ArbitrationGateway} bean (the Rabbit adapter) is present.
 */
@Component
@ConditionalOnMissingBean(name = "rabbitArbitrationClient")
public class StubArbitrationClient implements ArbitrationGateway {

    @Override
    public Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task) {
        RulingCategory category = switch (dispute.reasonCategory()) {
            case A_MISMATCH -> RulingCategory.NOT_FULFILLED;
            case B_FACTUAL -> RulingCategory.PARTIALLY_FULFILLED;
            case C_INCOMPLETE -> RulingCategory.FULFILLED;
            case D_CHANGED_MIND -> throw new IllegalStateException("D_CHANGED_MIND never opens a dispute");
        };
        return Optional.of(new RulingInfo(category, "stub ruling for " + dispute.reasonCategory()));
    }
}
```

> IMPLEMENTER NOTE — `@ConditionalOnMissingBean(name = "rabbitArbitrationClient")` keeps the stub active in Phase 2 and lets Phase 3's `RabbitArbitrationClient` (bean name `rabbitArbitrationClient`) silently take over. If the project prefers profile-gating, use `@Profile` consistently with how `RabbitDispatchConfig` is gated — but the conditional-on-missing-bean approach needs no config and keeps tests simplest. Confirm `spring-boot-autoconfigure` is on the infrastructure classpath (it is — the dispatch messaging config uses Spring Boot).

- [ ] **Step 6: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am test -Dtest=StubArbitrationClientTest`
Expected: PASS (3/3).

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-application backend/hireai-infrastructure
git commit -m "feat(adjudication): ArbitrationGateway port + synchronous StubArbitrationClient"
```

---

### Task 6: `DisputeAppService` — open / apply-ruling / fallback

The orchestration: open a dispute, request a ruling, and (when it arrives synchronously) settle deterministically by category and resolve the task. `applyRuling` + `resolveByFallback` are public for Phase 3's callback/DLQ to call.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java`
- Test: `backend/hireai-application/src/test/java/com/hireai/application/biz/adjudication/dispute/DisputeAppServiceImplTest.java`

**Interfaces:**
- Consumes: `DisputeRepository`, `TaskRepository` (`findByIdForUpdate`), `AgentRepository.findOwnerByVersionId`, `SettlementWriteAppService` (`settleAccepted`/`settleSplit`/`settleRejected`), `ArbitrationGateway`.
- Produces:
  - `DisputeAppService.openDispute(TaskModel disputedTask, UUID raisedBy, RejectReason reasonCategory) -> UUID` (returns dispute id). Precondition: `disputedTask.status() == DISPUTED` (the caller already transitioned + saved it).
  - `DisputeAppService.applyRuling(UUID disputeId, RulingInfo ruling)` — first-ruling-wins; loads dispute, records ruling, settles by category, resolves task + dispute. No-op if the dispute is no longer resolvable.
  - `DisputeAppService.resolveByFallback(UUID disputeId)` — full refund + resolve (Phase 3 DLQ 兜底).

- [ ] **Step 1: Write the failing test**

```java
// DisputeAppServiceImplTest.java
package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.dispute.impl.DisputeAppServiceImpl;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DisputeAppServiceImplTest {

    DisputeRepository disputeRepository;
    TaskRepositoryStub taskRepository; // see note: use a Mockito mock of TaskRepository
    AgentRepository agentRepository;
    SettlementWriteAppService settlement;
    ArbitrationGateway gateway;
    DisputeAppServiceImpl service;

    UUID clientId = UUID.randomUUID();
    UUID builderId = UUID.randomUUID();
    UUID agentVersionId = UUID.randomUUID();
    TaskModel disputedTask;

    // NOTE: replace TaskRepositoryStub with a Mockito mock(TaskRepository.class) in the real test;
    // shown inline here only to make the example self-contained for the plan.
    interface TaskRepositoryStub extends com.hireai.domain.biz.task.repository.TaskRepository {}

    @BeforeEach
    void setUp() {
        disputeRepository = mock(DisputeRepository.class);
        taskRepository = mock(TaskRepositoryStub.class);
        agentRepository = mock(AgentRepository.class);
        settlement = mock(SettlementWriteAppService.class);
        gateway = mock(ArbitrationGateway.class);
        service = new DisputeAppServiceImpl(disputeRepository, taskRepository, agentRepository, settlement, gateway);

        when(disputeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(agentRepository.findOwnerByVersionId(agentVersionId)).thenReturn(Optional.of(builderId));

        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.TEXT, null, null), "cat");
        disputedTask = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "ok", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "mismatch");
        when(taskRepository.findByIdForUpdate(disputedTask.id())).thenReturn(Optional.of(disputedTask));
    }

    @Test
    void openWithSynchronousNotFulfilledRulingRefundsAndResolves() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.NOT_FULFILLED, "no")));

        UUID disputeId = service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);

        verify(settlement).settleRejected(eq(disputedTask.id()), eq(clientId), eq(Money.of("100.00")));
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(disputeId).isNotNull();
        // task resolved
        ArgumentCaptor<TaskModel> tcap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository, atLeastOnce()).save(tcap.capture());
        assertThat(tcap.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
    }

    @Test
    void synchronousPartialRulingSplits() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.PARTIALLY_FULFILLED, "half")));
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verify(settlement).settleSplit(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
    }

    @Test
    void synchronousFulfilledRulingPaysBuilder() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.FULFILLED, "ok")));
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verify(settlement).settleAccepted(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
    }

    @Test
    void emptyGatewayResponseLeavesDisputeArbitrating() {
        when(gateway.requestRuling(any(), any())).thenReturn(Optional.empty());
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verifyNoInteractions(settlement);
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.ARBITRATING);
    }

    @Test
    void applyRulingIsNoOpWhenDisputeAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new com.hireai.domain.biz.adjudication.model.Ruling(
                        1, RulingCategory.FULFILLED, "x",
                        com.hireai.domain.biz.adjudication.enums.RulingDecidedBy.ARBITRATOR))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        service.applyRuling(resolved.id(), new RulingInfo(RulingCategory.NOT_FULFILLED, "late"));

        verifyNoInteractions(settlement);
    }
}
```

> IMPLEMENTER NOTE — drop the `TaskRepositoryStub` indirection; use `mock(com.hireai.domain.biz.task.repository.TaskRepository.class)` directly. Confirm `AgentRepository.findOwnerByVersionId` package/signature from the accept path. Confirm the existing app-test convention (plain Mockito `mock(...)`, no Spring context) matches Phase-1 app tests.

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-application -am test -Dtest=DisputeAppServiceImplTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create the interface**

```java
// DisputeAppService.java
package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.lang.NonNull;

import java.util.UUID;

public interface DisputeAppService {

    /** Open a dispute for an already-DISPUTED task and request a ruling; settles inline if the ruling is synchronous. */
    UUID openDispute(@NonNull TaskModel disputedTask, @NonNull UUID raisedBy, @NonNull RejectReason reasonCategory);

    /** Apply an arbitrator ruling (first-ruling-wins): settle by category + resolve. No-op if not resolvable. */
    void applyRuling(@NonNull UUID disputeId, @NonNull RulingInfo ruling);

    /** Platform refund fallback (DLQ 兜底): full refund + resolve. */
    void resolveByFallback(@NonNull UUID disputeId);
}
```

- [ ] **Step 4: Implement the service**

```java
// DisputeAppServiceImpl.java
package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DisputeAppServiceImpl implements DisputeAppService {

    private static final int TIER_1 = 1;

    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ArbitrationGateway arbitrationGateway;

    @Override
    public UUID openDispute(TaskModel disputedTask, UUID raisedBy, RejectReason reasonCategory) {
        String correlationId = "dispute-" + disputedTask.id();
        DisputeModel dispute = DisputeModel.open(disputedTask.id(), raisedBy, reasonCategory, correlationId);
        disputeRepository.save(dispute);

        Optional<RulingInfo> immediate = arbitrationGateway.requestRuling(dispute, disputedTask);
        if (immediate.isPresent()) {
            settleAndResolve(dispute, immediate.get(), RulingDecidedBy.ARBITRATOR);
        } else {
            disputeRepository.save(dispute.startArbitrating());
            log.info("Dispute {} handed off for async arbitration", dispute.id());
        }
        return dispute.id();
    }

    @Override
    public void applyRuling(UUID disputeId, RulingInfo ruling) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.isResolvable()) {
            log.info("Dispute {} already {}; ruling ignored (first-ruling-wins)", disputeId, dispute.status());
            return;
        }
        settleAndResolve(dispute, ruling, RulingDecidedBy.ARBITRATOR);
    }

    @Override
    public void resolveByFallback(UUID disputeId) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.isResolvable()) {
            log.info("Dispute {} already {}; fallback ignored", disputeId, dispute.status());
            return;
        }
        Ruling fallback = new Ruling(TIER_1, RulingCategory.NOT_FULFILLED,
                "arbitration unavailable; refunded by platform fallback", RulingDecidedBy.FALLBACK);
        TaskModel task = lockTask(dispute.taskId());
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        taskRepository.save(task.resolveDispute(TaskResolution.REJECTED));
        disputeRepository.save(dispute.resolveByFallback(fallback));
        log.info("Dispute {} resolved by refund fallback", disputeId);
    }

    /** Records the ruling, settles deterministically by category, and resolves both task and dispute. */
    private void settleAndResolve(DisputeModel dispute, RulingInfo info, RulingDecidedBy decidedBy) {
        Ruling ruling = new Ruling(TIER_1, info.category(), info.rationale(), decidedBy);
        DisputeModel ruled = dispute.recordRuling(ruling);

        TaskModel task = lockTask(dispute.taskId());
        UUID budgetTaskId = task.id();
        switch (info.category()) {
            case FULFILLED -> {
                UUID builderId = requireBuilder(task);
                settlementWriteAppService.settleAccepted(budgetTaskId, task.clientId(), builderId, task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.ACCEPTED));
            }
            case PARTIALLY_FULFILLED -> {
                UUID builderId = requireBuilder(task);
                settlementWriteAppService.settleSplit(budgetTaskId, task.clientId(), builderId, task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.ACCEPTED));
            }
            case NOT_FULFILLED -> {
                settlementWriteAppService.settleRejected(budgetTaskId, task.clientId(), task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.REJECTED));
            }
        }
        disputeRepository.save(ruled.resolve());
    }

    private TaskModel lockTask(UUID taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }

    private UUID requireBuilder(TaskModel task) {
        return agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No builder for agent version " + task.agentVersionId()));
    }

    private DisputeModel requireDispute(UUID disputeId) {
        return disputeRepository.findById(disputeId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Dispute not found: " + disputeId));
    }
}
```

> IMPLEMENTER NOTE — verify `AgentRepository.findOwnerByVersionId(UUID) -> Optional<UUID>` import path matches the one used by `TaskReviewAppServiceImpl.accept`. Verify `@NonNull` import (`org.springframework.lang.NonNull`) matches the project convention used in `TaskReviewAppService`.

- [ ] **Step 5: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-application -am test -Dtest=DisputeAppServiceImplTest`
Expected: PASS (5/5).

- [ ] **Step 6: Run the full build, verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-application
git commit -m "feat(adjudication): DisputeAppService (open/apply-ruling/fallback)"
```

---

### Task 7: Reason-gated reject wiring

Make the existing reject endpoint require a reason category and fork: `D` → charge, `A/B/C` → open dispute.

**Files:**
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/dto/RejectTaskRequest.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/TaskReviewAppService.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/TaskReviewAppServiceImpl.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/TaskController.java` (reject handler — pass the category through)
- Test: `backend/hireai-application/src/test/java/com/hireai/application/biz/task/TaskReviewRejectGateTest.java`
- Modify (reconcile): existing reject tests that call `reject(taskId, clientId, reason)` with the old 3-arg signature.

**Interfaces:**
- Produces: `TaskReviewAppService.reject(UUID taskId, UUID clientId, RejectReason reasonCategory, String reason) -> UUID` (category now **required**; null → `VALIDATION_ERROR`).
- `RejectTaskRequest(@NotNull RejectReason reasonCategory, @Size(max=500) String reason)`.

> DESIGN NOTE — the controller maps the body to `(reasonCategory, reason)`. Because `reasonCategory` is now required, the reject body is no longer optional: change `@RequestBody(required = false)` to `@Valid @RequestBody RejectTaskRequest` (a missing/invalid body → 400 via bean validation). `D_CHANGED_MIND` → `task.chargeChangedMind` + `settleAccepted` (charge); `A/B/C` → `task.dispute` (save) → `disputeAppService.openDispute`.

- [ ] **Step 1: Write the failing test**

```java
// TaskReviewRejectGateTest.java
package com.hireai.application.biz.task;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.impl.TaskReviewAppServiceImpl;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskReviewRejectGateTest {

    TaskRepository taskRepository;
    AgentRepository agentRepository;
    SettlementWriteAppService settlement;
    DisputeAppService disputeAppService;
    TaskReviewAppServiceImpl service;

    UUID clientId = UUID.randomUUID();
    UUID builderId = UUID.randomUUID();
    UUID agentVersionId = UUID.randomUUID();
    TaskModel pendingReview;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        agentRepository = mock(AgentRepository.class);
        settlement = mock(SettlementWriteAppService.class);
        disputeAppService = mock(DisputeAppService.class);
        service = new TaskReviewAppServiceImpl(taskRepository, agentRepository, settlement, disputeAppService);

        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(agentRepository.findOwnerByVersionId(agentVersionId)).thenReturn(Optional.of(builderId));

        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.TEXT, null, null), "cat");
        pendingReview = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "ok", null))
                .passValidation();
        when(taskRepository.findByIdForUpdate(pendingReview.id())).thenReturn(Optional.of(pendingReview));
    }

    @Test
    void changedMindChargesClientAndOpensNoDispute() {
        service.reject(pendingReview.id(), clientId, RejectReason.D_CHANGED_MIND, "not needed");
        verify(settlement).settleAccepted(eq(pendingReview.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
        verifyNoInteractions(disputeAppService);
        ArgumentCaptor<TaskModel> cap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(cap.getValue().rejectReasonCategory()).isEqualTo(RejectReason.D_CHANGED_MIND);
    }

    @Test
    void mismatchOpensDispute() {
        service.reject(pendingReview.id(), clientId, RejectReason.A_MISMATCH, "wrong");
        verify(disputeAppService).openDispute(any(TaskModel.class), eq(clientId), eq(RejectReason.A_MISMATCH));
        verify(settlement, never()).settleRejected(any(), any(), any());
        ArgumentCaptor<TaskModel> cap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(TaskStatus.DISPUTED);
    }

    @Test
    void nullReasonCategoryRejected() {
        assertThatThrownBy(() -> service.reject(pendingReview.id(), clientId, null, "x"))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-application -am test -Dtest=TaskReviewRejectGateTest`
Expected: COMPILATION FAILURE (4-arg `reject` + constructor arg absent).

- [ ] **Step 3: Update the interface**

In `TaskReviewAppService.java`:
```java
    UUID reject(@NonNull UUID taskId, @NonNull UUID clientId, RejectReason reasonCategory, String reason);
```
(import `com.hireai.domain.biz.task.enums.RejectReason`). Keep `@Validated` on the interface.

- [ ] **Step 4: Update the impl**

In `TaskReviewAppServiceImpl.java`: add `private final DisputeAppService disputeAppService;` to the constructor (it is `@RequiredArgsConstructor`, so just add the field), and replace `reject`:

```java
    @Override
    public UUID reject(UUID taskId, UUID clientId, RejectReason reasonCategory, String reason) {
        if (reasonCategory == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A reject reason category is required");
        }
        TaskModel task = loadOwned(taskId, clientId);

        if (reasonCategory == RejectReason.D_CHANGED_MIND) {
            // Buyer's remorse on conformant work → charge 85/15, no dispute.
            UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                    .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                            "No builder for agent version " + task.agentVersionId()));
            settlementWriteAppService.settleAccepted(taskId, clientId, builderId, task.budget());
            taskRepository.save(task.chargeChangedMind(reason));
            return taskId;
        }

        // A/B/C → open a dispute; settlement happens when the ruling lands.
        TaskModel disputed = task.dispute(reasonCategory, reason);
        taskRepository.save(disputed);
        disputeAppService.openDispute(disputed, clientId, reasonCategory);
        return taskId;
    }
```

(imports: `RejectReason`, `DisputeAppService`; `DomainException`/`ResultCode` already present.)

- [ ] **Step 5: Update the controller DTO + handler**

`RejectTaskRequest.java`:
```java
package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.enums.RejectReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RejectTaskRequest(@NotNull RejectReason reasonCategory, @Size(max = 500) String reason) {}
```

`TaskController.java` reject handler — require a valid body and pass the category:
```java
    @PostMapping("/{id}/reject")
    public WebResult<TaskDTO> reject(@PathVariable("id") UUID id,
                                     @Valid @RequestBody RejectTaskRequest request) {
        UUID clientId = currentUser.currentUserId();
        reviewAppService.reject(id, clientId, request.reasonCategory(), request.reason());
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId)));
    }
```

- [ ] **Step 6: Reconcile existing reject tests**

Find every caller of the old 3-arg `reject(...)` and the old `RejectTaskRequest(reason)`:
Run: `grep -rn "reject(" backend --include=*.java | grep -i test` and `grep -rn "new RejectTaskRequest" backend`.
Update each to the new signatures: supply a `RejectReason` (use `RejectReason.D_CHANGED_MIND` to preserve the old "reject resolves the task with a charge" outcome, **or** `A_MISMATCH` if the test intends a dispute — choose per the test's asserted outcome). For any controller/web test asserting the old "reject → full refund" behavior, update the assertion to the new semantics (`D` → charge / `A` → dispute). Do not weaken assertions; change them to the correct new outcome.

> IMPLEMENTER NOTE — this is the riskiest reconcile. List each touched test in the report with the old vs new expected outcome. If a test's intent is genuinely "client rejects and is fully refunded", that outcome no longer exists unconditionally (only `NOT_FULFILLED` via dispute refunds) — flag it rather than silently forcing a pass.

- [ ] **Step 7: Run the targeted + full build**

Run: `mvn -f backend/pom.xml -pl hireai-application -am test -Dtest=TaskReviewRejectGateTest`
Then: `mvn -f backend/pom.xml -q -B test`
Expected: PASS (3/3) then BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/hireai-application backend/hireai-controller
git commit -m "feat(task): reason-gated reject (D charges, A/B/C open a dispute)"
```

---

### Task 8: Integration test — dispute happy path + D-charge + reason-gate

End-to-end through Testcontainers Postgres: reject with A/B/C drives a stub ruling → deterministic settlement → task `RESOLVED` + dispute `RESOLVED` + correct wallet balances and settlement row type; `D` charges; a missing category is rejected.

**Files:**
- Create: `backend/hireai-main/src/test/java/com/hireai/main/adjudication/DisputeFlowIntegrationTest.java`

**Interfaces:**
- Consumes: the full assembled context (the stub `ArbitrationGateway` is active — no Rabbit adapter exists yet).

- [ ] **Step 1: Write the integration test**

```java
// DisputeFlowIntegrationTest.java
package com.hireai.main.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the Phase-2 dispute flow against the synchronous StubArbitrationClient:
 *   A_MISMATCH → NOT_FULFILLED → full refund
 *   B_FACTUAL → PARTIALLY_FULFILLED → SPLIT
 *   C_INCOMPLETE → FULFILLED → 85/15 payout
 * plus D_CHANGED_MIND → charge, and a missing category → rejected.
 */
class DisputeFlowIntegrationTest extends PostgresIntegrationTest {

    @Autowired TaskReviewAppService reviewAppService;   // import from application
    @Autowired TaskRepository taskRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired SettlementRepository settlementRepository;
    @Autowired WalletRepository walletRepository;
    // ... plus whatever helper seeds a PENDING_REVIEW task with a funded client wallet + an agent/builder.

    // IMPLEMENTER: build a small fixture that (a) opens + funds a client wallet, (b) registers an agent
    // version owned by a builder, (c) creates a task through submit→assign→execute→recordResult→passValidation
    // so it sits at PENDING_REVIEW with the budget in escrow. Reuse the seeding helpers/builders that
    // ValidationGateIntegrationTest already uses; do NOT hand-insert rows that bypass the aggregates.

    @Test
    void mismatchDisputeRefundsClientInFull() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.A_MISMATCH, "wrong output");

        TaskModel task = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(disputeRepository.findByTaskId(f.taskId()).orElseThrow().status())
                .isEqualTo(DisputeStatus.RESOLVED);
        WalletModel client = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(client.available()).isEqualTo(Money.of("100.00")); // fully refunded
        assertThat(client.escrow()).isEqualTo(Money.ZERO);
        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.REJECT);
    }

    @Test
    void factualDisputeSplits() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.B_FACTUAL, "some errors");

        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.SPLIT);
        WalletModel client = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(client.available()).isEqualTo(Money.of("50.00")); // half refunded
        WalletModel builder = walletRepository.findByUserId(f.builderId()).orElseThrow();
        assertThat(builder.available()).isEqualTo(Money.of("42.50")); // half net
    }

    @Test
    void incompleteDisputePaysBuilder() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.C_INCOMPLETE, "missing parts");

        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.ACCEPT);
        WalletModel builder = walletRepository.findByUserId(f.builderId()).orElseThrow();
        assertThat(builder.available()).isEqualTo(Money.of("85.00"));
    }

    @Test
    void changedMindCharges() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.D_CHANGED_MIND, "not needed");

        assertThat(disputeRepository.findByTaskId(f.taskId())).isEmpty(); // no dispute row
        WalletModel builder = walletRepository.findByUserId(f.builderId()).orElseThrow();
        assertThat(builder.available()).isEqualTo(Money.of("85.00"));
        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.ACCEPT);
    }
}
```

> IMPLEMENTER NOTE — the `Fixture` + `seedPendingReview(...)` helper is the real work here. Reuse the exact seeding approach from `ValidationGateIntegrationTest` (same base class, same wallet/agent seeding). If the client and builder are seeded as the same user in existing tests, use **distinct** users here so the builder-payout assertions are meaningful. The numbers assume a 100.00 budget, 15% commission: FULFILLED→builder 85.00; SPLIT→builder 42.50 + client refund 50.00; NOT_FULFILLED→client 100.00.

- [ ] **Step 2: Run it (skips without Docker)**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=DisputeFlowIntegrationTest`
Expected: PASS with Docker; SKIPPED without Docker.

- [ ] **Step 3: Run the full build, verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/hireai-main
git commit -m "test(adjudication): end-to-end dispute flow (refund/split/payout + charge + reason-gate)"
```

---

## Self-review checklist (run before dispatching execution)

1. **Spec coverage** — §5 reason-gate (D-charge vs A/B/C-dispute) → Task 7; `Dispute` aggregate + `Ruling` + ruling→settlement → Tasks 2, 6; `SPLIT` wired → Task 1; `ArbitrationGateway` + stub → Task 5; `V17` (disputes + `tasks.reject_reason_category`) → Task 3; `DISPUTED` task state → Task 4; integration coverage of all three categories → Task 8. `ARBITRATING`/`ESCALATED`/`resolveByFallback`/the empty-Optional async branch are built but exercised only by unit tests in Phase 2 (Phase 3 wires the transport).
2. **Type consistency** — `reject(UUID,UUID,RejectReason,String)` used identically in interface, impl, controller, and tests; `settleSplit(UUID,UUID,UUID,Money)` identical in app interface/impl and `DisputeAppServiceImpl`; `requestRuling(DisputeModel,TaskModel)->Optional<RulingInfo>` identical in port, stub, and app service; `DisputeModel.open/recordRuling/resolve/resolveByFallback/startArbitrating` names match across Tasks 2/3/6.
3. **No placeholders** — every step carries real code except the two clearly-flagged IMPLEMENTER fixture notes (Task 3 base class, Task 8 seeding), which require reading existing test helpers rather than inventing them.
4. **Invariants** — Inv #3: all money via `ledger.settlement` from the category; the gateway returns only category+rationale. Inv #2: settlement still appends ledger + settlement rows. Inv #5: `clientId`/`raisedBy` from JWT, owner-check retained. SPLIT reconciles by construction (refund-by-subtraction).
