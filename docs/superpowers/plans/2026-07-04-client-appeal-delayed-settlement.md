# Client Appeal to Human (Delayed Settlement) + Discoverable Disputes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the arbitrator's ruling into a *proposed* outcome the client can accept or appeal to a human administrator, settling escrow exactly once, and make in-flight disputes discoverable via a dedicated client surface.

**Architecture:** Reuse the existing dispute state machine (`OPEN→ARBITRATING→RULED→RESOLVED`, plus `ESCALATED`). Move settlement *out* of the arbitrator-callback path into three finalizers (client-accept, admin-rule, auto-accept sweeper); each settles deterministically from the dispute's highest-tier ruling. Add one new domain transition `appeal()` (`RULED→ESCALATED`), two client endpoints, a client disputes list, and the frontend panel/list/nav.

**Tech Stack:** Spring Boot (Java 21) COLA multi-module; Flyway; JPA; RabbitMQ; JUnit 5 + Testcontainers; Next.js 16 + TypeScript + Tailwind; Vitest + MSW.

## Global Constraints

- **No schema migration** — all dispute states already exist (V17 CHECK); no new columns. Do not add a migration file.
- **Money moves exactly once** (Inv #2, append-only ledger — no reversal/compensating entries). Settlement computed deterministically in the domain/app from the ruling category (Inv #3).
- **Server-side identity** (Inv #5): derive the acting user from the JWT via `CurrentUserProvider.currentUserId()`; enforce `dispute.raisedBy() == clientId` for client actions.
- **Layering:** `controller → application → domain ← infrastructure`. Domain stays framework-free. App services are `interface + impl/` (`@Service` on impl). App-service interfaces annotate params with `org.jspecify.annotations.NonNull` (NOT lombok).
- **Build/test backend** only via the bootable module: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<Class> test`. Full suite: `mvn -f backend/pom.xml -B test`.
- **Frontend** reuses the Mission-Control UI kit (`Card`/`Badge`/`Button`/`Field`) — no new visual language. Vitest under the running stack: `npx vitest run --no-file-parallelism`.
- **Commits:** conventional-commits; NO `Co-Authored-By` trailer; NO `--no-verify`. Stage only touched files under `backend/`, `frontend/`, `docs/`.
- No new third-party dependencies.

---

## File Map

**Domain (`hireai-domain`)**
- Modify: `.../biz/adjudication/model/DisputeModel.java` — add `appeal()`.
- Modify: `.../biz/adjudication/repository/DisputeRepository.java` — add `findStaleRuledIds`.

**Application (`hireai-application`)**
- Modify: `.../biz/adjudication/dispute/DisputeAppService.java` — add `acceptRuling`, `appeal`, `autoAcceptStaleRulings`.
- Modify: `.../biz/adjudication/dispute/impl/DisputeAppServiceImpl.java` — split `settleAndResolve`; rewire callers; new methods.
- Modify: `.../biz/adjudication/dispute/DisputeReadAppService.java` (+ impl) — add `myDisputes`.
- Create: `.../biz/adjudication/dispute/view/DisputeMineRow.java` — read row record.
- Create: `.../biz/adjudication/port/DisputeQueryPort.java` — read port.

**Repository / Infrastructure**
- Modify: `hireai-repository/.../adjudication/DisputeJpaRepository.java` + `DisputeRepositoryImpl.java` — `findStaleRuledIds`.
- Create: `hireai-repository/.../adjudication/JdbcDisputeQueryDao.java` — `myDisputes` SQL (mirrors `JdbcAdminQueryDao`).
- Create: `hireai-infrastructure/.../messaging/RulingAcceptSweeper.java` — auto-accept `@Scheduled`.

**Controller (`hireai-controller`)**
- Modify: `.../biz/adjudication/DisputeController.java` — inject write service; `accept-ruling`, `appeal`, `mine`.
- Modify: `.../biz/adjudication/dto/DisputeOutcomeDTO.java` + `Dispute2DTOConverter.java` — add `disputeId`.
- Create: `.../biz/adjudication/dto/DisputeMineRowDTO.java`.

**Main (`hireai-main`)**
- Modify: `src/main/resources/application.yml` — `ruling-accept-after`, `accept-sweep-interval`.

**Frontend (`frontend/`)**
- Modify: `lib/types.ts` — extend `DisputeOutcomeDTO`, add `DisputeMineRowDTO`.
- Create: `components/DisputeProgressPanel.tsx` — replaces `DisputeOutcomePanel` usage.
- Modify: `app/client/tasks/[id]/page.tsx` — render the progress panel while `DISPUTED`.
- Create: `app/client/disputes/page.tsx` — the client disputes list.
- Modify: `components/Nav.tsx` — CLIENT "Disputes" link + badge.
- Create: `lib/useDisputeCount.ts` — badge count hook.
- Tests: `test/disputeProgressPanel.test.tsx`, `test/clientDisputes.test.tsx`, `test/msw/handlers.ts` (add handlers).

---

## Task 1: Domain — `appeal()` transition (`RULED → ESCALATED`)

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/domain/adjudication/DisputeModelTest.java`

**Interfaces:**
- Produces: `DisputeModel.appeal()` → new `DisputeModel` with status `ESCALATED`; throws `DomainException(DOMAIN_RULE_VIOLATION)` unless current status is `RULED`.

- [ ] **Step 1: Write the failing test** — append to `DisputeModelTest.java`

```java
@Test
void appeal_movesRuledToEscalated() {
    DisputeModel ruled = DisputeModel
            .open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.B_FACTUAL, "corr")
            .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR, Instant.now()));
    assertThat(ruled.status()).isEqualTo(DisputeStatus.RULED);

    DisputeModel appealed = ruled.appeal();

    assertThat(appealed.status()).isEqualTo(DisputeStatus.ESCALATED);
    assertThat(appealed.rulings()).hasSize(1); // arbitrator proposal preserved as admin context
}

@Test
void appeal_fromNonRuled_throws() {
    DisputeModel open = DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.A_MISMATCH, "corr");
    assertThatThrownBy(open::appeal)
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("appeal requires RULED");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeModelTest test`
Expected: FAIL — `cannot find symbol: method appeal()`.

- [ ] **Step 3: Add the transition** — in `DisputeModel.java`, after `escalate()` (around line 93)

```java
    /**
     * RULED → ESCALATED: the client appeals the arbitrator's PROPOSED ruling to the human admin.
     * The arbitrator ruling stays in the history as the admin's context; settlement has not run yet
     * (delayed-settlement model), so no money reversal is involved.
     */
    public DisputeModel appeal() {
        if (status != DisputeStatus.RULED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "appeal requires RULED; was " + status);
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ESCALATED,
                rulings, correlationId, createdAt, resolvedAt);
    }
```

Also update the class-level state-machine javadoc (line 21-22) to note the `RULED → {RESOLVED via accept | ESCALATED via appeal}` branches.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeModelTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java backend/hireai-main/src/test/java/com/hireai/domain/adjudication/DisputeModelTest.java
git commit -m "feat(domain): DisputeModel.appeal() RULED->ESCALATED for client appeals"
```

---

## Task 2: App — decouple settlement from ruling (arbitrator callback stops settling)

This is the crux. Split the fused `settleAndResolve` into `recordProposedRuling` (no money) + `settleFromEffective` (money once). Rewire `applyRuling` and `openDispute` to record-only. Migrate the unit tests that assumed the callback settles.

**Files:**
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/adjudication/DisputeAppServiceImplTest.java` (existing)

**Interfaces:**
- Consumes: `DisputeModel.recordRuling`, `.effectiveRuling()`, `.resolve()`; `SettlementWriteAppService.{settleAccepted,settleRejected,settleSplit}`; `TaskRepository.findByIdForUpdate`.
- Produces (private): `recordProposedRuling(DisputeModel, Ruling)`, `settleFromEffective(DisputeModel)`.

- [ ] **Step 1: Write/adjust the failing test** — in `DisputeAppServiceImplTest.java`, replace any test asserting `applyRuling` settles with:

```java
@Test
void applyRuling_recordsProposal_doesNotSettle() {
    DisputeModel arb = DisputeModel.open(TASK_ID, CLIENT_ID, RejectReason.B_FACTUAL, "corr").startArbitrating();
    when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(arb));

    service.applyRuling(DISPUTE_ID, new RulingInfo(RulingCategory.NOT_FULFILLED, "off-topic"));

    // Ruling recorded, dispute now RULED, but NO settlement and NO task lock/resolve happened.
    ArgumentCaptor<DisputeModel> saved = ArgumentCaptor.forClass(DisputeModel.class);
    verify(disputeRepository).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DisputeStatus.RULED);
    verifyNoInteractions(settlementWriteAppService);
    verify(taskRepository, never()).findByIdForUpdate(any());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeAppServiceImplTest test`
Expected: FAIL — current `applyRuling` calls `settleAndResolve` → `settlementWriteAppService` is invoked.

- [ ] **Step 3: Refactor `DisputeAppServiceImpl`**

Replace the `applyRuling`, `openDispute` synchronous branch, and `settleAndResolve` with:

```java
    @Override
    public UUID openDispute(TaskModel disputedTask, UUID raisedBy, RejectReason reasonCategory) {
        String correlationId = "dispute-" + disputedTask.id();
        DisputeModel dispute = DisputeModel.open(disputedTask.id(), raisedBy, reasonCategory, correlationId);
        disputeRepository.save(dispute);

        Optional<RulingInfo> immediate = arbitrationGateway.requestRuling(dispute, disputedTask);
        if (immediate.isPresent()) {
            // Delayed settlement: an immediate (stub/sync) ruling is a PROPOSAL, not a settlement.
            recordProposedRuling(dispute, toRuling(immediate.get(), TIER_1, RulingDecidedBy.ARBITRATOR));
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
        // Arbitrator ruling is now a PROPOSAL. Escrow stays held until the client accepts/appeals.
        recordProposedRuling(dispute, toRuling(ruling, TIER_1, RulingDecidedBy.ARBITRATOR));
    }

    /** Records a ruling and moves the dispute to RULED. No money moves. */
    private void recordProposedRuling(DisputeModel dispute, Ruling ruling) {
        disputeRepository.save(dispute.recordRuling(ruling));
        log.info("Dispute {} proposed ruling {} (tier {}); awaiting client", dispute.id(), ruling.category(), ruling.tier());
    }

    private Ruling toRuling(RulingInfo info, int tier, RulingDecidedBy by) {
        return new Ruling(tier, info.category(), info.rationale(), by, Instant.now());
    }

    /** Settles escrow ONCE from the dispute's effective (highest-tier) ruling, then resolves task + dispute. */
    private void settleFromEffective(DisputeModel dispute) {
        RulingCategory category = dispute.effectiveRuling()
                .orElseThrow(() -> new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Dispute " + dispute.id() + " has no ruling to settle"))
                .category();
        TaskModel task = lockTask(dispute.taskId());
        switch (category) {
            case FULFILLED -> {
                UUID builderId = requireBuilder(task);
                settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.ACCEPTED));
            }
            case PARTIALLY_FULFILLED -> {
                UUID builderId = requireBuilder(task);
                settlementWriteAppService.settleSplit(task.id(), task.clientId(), builderId, task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.PARTIALLY_ACCEPTED));
            }
            case NOT_FULFILLED -> {
                settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.REJECTED));
            }
        }
        disputeRepository.save(dispute.resolve());
    }
```

Update `adminRule` to use the two new helpers (record tier-2, then settle from effective):

```java
    @Override
    public void adminRule(UUID disputeId, RulingCategory category, String rationale, UUID adminId) {
        DisputeModel dispute = requireDispute(disputeId);
        DisputeStatus s = dispute.status();
        if (s != DisputeStatus.OPEN && s != DisputeStatus.ARBITRATING && s != DisputeStatus.ESCALATED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute " + disputeId + " is " + s + "; already settled — admin cannot re-rule");
        }
        log.info("Admin {} ruling dispute {} as {}", adminId, disputeId, category);
        DisputeModel ruled = dispute.recordRuling(toRuling(new RulingInfo(category, rationale), TIER_2, RulingDecidedBy.ADMINISTRATOR));
        settleFromEffective(ruled);
    }
```

Delete the old `settleAndResolve` method. Keep `lockTask`, `requireBuilder`, `requireDispute`.

- [ ] **Step 4: Run to verify it passes** (and fix other now-broken unit tests in the same file)

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeAppServiceImplTest test`
Expected: PASS. Any sibling test in this file asserting that `openDispute` (sync stub) settles must be updated to assert `RULED` + no settlement (same shape as Step 1).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java backend/hireai-main/src/test/java/com/hireai/application/adjudication/DisputeAppServiceImplTest.java
git commit -m "refactor(adjudication): decouple settlement from ruling; arbitrator ruling is now a proposal"
```

---

## Task 3: App — `acceptRuling` + `appeal` (ownership + task-lock race safety)

**Files:**
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java`
- Test: `.../DisputeAppServiceImplTest.java`

**Interfaces:**
- Produces: `DisputeAppService.acceptRuling(@NonNull UUID disputeId, @NonNull UUID clientId)`; `DisputeAppService.appeal(@NonNull UUID disputeId, @NonNull UUID clientId)`. Both throw `DomainException(DOMAIN_RULE_VIOLATION)` if the caller is not the dispute's `raisedBy`, or the status is not `RULED`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void acceptRuling_settlesFromProposal() {
    DisputeModel ruled = ruledDispute(RulingCategory.FULFILLED); // OPEN->recordRuling helper in test
    when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(ruled));
    when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
    when(agentRepository.findOwnerByVersionId(any())).thenReturn(Optional.of(BUILDER_ID));

    service.acceptRuling(DISPUTE_ID, CLIENT_ID);

    verify(settlementWriteAppService).settleAccepted(eq(TASK_ID), eq(CLIENT_ID), eq(BUILDER_ID), any());
}

@Test
void acceptRuling_byNonOwner_throws() {
    when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(ruledDispute(RulingCategory.FULFILLED)));
    assertThatThrownBy(() -> service.acceptRuling(DISPUTE_ID, UUID.randomUUID()))
            .isInstanceOf(DomainException.class).hasMessageContaining("not your dispute");
    verifyNoInteractions(settlementWriteAppService);
}

@Test
void appeal_movesToEscalated_noSettlement() {
    when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(ruledDispute(RulingCategory.FULFILLED)));
    when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

    service.appeal(DISPUTE_ID, CLIENT_ID);

    ArgumentCaptor<DisputeModel> saved = ArgumentCaptor.forClass(DisputeModel.class);
    verify(disputeRepository).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DisputeStatus.ESCALATED);
    verifyNoInteractions(settlementWriteAppService);
}
```

Add a `ruledDispute(category)` test helper that builds an OPEN dispute (`raisedBy = CLIENT_ID`, `taskId = TASK_ID`) then `.recordRuling(tier-1 ARBITRATOR, category)`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeAppServiceImplTest test`
Expected: FAIL — `acceptRuling`/`appeal` not defined.

- [ ] **Step 3: Add to the interface** (`DisputeAppService.java`)

```java
    /** Client accepts the arbitrator's PROPOSED ruling → settle once by category, resolve. */
    void acceptRuling(@NonNull UUID disputeId, @NonNull UUID clientId);

    /** Client appeals the PROPOSED ruling to the human admin (RULED → ESCALATED). */
    void appeal(@NonNull UUID disputeId, @NonNull UUID clientId);

    /** Auto-accept RULED disputes untouched since before {@code cutoff} (settle from the proposal). */
    void autoAcceptStaleRulings(@NonNull Instant cutoff);
```

- [ ] **Step 4: Implement in `DisputeAppServiceImpl`** (`autoAcceptStaleRulings` body lands in Task 4)

```java
    @Override
    public void acceptRuling(UUID disputeId, UUID clientId) {
        DisputeModel dispute = lockAndRevalidateRuled(disputeId, clientId);
        settleFromEffective(dispute);
    }

    @Override
    public void appeal(UUID disputeId, UUID clientId) {
        DisputeModel dispute = lockAndRevalidateRuled(disputeId, clientId);
        disputeRepository.save(dispute.appeal());
        log.info("Client {} appealed dispute {} to admin backstop", clientId, disputeId);
    }

    /**
     * Serializes the RULED→{RESOLVED|ESCALATED} transitions: takes the task pessimistic lock (the
     * same lock settlement uses) so accept/appeal/auto-accept can't both win, then re-reads and
     * re-validates the dispute under the lock. Ownership (Inv #5) is checked here too.
     */
    private DisputeModel lockAndRevalidateRuled(UUID disputeId, UUID clientId) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.raisedBy().equals(clientId)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "not your dispute: " + disputeId);
        }
        lockTask(dispute.taskId()); // serialization point
        DisputeModel fresh = requireDispute(disputeId); // re-read under lock
        if (fresh.status() != DisputeStatus.RULED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute " + disputeId + " is " + fresh.status() + "; no proposed ruling to act on");
        }
        return fresh;
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeAppServiceImplTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeAppService.java backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java backend/hireai-main/src/test/java/com/hireai/application/adjudication/DisputeAppServiceImplTest.java
git commit -m "feat(adjudication): client acceptRuling + appeal with ownership + task-lock race safety"
```

---

## Task 4: Repo + sweeper — auto-accept timeout

**Files:**
- Modify: `backend/hireai-domain/.../adjudication/repository/DisputeRepository.java`
- Modify: `backend/hireai-repository/.../adjudication/DisputeJpaRepository.java`, `DisputeRepositoryImpl.java`
- Modify: `backend/hireai-application/.../adjudication/dispute/impl/DisputeAppServiceImpl.java` (`autoAcceptStaleRulings` body)
- Create: `backend/hireai-infrastructure/.../messaging/RulingAcceptSweeper.java`
- Modify: `backend/hireai-main/src/main/resources/application.yml`
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/messaging/RulingAcceptSweeperTest.java`

