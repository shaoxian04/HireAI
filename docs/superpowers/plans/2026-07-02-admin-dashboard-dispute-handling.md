# Admin Dashboard + Administrator Dispute Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the platform an ADMIN surface — a read-only overview plus a human backstop that rules disputes the arbitrator could not resolve.

**Architecture:** A DLQ'd or stale arbitration escalates its dispute to a new `ESCALATED` state (via the existing `ArbitrationDlqListener` and a new `@Scheduled` sweeper). An admin then issues a tier-2 `ADMINISTRATOR` ruling that settles escrow once, deterministically, from the chosen category (money moves exactly once — no reversal). A thin `JdbcAdminQueryDao` read layer powers the overview + browsers; a new `/api/admin/**` controller gated by `ROLE_ADMIN` and a new `/admin` Next.js surface expose it.

**Tech Stack:** Spring Boot (Java 21, COLA multi-module reactor), Flyway/Postgres, RabbitMQ, JUnit5 + Mockito + Testcontainers; Next.js 16 (App Router, TS, Tailwind) + Vitest + Testing Library + MSW.

## Global Constraints

- **Design spec:** `docs/superpowers/specs/2026-07-02-admin-dashboard-dispute-handling-design.md`. This plan implements it verbatim; do not add scope.
- **Money invariants (never compromise):** the LLM/admin produces only a `{category, rationale}`; Java computes settlement deterministically (Inv #3). `ledger_entries` + `dispute_rulings` are append-only (Inv #2). The admin rules only **un-settled** disputes (`OPEN|ARBITRATING|ESCALATED`); money moves once, no reversal, no claw-back.
- **Server-side identity (Inv #5):** the admin's id comes from `CurrentUserProvider.currentUserId()` (JWT principal), never a path/body. `/api/admin/**` requires `ROLE_ADMIN`.
- **Layering:** `controller → application → domain ← infrastructure/repository`; `domain`/`utility` carry no Spring. Bootable module is `hireai-main` only.
- **Migrations:** never edit an applied migration (Flyway checksum). New file only — next number is **`V23`**.
- **Commits:** Conventional Commits (`feat:`/`fix:`/`test:`/`docs:`). **No `Co-Authored-By` trailer** (attribution disabled globally). Never `git add -A` — stage only the exact paths listed. Never `--no-verify` (a hook blocks it).
- **DomainException → HTTP:** `NOT_FOUND → 404`; `DOMAIN_RULE_VIOLATION` / `INSUFFICIENT_BALANCE → 409`; else `400` (`GlobalExceptionConfiguration`).
- **`DisputeStatus` already allows `ESCALATED`** (V17 CHECK) and `dispute_rulings.decided_by` already allows `ADMINISTRATOR` (V21 CHECK) — **no schema change** for those.
- **Enums:** `RulingCategory = {FULFILLED, PARTIALLY_FULFILLED, NOT_FULFILLED}`; `RulingDecidedBy = {ARBITRATOR, ADMINISTRATOR, FALLBACK}`; `DisputeStatus = {OPEN, ARBITRATING, RULED, RESOLVED, ESCALATED}`; `TaskResolution = {ACCEPTED, REJECTED, PARTIALLY_ACCEPTED}`.
- **Test commands** (from repo root unless noted):
  - Domain unit: `mvn -f backend/pom.xml -pl hireai-domain -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test`
  - App unit: `mvn -f backend/pom.xml -pl hireai-application -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test`
  - Infra unit: `mvn -f backend/pom.xml -pl hireai-infrastructure -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test`
  - Integration (Testcontainers, in `hireai-main`; **auto-skips without Docker**): `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=<Class> test`
  - Full backend build: `mvn -f backend/pom.xml -q -B package`
  - Frontend test: `cd frontend && npx vitest run <path> --no-file-parallelism --pool=forks`
  - Frontend lint/build: `npm --prefix frontend run lint` · `npm --prefix frontend run build`

---

## Task 1: Domain — `escalate()` transition + widen `recordRuling` to accept `ESCALATED`

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/DisputeModelTest.java`

**Interfaces:**
- Consumes: existing `DisputeModel` (private ctor, `DisputeStatus`, `Ruling`, `DomainException`, `ResultCode`).
- Produces: `DisputeModel escalate()` (`OPEN|ARBITRATING → ESCALATED`); `recordRuling(Ruling)` now also accepts an `ESCALATED` origin. `isResolvable()` is **unchanged** (`OPEN|ARBITRATING`).

- [ ] **Step 1: Write the failing tests** — append to `DisputeModelTest.java` (before the closing brace):

```java
    @Test
    void escalateFromArbitratingSignalsNeedsAdmin() {
        DisputeModel d = openDispute().startArbitrating().escalate();
        assertThat(d.status()).isEqualTo(DisputeStatus.ESCALATED);
        // isResolvable() gates the ARBITRATOR callback; an escalated dispute must be excluded so a
        // late arbitrator ruling is ignored and the admin owns it.
        assertThat(d.isResolvable()).isFalse();
    }

    @Test
    void escalateFromOpenIsAllowed() {
        assertThat(openDispute().escalate().status()).isEqualTo(DisputeStatus.ESCALATED);
    }

    @Test
    void escalateRejectedOnceResolved() {
        DisputeModel resolved = openDispute()
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR, FIXED))
                .resolve();
        assertThatThrownBy(resolved::escalate).isInstanceOf(DomainException.class);
    }

    @Test
    void adminCanRuleAnEscalatedDisputeAtTierTwo() {
        Ruling admin = new Ruling(2, RulingCategory.NOT_FULFILLED, "backstop refund",
                RulingDecidedBy.ADMINISTRATOR, FIXED);
        DisputeModel resolved = openDispute().startArbitrating().escalate().recordRuling(admin).resolve();
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.effectiveRuling().get().decidedBy()).isEqualTo(RulingDecidedBy.ADMINISTRATOR);
        assertThat(resolved.effectiveRuling().get().tier()).isEqualTo(2);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am -Dtest=DisputeModelTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compile error `cannot find symbol: method escalate()`.

- [ ] **Step 3: Add `escalate()` and widen `recordRuling`** in `DisputeModel.java`.

Replace the existing `recordRuling` guard. The current method starts:
```java
    public DisputeModel recordRuling(Ruling ruling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "recordRuling requires OPEN|ARBITRATING; was " + status);
        }
```
Change that guard to also permit `ESCALATED`:
```java
    public DisputeModel recordRuling(Ruling ruling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING
                && status != DisputeStatus.ESCALATED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "recordRuling requires OPEN|ARBITRATING|ESCALATED; was " + status);
        }
```

Add the new transition immediately after `startArbitrating()` (keep the file's javadoc style):
```java
    /**
     * OPEN|ARBITRATING → ESCALATED: hand a stranded dispute to the human admin backstop. Written by
     * the DLQ listener (loud failure) and the stale-arbitration sweeper (quiet failure). Records no
     * ruling — escalation only flags "needs admin". isResolvable() now returns false, so a late
     * ARBITRATOR callback is ignored (the admin owns it).
     */
    public DisputeModel escalate() {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "escalate requires OPEN|ARBITRATING; was " + status);
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ESCALATED,
                rulings, correlationId, createdAt, resolvedAt);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am -Dtest=DisputeModelTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (all existing + 4 new tests).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/DisputeModelTest.java
git commit -m "feat(adjudication): DisputeModel.escalate() + allow admin ruling from ESCALATED"
```

---

## Task 2: Application — `adminRule` + `escalate` + stale-query; `settleAndResolve(tier, decidedBy)` refactor

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/DisputeRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeJpaRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryImpl.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java`
- Test: `backend/hireai-application/src/test/java/com/hireai/application/biz/adjudication/dispute/DisputeAppServiceImplTest.java`

**Interfaces:**
- Consumes: `DisputeModel.escalate()` (Task 1); existing `SettlementWriteAppService`, `DisputeRepository`, `TaskRepository`, `AgentRepository`, `RulingInfo`, `Ruling`.
- Produces on `DisputeAppService`:
  - `void escalate(UUID disputeId)` — no-op if not `isResolvable()`.
  - `void adminRule(UUID disputeId, RulingCategory category, String rationale, UUID adminId)` — tier-2 `ADMINISTRATOR`; throws `DomainException(DOMAIN_RULE_VIOLATION)` if the dispute isn't `OPEN|ARBITRATING|ESCALATED`.
  - `List<UUID> staleArbitratingDisputeIds(Instant cutoff)`.
  - `resolveByFallback` is **kept** here (removed in Task 3 once the listener stops calling it).
- Produces on `DisputeRepository`: `List<UUID> findStaleArbitratingIds(Instant cutoff)`.

- [ ] **Step 1: Write the failing app-service tests** — append to `DisputeAppServiceImplTest.java` (before the closing brace). Add imports at the top: `import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;` (already present), `import java.util.List;`.

```java
    @Test
    void escalateTransitionsArbitratingToEscalated() {
        DisputeModel arbitrating = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating();
        when(disputeRepository.findById(arbitrating.id())).thenReturn(Optional.of(arbitrating));

        service.escalate(arbitrating.id());

        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.ESCALATED);
    }

    @Test
    void escalateIsNoOpWhenAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "x", RulingDecidedBy.ARBITRATOR,
                        Instant.parse("2026-07-01T00:00:00Z")))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        service.escalate(resolved.id());

        verify(disputeRepository, never()).save(any());
    }

    @Test
    void adminRuleOnEscalatedRefundsAndResolvesAtTierTwo() {
        DisputeModel escalated = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating().escalate();
        when(disputeRepository.findById(escalated.id())).thenReturn(Optional.of(escalated));

        service.adminRule(escalated.id(), RulingCategory.NOT_FULFILLED, "backstop refund", UUID.randomUUID());

        verify(settlement).settleRejected(eq(disputedTask.id()), eq(clientId), eq(Money.of("100.00")));
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        DisputeModel saved = cap.getValue();
        assertThat(saved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(saved.effectiveRuling().get().decidedBy()).isEqualTo(RulingDecidedBy.ADMINISTRATOR);
        assertThat(saved.effectiveRuling().get().tier()).isEqualTo(2);
    }

    @Test
    void adminRuleFulfilledPaysBuilder() {
        DisputeModel escalated = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating().escalate();
        when(disputeRepository.findById(escalated.id())).thenReturn(Optional.of(escalated));

        service.adminRule(escalated.id(), RulingCategory.FULFILLED, "meets spec", UUID.randomUUID());

        verify(settlement).settleAccepted(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
    }

    @Test
    void adminRuleRejectedWhenDisputeAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "x", RulingDecidedBy.ARBITRATOR,
                        Instant.parse("2026-07-01T00:00:00Z")))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.adminRule(resolved.id(), RulingCategory.NOT_FULFILLED, "late", UUID.randomUUID()))
                .isInstanceOf(com.hireai.utility.exception.DomainException.class);
        verifyNoInteractions(settlement);
    }

    @Test
    void staleArbitratingDisputeIdsDelegatesToRepository() {
        Instant cutoff = Instant.parse("2026-07-01T00:00:00Z");
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(disputeRepository.findStaleArbitratingIds(cutoff)).thenReturn(ids);

        assertThat(service.staleArbitratingDisputeIds(cutoff)).isEqualTo(ids);
    }
