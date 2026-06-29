# Capability Re-division — Slice 2: Ledger / Wallet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Relocate the `wallet` code into a `ledger.wallet` subdomain package across every module, and add an `@Version` optimistic lock to the Wallet aggregate (closing the concurrent-freeze last-writer-wins window) — keeping the suite green.

**Architecture:** Slice 2 of the incremental-strangler refactor (spec: `docs/superpowers/specs/2026-06-29-backend-capability-redivision-design.md`). Pure relocation first (behavior-identical), then the `@Version` deepening (migration `V13` + `WalletDO` version field + a load-then-mutate save so the optimistic check works + a CI-gated integration test). Settlement classes currently in `biz.wallet` ride the rename into `ledger.wallet`; slice 3 will move them into `ledger.settlement`. Controllers stay route-grouped (`/api/wallet`, `/api/builder/earnings` unchanged).

**Tech Stack:** Java 21, Spring Boot 3.x, COLA reactor, JPA/Hibernate, Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (auto-skip without Docker).

## Global Constraints

- **Suite green at every commit:** `mvn -f backend/pom.xml -q -B test` → BUILD SUCCESS, zero failures. (Docker daemon is unavailable locally, so the 59 Testcontainers `*IntegrationTest`s auto-skip; the CI workflow runs them on push. The `@Version` behavior is validated by an integration test that runs in CI.)
- **COLA layering compiler-enforced;** `hireai-domain`/`hireai-utility` carry no Spring.
- **Routes unchanged:** `controller.biz.wallet` (WalletController, BuilderEarningsController + dtos/converter) stays where it is; only its imports change.
- **Additive migration only:** new `V13`; `V1`–`V12` immutable. No data backfill needed (DEFAULT 0).
- **Stage ONLY `backend/`** in commits — the working tree has unrelated untracked files (docs/junk, an embedded `.claude/` repo). Never `git add -A`.
- **No `Co-Authored-By`** trailer. Windows / Git Bash environment.

## File Structure

After this slice, the Ledger subdomain (wallet half) owns:

```
hireai-domain      com.hireai.domain.biz.ledger.wallet
                     model/      WalletModel, LedgerEntryModel
                     enums/      LedgerEntryType
                     repository/ WalletRepository, WalletLedgerQuery
                     service/    WalletTopUp/WalletFreeze DomainService(+impl)
                                 + (temporarily) SettlementPolicy, SettlementDomainService(+impl)  [→ ledger.settlement in slice 3]
                     info/       SettlementBreakdown  [→ ledger.settlement in slice 3]
hireai-application com.hireai.application.biz.ledger.wallet
                     WalletRead/WriteAppService(+impl), BuilderEarningsReadAppService(+impl)
hireai-repository  com.hireai.infrastructure.repository.ledger.wallet
                     WalletDO (+@Version), LedgerEntryDO, *JpaRepository, WalletRepositoryImpl
```

Unchanged locations (imports update only): `controller.biz.wallet.*`, `application.config.DomainServiceConfig`, and all `task`/`identity` code that references wallet/settlement.

New migration: `backend/hireai-main/src/main/resources/db/migration/V13__wallet_version.sql`.

---

### Task 1: Relocate `wallet` → `ledger.wallet` (mechanical, behavior-identical)

Move three package trees and rewrite their fully-qualified prefixes everywhere. One atomic compile-green unit. No behavior change — the existing suite is the test.

**Files:** moves only:
- `com.hireai.domain.biz.wallet` → `com.hireai.domain.biz.ledger.wallet`
- `com.hireai.application.biz.wallet` → `com.hireai.application.biz.ledger.wallet`
- `com.hireai.infrastructure.repository.wallet` → `com.hireai.infrastructure.repository.ledger.wallet`
- Test-only package `com.hireai.wallet` → `com.hireai.ledger`

**Interfaces produced:** the relocated FQNs above. Later tasks/slices import `com.hireai.domain.biz.ledger.wallet.*` etc.

- [ ] **Step 1: Move the directories**

Run (Git Bash, from repo root):

```bash
cd backend
mkdir -p hireai-domain/src/main/java/com/hireai/domain/biz/ledger
mkdir -p hireai-application/src/main/java/com/hireai/application/biz/ledger
mkdir -p hireai-repository/src/main/java/com/hireai/infrastructure/repository/ledger
mkdir -p hireai-main/src/test/java/com/hireai/domain/biz/ledger
git mv hireai-domain/src/main/java/com/hireai/domain/biz/wallet hireai-domain/src/main/java/com/hireai/domain/biz/ledger/wallet
git mv hireai-application/src/main/java/com/hireai/application/biz/wallet hireai-application/src/main/java/com/hireai/application/biz/ledger/wallet
git mv hireai-repository/src/main/java/com/hireai/infrastructure/repository/wallet hireai-repository/src/main/java/com/hireai/infrastructure/repository/ledger/wallet
git mv hireai-main/src/test/java/com/hireai/domain/biz/wallet hireai-main/src/test/java/com/hireai/domain/biz/ledger/wallet
git mv hireai-main/src/test/java/com/hireai/wallet hireai-main/src/test/java/com/hireai/ledger
```