**Interfaces:**
- Consumes: `DisputeAppService.autoAcceptStaleRulings(Instant)`, `DisputeRepository.findStaleRuledIds(Instant)`.
- Produces: `RulingAcceptSweeper.sweep()` (package-visible).

- [ ] **Step 1: Domain + repo query.** Add to `DisputeRepository.java`:

```java
    /** Ids of disputes stuck in RULED (proposed, unacted) since before {@code cutoff} (auto-accept sweeper). */
    List<UUID> findStaleRuledIds(Instant cutoff);
```

Add to `DisputeJpaRepository.java`. **Note:** `DisputeDO` maps only `gmt_create`, not `gmt_modified` (the column exists but Hibernate never updates it), so "how long in RULED" comes from the **latest ruling's `decided_at`** (the arbitrator proposal timestamp) via a native query — not `gmt_modified`:

```java
    @Query(value = """
        SELECT d.id FROM disputes d
        WHERE d.status = 'RULED'
          AND (SELECT max(r.decided_at) FROM dispute_rulings r WHERE r.dispute_id = d.id) < :cutoff
        """, nativeQuery = true)
    List<UUID> findStaleRuledIds(Instant cutoff);
```

Delegate in `DisputeRepositoryImpl.java`:

```java
    @Override
    public List<UUID> findStaleRuledIds(Instant cutoff) {
        return disputeJpaRepository.findStaleRuledIds(cutoff);
    }
```