```

Add `import static org.mockito.Mockito.never;` if not covered by the existing `import static org.mockito.Mockito.*;` (it is — the file already wildcard-imports Mockito).

- [ ] **Step 2: Run to verify failure**

Run: `mvn -f backend/pom.xml -pl hireai-application -am -Dtest=DisputeAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `cannot find symbol: method escalate(UUID)` / `adminRule(...)` / `staleArbitratingDisputeIds(...)` / `findStaleArbitratingIds(...)`.

- [ ] **Step 3a: Extend `DisputeRepository`** (domain interface) — add:
```java
    java.util.List<UUID> findStaleArbitratingIds(java.time.Instant cutoff);
```

- [ ] **Step 3b: Add the JPA query** in `DisputeJpaRepository.java`:
```java
    @org.springframework.data.jpa.repository.Query(
        "SELECT d.id FROM DisputeDO d WHERE d.status = 'ARBITRATING' AND d.gmtCreate < :cutoff")
    java.util.List<UUID> findStaleArbitratingIds(java.time.Instant cutoff);
```

- [ ] **Step 3c: Implement in `DisputeRepositoryImpl.java`** — add the delegating method and its imports (`java.time.Instant` already imported):
```java
    @Override
    public List<UUID> findStaleArbitratingIds(Instant cutoff) {
        return jpa.findStaleArbitratingIds(cutoff);
    }
```

- [ ] **Step 3d: Extend `DisputeAppService`** interface — add (keep existing methods incl. `resolveByFallback`):
```java
    /** OPEN|ARBITRATING → ESCALATED (DLQ or stale-sweep). No-op if already resolved/escalated. */
    void escalate(@NonNull UUID disputeId);

    /** Human-backstop ruling on an un-settled dispute: settle once by category, resolve. tier-2 ADMINISTRATOR. */
    void adminRule(@NonNull UUID disputeId, @NonNull com.hireai.domain.biz.adjudication.enums.RulingCategory category,
                   String rationale, @NonNull UUID adminId);

    /** Ids of disputes stuck in ARBITRATING since before {@code cutoff} (for the sweeper). */
    java.util.List<UUID> staleArbitratingDisputeIds(@NonNull java.time.Instant cutoff);
```

- [ ] **Step 3e: Implement in `DisputeAppServiceImpl.java`.** Add `import com.hireai.domain.biz.adjudication.enums.DisputeStatus;` and `import java.util.List;`. Add a constant next to `TIER_1`:
```java
    private static final int TIER_2 = 2;
```
Change the `settleAndResolve` signature and its two callers. Current:
```java
    private void settleAndResolve(DisputeModel dispute, RulingInfo info, RulingDecidedBy decidedBy) {
        Ruling ruling = new Ruling(TIER_1, info.category(), info.rationale(), decidedBy, Instant.now());
```
becomes:
```java
    private void settleAndResolve(DisputeModel dispute, RulingInfo info, int tier, RulingDecidedBy decidedBy) {
        Ruling ruling = new Ruling(tier, info.category(), info.rationale(), decidedBy, Instant.now());
```
Update the two existing call sites to pass `TIER_1`:
- in `openDispute`: `settleAndResolve(dispute, immediate.get(), TIER_1, RulingDecidedBy.ARBITRATOR);`
- in `applyRuling`: `settleAndResolve(dispute, ruling, TIER_1, RulingDecidedBy.ARBITRATOR);`

Add the new methods:
```java
    @Override
    public void escalate(UUID disputeId) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.isResolvable()) {
            log.info("Dispute {} is {}; escalate skipped", disputeId, dispute.status());
            return;
        }
        disputeRepository.save(dispute.escalate());
        log.info("Dispute {} escalated to ESCALATED (needs admin backstop)", disputeId);
    }

    @Override
    public void adminRule(UUID disputeId, RulingCategory category, String rationale, UUID adminId) {
        DisputeModel dispute = requireDispute(disputeId);
        DisputeStatus s = dispute.status();
        if (s != DisputeStatus.OPEN && s != DisputeStatus.ARBITRATING && s != DisputeStatus.ESCALATED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute " + disputeId + " is " + s + "; already settled — admin cannot re-rule");
        }
        log.info("Admin {} ruling dispute {} as {}", adminId, disputeId, category);
        settleAndResolve(dispute, new RulingInfo(category, rationale), TIER_2, RulingDecidedBy.ADMINISTRATOR);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> staleArbitratingDisputeIds(Instant cutoff) {
        return disputeRepository.findStaleArbitratingIds(cutoff);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -f backend/pom.xml -pl hireai-application -am -Dtest=DisputeAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS. Then confirm the reactor still compiles (the listener still uses `resolveByFallback`, kept intentionally):
Run: `mvn -f backend/pom.xml -q -B -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/DisputeRepository.java backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeJpaRepository.java backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryImpl.java backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java backend/hireai-application/src/test/java/com/hireai/application/biz/adjudication/dispute/DisputeAppServiceImplTest.java
git commit -m "feat(adjudication): admin backstop ruling + escalate + stale-arbitration query"
```

---

## Task 3: Infra — DLQ escalates (drop auto-refund); scheduled stale-sweeper

**Files:**
- Modify: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ArbitrationDlqListener.java`
- Modify: `backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/messaging/ArbitrationDlqListenerTest.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ArbitrationSweeper.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/SchedulingConfig.java`
- Test: `backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/messaging/ArbitrationSweeperTest.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java` (remove `resolveByFallback`)
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java` (remove `resolveByFallback`)
- Modify: `backend/hireai-main/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `DisputeAppService.escalate(UUID)` + `staleArbitratingDisputeIds(Instant)` (Task 2).
- Produces: `ArbitrationSweeper.sweep()` (callable directly by tests) invoked by `@Scheduled scheduledSweep()`; `SchedulingConfig` enabling scheduling under `!test`.

- [ ] **Step 1: Write the failing sweeper test** — `ArbitrationSweeperTest.java`:

```java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArbitrationSweeperTest {

    @Test
    void escalatesEveryStaleDispute() {
        DisputeAppService service = mock(DisputeAppService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.staleArbitratingDisputeIds(any(Instant.class))).thenReturn(List.of(a, b));

        ArbitrationSweeper sweeper = new ArbitrationSweeper(service, Duration.ofMinutes(2));
        sweeper.sweep();

        verify(service).escalate(a);
        verify(service).escalate(b);
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        DisputeAppService service = mock(DisputeAppService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.staleArbitratingDisputeIds(any(Instant.class))).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(service).escalate(a);

        ArbitrationSweeper sweeper = new ArbitrationSweeper(service, Duration.ofMinutes(2));
        sweeper.sweep();

        verify(service).escalate(b); // b still processed despite a throwing
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am -Dtest=ArbitrationSweeperTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `ArbitrationSweeper` does not exist.

- [ ] **Step 3a: Create `ArbitrationSweeper.java`:**

```java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Human-backstop sweeper: a dispute whose worker ACKed but never posted a ruling never dead-letters,
 * so it sits in ARBITRATING forever. This job flips any ARBITRATING dispute older than
 * {@code hireai.arbitration.stale-after} to ESCALATED so an admin can rule it. Each escalate() is a
 * cross-bean call (own transaction); a late arbitrator callback is guarded out by first-ruling-wins.
 */
@Slf4j
@Component
@Profile("!test")
public class ArbitrationSweeper {

    private final DisputeAppService disputeAppService;
    private final Duration staleAfter;