(If a listed test dir does not exist, skip that one `git mv` and note it.)

- [ ] **Step 2: Rewrite the package prefixes across all Java files**

Run (Git Bash, from repo ROOT). The three module prefixes are non-overlapping and do NOT touch `controller.biz.wallet`:

```bash
grep -rl --include='*.java' -e 'com\.hireai\.domain\.biz\.wallet' -e 'com\.hireai\.application\.biz\.wallet' -e 'com\.hireai\.infrastructure\.repository\.wallet' backend \
  | xargs sed -i \
      -e 's/com\.hireai\.domain\.biz\.wallet/com.hireai.domain.biz.ledger.wallet/g' \
      -e 's/com\.hireai\.application\.biz\.wallet/com.hireai.application.biz.ledger.wallet/g' \
      -e 's/com\.hireai\.infrastructure\.repository\.wallet/com.hireai.infrastructure.repository.ledger.wallet/g'
# Test-only package com.hireai.wallet -> com.hireai.ledger (does not collide with the longer prefixes)
grep -rl --include='*.java' 'com\.hireai\.wallet' backend | xargs sed -i 's/com\.hireai\.wallet/com.hireai.ledger/g'
```

- [ ] **Step 3: Verify no stale references remain, controllers untouched**

```bash
grep -rn --include='*.java' -e 'com\.hireai\.domain\.biz\.wallet' -e 'com\.hireai\.application\.biz\.wallet' -e 'com\.hireai\.infrastructure\.repository\.wallet' -e 'com\.hireai\.wallet\b' backend
```

Expected: NO output. Then confirm controllers stayed: `grep -rln 'controller\.biz\.wallet' backend` (must still show matches; these are unchanged).

- [ ] **Step 4: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 328 tests, 0 failures, 59 skipped (same as the slice-1 baseline).

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor(ledger): relocate wallet packages into the ledger.wallet subdomain"
```

---

### Task 2: Add `@Version` optimistic lock to the Wallet aggregate

Add a JPA `@Version` to `WalletDO` (migration `V13` adds the column) and make `WalletRepositoryImpl.save` load-then-mutate the existing row so Hibernate's version check actually fires on concurrent updates. Keep the version entirely in persistence — the domain `WalletModel` stays framework-free and unchanged.

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V13__wallet_version.sql`
- Modify: `hireai-repository/.../ledger/wallet/WalletDO.java` (add `@Version`)
- Modify: `hireai-repository/.../ledger/wallet/WalletRepositoryImpl.java` (load-then-mutate save)
- Create test: `hireai-main/src/test/java/com/hireai/ledger/WalletVersionIntegrationTest.java` (Testcontainers; CI-gated)

**Interfaces:** no domain-facing API change. `WalletRepository.save(WalletModel)` keeps its signature; only its persistence behavior gains optimistic locking.

- [ ] **Step 1: Write the migration**

Create `backend/hireai-main/src/main/resources/db/migration/V13__wallet_version.sql`:

```sql
-- V13: optimistic-lock version column on wallets.
-- Closes the concurrent-freeze last-writer-wins window (spec §6.1/§6.5 hardening):
-- two simultaneous balance updates on one wallet now collide on the version check,
-- and the loser retries instead of silently overwriting. Existing rows start at 0.
ALTER TABLE wallets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Add `@Version` to `WalletDO`**

In `hireai-repository/.../ledger/wallet/WalletDO.java`, add the import `import jakarta.persistence.Version;` and the field + getter (Hibernate manages the value; no setter):

```java
    @Version
    @Column(name = "version", nullable = false)
    private long version;
```

```java
    public long getVersion() { return version; }
```

- [ ] **Step 3: Make `save` load-then-mutate so the version check fires**

Replace the `save` method in `hireai-repository/.../ledger/wallet/WalletRepositoryImpl.java` with:

```java
    @Override
    public WalletModel save(WalletModel wallet) {
        // Load-then-mutate the managed row so JPA's @Version optimistic check fires on
        // concurrent updates; insert a fresh row for a brand-new wallet.
        WalletDO entity = walletJpa.findById(wallet.id())
                .map(existing -> {
                    existing.setAvailableBalance(wallet.available().value());
                    existing.setEscrowBalance(wallet.escrow().value());
                    return existing;
                })
                .orElseGet(() -> new WalletDO(
                        wallet.id(), wallet.userId(),
                        wallet.available().value(), wallet.escrow().value()));
        walletJpa.save(entity);

        for (LedgerEntryModel entry : wallet.pendingEntries()) {
            ledgerJpa.save(new LedgerEntryDO(
                    entry.id(), wallet.id(), entry.type(),
                    entry.amount().value(), entry.balanceAfter().value(),
                    entry.relatedTaskId(), entry.correlationId(), entry.createdAt()));
        }
        wallet.clearPendingEntries();
        return wallet;
    }