- [ ] **Step 2: `autoAcceptStaleRulings` in `DisputeAppServiceImpl`** — each id in its own settle path:

```java
    @Override
    public void autoAcceptStaleRulings(Instant cutoff) {
        for (UUID id : disputeRepository.findStaleRuledIds(cutoff)) {
            DisputeModel dispute = requireDispute(id);
            lockTask(dispute.taskId());
            DisputeModel fresh = requireDispute(id);
            if (fresh.status() == DisputeStatus.RULED) {
                settleFromEffective(fresh);
                log.info("Auto-accepted stale proposed ruling for dispute {}", id);
            }
        }
    }
```
Add `List<UUID> staleRuledDisputeIds(Instant)` is NOT needed — the sweeper calls `autoAcceptStaleRulings` directly.

- [ ] **Step 3: Write the sweeper test** (`RulingAcceptSweeperTest.java`) — mirrors `ArbitrationSweeperTest`:

```java
@ExtendWith(MockitoExtension.class)
class RulingAcceptSweeperTest {
    @Mock DisputeAppService disputeAppService;

    @Test
    void sweep_autoAcceptsWithComputedCutoff() {
        RulingAcceptSweeper sweeper = new RulingAcceptSweeper(disputeAppService, Duration.ofMinutes(2));
        sweeper.sweep();
        verify(disputeAppService).autoAcceptStaleRulings(any(Instant.class));
    }
}
```