    public ArbitrationSweeper(DisputeAppService disputeAppService,
                              @Value("${hireai.arbitration.stale-after:PT2M}") Duration staleAfter) {
        this.disputeAppService = disputeAppService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${hireai.arbitration.sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: does one pass, escalating every stale dispute. */
    void sweep() {
        Instant cutoff = Instant.now().minus(staleAfter);
        List<UUID> stale = disputeAppService.staleArbitratingDisputeIds(cutoff);
        for (UUID id : stale) {
            try {
                disputeAppService.escalate(id);
            } catch (Exception e) {
                log.warn("Sweeper: failed to escalate stale dispute {}", id, e);
            }
        }
        if (!stale.isEmpty()) {
            log.info("Sweeper: escalated {} stale ARBITRATING dispute(s) to admin", stale.size());
        }
    }
}
```

- [ ] **Step 3b: Create `SchedulingConfig.java`** (enable scheduling outside tests):

```java
package com.hireai.infrastructure.messaging;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled beans (the arbitration sweeper) outside the permissive test profile. */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 3c: Run the sweeper test to verify pass**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am -Dtest=ArbitrationSweeperTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 3d: Repoint the DLQ listener** — replace the body of `ArbitrationDlqListener.java` so the dead-letter escalates instead of auto-refunding:

```java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** An exhausted/poison arbitration request dead-letters here → escalate the dispute to the admin backstop. */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ArbitrationDlqListener {

    private final DisputeAppService disputeAppService;

    @RabbitListener(queues = ArbitrationQueues.DLQ)
    public void onDeadLetter(ArbitrationRequestMessage message) {
        log.warn("Arbitration request dead-lettered for dispute {} (correlation {}); escalating to admin",
                message.disputeId(), message.correlationId());
        disputeAppService.escalate(message.disputeId());
    }
}
```

- [ ] **Step 3e: Remove the now-unused `resolveByFallback`.** In `DisputeAppService.java` delete the method + its javadoc:
```java
    /** Platform refund fallback (DLQ 兜底): full refund + resolve. */
    void resolveByFallback(@NonNull UUID disputeId);
```
In `DisputeAppServiceImpl.java` delete the whole `resolveByFallback(...)` override method. (Leave `DisputeModel.resolveByFallback` and the `FALLBACK` enum in place — they remain valid domain concepts; per the spec they are simply no longer written.)

- [ ] **Step 3f: Update `ArbitrationDlqListenerTest.java`** — change the expectation from `resolveByFallback` to `escalate`. Replace the verify assertion so it reads:
```java
        verify(disputeAppService).escalate(disputeId);
```
and remove any `verify(...).resolveByFallback(...)` / stubbing of `resolveByFallback`. (The message-construction with `taskDescription` arg is unchanged.)

- [ ] **Step 3g: Add config keys** to `application.yml` under `hireai.arbitration` (beside `callback-secret`):
```yaml
    # Human-backstop sweeper: an ARBITRATING dispute older than this is escalated to the admin.
    stale-after: ${ARBITRATION_STALE_AFTER:PT2M}
    sweep-interval: ${ARBITRATION_SWEEP_INTERVAL:PT1M}
```

- [ ] **Step 4: Run the infra tests + full compile**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am -Dtest=ArbitrationSweeperTest,ArbitrationDlqListenerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.
Run: `mvn -f backend/pom.xml -q -B -DskipTests package`
Expected: BUILD SUCCESS (no remaining `resolveByFallback` references).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ArbitrationDlqListener.java backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ArbitrationSweeper.java backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/SchedulingConfig.java backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/messaging/ArbitrationSweeperTest.java backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/messaging/ArbitrationDlqListenerTest.java backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java backend/hireai-main/src/main/resources/application.yml
git commit -m "feat(adjudication): DLQ + scheduled sweeper escalate stranded disputes to admin"
```

---

## Task 4: Migration — seed the admin account (`V23`)

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V23__seed_admin_user.sql`

**Interfaces:**
- Consumes: current `users` columns (`id, email, password_hash, is_active, display_name` — `role` was dropped in V10) and `user_roles(user_id, role)`.
- Produces: a login-able `admin@hireai.local` with the single `ADMIN` role.

- [ ] **Step 1: Create `V23__seed_admin_user.sql`.** Reuse the exact BCrypt hash of `DemoPass123!` from V5. Admin needs **no wallet** (it neither pays nor earns; the overview queries LEFT JOIN wallets).

```sql
-- V23: Seed the platform administrator (dispute backstop + read-only dashboard). There is no public
-- path to the ADMIN role (self-register grants CLIENT only), so the admin is provisioned here.
-- password_hash is the same BCrypt hash of DemoPass123! seeded for the demo users in V5.
-- Fixed UUID (…0002; …0001 is the dev user, …0010/…0011 the demo client/builder) → idempotent.

INSERT INTO users (id, email, password_hash, is_active, display_name) VALUES
    ('00000000-0000-0000-0000-000000000002', 'admin@hireai.local',
     '$2a$10$x2486D5qTVSOkjdaS71oiuJZqFgGx2xK.Y//oon.LpOf6Ox4cEoN6', true, 'Platform Admin');

INSERT INTO user_roles (user_id, role) VALUES
    ('00000000-0000-0000-0000-000000000002', 'ADMIN');
```

- [ ] **Step 2: Verify Flyway validates + the account resolves.** Any context-loading integration test exercises the migration. If Docker is available:

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=DisputeControllerIntegrationTest test`
Expected: PASS (context loads → Flyway applied V23 with no validation error). Without Docker, this auto-skips; the migration is still exercised in Task 7.

- [ ] **Step 3: Commit**

```bash
git add backend/hireai-main/src/main/resources/db/migration/V23__seed_admin_user.sql
git commit -m "feat(identity): seed admin@hireai.local with the ADMIN role (V23)"
```

---

## Task 5: Read layer — `AdminQueryPort` + `JdbcAdminQueryDao` + `AdminReadAppService` + views

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/admin/AdminQueryPort.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/admin/view/AdminViews.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/admin/AdminReadAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/admin/impl/AdminReadAppServiceImpl.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/admin/JdbcAdminQueryDao.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/admin/JdbcAdminQueryDaoIntegrationTest.java`

**Interfaces:**
- Consumes: `NamedParameterJdbcTemplate` (Spring), `DisputeRepository` (for detail rulings), the schema (Global Constraints), `DomainException`/`ResultCode`.
- Produces:
  - `AdminQueryPort` with `Overview overview()`, `List<DisputeRow> disputeQueue(boolean needsAttentionOnly)`, `Optional<Evidence> disputeEvidence(UUID taskId)`, `List<TaskRow> recentTasks(int limit)`, `List<UserRow> usersWithWallets()`, `List<AgentRow> agents()`.
  - View records in `AdminViews` (returned straight to the controller as JSON): `Overview`, `DisputeRow`, `Evidence`, `TaskRow`, `UserRow`, `AgentRow`, `DisputeDetail`, `RulingView`.
  - `AdminReadAppService` with `overview()`, `disputeQueue(boolean)`, `disputeDetail(UUID)`, `recentTasks()`, `users()`, `agents()`.

- [ ] **Step 1: Create the view records** — `AdminViews.java`:

```java
package com.hireai.application.biz.admin.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only projections the admin surface renders. Returned straight to the controller as JSON. */
public final class AdminViews {

    private AdminViews() {}

    public record Overview(long disputesOpen, long disputesArbitrating, long disputesEscalated,
                           long disputesResolved, long tasksTotal, long usersTotal, long agentsTotal,
                           BigDecimal escrowHeld, BigDecimal commissionEarned) {}

    public record DisputeRow(UUID disputeId, UUID taskId, String taskTitle, String status,
                             String reasonCategory, Instant createdAt, String clientName,
                             boolean hasArbitratorRuling, boolean needsAttention) {}

    public record Evidence(UUID taskId, String taskTitle, String taskDescription, String clientName,
                           String outputSpecJson, String resultPayloadJson, String resultUrl,
                           String agentStatus) {}

    public record TaskRow(UUID id, String title, String status, BigDecimal budget, String clientName,
                          Instant createdAt) {}

    public record UserRow(UUID id, String name, String email, List<String> roles,
                          BigDecimal availableBalance, BigDecimal escrowBalance) {}

    public record AgentRow(UUID id, String name, String status, String builderName,
                           BigDecimal reputationScore, BigDecimal price) {}

    public record RulingView(int tier, String decidedBy, String category, String rationale,
                             Instant decidedAt) {}

    public record DisputeDetail(UUID disputeId, UUID taskId, String taskTitle, String taskDescription,
                                String status, String reasonCategory, Instant createdAt, String clientName,
                                String outputSpecJson, String resultPayloadJson, String resultUrl,
                                String agentStatus, boolean actionable, List<RulingView> rulings) {}
}
```

- [ ] **Step 2: Create the port** — `AdminQueryPort.java`:

```java
package com.hireai.application.biz.admin;

import com.hireai.application.biz.admin.view.AdminViews;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only cross-aggregate projections for the admin surface (implemented by a JDBC DAO). */
public interface AdminQueryPort {

    AdminViews.Overview overview();

    List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly);

    Optional<AdminViews.Evidence> disputeEvidence(UUID taskId);

    List<AdminViews.TaskRow> recentTasks(int limit);

    List<AdminViews.UserRow> usersWithWallets();

    List<AdminViews.AgentRow> agents();
}
```

- [ ] **Step 3: Create the read service interface** — `AdminReadAppService.java`:

```java
package com.hireai.application.biz.admin;

import com.hireai.application.biz.admin.view.AdminViews;

import java.util.List;
import java.util.UUID;

/** Admin read orchestration. Overview/browsers delegate to the query port; detail joins the dispute aggregate. */
public interface AdminReadAppService {

    AdminViews.Overview overview();

    List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly);

    AdminViews.DisputeDetail disputeDetail(UUID disputeId);

    List<AdminViews.TaskRow> recentTasks();

    List<AdminViews.UserRow> users();

    List<AdminViews.AgentRow> agents();
}
```

- [ ] **Step 4: Implement the read service** — `AdminReadAppServiceImpl.java`. Detail = the dispute aggregate (rulings, status) + the JDBC evidence row:

```java
package com.hireai.application.biz.admin.impl;

import com.hireai.application.biz.admin.AdminQueryPort;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdminReadAppServiceImpl implements AdminReadAppService {

    private static final int RECENT_TASK_LIMIT = 50;

    private final AdminQueryPort adminQueryPort;
    private final DisputeRepository disputeRepository;

    public AdminReadAppServiceImpl(AdminQueryPort adminQueryPort, DisputeRepository disputeRepository) {
        this.adminQueryPort = adminQueryPort;
        this.disputeRepository = disputeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminViews.Overview overview() {
        return adminQueryPort.overview();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly) {
        return adminQueryPort.disputeQueue(needsAttentionOnly);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminViews.DisputeDetail disputeDetail(UUID disputeId) {
        DisputeModel dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Dispute not found: " + disputeId));
        AdminViews.Evidence ev = adminQueryPort.disputeEvidence(dispute.taskId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "Task not found for dispute: " + disputeId));
        List<AdminViews.RulingView> rulings = dispute.rulings().stream()
                .map(r -> new AdminViews.RulingView(r.tier(), r.decidedBy().name(), r.category().name(),
                        r.rationale(), r.decidedAt()))
                .toList();
        boolean actionable = switch (dispute.status()) {
            case OPEN, ARBITRATING, ESCALATED -> true;
            case RULED, RESOLVED -> false;
        };
        return new AdminViews.DisputeDetail(disputeId, dispute.taskId(), ev.taskTitle(), ev.taskDescription(),
                dispute.status().name(), dispute.reasonCategory().name(), dispute.createdAt(), ev.clientName(),
                ev.outputSpecJson(), ev.resultPayloadJson(), ev.resultUrl(), ev.agentStatus(), actionable, rulings);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.TaskRow> recentTasks() {
        return adminQueryPort.recentTasks(RECENT_TASK_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.UserRow> users() {
        return adminQueryPort.usersWithWallets();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.AgentRow> agents() {
        return adminQueryPort.agents();
    }
}
```

- [ ] **Step 5: Implement the DAO** — `JdbcAdminQueryDao.java` (mirrors `JdbcCatalogueQueryDao` — `NamedParameterJdbcTemplate`, hand-written SQL, `::text` casts for JSONB):

```java
package com.hireai.infrastructure.repository.admin;

import com.hireai.application.biz.admin.AdminQueryPort;
import com.hireai.application.biz.admin.view.AdminViews;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only admin projections over disputes/tasks/results/agents/wallets/users/settlements. */
@Repository
public class JdbcAdminQueryDao implements AdminQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdminQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AdminViews.Overview overview() {
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM disputes WHERE status = 'OPEN')        AS disputes_open,
                  (SELECT COUNT(*) FROM disputes WHERE status = 'ARBITRATING') AS disputes_arbitrating,
                  (SELECT COUNT(*) FROM disputes WHERE status = 'ESCALATED')   AS disputes_escalated,
                  (SELECT COUNT(*) FROM disputes WHERE status = 'RESOLVED')    AS disputes_resolved,
                  (SELECT COUNT(*) FROM tasks)                                 AS tasks_total,
                  (SELECT COUNT(*) FROM users)                                 AS users_total,
                  (SELECT COUNT(*) FROM agents)                                AS agents_total,
                  (SELECT COALESCE(SUM(escrow_balance), 0) FROM wallets)       AS escrow_held,
                  (SELECT COALESCE(SUM(commission), 0) FROM settlements)       AS commission_earned
                """;
        return jdbc.queryForObject(sql, Map.of(), (rs, i) -> new AdminViews.Overview(
                rs.getLong("disputes_open"), rs.getLong("disputes_arbitrating"),
                rs.getLong("disputes_escalated"), rs.getLong("disputes_resolved"),
                rs.getLong("tasks_total"), rs.getLong("users_total"), rs.getLong("agents_total"),
                rs.getBigDecimal("escrow_held"), rs.getBigDecimal("commission_earned")));
    }

    @Override
    public List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly) {
        String filter = needsAttentionOnly ? " WHERE d.status IN ('OPEN','ESCALATED') " : " ";
        String sql = """
                SELECT d.id AS dispute_id, d.task_id, t.title AS task_title, d.status,
                       d.reason_category, d.gmt_create,
                       split_part(u.email, '@', 1) AS client_name,
                       EXISTS (SELECT 1 FROM dispute_rulings r
                               WHERE r.dispute_id = d.id AND r.decided_by = 'ARBITRATOR') AS has_arbitrator_ruling
                FROM disputes d
                JOIN tasks t ON t.id = d.task_id
                JOIN users u ON u.id = d.raised_by
                """ + filter + " ORDER BY d.gmt_create ASC";
        return jdbc.query(sql, Map.of(), (rs, i) -> {
            String status = rs.getString("status");
            boolean needsAttention = "OPEN".equals(status) || "ESCALATED".equals(status);
            return new AdminViews.DisputeRow(
                    rs.getObject("dispute_id", UUID.class), rs.getObject("task_id", UUID.class),
                    rs.getString("task_title"), status, rs.getString("reason_category"),
                    toInstant(rs.getTimestamp("gmt_create")), rs.getString("client_name"),
                    rs.getBoolean("has_arbitrator_ruling"), needsAttention);
        });
    }

    @Override
    public Optional<AdminViews.Evidence> disputeEvidence(UUID taskId) {
        String sql = """
                SELECT t.id AS task_id, t.title, t.description AS task_description,
                       split_part(u.email, '@', 1) AS client_name,
                       av.output_spec::text AS output_spec_json,
                       tr.result_payload::text AS result_payload_json, tr.result_url, tr.agent_status
                FROM tasks t
                JOIN users u ON u.id = t.client_id
                LEFT JOIN agent_versions av ON av.id = t.agent_version_id
                LEFT JOIN task_results tr ON tr.task_id = t.id
                WHERE t.id = :taskId
                """;
        var params = new MapSqlParameterSource().addValue("taskId", taskId);
        List<AdminViews.Evidence> rows = jdbc.query(sql, params, (rs, i) -> new AdminViews.Evidence(
                rs.getObject("task_id", UUID.class), rs.getString("title"), rs.getString("task_description"),
                rs.getString("client_name"), rs.getString("output_spec_json"),
                rs.getString("result_payload_json"), rs.getString("result_url"), rs.getString("agent_status")));
        return rows.stream().findFirst();
    }

    @Override
    public List<AdminViews.TaskRow> recentTasks(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        String sql = """
                SELECT t.id, t.title, t.status, t.budget,
                       split_part(u.email, '@', 1) AS client_name, t.gmt_create
                FROM tasks t JOIN users u ON u.id = t.client_id
                ORDER BY t.gmt_create DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("limit", bounded);
        return jdbc.query(sql, params, (rs, i) -> new AdminViews.TaskRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("status"),
                rs.getBigDecimal("budget"), rs.getString("client_name"),
                toInstant(rs.getTimestamp("gmt_create"))));
    }

    @Override
    public List<AdminViews.UserRow> usersWithWallets() {
        String sql = """
                SELECT u.id, split_part(u.email, '@', 1) AS name, u.email,
                       COALESCE(array_agg(ur.role ORDER BY ur.role) FILTER (WHERE ur.role IS NOT NULL), '{}') AS roles,
                       COALESCE(w.available_balance, 0) AS available_balance,
                       COALESCE(w.escrow_balance, 0) AS escrow_balance
                FROM users u
                LEFT JOIN user_roles ur ON ur.user_id = u.id
                LEFT JOIN wallets w ON w.user_id = u.id
                GROUP BY u.id, u.email, w.available_balance, w.escrow_balance
                ORDER BY u.gmt_create DESC
                """;
        return jdbc.query(sql, Map.of(), (rs, i) -> new AdminViews.UserRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                stringList(rs.getArray("roles")), rs.getBigDecimal("available_balance"),
                rs.getBigDecimal("escrow_balance")));
    }

    @Override
    public List<AdminViews.AgentRow> agents() {
        String sql = """
                SELECT a.id, a.name, a.status, split_part(u.email, '@', 1) AS builder_name,
                       a.reputation_score, v.price
                FROM agents a
                JOIN users u ON u.id = a.owner_id
                LEFT JOIN agent_versions v ON v.id = a.current_version_id
                ORDER BY a.gmt_create DESC
                """;
        return jdbc.query(sql, Map.of(), (rs, i) -> new AdminViews.AgentRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getString("builder_name"), rs.getBigDecimal("reputation_score"),
                rs.getBigDecimal("price")));
    }

    private static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
```

- [ ] **Step 6: Write the DAO integration test** — `JdbcAdminQueryDaoIntegrationTest.java`. Model the Testcontainers harness on the existing `CatalogueQueryDaoIntegrationTest` (same `@SpringBootTest` + Postgres container + `@ActiveProfiles("test")`). Seed a minimal dataset via the autowired `NamedParameterJdbcTemplate`/`JdbcTemplate` (a client user, a task, a dispute in ESCALATED), then assert:

```java
    @Test
    void overviewCountsEscalatedDisputesAndEscrow() {
        // (seed: 1 ESCALATED dispute, a wallet with escrow_balance = 20.00, a settlement commission = 1.50)
        AdminViews.Overview o = dao.overview();
        assertThat(o.disputesEscalated()).isGreaterThanOrEqualTo(1);
        assertThat(o.escrowHeld()).isGreaterThanOrEqualTo(new java.math.BigDecimal("20.00"));
        assertThat(o.commissionEarned()).isGreaterThanOrEqualTo(new java.math.BigDecimal("1.50"));
    }

    @Test
    void needsAttentionQueueReturnsEscalatedNotResolved() {
        List<AdminViews.DisputeRow> rows = dao.disputeQueue(true);
        assertThat(rows).allMatch(r -> r.status().equals("OPEN") || r.status().equals("ESCALATED"));
        assertThat(rows).anyMatch(AdminViews.DisputeRow::needsAttention);
    }

    @Test
    void evidenceReturnsTaskDescriptionAndResult() {
        // (seed a task with a task_result payload for the disputed task)
        Optional<AdminViews.Evidence> ev = dao.disputeEvidence(SEEDED_TASK_ID);
        assertThat(ev).isPresent();
        assertThat(ev.get().taskDescription()).isNotBlank();
    }
```

> Copy the container/bootstrap boilerplate (annotations, `@DynamicPropertySource`, container static field) verbatim from `backend/hireai-main/src/test/java/com/hireai/offering/CatalogueQueryDaoIntegrationTest.java`; autowire `JdbcAdminQueryDao dao` and a `NamedParameterJdbcTemplate` for seeding. Use fixed UUIDs for `SEEDED_TASK_ID` etc.

- [ ] **Step 7: Run** (skips without Docker)

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=JdbcAdminQueryDaoIntegrationTest test`
Expected: PASS with Docker; SKIP without.
Also confirm compile: `mvn -f backend/pom.xml -q -B -DskipTests package` → BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/admin/ backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/admin/ backend/hireai-main/src/test/java/com/hireai/admin/JdbcAdminQueryDaoIntegrationTest.java
git commit -m "feat(admin): read layer — AdminQueryPort + JdbcAdminQueryDao + read service"
```

---

## Task 6: Controller + security gate — `/api/admin/**` (`ROLE_ADMIN`)

**Files:**
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/admin/AdminController.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/admin/dto/AdminRuleRequest.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/biz/admin/AdminControllerTest.java`

**Interfaces:**
- Consumes: `AdminReadAppService` (Task 5), `DisputeAppService.adminRule` (Task 2), `CurrentUserProvider.currentUserId()`, `BaseController.ok(...)`, `RulingCategory`.
- Produces: `GET /api/admin/{overview,disputes,disputes/{id},tasks,users,agents}` + `POST /api/admin/disputes/{id}/rule`; `/api/admin/**` requires `ROLE_ADMIN`.

- [ ] **Step 1: Write the failing web-slice test** — `AdminControllerTest.java`. It runs the **real** secured chain (no `@ActiveProfiles("test")`) and uses `@WithMockUser(roles=...)` to exercise the `ROLE_ADMIN` gate; `JwtService` is mocked so the chain wires:

```java
package com.hireai.controller.biz.admin;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.application.port.security.JwtService;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean AdminReadAppService adminReadAppService;
    @MockBean DisputeAppService disputeAppService;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean JwtService jwtService; // required to wire the secured filter chain

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @WithAnonymousUser
    void overviewRejectsAnonymousWith401() throws Exception {
        mockMvc.perform(get("/api/admin/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void overviewRejectsNonAdminWith403() throws Exception {
        mockMvc.perform(get("/api/admin/overview")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void overviewAllowsAdmin() throws Exception {
        when(adminReadAppService.overview()).thenReturn(new AdminViews.Overview(
                1, 0, 2, 3, 10, 4, 2, new BigDecimal("20.00"), new BigDecimal("1.50")));
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.disputesEscalated").value(2))
                .andExpect(jsonPath("$.data.escrowHeld").value(20.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ruleDelegatesToAdminRuleWithJwtIdentity() throws Exception {
        UUID disputeId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(ADMIN_ID);
        when(adminReadAppService.disputeDetail(disputeId)).thenReturn(new AdminViews.DisputeDetail(
                disputeId, UUID.randomUUID(), "t", "d", "RESOLVED", "A_MISMATCH",
                java.time.Instant.parse("2026-07-02T00:00:00Z"), "client", null, null, null, null,
                false, List.of()));

        mockMvc.perform(post("/api/admin/disputes/{id}/rule", disputeId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"NOT_FULFILLED\",\"rationale\":\"backstop refund\"}"))
                .andExpect(status().isOk());

        verify(disputeAppService).adminRule(eq(disputeId),
                eq(com.hireai.domain.biz.adjudication.enums.RulingCategory.NOT_FULFILLED),
                eq("backstop refund"), eq(ADMIN_ID));
    }
}
```

> Note: `@WebMvcTest` with no `@ActiveProfiles` runs the default profile, so the `@Profile("!test")` `securedFilterChain` loads (and the OAuth chain stays off — `hireai.auth.oauth2.enabled` defaults false). CSRF is disabled in that chain, so `.with(csrf())` is harmless. If the JWT filter needs additional collaborators to instantiate, add them as `@MockBean`.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=AdminControllerTest test`
Expected: FAIL — `AdminController` does not exist.

- [ ] **Step 3a: Create the request DTO** — `AdminRuleRequest.java`:

```java
package com.hireai.controller.biz.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin ruling body: a category (validated against RulingCategory in the controller) + a required rationale. */
public record AdminRuleRequest(@NotBlank String category, @NotBlank String rationale) {}
```

- [ ] **Step 3b: Create `AdminController.java`:**

```java
package com.hireai.controller.biz.admin;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.admin.dto.AdminRuleRequest;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin surface: read-only overview/browsers + the tier-2 human-backstop ruling. Gated by ROLE_ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class AdminController extends BaseController {

    private final AdminReadAppService adminReadAppService;
    private final DisputeAppService disputeAppService;
    private final CurrentUserProvider currentUser;

    public AdminController(AdminReadAppService adminReadAppService, DisputeAppService disputeAppService,
                           CurrentUserProvider currentUser) {
        this.adminReadAppService = adminReadAppService;
        this.disputeAppService = disputeAppService;
        this.currentUser = currentUser;
    }

    @GetMapping("/overview")
    public WebResult<AdminViews.Overview> overview() {
        return ok(adminReadAppService.overview());
    }

    @GetMapping("/disputes")
    public WebResult<List<AdminViews.DisputeRow>> disputes(
            @RequestParam(value = "filter", defaultValue = "needs_attention") String filter) {
        boolean needsAttentionOnly = !"all".equalsIgnoreCase(filter);
        return ok(adminReadAppService.disputeQueue(needsAttentionOnly));
    }

    @GetMapping("/disputes/{id}")
    public WebResult<AdminViews.DisputeDetail> disputeDetail(@PathVariable("id") UUID id) {
        return ok(adminReadAppService.disputeDetail(id));
    }

    @PostMapping("/disputes/{id}/rule")
    public WebResult<AdminViews.DisputeDetail> rule(@PathVariable("id") UUID id,
                                                    @Valid @RequestBody AdminRuleRequest request) {
        RulingCategory category;
        try {
            category = RulingCategory.valueOf(request.category());
        } catch (IllegalArgumentException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Invalid ruling category: " + request.category());
        }
        disputeAppService.adminRule(id, category, request.rationale(), currentUser.currentUserId());
        return ok(adminReadAppService.disputeDetail(id));
    }

    @GetMapping("/tasks")
    public WebResult<List<AdminViews.TaskRow>> tasks() {
        return ok(adminReadAppService.recentTasks());
    }

    @GetMapping("/users")
    public WebResult<List<AdminViews.UserRow>> users() {
        return ok(adminReadAppService.users());
    }

    @GetMapping("/agents")
    public WebResult<List<AdminViews.AgentRow>> agents() {
        return ok(adminReadAppService.agents());
    }
}
```

- [ ] **Step 3c: Add the security gate.** In `SecurityConfig.java`, inside `securedFilterChain`'s `authorizeHttpRequests`, add the admin matcher **before** `.anyRequest().authenticated()`:

```java
                        .requestMatchers("/api/arbitration-callbacks/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=AdminControllerTest test`
Expected: PASS (401 anon, 403 client, 200 admin, rule delegates).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-controller/src/main/java/com/hireai/controller/biz/admin/ backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java backend/hireai-main/src/test/java/com/hireai/controller/biz/admin/AdminControllerTest.java
git commit -m "feat(admin): /api/admin/** endpoints gated by ROLE_ADMIN"
```

---

## Task 7: Integration — admin ruling settles E2E; DLQ + sweeper escalate

**Files:**
- Test: `backend/hireai-main/src/test/java/com/hireai/admin/AdminDisputeFlowIntegrationTest.java`

**Interfaces:**
- Consumes: the whole assembled context (`DisputeAppService`, `AdminReadAppService`, `ArbitrationSweeper`, repositories, real Postgres). Model the harness on `backend/hireai-main/src/test/java/com/hireai/adjudication/DisputeFlowIntegrationTest.java`.
- Produces: end-to-end proof that an admin ruling refunds/pays once and resolves; that a stale ARBITRATING dispute sweeps to `ESCALATED`.

- [ ] **Step 1: Write the integration tests.** Reuse `DisputeFlowIntegrationTest`'s container bootstrap + task/dispute seeding helpers. Autowire `DisputeAppService`, `AdminReadAppService`, `ArbitrationSweeper`, `TaskRepository`, `DisputeRepository`, and a `WalletRepository`/`JdbcTemplate` to read balances.

```java
    @Test
    void adminRuleNotFulfilledRefundsClientAndResolves() {
        // Arrange: a task in PENDING_REVIEW disputed (A/B/C) whose gateway did NOT rule (async → ARBITRATING),
        // then escalate it (simulate DLQ/sweep).
        UUID disputeId = openEscalatedDisputeForFundedTask(/* budget */ new BigDecimal("30.00"));
        BigDecimal availableBefore = clientAvailableBalance();

        // Act
        disputeAppService.adminRule(disputeId, RulingCategory.NOT_FULFILLED, "backstop refund", ADMIN_ID);

        // Assert: full refund back to available, dispute + task RESOLVED, tier-2 ADMINISTRATOR ruling recorded.
        assertThat(clientAvailableBalance()).isEqualByComparingTo(availableBefore.add(new BigDecimal("30.00")));
        AdminViews.DisputeDetail detail = adminReadAppService.disputeDetail(disputeId);
        assertThat(detail.status()).isEqualTo("RESOLVED");
        assertThat(detail.actionable()).isFalse();
        assertThat(detail.rulings()).anyMatch(r -> r.decidedBy().equals("ADMINISTRATOR") && r.tier() == 2);
    }

    @Test
    void adminRuleOnResolvedDisputeThrowsConflict() {
        UUID disputeId = openEscalatedDisputeForFundedTask(new BigDecimal("30.00"));
        disputeAppService.adminRule(disputeId, RulingCategory.NOT_FULFILLED, "first", ADMIN_ID);
        assertThatThrownBy(() -> disputeAppService.adminRule(disputeId, RulingCategory.FULFILLED, "second", ADMIN_ID))
                .isInstanceOf(com.hireai.utility.exception.DomainException.class);
    }

    @Test
    void sweeperEscalatesAgedArbitratingDispute() {
        // Insert a dispute row directly in ARBITRATING with gmt_create 10 minutes in the past.
        UUID disputeId = insertArbitratingDispute(Instant.now().minusSeconds(600));
        sweeper.sweep(); // stale-after default PT2M → this row is stale
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status())
                .isEqualTo(com.hireai.domain.biz.adjudication.enums.DisputeStatus.ESCALATED);
    }
```

> Implement the private helpers (`openEscalatedDisputeForFundedTask`, `clientAvailableBalance`, `insertArbitratingDispute`) using the same repositories/JdbcTemplate the sibling integration tests use. `ADMIN_ID = 00000000-0000-0000-0000-000000000002` (seeded in V23). For `openEscalatedDisputeForFundedTask`: submit+fund a task, drive it to PENDING_REVIEW, `disputeAppService.openDispute(...)` with a gateway that returns `Optional.empty()` (async) so it lands in ARBITRATING, then `disputeAppService.escalate(disputeId)`.

- [ ] **Step 2: Run** (skips without Docker)

Run: `mvn -f backend/pom.xml -pl hireai-main -am -Dtest=AdminDisputeFlowIntegrationTest test`
Expected: PASS with Docker; SKIP without. If Docker is unavailable in this environment, note it and rely on CI to run the full suite.

- [ ] **Step 3: Full backend build**

Run: `mvn -f backend/pom.xml -q -B package`
Expected: BUILD SUCCESS (integration tests skip without Docker; all unit tests pass).

- [ ] **Step 4: Commit**

```bash
git add backend/hireai-main/src/test/java/com/hireai/admin/AdminDisputeFlowIntegrationTest.java
git commit -m "test(admin): E2E admin ruling settlement + sweeper escalation"
```

---

## Task 8: Frontend plumbing — ADMIN in types/api/RoleGuard/auth/Nav/Badge

**Files:**
- Modify: `frontend/lib/types.ts`
- Modify: `frontend/lib/auth.tsx`
- Modify: `frontend/components/RoleGuard.tsx`
- Modify: `frontend/components/Nav.tsx`
- Modify: `frontend/app/login/page.tsx`
- Modify: `frontend/components/ui.tsx` (or the Badge file in the ui kit) — add dispute-status colors
- Test: `frontend/lib/auth.test.tsx` (extend)

**Interfaces:**
- Consumes: existing `Role` type (already `"CLIENT" | "BUILDER" | "ADMIN"`), `homeFor`, `useAuth`, `RulingDTO`, `RejectReason`, `TaskStatus`.
- Produces: admin DTO types; `homeFor` returns `/admin` for admin-only; `RoleGuard` accepts `role="ADMIN"`; `Nav` shows an ADMIN branch; login routes admins to `/admin`.

- [ ] **Step 1: Add admin DTO types** to `frontend/lib/types.ts` (append):

```typescript
export interface AdminOverviewDTO {
  disputesOpen: number;
  disputesArbitrating: number;
  disputesEscalated: number;
  disputesResolved: number;
  tasksTotal: number;
  usersTotal: number;
  agentsTotal: number;
  escrowHeld: number;
  commissionEarned: number;
}

export interface AdminDisputeRowDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  status: string;
  reasonCategory: RejectReason;
  createdAt: string;
  clientName: string;
  hasArbitratorRuling: boolean;
  needsAttention: boolean;
}

export interface AdminDisputeDetailDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  taskDescription: string;
  status: string;
  reasonCategory: RejectReason;
  createdAt: string;
  clientName: string;
  outputSpecJson: string | null;
  resultPayloadJson: string | null;
  resultUrl: string | null;
  agentStatus: string | null;
  actionable: boolean;
  rulings: RulingDTO[];
}