```

- [ ] **Step 4: Write the integration test (CI-gated; verifies the version increments)**

Create `hireai-main/src/test/java/com/hireai/ledger/WalletVersionIntegrationTest.java`. Follow the existing `WalletLedgerIntegrationTest` for the Testcontainers/`@SpringBootTest` setup (same base class / annotations / Postgres container). The test:

```java
// Package + imports follow WalletLedgerIntegrationTest (same Testcontainers harness).
// Behavior under test: a topUp persists, and the wallet's @Version increments on update,
// proving optimistic locking is wired. Runs only with Docker (CI); auto-skips locally.

    @Test
    void walletVersionIncrementsOnUpdate() {
        UUID userId = UUID.randomUUID();
        // seed user row if the harness requires it (mirror WalletLedgerIntegrationTest setup)
        walletRepository.save(WalletModel.openFor(userId));

        WalletModel w1 = walletRepository.findByUserId(userId).orElseThrow();
        long v0 = jdbcTemplate.queryForObject(
                "SELECT version FROM wallets WHERE id = ?", Long.class, w1.id());

        w1.topUp(Money.of(new BigDecimal("10.00")), "corr-1");
        walletRepository.save(w1);

        long v1 = jdbcTemplate.queryForObject(
                "SELECT version FROM wallets WHERE id = ?", Long.class, w1.id());
        assertThat(v1).isGreaterThan(v0);
    }
```

Use the same field injections (`walletRepository`, `jdbcTemplate`) and user-seeding approach the sibling integration tests use; if the harness lacks a `jdbcTemplate`, assert via a re-load that the update persisted and add the version assertion through whatever DB-access the harness exposes. The implementer should read `WalletLedgerIntegrationTest` first and match its exact setup.

- [ ] **Step 5: Run the suite (unit green locally; integration auto-skips without Docker)**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures. The new `WalletVersionIntegrationTest` is SKIPPED locally (no Docker) — it will run in CI. Confirm `Hibernate` does not fail mapping validation in any locally-running context test (none load the DB without Docker, so this is also CI-verified).

- [ ] **Step 6: Commit**

```bash
git add backend/
git commit -m "feat(ledger): add @Version optimistic lock to the Wallet aggregate (V13)"
```

> NOTE FOR CONTROLLER: the `@Version` mapping, the `V13` migration, and the load-then-mutate `save` are exercised only by Testcontainers integration tests — i.e. validated in CI, not locally. Flag this slice's persistence change for the CI run / final review.

---

### Task 3: Slice gate + tag

- [ ] **Step 1: Full suite green**

```bash
mvn -f backend/pom.xml -B test 2>&1 | grep -E "Tests run: [0-9]+, Failures|BUILD SUCCESS|BUILD FAILURE" | tail -5
```

Expected: BUILD SUCCESS; aggregate `Tests run: N, Failures: 0, Errors: 0` (skips for Testcontainers). Test count = slice-1 baseline (328) + new integration test (1, skipped locally).

- [ ] **Step 2: Tag**

```bash
git tag redivision-2-ledger-wallet
```

---

## Self-Review

**Spec coverage (spec §4 Ledger + §6 step 2):**
- "relocate `wallet` → `ledger.wallet`" → Task 1. ✓
- "add `@Version` optimistic lock on Wallet (small migration)" → Task 2 (`V13` + `WalletDO` + load-then-mutate `save` + CI-gated test). ✓
- "Settlement promoted ... move accept/reject orchestration" → explicitly slice 3, out of scope here (settlement classes only relocate alongside wallet). ✓
- Routes stable (controllers stay) → Task 1 keeps `controller.biz.wallet`; verified by Task 1 Step 3 grep. ✓
- Additive migration `V13`, immutable history → Task 2. ✓

**Placeholder scan:** the integration test (Task 2 Step 4) intentionally defers its harness boilerplate to "match `WalletLedgerIntegrationTest`" because the exact base-class/annotation setup must mirror the sibling test verbatim — the implementer reads that file. The behavioral assertion (version increments on update) is fully specified. No other placeholders.

**Type consistency:** `WalletDO.getVersion()` and the `@Version long version` are consistent; `save`'s load-then-mutate uses the existing `WalletDO` setters (`setAvailableBalance`/`setEscrowBalance`) and constructor, both already present in the current `WalletDO`.

**Risk note:** Task 2's persistence change is not exercised by any locally-running test (no Docker). It is correct by construction (load managed entity → mutate → save lets Hibernate version-check), and CI's Testcontainers job validates it. Flagged for the final whole-branch review.