- [ ] **Step 4: Create `RulingAcceptSweeper.java`** (mirror `ArbitrationSweeper`):

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

/**
 * Auto-accept sweeper: a RULED dispute is a PROPOSED ruling awaiting the client. If the client never
 * accepts or appeals, escrow would hang forever. This job auto-accepts any RULED dispute older than
 * {@code hireai.arbitration.ruling-accept-after}, settling from the arbitrator's proposal.
 */
@Slf4j
@Component
@Profile("!test")
public class RulingAcceptSweeper {

    private final DisputeAppService disputeAppService;
    private final Duration acceptAfter;

    public RulingAcceptSweeper(DisputeAppService disputeAppService,
                               @Value("${hireai.arbitration.ruling-accept-after:PT2M}") Duration acceptAfter) {
        this.disputeAppService = disputeAppService;
        this.acceptAfter = acceptAfter;
    }

    @Scheduled(fixedDelayString = "${hireai.arbitration.accept-sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    void sweep() {
        try {
            disputeAppService.autoAcceptStaleRulings(Instant.now().minus(acceptAfter));
        } catch (Exception e) {
            log.warn("Ruling-accept sweeper pass failed", e);
        }
    }
}
```

- [ ] **Step 5: Config** — add under `hireai.arbitration` in `application.yml`:

```yaml
    ruling-accept-after: ${RULING_ACCEPT_AFTER:PT2M}
    accept-sweep-interval: ${RULING_ACCEPT_SWEEP_INTERVAL:PT1M}
```

- [ ] **Step 6: Run tests**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RulingAcceptSweeperTest test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/DisputeRepository.java backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeJpaRepository.java backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryImpl.java backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/RulingAcceptSweeper.java backend/hireai-main/src/main/resources/application.yml backend/hireai-main/src/test/java/com/hireai/infrastructure/messaging/RulingAcceptSweeperTest.java
git commit -m "feat(adjudication): auto-accept sweeper for stale proposed rulings"
```

---

## Task 5: Integration — full appeal flow + migrate settle-assuming integration tests

**Files:**
- Modify: `backend/hireai-main/src/test/java/com/hireai/adjudication/AdminDisputeFlowIntegrationTest.java`
- Modify: `backend/hireai-main/src/test/java/com/hireai/adjudication/ArbitrationTransportIntegrationTest.java`
- Create/extend: an integration test method `rejectProposeAppealAdmin_settlesOnce`.

**Interfaces:**
- Consumes: the full app context; the `test` profile uses the synchronous `StubArbitrationClient` (so `openDispute` now leaves the dispute `RULED`, not settled).

- [ ] **Step 1: Migrate the assertions.** In `ArbitrationTransportIntegrationTest` and any test where a reject/arbitrator ruling previously left the task `RESOLVED`, change the expectation to: after the arbitrator proposes, the task is still `DISPUTED`, the dispute is `RULED`, and the wallet still shows escrow held (available unchanged, escrow = budget). Then call `acceptRuling` (or `appeal`) and assert the settlement.

- [ ] **Step 2: Add the appeal E2E integration test**

```java
@Test
void rejectProposeAppealAdmin_settlesExactlyOnce() {
    // given a task at PENDING_REVIEW with a result, seeded via the harness helpers
    UUID taskId = seedReviewableTask(CLIENT_ID, budget("100"));

    // client rejects (B_FACTUAL) -> dispute opens; stub proposes a ruling synchronously -> RULED
    reviewAppService.reject(taskId, CLIENT_ID, RejectReason.B_FACTUAL, "off-topic");
    UUID disputeId = disputeRepository.findByTaskId(taskId).orElseThrow().id();
    assertThat(disputeRepository.findById(disputeId).orElseThrow().status()).isEqualTo(DisputeStatus.RULED);
    assertThat(walletOf(CLIENT_ID).escrowBalance()).isEqualByComparingTo("100"); // still held

    // client appeals -> ESCALATED (admin queue), still no settlement
    disputeAppService.appeal(disputeId, CLIENT_ID);
    assertThat(disputeRepository.findById(disputeId).orElseThrow().status()).isEqualTo(DisputeStatus.ESCALATED);
    assertThat(walletOf(CLIENT_ID).escrowBalance()).isEqualByComparingTo("100");

    // admin overrides to NOT_FULFILLED -> settles once, full refund
    disputeAppService.adminRule(disputeId, RulingCategory.NOT_FULFILLED, "human override", ADMIN_ID);
    assertThat(disputeRepository.findById(disputeId).orElseThrow().status()).isEqualTo(DisputeStatus.RESOLVED);
    assertThat(walletOf(CLIENT_ID).availableBalance()).isEqualByComparingTo("1000"); // refunded
    assertThat(walletOf(CLIENT_ID).escrowBalance()).isEqualByComparingTo("0");
    assertThat(ledgerSettlementCount(taskId)).isEqualTo(1); // exactly once
}
```
Use the existing seed/helper utilities in the test class (`seedReviewableTask`, `walletOf`, `ledgerSettlementCount` — mirror whatever `AdminDisputeFlowIntegrationTest` already uses; if a helper is missing, add it there).