export interface AdminTaskRowDTO {
  id: string;
  title: string;
  status: TaskStatus;
  budget: number;
  clientName: string;
  createdAt: string;
}

export interface AdminUserRowDTO {
  id: string;
  name: string;
  email: string;
  roles: Role[];
  availableBalance: number;
  escrowBalance: number;
}

export interface AdminAgentRowDTO {
  id: string;
  name: string;
  status: string;
  builderName: string;
  reputationScore: number;
  price: number | null;
}
```

- [ ] **Step 2: Extend `homeFor`** in `frontend/lib/auth.tsx`. Replace:
```typescript
function homeFor(roles: Role[]): "/client" | "/builder" {
  return roles.includes("CLIENT") ? "/client" : "/builder";
}
```
with:
```typescript
function homeFor(roles: Role[]): "/client" | "/builder" | "/admin" {
  if (roles.includes("CLIENT")) return "/client";
  if (roles.includes("BUILDER")) return "/builder";
  if (roles.includes("ADMIN")) return "/admin";
  return "/client";
}
```
(The `persist()` surface line — `roles.includes("CLIENT") ? "CLIENT" : roles[0]` — already yields `ADMIN` for an admin-only user; no change.)

- [ ] **Step 3: Extend `RoleGuard`** in `frontend/components/RoleGuard.tsx`. Change the prop type and the mismatch redirect:
```typescript
  role,
  children,
}: {
  role?: "CLIENT" | "BUILDER" | "ADMIN";
  children: React.ReactNode;
}) {
```
and:
```typescript
    if (role && token && !hasRole(role)) {
      router.replace(
        hasRole("CLIENT") ? "/client" : hasRole("BUILDER") ? "/builder" : hasRole("ADMIN") ? "/admin" : "/login",
      );
    }
```

- [ ] **Step 4: Route admins on login** in `frontend/app/login/page.tsx`. Add the import and replace the redirect line. Add to the imports from `@/lib/auth`:
```typescript
import { useAuth, homeFor } from "@/lib/auth";
```
(the file currently imports only `useAuth`). Then replace line 31:
```typescript
      router.replace(res.roles.includes("BUILDER") && !res.roles.includes("CLIENT") ? "/builder" : "/client");
```
with:
```typescript
      router.replace(homeFor(res.roles));
```
Note: `homeFor` is exported from `@/lib/auth` (`export { homeFor }`).

- [ ] **Step 5: Add the ADMIN Nav branch** in `frontend/components/Nav.tsx`. Change the `home` line:
```typescript
  const home = activeSurface === "BUILDER" ? "/builder" : activeSurface === "ADMIN" ? "/admin" : "/client";
```
and add, after the `activeSurface === "BUILDER"` block (before the `{dual && ...}` block):
```tsx
            {activeSurface === "ADMIN" && (
              <div className="hidden items-center gap-1 md:flex">
                {[
                  { href: "/admin", label: "Overview" },
                  { href: "/admin/disputes", label: "Disputes" },
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

- [ ] **Step 6: Add dispute-status Badge colors.** In the ui kit's Badge status map (`frontend/components/ui.tsx`, the `STATUS_CLASSES`/`statusColor` object), add entries so admin dispute statuses render distinctly (Badge already falls back to neutral for unknowns, so this is polish). Add keys mapping to the existing color classes used by siblings:
```
OPEN → amber, ARBITRATING → cyan, ESCALATED → red, RULED → violet, RESOLVED → accent
```
Match the exact class-string format already used in that object (copy a neighboring entry and swap the key/color). If the map lives in a separate `components/ui/Badge.tsx`, edit there.

- [ ] **Step 7: Extend the auth test** — add to `frontend/lib/auth.test.tsx`:
```typescript
  it("homeFor routes an admin-only user to /admin", async () => {
    const { homeFor } = await import("./auth");
    expect(homeFor(["ADMIN"])).toBe("/admin");
    expect(homeFor(["CLIENT", "BUILDER"])).toBe("/client");
    expect(homeFor(["BUILDER"])).toBe("/builder");
  });
```

- [ ] **Step 8: Run tests + lint**

Run: `cd frontend && npx vitest run lib/auth.test.tsx --no-file-parallelism --pool=forks`
Expected: PASS.
Run: `npm --prefix frontend run lint`
Expected: no errors.

- [ ] **Step 9: Commit**

```bash
git add frontend/lib/types.ts frontend/lib/auth.tsx frontend/components/RoleGuard.tsx frontend/components/Nav.tsx frontend/app/login/page.tsx frontend/components/ui.tsx frontend/lib/auth.test.tsx
git commit -m "feat(frontend): ADMIN surface plumbing (types, auth, RoleGuard, Nav, login)"
```

---

## Task 9: Frontend — admin overview page (`/admin`)

**Files:**
- Create: `frontend/app/admin/page.tsx`
- Modify: `frontend/test/msw/handlers.ts` (add admin handlers)
- Test: `frontend/test/adminOverview.test.tsx`

**Interfaces:**
- Consumes: `api()`, `AppShell`, `RoleGuard`, `Card`/`Badge`, `AdminOverviewDTO`/`AdminTaskRowDTO`/`AdminUserRowDTO`/`AdminAgentRowDTO`.
- Produces: the `/admin` overview page (metric tiles + read-only tables), fetched in parallel.

- [ ] **Step 1: Write the failing test** — `frontend/test/adminOverview.test.tsx` (mirror `test/taskDetail.test.tsx` setup — MSW server, next/navigation mock, ADMIN session):

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import AdminOverviewPage from "@/app/admin/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-admin", roles: ["ADMIN"] }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe("admin overview", () => {
  it("renders escrow held and escalated-dispute count", async () => {
    render(
      <AuthProvider>
        <AdminOverviewPage />
      </AuthProvider>,
    );
    expect(await screen.findByText(/escrow held/i)).toBeInTheDocument();
    expect(await screen.findByText("2")).toBeInTheDocument(); // disputesEscalated from the mock
  });
});
```

- [ ] **Step 2: Add MSW handlers** to `frontend/test/msw/handlers.ts` — add `http.get` handlers for the admin endpoints returning the `WebResult` envelope. Match the existing handlers' style in that file (they already use `http`/`HttpResponse` from `msw`). Add:

```typescript
  http.get("/api/admin/overview", () =>
    HttpResponse.json({
      success: true, code: "OK", message: "",
      data: {
        disputesOpen: 1, disputesArbitrating: 0, disputesEscalated: 2, disputesResolved: 3,
        tasksTotal: 10, usersTotal: 4, agentsTotal: 2, escrowHeld: 20, commissionEarned: 1.5,
      },
    }),
  ),
  http.get("/api/admin/tasks", () => HttpResponse.json({ success: true, code: "OK", message: "", data: [] })),
  http.get("/api/admin/users", () => HttpResponse.json({ success: true, code: "OK", message: "", data: [] })),
  http.get("/api/admin/agents", () => HttpResponse.json({ success: true, code: "OK", message: "", data: [] })),
```

> Place these inside the existing `handlers` array. If the file exports `server = setupServer(...handlers)`, no other change is needed.

- [ ] **Step 3: Create the page** — `frontend/app/admin/page.tsx`:

```tsx
"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge } from "@/components/ui";
import type {
  AdminOverviewDTO,
  AdminTaskRowDTO,
  AdminUserRowDTO,
  AdminAgentRowDTO,
} from "@/lib/types";

function Tile({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-md border border-line bg-surface-2 p-4">
      <p className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-dim">{label}</p>
      <p className="mt-1 text-2xl font-extrabold tabular tracking-tight text-fg">{value}</p>
    </div>
  );
}

function AdminOverview() {
  const [overview, setOverview] = useState<AdminOverviewDTO | null>(null);
  const [tasks, setTasks] = useState<AdminTaskRowDTO[]>([]);
  const [users, setUsers] = useState<AdminUserRowDTO[]>([]);
  const [agents, setAgents] = useState<AdminAgentRowDTO[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<AdminOverviewDTO>("/admin/overview"),
      api<AdminTaskRowDTO[]>("/admin/tasks"),
      api<AdminUserRowDTO[]>("/admin/users"),
      api<AdminAgentRowDTO[]>("/admin/agents"),
    ])
      .then(([o, t, u, a]) => {
        if (cancelled) return;
        setOverview(o);
        setTasks(t);
        setUsers(u);
        setAgents(a);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load admin overview");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return (
      <Card>
        <p role="alert" className="font-mono text-sm text-red">{error}</p>
      </Card>
    );
  }
  if (!overview) {
    return (
      <Card>
        <p className="font-mono text-sm text-dim">Loading overview…</p>
      </Card>
    );
  }

  return (
    <div className="space-y-8">
      <div>
        <p className="eyebrow">Platform</p>
        <h1 className="mt-1 text-2xl font-extrabold tracking-tight">Admin overview</h1>
      </div>

      <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        <Link href="/admin/disputes" className="contents">
          <Tile label="Disputes · needs attention" value={overview.disputesOpen + overview.disputesEscalated} />
        </Link>
        <Tile label="Escalated" value={overview.disputesEscalated} />
        <Tile label="Arbitrating" value={overview.disputesArbitrating} />
        <Tile label="Resolved disputes" value={overview.disputesResolved} />
        <Tile label="Tasks" value={overview.tasksTotal} />
        <Tile label="Users" value={overview.usersTotal} />
        <Tile label="Agents" value={overview.agentsTotal} />
        <Tile label="Escrow held" value={`${overview.escrowHeld} cr`} />
        <Tile label="Commission earned" value={`${overview.commissionEarned} cr`} />
      </section>

      <section className="space-y-3">
        <h2 className="eyebrow">Recent tasks</h2>
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                <th className="p-3">Title</th><th className="p-3">Client</th>
                <th className="p-3">Status</th><th className="p-3">Budget</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.id} className="border-b border-line/50">
                  <td className="p-3 text-fg">{t.title}</td>
                  <td className="p-3 font-mono text-xs text-muted">{t.clientName}</td>
                  <td className="p-3"><Badge status={t.status}>{t.status}</Badge></td>
                  <td className="p-3 tabular text-accent">{t.budget} cr</td>
                </tr>
              ))}
              {tasks.length === 0 && (
                <tr><td colSpan={4} className="p-3 font-mono text-xs text-dim">No tasks yet.</td></tr>
              )}
            </tbody>
          </table>
        </Card>
      </section>

      <section className="grid gap-6 lg:grid-cols-2">
        <div className="space-y-3">
          <h2 className="eyebrow">Users &amp; wallets</h2>
          <Card className="overflow-x-auto p-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                  <th className="p-3">User</th><th className="p-3">Roles</th><th className="p-3">Available</th><th className="p-3">Escrow</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id} className="border-b border-line/50">
                    <td className="p-3 font-mono text-xs text-fg">{u.email}</td>
                    <td className="p-3 font-mono text-[0.65rem] text-muted">{u.roles.join(", ")}</td>
                    <td className="p-3 tabular text-muted">{u.availableBalance} cr</td>
                    <td className="p-3 tabular text-muted">{u.escrowBalance} cr</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </div>
        <div className="space-y-3">
          <h2 className="eyebrow">Agents</h2>
          <Card className="overflow-x-auto p-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                  <th className="p-3">Name</th><th className="p-3">Builder</th><th className="p-3">Status</th><th className="p-3">Price</th>
                </tr>
              </thead>
              <tbody>
                {agents.map((a) => (
                  <tr key={a.id} className="border-b border-line/50">
                    <td className="p-3 text-fg">{a.name}</td>
                    <td className="p-3 font-mono text-xs text-muted">{a.builderName}</td>
                    <td className="p-3"><Badge status={a.status}>{a.status}</Badge></td>
                    <td className="p-3 tabular text-muted">{a.price == null ? "—" : `${a.price} cr`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </div>
      </section>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="ADMIN">
        <AdminOverview />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 4: Run the test + lint**

Run: `cd frontend && npx vitest run test/adminOverview.test.tsx --no-file-parallelism --pool=forks`
Expected: PASS.
Run: `npm --prefix frontend run lint`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/admin/page.tsx frontend/test/msw/handlers.ts frontend/test/adminOverview.test.tsx
git commit -m "feat(frontend): admin overview page (/admin)"
```

---

## Task 10: Frontend — dispute queue page (`/admin/disputes`)

**Files:**
- Create: `frontend/app/admin/disputes/page.tsx`
- Modify: `frontend/test/msw/handlers.ts` (add queue handler)
- Test: `frontend/test/adminDisputeQueue.test.tsx`

**Interfaces:**
- Consumes: `api()`, `AppShell`, `RoleGuard`, `Card`/`Badge`/`Button`, `AdminDisputeRowDTO`.
- Produces: the `/admin/disputes` queue with a needs-attention/all toggle; rows link to `/admin/disputes/[id]`.

- [ ] **Step 1: Write the failing test** — `frontend/test/adminDisputeQueue.test.tsx`:

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import AdminDisputesPage from "@/app/admin/disputes/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-admin", roles: ["ADMIN"] }));
});
afterEach(() => { server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

describe("admin dispute queue", () => {
  it("lists a needs-attention dispute with its task title", async () => {
    render(<AuthProvider><AdminDisputesPage /></AuthProvider>);
    expect(await screen.findByText("Summarise the Q3 report")).toBeInTheDocument();
    expect(await screen.findByText("ESCALATED")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Add the MSW handler** to `frontend/test/msw/handlers.ts` (inside the `handlers` array):

```typescript
  http.get("/api/admin/disputes", () =>
    HttpResponse.json({
      success: true, code: "OK", message: "",
      data: [
        {
          disputeId: "d-1", taskId: "t-1", taskTitle: "Summarise the Q3 report",
          status: "ESCALATED", reasonCategory: "A_MISMATCH", createdAt: "2026-07-02T00:00:00Z",
          clientName: "client", hasArbitratorRuling: false, needsAttention: true,
        },
      ],
    }),
  ),
```

- [ ] **Step 3: Create the page** — `frontend/app/admin/disputes/page.tsx`:

```tsx
"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, Button } from "@/components/ui";
import type { AdminDisputeRowDTO } from "@/lib/types";

function AdminDisputes() {
  const [rows, setRows] = useState<AdminDisputeRowDTO[]>([]);
  const [needsAttentionOnly, setNeedsAttentionOnly] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    const filter = needsAttentionOnly ? "needs_attention" : "all";
    api<AdminDisputeRowDTO[]>(`/admin/disputes?filter=${filter}`)
      .then((r) => { if (!cancelled) { setRows(r); setError(null); } })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load disputes"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [needsAttentionOnly]);

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="eyebrow">Backstop</p>
          <h1 className="mt-1 text-2xl font-extrabold tracking-tight">Disputes</h1>
        </div>
        <div className="flex items-center gap-2">
          <Button variant={needsAttentionOnly ? "primary" : "ghost"} onClick={() => setNeedsAttentionOnly(true)}>
            Needs attention
          </Button>
          <Button variant={needsAttentionOnly ? "ghost" : "primary"} onClick={() => setNeedsAttentionOnly(false)}>
            All
          </Button>
        </div>
      </div>

      {error && <Card><p role="alert" className="font-mono text-sm text-red">{error}</p></Card>}

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
              <th className="p-3">Task</th><th className="p-3">Reason</th><th className="p-3">Status</th>
              <th className="p-3">Raised by</th><th className="p-3">Opened</th><th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.disputeId} className="border-b border-line/50 hover:bg-surface-2/50">
                <td className="p-3 text-fg">{r.taskTitle}</td>
                <td className="p-3 font-mono text-xs text-muted">{r.reasonCategory}</td>
                <td className="p-3"><Badge status={r.status}>{r.status}</Badge></td>
                <td className="p-3 font-mono text-xs text-muted">{r.clientName}</td>
                <td className="p-3 font-mono text-xs text-dim">{new Date(r.createdAt).toLocaleString()}</td>
                <td className="p-3 text-right">
                  <Link href={`/admin/disputes/${r.disputeId}`} className="font-mono text-xs font-semibold text-accent hover:underline">
                    Review →
                  </Link>
                </td>
              </tr>
            ))}
            {!loading && rows.length === 0 && (
              <tr><td colSpan={6} className="p-3 font-mono text-xs text-dim">Nothing here.</td></tr>
            )}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="ADMIN">
        <AdminDisputes />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 4: Run test + lint**

Run: `cd frontend && npx vitest run test/adminDisputeQueue.test.tsx --no-file-parallelism --pool=forks`
Expected: PASS.
Run: `npm --prefix frontend run lint` → no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/admin/disputes/page.tsx frontend/test/msw/handlers.ts frontend/test/adminDisputeQueue.test.tsx
git commit -m "feat(frontend): admin dispute queue (/admin/disputes)"
```

---

## Task 11: Frontend — dispute detail + rule action (`/admin/disputes/[id]`)

**Files:**
- Create: `frontend/app/admin/disputes/[id]/page.tsx`
- Modify: `frontend/test/msw/handlers.ts` (add detail + rule handlers)
- Test: `frontend/test/adminDisputeDetail.test.tsx`

**Interfaces:**
- Consumes: `api()`, `useParams`, `AppShell`, `RoleGuard`, `Card`/`Badge`/`Button`/`Field`, `DisputeOutcomePanel`, `AdminDisputeDetailDTO`, `RulingCategory`.
- Produces: the detail view (evidence + rulings) with a rule form when `actionable`; posts `{category, rationale}` and swaps to the resolved detail.

- [ ] **Step 1: Write the failing test** — `frontend/test/adminDisputeDetail.test.tsx`:

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import AdminDisputeDetailPage from "@/app/admin/disputes/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "d-1" }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-admin", roles: ["ADMIN"] }));
});
afterEach(() => { server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

describe("admin dispute detail", () => {
  it("shows evidence and submits a ruling", async () => {
    render(<AuthProvider><AdminDisputeDetailPage /></AuthProvider>);

    expect(await screen.findByText(/task description/i)).toBeInTheDocument();

    // Choose NOT_FULFILLED + rationale, submit → the detail becomes RESOLVED.
    await userEvent.click(await screen.findByLabelText(/not fulfilled/i));
    await userEvent.type(screen.getByLabelText(/rationale/i), "backstop refund");
    await userEvent.click(screen.getByRole("button", { name: /issue ruling/i }));

    expect(await screen.findByText("RESOLVED")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Add MSW handlers** to `frontend/test/msw/handlers.ts` — a detail GET (actionable ESCALATED) and a rule POST that returns the RESOLVED detail:

```typescript
  http.get("/api/admin/disputes/d-1", () =>
    HttpResponse.json({
      success: true, code: "OK", message: "",
      data: {
        disputeId: "d-1", taskId: "t-1", taskTitle: "Summarise the Q3 report",
        taskDescription: "Summarise the attached Q3 financial report in 5 bullets.",
        status: "ESCALATED", reasonCategory: "A_MISMATCH", createdAt: "2026-07-02T00:00:00Z",
        clientName: "client", outputSpecJson: "{\"format\":\"TEXT\"}",
        resultPayloadJson: "{\"summary\":\"...\"}", resultUrl: null, agentStatus: "COMPLETED",
        actionable: true, rulings: [],
      },
    }),
  ),
  http.post("/api/admin/disputes/d-1/rule", () =>
    HttpResponse.json({
      success: true, code: "OK", message: "",
      data: {
        disputeId: "d-1", taskId: "t-1", taskTitle: "Summarise the Q3 report",
        taskDescription: "Summarise the attached Q3 financial report in 5 bullets.",
        status: "RESOLVED", reasonCategory: "A_MISMATCH", createdAt: "2026-07-02T00:00:00Z",
        clientName: "client", outputSpecJson: "{\"format\":\"TEXT\"}",
        resultPayloadJson: "{\"summary\":\"...\"}", resultUrl: null, agentStatus: "COMPLETED",
        actionable: false,
        rulings: [{ tier: 2, decidedBy: "ADMINISTRATOR", category: "NOT_FULFILLED", rationale: "backstop refund", decidedAt: "2026-07-02T01:00:00Z" }],
      },
    }),
  ),
```

- [ ] **Step 3: Create the page** — `frontend/app/admin/disputes/[id]/page.tsx`:

```tsx
"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, Button, Field } from "@/components/ui";
import type { AdminDisputeDetailDTO, RulingCategory } from "@/lib/types";

const CATEGORIES: { value: RulingCategory; label: string }[] = [
  { value: "FULFILLED", label: "Fulfilled — pay the builder" },
  { value: "PARTIALLY_FULFILLED", label: "Partially fulfilled — split" },
  { value: "NOT_FULFILLED", label: "Not fulfilled — full refund" },
];

function prettyJson(raw: string | null): string {
  if (!raw) return "—";
  try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return raw; }
}

function Evidence({ label, body }: { label: string; body: string }) {
  return (
    <div className="space-y-1">
      <p className="eyebrow">{label}</p>
      <pre className="overflow-auto rounded-md border border-line bg-canvas p-3 font-mono text-xs leading-relaxed text-fg">{body}</pre>
    </div>
  );
}

function AdminDisputeDetail() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [detail, setDetail] = useState<AdminDisputeDetailDTO | null>(null);
  const [category, setCategory] = useState<RulingCategory | null>(null);
  const [rationale, setRationale] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api<AdminDisputeDetailDTO>(`/admin/disputes/${id}`)
      .then((d) => { if (!cancelled) setDetail(d); })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load dispute"); });
    return () => { cancelled = true; };
  }, [id]);

  async function submit() {
    if (!category) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await api<AdminDisputeDetailDTO>(`/admin/disputes/${id}/rule`, {
        method: "POST",
        body: JSON.stringify({ category, rationale: rationale.trim() || category }),
      });
      setDetail(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit the ruling");
    } finally {
      setBusy(false);
    }
  }

  if (error && !detail) return <Card><p role="alert" className="font-mono text-sm text-red">{error}</p></Card>;
  if (!detail) return <Card><p className="font-mono text-sm text-dim">Loading dispute…</p></Card>;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link href="/admin/disputes" className="font-mono text-xs text-dim transition hover:text-accent">← disputes</Link>

      <Card className="space-y-5">
        <header className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-xl font-extrabold tracking-tight">{detail.taskTitle}</h1>
            <p className="mt-1 font-mono text-xs text-dim">
              #{detail.disputeId.slice(0, 8)} · reason {detail.reasonCategory} · raised by {detail.clientName}
            </p>
          </div>
          <Badge status={detail.status}>{detail.status}</Badge>
        </header>

        <Evidence label="Task description (what the client asked)" body={detail.taskDescription} />
        <Evidence label="Output spec (the binding contract)" body={prettyJson(detail.outputSpecJson)} />
        <Evidence label="Agent result" body={prettyJson(detail.resultPayloadJson)} />
        {detail.resultUrl && (
          <a href={detail.resultUrl} target="_blank" rel="noreferrer" className="inline-flex font-mono text-xs font-semibold text-accent hover:underline">
            Open deliverable →
          </a>
        )}

        {detail.rulings.length > 0 && (
          <section className="space-y-2 border-t border-line pt-5">
            <p className="eyebrow">Ruling history</p>
            {detail.rulings.map((r, idx) => (
              <div key={idx} className="rounded-md border border-line bg-surface-2 p-3 font-mono text-xs text-muted">
                <span className="text-accent">{r.decidedBy}</span> · tier {r.tier} · {r.category}
                {r.rationale ? <span className="block text-dim">“{r.rationale}”</span> : null}
              </div>
            ))}
          </section>
        )}

        {detail.actionable ? (
          <section aria-label="Issue ruling" className="space-y-4 border-t border-line pt-5">
            <p className="eyebrow">Backstop ruling</p>
            <p className="text-sm text-muted">
              No money has settled yet. Your ruling settles escrow once, deterministically, from the category.
            </p>
            {error && <p role="alert" className="font-mono text-xs text-red">{error}</p>}
            <Field label="Outcome" hint="Required">
              <div className="space-y-2" role="radiogroup">
                {CATEGORIES.map(({ value, label }) => (
                  <label key={value} className="flex cursor-pointer items-center gap-3 rounded-md border border-line bg-canvas px-3 py-2.5 font-mono text-xs text-fg hover:border-line-bright">
                    <input type="radio" name="admin-ruling" value={value} aria-label={label}
                      checked={category === value} onChange={() => setCategory(value)} className="accent-accent" />
                    {label}
                  </label>
                ))}
              </div>
            </Field>
            <Field label="Rationale" htmlFor="admin-rationale">
              <textarea id="admin-rationale" aria-label="rationale" maxLength={1000} rows={3}
                value={rationale} onChange={(e) => setRationale(e.target.value)}
                className="w-full rounded-md border border-line bg-canvas p-3 font-mono text-xs text-fg" />
            </Field>
            <Button variant="danger" onClick={submit} disabled={busy || !category}>Issue ruling</Button>
          </section>
        ) : (
          <p className="border-t border-line pt-5 font-mono text-xs text-dim">
            This dispute is {detail.status} — read-only.
          </p>
        )}
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="ADMIN">
        <AdminDisputeDetail />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 4: Run test + lint**