- [ ] **Step 3: Run (skips without Docker; run where Docker is available)**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AdminDisputeFlowIntegrationTest,ArbitrationTransportIntegrationTest test`
Expected: PASS (or auto-skip if no Docker daemon).

- [ ] **Step 4: Full backend suite** (catch every migrated test)

Run: `mvn -f backend/pom.xml -B test`
Expected: green (Testcontainers tests auto-skip without Docker). Fix any remaining test that assumed callback-settles.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-main/src/test/java/com/hireai/adjudication/
git commit -m "test(adjudication): appeal flow settles once; migrate settle-on-callback assertions"
```

---

## Task 6: Read — `myDisputes` (port + DAO + read service)

**Files:**
- Create: `backend/hireai-application/.../adjudication/dispute/view/DisputeMineRow.java`
- Create: `backend/hireai-application/.../adjudication/port/DisputeQueryPort.java`
- Modify: `backend/hireai-application/.../adjudication/dispute/DisputeReadAppService.java` (+ impl)
- Create: `backend/hireai-repository/.../adjudication/JdbcDisputeQueryDao.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/adjudication/JdbcDisputeQueryDaoIntegrationTest.java`

**Interfaces:**
- Produces: `DisputeReadAppService.myDisputes(@NonNull UUID clientId)` → `List<DisputeMineRow>`; `DisputeMineRow(UUID disputeId, UUID taskId, String taskTitle, String status, String proposedCategory, Instant updatedAt)`.

- [ ] **Step 1: `DisputeMineRow` record**

```java
package com.hireai.application.biz.adjudication.dispute.view;

import java.time.Instant;
import java.util.UUID;

public record DisputeMineRow(UUID disputeId, UUID taskId, String taskTitle, String status,
                             String proposedCategory, Instant updatedAt) {}
```

- [ ] **Step 2: `DisputeQueryPort`**

```java
package com.hireai.application.biz.adjudication.port;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import java.util.List;
import java.util.UUID;

public interface DisputeQueryPort {
    List<DisputeMineRow> findDisputesForClient(UUID clientId);
}
```

- [ ] **Step 3: Extend `DisputeReadAppService`** (interface + impl). Interface:

```java
    /** All disputes the given client raised (action-needed first), for the /client/disputes surface. */
    java.util.List<com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow> myDisputes(@NonNull UUID clientId);
```
Impl delegates to the injected `DisputeQueryPort.findDisputesForClient(clientId)`.

- [ ] **Step 4: `JdbcDisputeQueryDao`** (mirror `JdbcAdminQueryDao`; RULED first via ORDER BY)

```java
package com.hireai.infrastructure.repository.adjudication;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDisputeQueryDao implements DisputeQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDisputeQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // dispute_rulings columns are tier/category/rationale/decided_at (V21). gmt_modified is not
    // entity-maintained, so updated_at derives from the latest ruling's decided_at, else gmt_create.
    private static final String SQL = """
        SELECT d.id AS dispute_id, d.task_id, t.title AS task_title, d.status,
               lr.category AS proposed_category,
               COALESCE(lr.decided_at, d.gmt_create) AS updated_at
        FROM disputes d
        JOIN tasks t ON t.id = d.task_id
        LEFT JOIN LATERAL (
            SELECT r.category, r.decided_at FROM dispute_rulings r
            WHERE r.dispute_id = d.id ORDER BY r.tier DESC, r.decided_at DESC LIMIT 1
        ) lr ON true
        WHERE d.raised_by = :clientId
        ORDER BY CASE d.status WHEN 'RULED' THEN 0 WHEN 'ARBITRATING' THEN 1
                               WHEN 'ESCALATED' THEN 1 WHEN 'OPEN' THEN 1 ELSE 2 END,
                 COALESCE(lr.decided_at, d.gmt_create) DESC
        """;

    @Override
    public List<DisputeMineRow> findDisputesForClient(UUID clientId) {
        return jdbc.query(SQL, new MapSqlParameterSource("clientId", clientId), (rs, i) ->
                new DisputeMineRow(
                        rs.getObject("dispute_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("task_title"),
                        rs.getString("status"),
                        rs.getString("proposed_category"),
                        rs.getTimestamp("updated_at").toInstant()));
    }
}
```
(Column names verified against V21/V17: `dispute_rulings(dispute_id, tier, category, rationale, decided_at)`; `disputes(id, task_id, raised_by, status, gmt_create, gmt_modified)`.)

- [ ] **Step 5: Integration test** (mirror `JdbcAdminQueryDaoIntegrationTest`): seed a client with two disputes (one `RULED`, one `RESOLVED`); assert `findDisputesForClient` returns both, `RULED` first, with `proposedCategory` populated.

- [ ] **Step 6: Run + commit**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JdbcDisputeQueryDaoIntegrationTest test` (auto-skips w/o Docker)
```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/ backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/JdbcDisputeQueryDao.java backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/adjudication/JdbcDisputeQueryDaoIntegrationTest.java
git commit -m "feat(adjudication): client-scoped myDisputes read (port + jdbc dao)"
```

---

## Task 7: Controller — accept-ruling / appeal / mine + `disputeId` on the by-task DTO

**Files:**
- Modify: `backend/hireai-controller/.../adjudication/DisputeController.java`
- Modify: `backend/hireai-controller/.../adjudication/dto/DisputeOutcomeDTO.java`, `Dispute2DTOConverter.java`
- Create: `backend/hireai-controller/.../adjudication/dto/DisputeMineRowDTO.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/adjudication/DisputeControllerTest.java`

**Interfaces:**
- Consumes: `DisputeAppService.{acceptRuling,appeal}`, `DisputeReadAppService.{getOutcomeForUser,myDisputes}`, `CurrentUserProvider.currentUserId()`.
- Produces: `POST /api/disputes/{id}/accept-ruling`, `POST /api/disputes/{id}/appeal`, `GET /api/disputes/mine`; `DisputeOutcomeDTO` gains `disputeId`.

- [ ] **Step 1: Extend `DisputeOutcomeDTO`**

```java
public record DisputeOutcomeDTO(UUID disputeId, UUID taskId, String status, String effectiveCategory,
                                List<RulingDTO> rulings) {}
```
Update `Dispute2DTOConverter.toDTO` to pass `dispute.id()` first.

- [ ] **Step 2: `DisputeMineRowDTO`**

```java
package com.hireai.controller.biz.adjudication.dto;

import java.time.Instant;
import java.util.UUID;

public record DisputeMineRowDTO(UUID disputeId, UUID taskId, String taskTitle, String status,
                                String proposedCategory, Instant updatedAt) {}
```

- [ ] **Step 3: Write the controller test** (`@WebMvcTest(DisputeController.class)` + `@Import(SecurityConfig)` + `@MockBean JwtService`, mirroring `AdminControllerTest`):

```java
@Test @WithMockUser
void acceptRuling_delegates() throws Exception {
    mvc.perform(post("/api/disputes/{id}/accept-ruling", DISPUTE_ID).with(csrf()))
       .andExpect(status().isOk());
    verify(disputeAppService).acceptRuling(eq(DISPUTE_ID), any());
}

@Test
void acceptRuling_anonymous_401() throws Exception {
    mvc.perform(post("/api/disputes/{id}/accept-ruling", DISPUTE_ID).with(csrf()))
       .andExpect(status().isUnauthorized());
}

@Test @WithMockUser
void mine_returnsRows() throws Exception {
    when(disputeReadAppService.myDisputes(any())).thenReturn(List.of());
    mvc.perform(get("/api/disputes/mine")).andExpect(status().isOk());
}
```

- [ ] **Step 4: Extend `DisputeController`** — inject the write service; add endpoints:

```java
    private final DisputeAppService disputeAppService; // add to constructor

    @PostMapping("/{id}/accept-ruling")
    public WebResult<DisputeOutcomeDTO> acceptRuling(@PathVariable("id") UUID id) {
        disputeAppService.acceptRuling(id, currentUser.currentUserId());
        return ok(Dispute2DTOConverter.toDTO(disputeReadAppService.getOutcomeByDispute(id)));
    }

    @PostMapping("/{id}/appeal")
    public WebResult<DisputeOutcomeDTO> appeal(@PathVariable("id") UUID id) {
        disputeAppService.appeal(id, currentUser.currentUserId());
        return ok(Dispute2DTOConverter.toDTO(disputeReadAppService.getOutcomeByDispute(id)));
    }

    @GetMapping("/mine")
    public WebResult<List<DisputeMineRowDTO>> mine() {
        List<DisputeMineRowDTO> rows = disputeReadAppService.myDisputes(currentUser.currentUserId()).stream()
                .map(r -> new DisputeMineRowDTO(r.disputeId(), r.taskId(), r.taskTitle(), r.status(),
                        r.proposedCategory(), r.updatedAt()))
                .toList();
        return ok(rows);
    }
```
Add `DisputeModel getOutcomeByDispute(@NonNull UUID disputeId)` to `DisputeReadAppService` (+ impl) — loads by dispute id with an ownership check (client raised it OR owning builder), reusing the existing owner-check logic in `getOutcomeForUser`. (The refreshed view after accept/appeal is keyed by dispute id, not task id.)

- [ ] **Step 5: Run**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DisputeControllerTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/ backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/ backend/hireai-main/src/test/java/com/hireai/controller/adjudication/DisputeControllerTest.java
git commit -m "feat(adjudication): client accept-ruling/appeal/mine endpoints + disputeId on outcome DTO"
```

---

## Task 8: Frontend — types + `DisputeProgressPanel` + task-page wiring

**Files:**
- Modify: `frontend/lib/types.ts`
- Create: `frontend/components/DisputeProgressPanel.tsx`
- Modify: `frontend/app/client/tasks/[id]/page.tsx`
- Test: `frontend/test/disputeProgressPanel.test.tsx`, `frontend/test/msw/handlers.ts`

**Interfaces:**
- Consumes: `api<DisputeOutcomeDTO>()`; `DisputeOutcomeDTO` now has `disputeId` + `status`.
- Produces: `<DisputeProgressPanel outcome onChange />`.

- [ ] **Step 1: Extend types** in `lib/types.ts` — add `disputeId` to `DisputeOutcomeDTO`, and:

```ts
export interface DisputeMineRowDTO {
  disputeId: string;
  taskId: string;
  taskTitle: string;
  status: string;            // OPEN | ARBITRATING | RULED | ESCALATED | RESOLVED
  proposedCategory: RulingCategory | null;
  updatedAt: string;
}
```
Update `DisputeOutcomeDTO`:
```ts
export interface DisputeOutcomeDTO {
  disputeId: string;
  taskId: string;
  status: string;
  effectiveCategory: RulingCategory | null;
  rulings: RulingDTO[];
}
```

- [ ] **Step 2: Write the panel test** (`disputeProgressPanel.test.tsx`) — three states:

```tsx
it("RULED: shows proposed ruling + accept/appeal", () => {
  render(<DisputeProgressPanel outcome={ruled} onChange={vi.fn()} />);
  expect(screen.getByText(/proposed ruling/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /accept/i })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /appeal/i })).toBeInTheDocument();
});
it("ARBITRATING: shows under-review, no buttons", () => {
  render(<DisputeProgressPanel outcome={{ ...ruled, status: "ARBITRATING", rulings: [] }} onChange={vi.fn()} />);
  expect(screen.getByText(/under review/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /accept/i })).toBeNull();
});
it("RESOLVED: shows final outcome", () => {
  render(<DisputeProgressPanel outcome={{ ...ruled, status: "RESOLVED" }} onChange={vi.fn()} />);
  expect(screen.getByText(/resolved/i)).toBeInTheDocument();
});
```
(`ruled` = `{ disputeId:"d-1", taskId:"t-1", status:"RULED", effectiveCategory:"FULFILLED", rulings:[{tier:1,decidedBy:"ARBITRATOR",category:"FULFILLED",rationale:"ok",decidedAt:"..."}] }`.)