Run: `cd frontend && npx vitest run test/adminDisputeDetail.test.tsx --no-file-parallelism --pool=forks`
Expected: PASS.
Run: `npm --prefix frontend run lint` → no errors.

- [ ] **Step 5: Full frontend test + build**

Run: `cd frontend && npx vitest run --no-file-parallelism --pool=forks`
Expected: all suites PASS.
Run: `npm --prefix frontend run build`
Expected: build succeeds (the new `/admin/*` routes compile).

- [ ] **Step 6: Commit**

```bash
git add frontend/app/admin/disputes/[id]/page.tsx frontend/test/msw/handlers.ts frontend/test/adminDisputeDetail.test.tsx
git commit -m "feat(frontend): admin dispute detail + tier-2 backstop ruling"
```

---

## Task 12: Docs + memory

**Files:**
- Modify: `CLAUDE.md` (build-status paragraph)
- Modify: `docs/details/identity-and-authz.md` (admin gate) and/or `docs/details/data-model.md` (ESCALATED writer) as needed
- Create: `C:\Users\shaoxian04\.claude\projects\C--Users-shaoxian04-Documents-HireAI\memory\admin-dashboard-built.md` + index line in `MEMORY.md`

- [ ] **Step 1: Update `CLAUDE.md`.** In the backend build-status: change the Module 4 deferred list — the tier-2 Administrator override and the arbitration timeout sweeper are now **built** (admin human-backstop; DLQ + scheduled sweeper escalate to `ESCALATED`; auto-refund fallback removed). In the frontend build-status: **Admin surface built** (overview + dispute queue/detail/rule); update "Pending: Admin surface." Note `/api/admin/**` (`ROLE_ADMIN`) and `V23` admin seed.