- [ ] **Step 3: Create `DisputeProgressPanel.tsx`**

```tsx
"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Card, Button } from "@/components/ui";
import type { DisputeOutcomeDTO, RulingCategory } from "@/lib/types";

const CATEGORY_LABEL: Record<RulingCategory, string> = {
  FULFILLED: "Fulfilled — pay the builder",
  PARTIALLY_FULFILLED: "Partially fulfilled — split",
  NOT_FULFILLED: "Not fulfilled — full refund",
};

export function DisputeProgressPanel({
  outcome,
  onChange,
}: {
  outcome: DisputeOutcomeDTO;
  onChange: (next: DisputeOutcomeDTO) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const proposal = outcome.rulings[outcome.rulings.length - 1];

  async function act(kind: "accept-ruling" | "appeal") {
    setBusy(true);
    setError(null);
    try {
      const next = await api<DisputeOutcomeDTO>(`/disputes/${outcome.disputeId}/${kind}`, { method: "POST" });
      onChange(next);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-4 border-t border-line">
      <p className="eyebrow">Dispute</p>

      {(outcome.status === "OPEN" || outcome.status === "ARBITRATING") && (
        <p className="flex items-center gap-2 font-mono text-xs uppercase tracking-wider text-cyan">
          <span className="size-1.5 rounded-full bg-cyan dot-live" /> Under review by an arbitrator…
        </p>
      )}

      {outcome.status === "RULED" && proposal && (
        <div className="space-y-3">
          <p className="eyebrow text-dim">Proposed ruling · arbitrator</p>
          <p className="font-mono text-sm text-fg">{CATEGORY_LABEL[proposal.category]}</p>
          {proposal.rationale && <p className="text-sm leading-relaxed text-muted">{proposal.rationale}</p>}
          <p className="text-sm text-muted">
            Escrow is still held. Accept this, or appeal to a human administrator for a final decision.
          </p>
          {error && <p role="alert" className="font-mono text-xs text-red">{error}</p>}
          <div className="flex gap-2">
            <Button onClick={() => act("accept-ruling")} disabled={busy}>Accept ruling ▸</Button>
            <Button variant="ghost" onClick={() => act("appeal")} disabled={busy}>Appeal to a human</Button>
          </div>
        </div>
      )}

      {outcome.status === "ESCALATED" && (
        <p className="font-mono text-xs text-amber">Escalated to a human administrator for final review…</p>
      )}

      {outcome.status === "RESOLVED" && (
        <div className="space-y-2">
          <p className="eyebrow text-dim">Resolved</p>
          {outcome.effectiveCategory && (
            <p className="font-mono text-sm text-fg">{CATEGORY_LABEL[outcome.effectiveCategory]}</p>
          )}
          {outcome.rulings.map((r, i) => (
            <p key={i} className="text-sm text-muted">
              <span className="text-accent">{r.decidedBy === "ADMINISTRATOR" ? "Administrator" : "Arbitrator"}</span>
              {r.rationale ? ` — ${r.rationale}` : ""}
            </p>
          ))}
        </div>
      )}
    </Card>
  );
}
```

- [ ] **Step 4: Wire into the task page** (`app/client/tasks/[id]/page.tsx`) — replace the `DisputeOutcomePanel` import/usage. Change the outcome effect to fetch while the task is in a dispute, and keep polling until `RESOLVED`:

```tsx
// import { DisputeProgressPanel } from "@/components/DisputeProgressPanel";
// in the outcome effect, fetch when task.status === "DISPUTED" || "RESOLVED":
useEffect(() => {
  if (!task || task.status !== "DISPUTED") return;
  const t = setInterval(() => {
    api<DisputeOutcomeDTO>(`/disputes/by-task/${task.id}`)
      .then((o) => setOutcome(o))
      .catch((e) => { if (!isPendingError(e)) console.error(e); });
  }, POLL_MS);
  return () => clearInterval(t);
}, [task]);
// render:
{outcome && <DisputeProgressPanel outcome={outcome} onChange={setOutcome} />}
```
Keep the existing one-shot fetch for `RESOLVED`. Delete `components/DisputeOutcomePanel.tsx` only after confirming no other importer (grep first).

- [ ] **Step 5: Add MSW handlers** in `test/msw/handlers.ts`: `GET */api/disputes/by-task/:taskId`, `POST */api/disputes/:id/accept-ruling`, `POST */api/disputes/:id/appeal` returning `DisputeOutcomeDTO`.

- [ ] **Step 6: Run**

Run: `cd frontend && npx vitest run test/disputeProgressPanel.test.tsx`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/lib/types.ts frontend/components/DisputeProgressPanel.tsx frontend/app/client/tasks/[id]/page.tsx frontend/test/disputeProgressPanel.test.tsx frontend/test/msw/handlers.ts
git commit -m "feat(frontend): DisputeProgressPanel with accept/appeal; wire into task page"
```

---

## Task 9: Frontend — `/client/disputes` list + nav "Disputes" item + badge

**Files:**
- Create: `frontend/app/client/disputes/page.tsx`
- Create: `frontend/lib/useDisputeCount.ts`
- Modify: `frontend/components/Nav.tsx`
- Test: `frontend/test/clientDisputes.test.tsx`, `frontend/test/msw/handlers.ts`

**Interfaces:**
- Consumes: `api<DisputeMineRowDTO[]>("/disputes/mine")`.
- Produces: `useDisputeCount()` → `number` (count of `RULED`).

- [ ] **Step 1: `useDisputeCount` hook**

```ts
"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { DisputeMineRowDTO } from "@/lib/types";