- [ ] **Step 2: Update the detail docs** briefly: in `docs/details/identity-and-authz.md` note the `/api/admin/**` `hasRole("ADMIN")` gate; in `docs/details/data-model.md` note `ESCALATED` now has a writer (DLQ + sweeper) and `ADMINISTRATOR` tier-2 rulings.

- [ ] **Step 3: Write the memory** — `admin-dashboard-built.md`:

```markdown
---
name: admin-dashboard-built
description: Module 4 admin surface + human-backstop dispute handling — built (feat/module4-admin-dashboard)
metadata:
  type: project
---

Admin dashboard + administrator dispute handling built on `feat/module4-admin-dashboard` (2026-07-02).
Scope: read-only overview (metrics + task/agent/user-wallet browsers) + dispute queue → detail → **tier-2 human-backstop ruling**. Admin rules only **un-settled** disputes (`OPEN|ARBITRATING|ESCALATED`); money moves once, no reversal (settled disputes are read-only). DLQ no longer auto-refunds — `ArbitrationDlqListener` + a new `@Scheduled ArbitrationSweeper` both `escalate()` stranded disputes to `ESCALATED` (closes the deferred timeout-sweeper gap; `resolveByFallback` removed). `/api/admin/**` gated by `ROLE_ADMIN`; reads via `JdbcAdminQueryDao`; `admin@hireai.local` seeded in `V23` (DemoPass123!). Frontend: new `/admin` surface. See [[module4-adjudication-design]], [[arbitrator-evidence-gap]].
```

Then add to `MEMORY.md`: `- [Admin dashboard built](admin-dashboard-built.md) — human-backstop tier-2 disputes + read-only overview; DLQ+sweeper→ESCALATED`.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/details/identity-and-authz.md docs/details/data-model.md
git commit -m "docs: admin dashboard + backstop dispute handling built"
```
(Memory files live outside the repo — no git add needed for them.)

---

## Task 13: Live E2E verification

**Files:** none (verification only; screenshots saved to the repo root, untracked).

- [ ] **Step 1: Bring up the full local stack** per `docs/details/demo-runbook.md` (Postgres + RabbitMQ + stub agent + tunnel + backend on the freshly-built jar + arbitration worker + frontend). Never echo `OPENAI_API_KEY`.

- [ ] **Step 2: Force a stranded dispute.** Submit a task, drive it to `PENDING_REVIEW`, reject with an A/B/C reason to open a dispute. Then either (a) stop the arbitration worker before it rules and wait for the sweeper (`stale-after PT2M`) to flip it to `ESCALATED`, or (b) force a DLQ. Confirm via the DB or logs that the dispute is `ESCALATED`.

- [ ] **Step 3: Drive the admin UI with Playwright.** Log in as `admin@hireai.local` / `DemoPass123!` → land on `/admin` → open **Disputes** → confirm the stranded dispute is under **Needs attention** with an `ESCALATED` badge → open it → verify the evidence (task description, output spec, result) renders → choose **Not fulfilled** + a rationale → **Issue ruling**. Screenshot each step (e.g. `e2e-admin-escalated-queue.png`, `e2e-admin-ruling-resolved.png`).

- [ ] **Step 4: Verify settlement.** Confirm the client's wallet was refunded the full budget, the dispute shows a tier-2 `ADMINISTRATOR` ruling, and the client's task view reflects the resolution. Report results plainly (including anything that failed).

---

## Self-Review

**1. Spec coverage:**
- Domain `escalate()` + admin ruling tier-2 → Task 1. ✅
- App `escalate`/`adminRule`/`sweepStaleArbitrating`(as `staleArbitratingDisputeIds`)/`settleAndResolve` refactor/remove `resolveByFallback` → Tasks 2–3. ✅
- Infra DLQ→escalate + sweeper + `@EnableScheduling` + config → Task 3. ✅
- `V23` admin seed → Task 4. ✅
- Read layer (`JdbcAdminQueryDao` + `AdminReadAppService` + views) → Task 5. ✅
- `AdminController` + `/api/admin/**` `ROLE_ADMIN` gate → Task 6. ✅
- Integration: authz (Task 6), settlement + DLQ/sweeper (Task 7). ✅
- Frontend surface (auth/Nav/RoleGuard + 3 pages + vitest) → Tasks 8–11. ✅
- Docs + memory → Task 12. Live E2E → Task 13. ✅
- Invariants #2/#3/#5 addressed (money once, category-only, `ROLE_ADMIN` + JWT id). ✅

**2. Placeholder scan:** No "TBD"/"implement later". Two tasks reference an existing sibling test for Testcontainers *bootstrap boilerplate* (Task 5 → `CatalogueQueryDaoIntegrationTest`, Task 7 → `DisputeFlowIntegrationTest`) rather than repeating ~100 lines of container setup — the executor copies verbatim from a named existing file, and the assertion bodies + helper contracts are given in full. Task 8 Step 6 (Badge colors) references the exact keys/colors to add and notes the fallback, since the ui-kit file's internal format is matched at edit time.

**3. Type consistency:** `RulingCategory`/`RulingDecidedBy`/`DisputeStatus`/`TaskResolution` match the enums in Global Constraints. `AdminViews.*` record field names ↔ frontend `Admin*DTO` field names verified 1:1 (camelCase). `staleArbitratingDisputeIds(Instant)` (app) ↔ `findStaleArbitratingIds(Instant)` (repo) ↔ `@Query` field `gmtCreate` consistent. `adminRule(UUID, RulingCategory, String, UUID)` signature identical across interface, impl, controller call, and tests. `homeFor` return type widened to include `/admin` everywhere it's used. `DOMAIN_RULE_VIOLATION → 409` matches the conflict assertions.