/** Count of the client's disputes awaiting their decision (RULED). Best-effort; 0 on error. */
export function useDisputeCount(enabled: boolean): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    api<DisputeMineRowDTO[]>("/disputes/mine")
      .then((rows) => { if (!cancelled) setCount(rows.filter((r) => r.status === "RULED").length); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [enabled]);
  return count;
}
```

- [ ] **Step 2: Nav badge** — in `Nav.tsx`, inside the `activeSurface === "CLIENT"` block, add a Disputes link with the badge:

```tsx
// at top of Nav(): const disputeCount = useDisputeCount(activeSurface === "CLIENT");
// in the CLIENT links array, add { href: "/client/disputes", label: "Disputes" }, and render its badge:
<Link href="/client/disputes" className="relative rounded-md px-3 py-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted transition hover:text-fg">
  Disputes
  {disputeCount > 0 && (
    <span className="ml-1.5 rounded-full bg-accent/20 px-1.5 py-0.5 text-[0.6rem] font-bold text-accent">{disputeCount}</span>
  )}
</Link>
```
(Keep Marketplace + My tasks; render Disputes as a third item.)

- [ ] **Step 3: Write the list test** (`clientDisputes.test.tsx`) — MSW returns two rows (`RULED` + `RESOLVED`); assert both titles render and the `RULED` row shows an "Awaiting your decision" label first.

- [ ] **Step 4: Create `/client/disputes/page.tsx`** (mirror the admin disputes list; group RULED first):

```tsx
"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge } from "@/components/ui";
import type { DisputeMineRowDTO } from "@/lib/types";

const LABEL: Record<string, string> = {
  RULED: "Awaiting your decision",
  ARBITRATING: "Under review",
  OPEN: "Under review",
  ESCALATED: "Under admin review",
  RESOLVED: "Resolved",
};

function ClientDisputes() {
  const [rows, setRows] = useState<DisputeMineRowDTO[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<DisputeMineRowDTO[]>("/disputes/mine")
      .then((r) => { if (!cancelled) setRows(r); })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load disputes"); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <p className="eyebrow">Your disputes</p>
        <h1 className="mt-1 text-2xl font-extrabold tracking-tight">Disputes</h1>
      </div>
      {error && <Card><p role="alert" className="font-mono text-sm text-red">{error}</p></Card>}
      <Card className="overflow-x-auto p-0">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
              <th className="p-3">Task</th><th className="p-3">State</th><th className="p-3">Updated</th><th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.disputeId} className="border-b border-line/50 hover:bg-surface-2/50">
                <td className="p-3 text-fg">{r.taskTitle}</td>
                <td className="p-3"><Badge status={r.status}>{LABEL[r.status] ?? r.status}</Badge></td>
                <td className="p-3 font-mono text-xs text-dim">{new Date(r.updatedAt).toLocaleString()}</td>
                <td className="p-3 text-right">
                  <Link href={`/client/tasks/${r.taskId}`} className="font-mono text-xs font-semibold text-accent hover:underline">Open →</Link>
                </td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={4} className="p-3 font-mono text-xs text-dim">No disputes.</td></tr>}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <ClientDisputes />
      </RoleGuard>
    </AppShell>
  );
}
```
Add `GET */api/disputes/mine` to `test/msw/handlers.ts`.

- [ ] **Step 5: Run full frontend suite**

Run: `cd frontend && npx vitest run --no-file-parallelism`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add frontend/app/client/disputes/page.tsx frontend/lib/useDisputeCount.ts frontend/components/Nav.tsx frontend/test/clientDisputes.test.tsx frontend/test/msw/handlers.ts
git commit -m "feat(frontend): /client/disputes list + nav Disputes item with awaiting-decision badge"
```

---

## Task 10: Live E2E on the running Supabase stack

**Files:** none (verification).

- [ ] **Step 1:** Rebuild the backend jar and restart backend + worker (they run new code): stop `:8080`, `mvn -f backend/pom.xml -q -B -DskipTests package`, relaunch (cwd `backend/`, `ARBITRATION_CALLBACK_SECRET=e2e-arbitration-secret`), restart the worker.

- [ ] **Step 2 (appeal path):** As `client@hireai.local`, submit a `summarisation` task with an **off-topic** description; wait for `PENDING_REVIEW`; reject (B_FACTUAL). Confirm the task page now shows **"Under review by an arbitrator"**, then a **proposed ruling** with Accept / Appeal, and that the wallet still shows escrow held (no settlement). Click **Appeal**.

- [ ] **Step 3:** As `admin@hireai.local`, open `/admin/disputes` (the appealed dispute is `ESCALATED`), rule it, and confirm the client wallet settles **once**. Verify in the DB: `SELECT status FROM disputes WHERE id=…` is `RESOLVED`, exactly one settlement row for the task.

- [ ] **Step 4 (accept + discoverability):** Submit another dispute; from any page confirm the **Disputes** nav badge shows the awaiting-decision count; open `/client/disputes`, click through, **Accept** the proposed ruling; confirm single settlement.

- [ ] **Step 5 (auto-accept):** Temporarily set `RULING_ACCEPT_AFTER=PT20S` on the backend; open a dispute, leave it; confirm the sweeper auto-accepts and settles after ~20s.

- [ ] **Step 6:** No commit (verification only). If anything fails, return to the relevant task.

---

## Self-Review

- **Spec coverage:** delayed settlement (Tasks 2, 5) ✓; `appeal()` (Task 1) ✓; accept/appeal endpoints + ownership (Tasks 3, 7) ✓; auto-accept sweeper + config (Task 4) ✓; `/mine` read (Task 6) ✓; `DisputeProgressPanel` + task page (Task 8) ✓; `/client/disputes` + nav badge (Task 9) ✓; test migration called out (Tasks 2, 5) ✓; no schema migration ✓; live E2E incl. auto-accept (Task 10) ✓.
- **Types consistency:** `DisputeMineRow` (app) ↔ `DisputeMineRowDTO` (controller) ↔ `DisputeMineRowDTO` (TS) share field names `disputeId, taskId, taskTitle, status, proposedCategory, updatedAt`. `settleFromEffective` / `recordProposedRuling` used consistently across Tasks 2–4. `DisputeOutcomeDTO` gains `disputeId` in Task 7 and TS in Task 8. `getOutcomeByDispute` added in Task 7 and consumed by the accept/appeal endpoints.
- **Schema facts locked (verified against V17/V21):** `dispute_rulings(dispute_id, tier, category, rationale, decided_at)`. `DisputeDO` maps only `gmt_create` (not `gmt_modified`), so both the Task 4 stale-RULED query and the Task 6 `updated_at` derive from the **latest ruling's `decided_at`**, not `gmt_modified` — reflected in the corrected native/LATERAL SQL above.
```
