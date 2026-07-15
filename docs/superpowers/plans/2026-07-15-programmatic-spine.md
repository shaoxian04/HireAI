# Programmatic Submission Spine (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a programmatic task-submission channel — external client agents submit/track/settle tasks with an API key, over the unchanged submit/escrow/routing/settlement core, with idempotency and per-key spend caps.

**Architecture:** New adapters at the edges only. An `ApiKeyAuthenticationFilter` sets the same `SecurityContext` principal (user UUID) that the JWT filter does, so every existing controller and ownership check works unchanged (Invariant #5). A thin `SubmitOrchestrationAppService` wraps both submit endpoints with three guards — idempotency dedup, spend-cap check, attribution — before delegating to the existing submit services. Key management is JWT-only.

**Tech Stack:** Spring Boot (Java 21) COLA multi-module reactor, Spring Security, Spring Data JPA + `NamedParameterJdbcTemplate` (CQRS reads), Flyway (migration **V25**), PostgreSQL, JUnit 5 + Mockito + Testcontainers; Next.js 16 + TypeScript + Tailwind + Vitest/MSW on the frontend.

**Spec:** `docs/superpowers/specs/2026-07-14-programmatic-spine-design.md` (source of truth; read it before starting).

## Global Constraints

- **Branch:** `feat/programmatic-spine`, already cut off `feat/shortlist-selection` HEAD. Do NOT merge without explicit go-ahead and a passing `security-reviewer` pass.
- **One migration only: `V25`.** Next free Flyway slot. Hibernate `ddl-auto: validate` must stay green (every mapped column must match the schema exactly).
- **Layer purity (compiler-enforced):** the `domain` and `utility` modules carry NO Spring on the classpath. Domain services are framework-free and wired as `@Bean`s in `hireai-application/.../config/DomainServiceConfig.java`. App services are `interface + impl/` (Spring `@Service` on the impl).
- **Package roots:** domain `com.hireai.domain`, application `com.hireai.application`, repository/infra `com.hireai.infrastructure.repository`, controller `com.hireai.controller`, utility `com.hireai.utility`. Migrations + all tests live in `hireai-main`.
- **New context package:** `apikey` (e.g. `com.hireai.domain.biz.apikey`, `com.hireai.application.biz.apikey`, `com.hireai.infrastructure.repository.apikey`, `com.hireai.controller.biz.apikey`).
- **Hard invariants (never compromise):** #1 escrow-before-execution must hold *under retries* (idempotency's same-tx insert rolls back a duplicate freeze); #2 money/audit append-only (spend cap is an authorization read, never a second ledger); #3 deterministic money path (unchanged); #4 output-spec contract (unchanged); #5 server-side identity from the principal (never body/args); #6 signed HTTPS Agent I/O (untouched — outbound webhooks are Phase 4).
- **Result codes:** add `IDEMPOTENCY_CONFLICT` and `SPEND_CAP_EXCEEDED`; both map to HTTP **409**.
- **Auth semantics:** missing/invalid/revoked key → bare **401** (unauthenticated); API key on a JWT-only route → **403**; keys hashed (SHA-256 hex), only hash + 14-char prefix stored, raw key revealed exactly once; `ROLE_API_CLIENT` is submit-scoped (locked out of wallet/storefront/key-mgmt/admin).
- **Testcontainers tests** are annotated `@EnabledIf("dockerAvailable")` and skip cleanly when Docker is absent — they must not fail the build.
- **Backend test/build:** `mvn -f backend/pom.xml -B test` (whole suite lives in `hireai-main`). **Frontend gate:** in `frontend/` run `npx vitest run` **and** `npm run lint` **and** `npx tsc --noEmit` **and** `npm run build` — all clean, ≥80% coverage held.
- Commit after every task with a conventional-commit message. Attribution is disabled globally.

---

## File Structure

**New — domain (`hireai-domain`, framework-free):**
- `.../biz/apikey/model/ApiKeyModel.java` — aggregate root (issue/revoke/rehydrate).
- `.../biz/apikey/model/ApiKeyStatus.java` — `ACTIVE | REVOKED`.
- `.../biz/apikey/model/IssuedApiKey.java` — `(ApiKeyModel model, String rawKey)` carrier; raw returned once.
- `.../biz/apikey/model/SpendCaps.java` — value object; the spend-cap decision (`checkOrThrow`).
- `.../biz/apikey/service/ApiKeyIssueDomainService.java` (+ `impl/…Impl`) — mints a raw key + hashes it.
- `.../biz/apikey/repository/ApiKeyRepository.java` — persistence contract.
- `.../biz/apikey/repository/IdempotencyRepository.java` — persistence contract.
- `.../biz/apikey/repository/ApiKeyTaskRepository.java` — attribution contract.

**New — utility (`hireai-utility`, framework-free):**
- `.../hash/Sha256.java` — `hex(String)` helper (shared by key hashing + submit fingerprint).

**New — application (`hireai-application`):**
- `.../biz/apikey/ApiKeyManagementAppService.java` (+ `impl/…Impl`) — create/list/revoke (JWT-scoped).
- `.../biz/apikey/ApiKeyAuthService.java` (+ `impl/…Impl`) — `authenticate(rawKey)`.
- `.../biz/apikey/ApiKeyPrincipal.java` — `(userId, keyId, spendCap, dailySpendCap)`.
- `.../biz/task/SubmitOrchestrationAppService.java` (+ `impl/…Impl`) — idempotency + spend-cap + attribution wrapper.
- `.../biz/task/SubmitContext.java` — `(ownerId, idempotencyKey?, apiKeyId?, spendCap?, dailySpendCap?)`.
- `.../biz/task/SubmitFingerprint.java` — canonical-payload → SHA-256 hex.
- `.../port/query/SpendReadPort.java` — `committedFor` / `dailySpendFor` reads.

**New — repository/infra (`hireai-repository`):**
- `.../repository/apikey/ApiKeyDO.java`, `ApiKeyJpaRepository.java`, `ApiKeyRepositoryImpl.java`.
- `.../repository/apikey/IdempotencyRecordDO.java`, `IdempotencyJpaRepository.java`, `IdempotencyRepositoryImpl.java`.
- `.../repository/apikey/ApiKeyTaskDO.java`, `ApiKeyTaskJpaRepository.java`, `ApiKeyTaskRepositoryImpl.java`.
- `.../repository/apikey/JdbcSpendReadDao.java` — `SpendReadPort` impl.

**New — controller (`hireai-controller`):**
- `.../config/ApiKeyAuthenticationFilter.java` — reads the key header, sets the principal.
- `.../config/ApiKeyContext.java` — `(keyId, spendCap, dailySpendCap)` (auth details).
- `.../config/CurrentApiKeyProvider.java` (+ `HttpCurrentApiKeyProvider`, `NoApiKeyProvider`).
- `.../biz/apikey/ApiKeyController.java` + `dto/{CreateApiKeyRequest,ApiKeyDTO,CreatedApiKeyDTO}.java` + `ApiKey2DTOConverter.java`.

**New — migration (`hireai-main`):**
- `.../db/migration/V25__api_keys_and_idempotency.sql` — the three additive tables.

**Modified:**
- `hireai-utility/.../result/ResultCode.java` — two new codes.
- `hireai-controller/.../config/GlobalExceptionConfiguration.java` — 409 arm.
- `hireai-controller/.../config/SecurityConfig.java` — register the filter + default-deny allow-list.
- `hireai-controller/.../biz/task/TaskController.java` — build `SubmitContext`, call the orchestration service.
- `hireai-application/.../config/DomainServiceConfig.java` — register `ApiKeyIssueDomainService` bean.
- `frontend/lib/types.ts` — three new types.
- `frontend/components/Nav.tsx` — CLIENT-surface "API keys" link.
- `frontend/app/client/keys/page.tsx` (new) — key-management surface.

---

## Task 1: Result codes + 409 mapping

Adds the two new `ResultCode`s and maps them to HTTP 409. No dependencies; unblocks every later task that throws them.

**Files:**
- Modify: `backend/hireai-utility/src/main/java/com/hireai/utility/result/ResultCode.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/config/GlobalExceptionConfiguration.java:34`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/config/GlobalExceptionConfigurationTest.java`

**Interfaces:**
- Produces: `ResultCode.IDEMPOTENCY_CONFLICT`, `ResultCode.SPEND_CAP_EXCEEDED` — both map to `HttpStatus.CONFLICT` (409) when carried by a `DomainException`.

- [ ] **Step 1: Write the failing test**

Add to `GlobalExceptionConfigurationTest.java` (mirror the existing `DOMAIN_RULE_VIOLATION → 409` test in that file; if the file tests the advice via a tiny stub controller, add two cases to it — otherwise add a direct unit test of the handler):

```java
    @Test
    void idempotencyConflictMapsTo409() {
        var advice = new GlobalExceptionConfiguration();
        var resp = advice.handleDomain(
                new DomainException(ResultCode.IDEMPOTENCY_CONFLICT, "duplicate"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void spendCapExceededMapsTo409() {
        var advice = new GlobalExceptionConfiguration();
        var resp = advice.handleDomain(
                new DomainException(ResultCode.SPEND_CAP_EXCEEDED, "cap hit"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().code()).isEqualTo("SPEND_CAP_EXCEEDED");
    }
```

Add imports if missing: `com.hireai.utility.exception.DomainException`, `com.hireai.utility.result.ResultCode`, `org.springframework.http.HttpStatus`, `static org.assertj.core.api.Assertions.assertThat`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=GlobalExceptionConfigurationTest`
Expected: FAIL — compile error, `IDEMPOTENCY_CONFLICT`/`SPEND_CAP_EXCEEDED` do not exist.

- [ ] **Step 3: Add the result codes**

In `ResultCode.java`, add the two constants to the enum (after `INSUFFICIENT_BALANCE`):

```java
    INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT"),
    SPEND_CAP_EXCEEDED("SPEND_CAP_EXCEEDED"),
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED"),
```

- [ ] **Step 4: Map them to 409**

In `GlobalExceptionConfiguration.java`, extend the 409 arm (line ~34):

```java
            case INSUFFICIENT_BALANCE, DOMAIN_RULE_VIOLATION,
                 IDEMPOTENCY_CONFLICT, SPEND_CAP_EXCEEDED -> HttpStatus.CONFLICT;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=GlobalExceptionConfigurationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-utility backend/hireai-controller backend/hireai-main
git commit -m "feat(apikey): add IDEMPOTENCY_CONFLICT + SPEND_CAP_EXCEEDED result codes (409)"
```

---

## Task 2: API-key domain model, hashing, issuance, and spend-cap policy

The framework-free heart: the aggregate, the SHA-256 helper, the mint-a-key domain service, and the spend-cap decision value object. Pure unit tests, no DB, no Spring.

**Files:**
- Create: `backend/hireai-utility/src/main/java/com/hireai/utility/hash/Sha256.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/model/ApiKeyStatus.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/model/ApiKeyModel.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/model/IssuedApiKey.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/model/SpendCaps.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/service/ApiKeyIssueDomainService.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/service/impl/ApiKeyIssueDomainServiceImpl.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/apikey/model/ApiKeyModelTest.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/apikey/model/SpendCapsTest.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/apikey/service/ApiKeyIssueDomainServiceImplTest.java`
- Test: `backend/hireai-utility/src/test/java/com/hireai/utility/hash/Sha256Test.java`

**Interfaces:**
- Produces:
  - `Sha256.hex(String input) -> String` (64-char lowercase hex).
  - `ApiKeyStatus { ACTIVE, REVOKED }`.
  - `ApiKeyModel` — accessors `id():UUID`, `userId():UUID`, `keyHash():String`, `displayPrefix():String`, `name():String`, `spendCap():BigDecimal` (nullable), `dailySpendCap():BigDecimal` (nullable), `status():ApiKeyStatus`, `lastUsedAt():Instant` (nullable), `createdAt():Instant`, `revokedAt():Instant` (nullable); `isActive():boolean`; `revoke(Instant now):ApiKeyModel`; static `rehydrate(all fields)`.
  - `IssuedApiKey(ApiKeyModel model, String rawKey)` record.
  - `SpendCaps(BigDecimal spendCap, BigDecimal dailySpendCap)` — static `of(BigDecimal, BigDecimal)`; `void checkOrThrow(BigDecimal currentCommitted, BigDecimal currentDaily, BigDecimal budget)` throwing `DomainException(SPEND_CAP_EXCEEDED)`.
  - `ApiKeyIssueDomainService.issue(UUID userId, String name, BigDecimal spendCap, BigDecimal dailySpendCap, Instant now) -> IssuedApiKey`.

- [ ] **Step 1: Write the failing tests**

`Sha256Test.java`:

```java
package com.hireai.utility.hash;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {
    @Test
    void producesStable64CharLowercaseHex() {
        String h = Sha256.hex("hk_live_abc");
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(Sha256.hex("hk_live_abc")).isEqualTo(h); // deterministic
        assertThat(Sha256.hex("hk_live_abd")).isNotEqualTo(h); // sensitive
    }
}
```

`ApiKeyModelTest.java`:

```java
package com.hireai.domain.biz.apikey.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyModelTest {
    @Test
    void rehydrateExposesFieldsAndActiveReflectsStatus() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel k = ApiKeyModel.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "hash", "hk_live_a1b2c3", "ci-bot", null, null,
                ApiKeyStatus.ACTIVE, null, now, null);
        assertThat(k.isActive()).isTrue();
        assertThat(k.displayPrefix()).isEqualTo("hk_live_a1b2c3");
    }

    @Test
    void revokeTransitionsToRevokedAndStampsTime() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel k = ApiKeyModel.rehydrate(UUID.randomUUID(), UUID.randomUUID(),
                "hash", "hk_live_a1b2c3", "ci-bot", null, null,
                ApiKeyStatus.ACTIVE, null, now, null);
        ApiKeyModel revoked = k.revoke(Instant.parse("2026-07-15T11:00:00Z"));
        assertThat(revoked.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(Instant.parse("2026-07-15T11:00:00Z"));
        assertThat(revoked.isActive()).isFalse();
        assertThat(k.isActive()).isTrue(); // original unchanged (immutability)
    }
}
```

`SpendCapsTest.java`:

```java
package com.hireai.domain.biz.apikey.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpendCapsTest {
    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void uncappedNeverThrows() {
        SpendCaps.of(null, null).checkOrThrow(bd("999"), bd("999"), bd("10"));
    }

    @Test
    void concurrentUnderCapPasses() {
        SpendCaps.of(bd("100"), null).checkOrThrow(bd("80"), bd("0"), bd("20"));
    }

    @Test
    void concurrentOverCapThrows() {
        assertThatThrownBy(() -> SpendCaps.of(bd("100"), null)
                .checkOrThrow(bd("90"), bd("0"), bd("20")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.SPEND_CAP_EXCEEDED));
    }

    @Test
    void dailyOverCapThrows() {
        assertThatThrownBy(() -> SpendCaps.of(null, bd("50"))
                .checkOrThrow(bd("0"), bd("40"), bd("20")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.SPEND_CAP_EXCEEDED));
    }
}
```

`ApiKeyIssueDomainServiceImplTest.java`:

```java
package com.hireai.domain.biz.apikey.service;

import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.service.impl.ApiKeyIssueDomainServiceImpl;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyIssueDomainServiceImplTest {
    private final ApiKeyIssueDomainService svc =
            new ApiKeyIssueDomainServiceImpl(new SecureRandom());

    @Test
    void mintsPrefixedRawKeyStoredOnlyAsHashAndPrefix() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        IssuedApiKey issued = svc.issue(UUID.randomUUID(), "ci-bot", null, null, now);

        assertThat(issued.rawKey()).startsWith("hk_live_");
        assertThat(issued.rawKey().length()).isGreaterThan(20);
        // stored hash equals SHA-256 of the raw key; raw key is NOT recoverable from the model
        assertThat(issued.model().keyHash()).isEqualTo(Sha256.hex(issued.rawKey()));
        assertThat(issued.model().displayPrefix()).isEqualTo(issued.rawKey().substring(0, 14));
        assertThat(issued.model().status()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(issued.model().createdAt()).isEqualTo(now);
    }

    @Test
    void twoKeysDifferInRawAndHash() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        IssuedApiKey a = svc.issue(UUID.randomUUID(), "a", null, null, now);
        IssuedApiKey b = svc.issue(UUID.randomUUID(), "b", null, null, now);
        assertThat(a.rawKey()).isNotEqualTo(b.rawKey());
        assertThat(a.model().keyHash()).isNotEqualTo(b.model().keyHash());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=Sha256Test,ApiKeyModelTest,SpendCapsTest,ApiKeyIssueDomainServiceImplTest`
Expected: FAIL — the production classes don't exist yet (compile errors).

- [ ] **Step 3: Implement `Sha256`**

```java
package com.hireai.utility.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic SHA-256 → lowercase hex. Shared by API-key hashing and submit fingerprinting. */
public final class Sha256 {
    private Sha256() {}

    public static String hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
```

- [ ] **Step 4: Implement `ApiKeyStatus`**

```java
package com.hireai.domain.biz.apikey.model;

/** Lifecycle of an API key. */
public enum ApiKeyStatus { ACTIVE, REVOKED }
```

- [ ] **Step 5: Implement `ApiKeyModel`**

```java
package com.hireai.domain.biz.apikey.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * API-key aggregate root. Immutable; state transitions return new instances. The raw key is NEVER
 * held here — only its SHA-256 hash and a short display prefix. Issuance is performed by
 * {@code ApiKeyIssueDomainService} (which produces the raw key alongside the model, once).
 */
public final class ApiKeyModel {

    private final UUID id;
    private final UUID userId;
    private final String keyHash;
    private final String displayPrefix;
    private final String name;
    private final BigDecimal spendCap;       // nullable — max concurrent frozen escrow
    private final BigDecimal dailySpendCap;  // nullable — max committed per rolling 24h
    private final ApiKeyStatus status;
    private final Instant lastUsedAt;        // nullable
    private final Instant createdAt;
    private final Instant revokedAt;         // nullable

    private ApiKeyModel(UUID id, UUID userId, String keyHash, String displayPrefix, String name,
                        BigDecimal spendCap, BigDecimal dailySpendCap, ApiKeyStatus status,
                        Instant lastUsedAt, Instant createdAt, Instant revokedAt) {
        this.id = id;
        this.userId = userId;
        this.keyHash = keyHash;
        this.displayPrefix = displayPrefix;
        this.name = name;
        this.spendCap = spendCap;
        this.dailySpendCap = dailySpendCap;
        this.status = status;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    /** Factory for a freshly minted, ACTIVE key. Called only by the issue domain service. */
    public static ApiKeyModel issue(UUID userId, String keyHash, String displayPrefix, String name,
                                     BigDecimal spendCap, BigDecimal dailySpendCap, Instant createdAt) {
        return new ApiKeyModel(UUID.randomUUID(), userId, keyHash, displayPrefix, name,
                spendCap, dailySpendCap, ApiKeyStatus.ACTIVE, null, createdAt, null);
    }

    public static ApiKeyModel rehydrate(UUID id, UUID userId, String keyHash, String displayPrefix,
                                        String name, BigDecimal spendCap, BigDecimal dailySpendCap,
                                        ApiKeyStatus status, Instant lastUsedAt, Instant createdAt,
                                        Instant revokedAt) {
        return new ApiKeyModel(id, userId, keyHash, displayPrefix, name, spendCap, dailySpendCap,
                status, lastUsedAt, createdAt, revokedAt);
    }

    public ApiKeyModel revoke(Instant now) {
        return new ApiKeyModel(id, userId, keyHash, displayPrefix, name, spendCap, dailySpendCap,
                ApiKeyStatus.REVOKED, lastUsedAt, createdAt, now);
    }

    public boolean isActive() { return status == ApiKeyStatus.ACTIVE; }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String keyHash() { return keyHash; }
    public String displayPrefix() { return displayPrefix; }
    public String name() { return name; }
    public BigDecimal spendCap() { return spendCap; }
    public BigDecimal dailySpendCap() { return dailySpendCap; }
    public ApiKeyStatus status() { return status; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant revokedAt() { return revokedAt; }
}
```

- [ ] **Step 6: Implement `IssuedApiKey`**

```java
package com.hireai.domain.biz.apikey.model;

/** The result of issuing a key: the persistable model plus the raw key, shown to the user ONCE. */
public record IssuedApiKey(ApiKeyModel model, String rawKey) {}
```

- [ ] **Step 7: Implement `SpendCaps`**

```java
package com.hireai.domain.biz.apikey.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;

/**
 * The two independent, optional per-key spend caps and their enforcement. A {@code null} cap is
 * uncapped (skipped). {@code checkOrThrow} rejects if EITHER the concurrent frozen-escrow cap or
 * the rolling-24h daily cap would be exceeded by admitting {@code budget}.
 */
public record SpendCaps(BigDecimal spendCap, BigDecimal dailySpendCap) {

    public static SpendCaps of(BigDecimal spendCap, BigDecimal dailySpendCap) {
        return new SpendCaps(spendCap, dailySpendCap);
    }

    public void checkOrThrow(BigDecimal currentCommitted, BigDecimal currentDaily, BigDecimal budget) {
        if (spendCap != null && currentCommitted.add(budget).compareTo(spendCap) > 0) {
            throw new DomainException(ResultCode.SPEND_CAP_EXCEEDED,
                    "Concurrent spend cap exceeded: " + currentCommitted.add(budget)
                            + " would exceed the key's cap of " + spendCap);
        }
        if (dailySpendCap != null && currentDaily.add(budget).compareTo(dailySpendCap) > 0) {
            throw new DomainException(ResultCode.SPEND_CAP_EXCEEDED,
                    "Daily spend cap exceeded: " + currentDaily.add(budget)
                            + " would exceed the key's 24h cap of " + dailySpendCap);
        }
    }
}
```

- [ ] **Step 8: Implement the issue domain service (interface + impl)**

`ApiKeyIssueDomainService.java`:

```java
package com.hireai.domain.biz.apikey.service;

import com.hireai.domain.biz.apikey.model.IssuedApiKey;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Mints a new API key: generates a high-entropy raw key, hashes it, and builds the ACTIVE model. */
public interface ApiKeyIssueDomainService {
    IssuedApiKey issue(UUID userId, String name, BigDecimal spendCap, BigDecimal dailySpendCap, Instant now);
}
```

`impl/ApiKeyIssueDomainServiceImpl.java`:

```java
package com.hireai.domain.biz.apikey.service.impl;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.utility.hash.Sha256;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Raw key = {@code hk_live_} + 32 bytes of {@link SecureRandom} in URL-safe base64 (no padding).
 * Stored: SHA-256 hex of the raw key + the first 14 chars as a display prefix. The raw key is
 * returned once inside {@link IssuedApiKey} and never persisted.
 */
public class ApiKeyIssueDomainServiceImpl implements ApiKeyIssueDomainService {

    private static final String PREFIX = "hk_live_";
    private static final int PREFIX_DISPLAY_LEN = 14;
    private static final int RANDOM_BYTES = 32;

    private final SecureRandom random;

    public ApiKeyIssueDomainServiceImpl(SecureRandom random) {
        this.random = random;
    }

    @Override
    public IssuedApiKey issue(UUID userId, String name, BigDecimal spendCap,
                              BigDecimal dailySpendCap, Instant now) {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyHash = Sha256.hex(rawKey);
        String displayPrefix = rawKey.substring(0, PREFIX_DISPLAY_LEN);
        ApiKeyModel model = ApiKeyModel.issue(userId, keyHash, displayPrefix, name,
                spendCap, dailySpendCap, now);
        return new IssuedApiKey(model, rawKey);
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=Sha256Test,ApiKeyModelTest,SpendCapsTest,ApiKeyIssueDomainServiceImplTest`
Expected: PASS (all cases green).

- [ ] **Step 10: Commit**

```bash
git add backend/hireai-utility backend/hireai-domain
git commit -m "feat(apikey): domain model, SHA-256 hashing, key issuance, spend-cap policy"
```

---

## Task 3: Migration V25 + `api_keys` persistence

Creates **all three** tables in one Flyway version (V25), and wires the `api_keys` aggregate persistence. The `idempotency_keys` and `api_key_task` tables are created here too (one migration) but their DOs/repos are wired in Tasks 4 and 5 — Hibernate `validate` tolerates tables with no mapped entity.

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V25__api_keys_and_idempotency.sql`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/repository/ApiKeyRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/apikey/ApiKeyRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `ApiKeyModel`, `ApiKeyStatus` (Task 2).
- Produces: `ApiKeyRepository`:
  - `ApiKeyModel save(ApiKeyModel key)`
  - `Optional<ApiKeyModel> findActiveByHash(String keyHash)` — ACTIVE only
  - `Optional<ApiKeyModel> findById(UUID id)`
  - `List<ApiKeyModel> findByUserId(UUID userId)` — newest first
  - `void touchLastUsed(UUID id, Instant now)` — direct UPDATE (api_keys is NOT append-only)

- [ ] **Step 1: Write the migration**

`V25__api_keys_and_idempotency.sql`:

```sql
-- V25: programmatic submission spine (spec: docs/superpowers/specs/2026-07-14-programmatic-spine-design.md).
-- Three additive tables. The money tables (wallets, ledger_entries, settlements) are untouched:
-- spend caps are an authorization READ computed from tasks, never a second ledger (Invariant #2).

-- 1) API keys. Only the SHA-256 hex hash and a short display prefix are stored — never the raw key.
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id),
    key_hash        TEXT NOT NULL UNIQUE,           -- hex SHA-256 of the raw key
    display_prefix  TEXT NOT NULL,                  -- e.g. hk_live_a1b2c3
    name            TEXT,
    spend_cap       NUMERIC(18,2),                  -- NULL = uncapped (max concurrent frozen escrow)
    daily_spend_cap NUMERIC(18,2),                  -- NULL = uncapped (max committed per rolling 24h)
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    last_used_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_user ON api_keys (user_id);

-- 2) Idempotency records. UNIQUE(owner_id, idempotency_key) is the concurrency arbiter: a duplicate
-- insert in the SAME transaction as the submit rolls the whole submit back (undoes the escrow
-- freeze — no double-freeze, Invariant #1). Mirrors settlements.task_id UNIQUE (V14).
CREATE TABLE idempotency_keys (
    id                  UUID PRIMARY KEY,
    owner_id            UUID NOT NULL,
    idempotency_key     TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,   -- SHA-256 of the normalized submit payload
    task_id             UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, idempotency_key)
);

-- 3) Attribution. One row per task submitted via a key. Soft task_id reference (like
-- validation_reports) keeps the Task aggregate untouched. Powers both spend-cap reads.
CREATE TABLE api_key_task (
    task_id     UUID PRIMARY KEY,
    api_key_id  UUID NOT NULL REFERENCES api_keys (id),
    budget      NUMERIC(18,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_key_task_key ON api_key_task (api_key_id);
```

- [ ] **Step 2: Write the failing integration test**

`ApiKeyRepositoryIntegrationTest.java` (follows the `@EnabledIf("dockerAvailable")` + Testcontainers pattern used across the suite):

```java
package com.hireai.apikey;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ApiKeyRepositoryIntegrationTest {

    static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApiKeyRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        return id;
    }

    @Test
    void savedActiveKeyIsFoundByHashAndRevokedIsNot() {
        UUID user = newUser();
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel key = ApiKeyModel.issue(user, "abc-hash", "hk_live_a1b2c3", "ci-bot",
                new BigDecimal("100.00"), null, now);
        repository.save(key);

        assertThat(repository.findActiveByHash("abc-hash")).isPresent();
        assertThat(repository.findById(key.id())).isPresent();
        assertThat(repository.findByUserId(user)).hasSize(1);

        repository.save(key.revoke(now.plusSeconds(60)));
        assertThat(repository.findActiveByHash("abc-hash")).isEmpty(); // revoked → not active
        assertThat(repository.findById(key.id())).get()
                .extracting(ApiKeyModel::status).isEqualTo(ApiKeyStatus.REVOKED);
    }

    @Test
    void touchLastUsedUpdatesTimestamp() {
        UUID user = newUser();
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel key = ApiKeyModel.issue(user, "h2", "hk_live_zzz111", "bot", null, null, now);
        repository.save(key);
        repository.touchLastUsed(key.id(), now.plusSeconds(120));
        assertThat(repository.findById(key.id())).get()
                .extracting(ApiKeyModel::lastUsedAt).isEqualTo(now.plusSeconds(120));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyRepositoryIntegrationTest`
Expected: FAIL — `ApiKeyRepository`/DO don't exist (compile error). (If Docker is absent the test *skips*; write the code anyway — CI has Docker.)

- [ ] **Step 4: Implement the repository interface (domain)**

`ApiKeyRepository.java`:

```java
package com.hireai.domain.biz.apikey.repository;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the API-key aggregate. */
public interface ApiKeyRepository {

    ApiKeyModel save(ApiKeyModel key);

    /** Only an ACTIVE key by its hash — the auth lookup. Revoked keys are invisible here. */
    Optional<ApiKeyModel> findActiveByHash(String keyHash);

    Optional<ApiKeyModel> findById(UUID id);

    /** All of a user's keys, newest first (for the management list). */
    List<ApiKeyModel> findByUserId(UUID userId);

    /** Best-effort last-used bump. api_keys is NOT append-only, so a direct UPDATE is fine. */
    void touchLastUsed(UUID id, Instant now);
}
```

- [ ] **Step 5: Implement the JPA entity `ApiKeyDO`**

```java
package com.hireai.infrastructure.repository.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for an api_keys row. */
@Entity
@Table(name = "api_keys")
public class ApiKeyDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "key_hash", nullable = false) private String keyHash;
    @Column(name = "display_prefix", nullable = false) private String displayPrefix;
    @Column(name = "name") private String name;
    @Column(name = "spend_cap") private BigDecimal spendCap;
    @Column(name = "daily_spend_cap") private BigDecimal dailySpendCap;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected ApiKeyDO() {}

    public ApiKeyDO(UUID id, UUID userId, String keyHash, String displayPrefix, String name,
                    BigDecimal spendCap, BigDecimal dailySpendCap, String status,
                    Instant lastUsedAt, Instant createdAt, Instant revokedAt) {
        this.id = id;
        this.userId = userId;
        this.keyHash = keyHash;
        this.displayPrefix = displayPrefix;
        this.name = name;
        this.spendCap = spendCap;
        this.dailySpendCap = dailySpendCap;
        this.status = status;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getKeyHash() { return keyHash; }
    public String getDisplayPrefix() { return displayPrefix; }
    public String getName() { return name; }
    public BigDecimal getSpendCap() { return spendCap; }
    public BigDecimal getDailySpendCap() { return dailySpendCap; }
    public String getStatus() { return status; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
```

- [ ] **Step 6: Implement `ApiKeyJpaRepository`**

```java
package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyDO, UUID> {

    Optional<ApiKeyDO> findByKeyHashAndStatus(String keyHash, String status);

    List<ApiKeyDO> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("update ApiKeyDO k set k.lastUsedAt = :now where k.id = :id")
    void touchLastUsed(@Param("id") UUID id, @Param("now") Instant now);
}
```

- [ ] **Step 7: Implement `ApiKeyRepositoryImpl`**

```java
package com.hireai.infrastructure.repository.apikey;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepositoryImpl implements ApiKeyRepository {

    private final ApiKeyJpaRepository jpa;

    public ApiKeyRepositoryImpl(ApiKeyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ApiKeyModel save(ApiKeyModel k) {
        jpa.save(new ApiKeyDO(k.id(), k.userId(), k.keyHash(), k.displayPrefix(), k.name(),
                k.spendCap(), k.dailySpendCap(), k.status().name(), k.lastUsedAt(),
                k.createdAt(), k.revokedAt()));
        return k;
    }

    @Override
    public Optional<ApiKeyModel> findActiveByHash(String keyHash) {
        return jpa.findByKeyHashAndStatus(keyHash, ApiKeyStatus.ACTIVE.name()).map(this::toModel);
    }

    @Override
    public Optional<ApiKeyModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }

    @Override
    public List<ApiKeyModel> findByUserId(UUID userId) {
        return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toModel).toList();
    }

    @Override
    @Transactional
    public void touchLastUsed(UUID id, Instant now) {
        jpa.touchLastUsed(id, now);
    }

    private ApiKeyModel toModel(ApiKeyDO d) {
        return ApiKeyModel.rehydrate(d.getId(), d.getUserId(), d.getKeyHash(), d.getDisplayPrefix(),
                d.getName(), d.getSpendCap(), d.getDailySpendCap(),
                ApiKeyStatus.valueOf(d.getStatus()), d.getLastUsedAt(), d.getCreatedAt(),
                d.getRevokedAt());
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyRepositoryIntegrationTest`
Expected: PASS (or SKIP if Docker is unavailable locally).

- [ ] **Step 9: Verify the module compiles (ddl-validate runs in CI)**

Run: `mvn -f backend/pom.xml -B -q -pl hireai-main -am test-compile`
Expected: BUILD SUCCESS. **Docker is unavailable in this environment**, so `ApiKeyRepositoryIntegrationTest` (and every `*IntegrationTest`) SKIPs locally — that is expected, not a failure. Flyway-applies-V25 + Hibernate `ddl-auto: validate` (that `ApiKeyDO` maps cleanly against `api_keys`) is verified in **CI** (which has Docker). Statically double-check every `@Column(name=...)` against the V25 column names before moving on.

- [ ] **Step 10: Commit**

```bash
git add backend/hireai-main backend/hireai-domain backend/hireai-repository
git commit -m "feat(apikey): V25 migration (api_keys, idempotency_keys, api_key_task) + api_keys repository"
```

---

## Task 4: Idempotency persistence + submit fingerprint

The `idempotency_keys` table (created in V25, Task 3) gets its repository, plus the payload fingerprint helper. The UNIQUE-constraint concurrency guard is proven at the repository level here.

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/repository/IdempotencyRepository.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/model/IdempotencyRecord.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/SubmitFingerprint.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/IdempotencyRecordDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/IdempotencyJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/IdempotencyRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/task/SubmitFingerprintTest.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/apikey/IdempotencyRepositoryIntegrationTest.java`

**Interfaces:**
- Produces:
  - `IdempotencyRecord(UUID id, UUID ownerId, String idempotencyKey, String requestFingerprint, UUID taskId, Instant createdAt)` — plain domain record; static `create(ownerId, key, fingerprint, taskId, now)` mints a random `id`.
  - `IdempotencyRepository`: `void insert(IdempotencyRecord r)` (throws on UNIQUE violation — propagates the DB exception); `Optional<IdempotencyRecord> find(UUID ownerId, String idempotencyKey)`.
  - `SubmitFingerprint.of(String title, String description, String category, BigDecimal budget, String outputSpecJson) -> String` (SHA-256 hex of a canonical join; deterministic).

- [ ] **Step 1: Write the failing tests**

`SubmitFingerprintTest.java`:

```java
package com.hireai.application.biz.task;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class SubmitFingerprintTest {
    @Test
    void identicalPayloadsProduceSameFingerprint() {
        String a = SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{\"format\":\"JSON\"}");
        String b = SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{\"format\":\"JSON\"}");
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void anyFieldChangeChangesFingerprint() {
        String base = SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{}");
        assertThat(SubmitFingerprint.of("T2", "desc", "cat", new BigDecimal("10.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc2", "cat", new BigDecimal("10.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc", "cat2", new BigDecimal("10.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("11.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{\"x\":1}")).isNotEqualTo(base);
    }

    @Test
    void budgetScaleDoesNotFalselyDiffer() {
        // 10 and 10.00 are the same amount → same fingerprint (normalize scale).
        assertThat(SubmitFingerprint.of("T", "d", "c", new BigDecimal("10"), "{}"))
                .isEqualTo(SubmitFingerprint.of("T", "d", "c", new BigDecimal("10.00"), "{}"));
    }
}
```

`IdempotencyRepositoryIntegrationTest.java` (Testcontainers, same header as Task 3's test):

```java
package com.hireai.apikey;

import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class IdempotencyRepositoryIntegrationTest {

    static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired IdempotencyRepository repository;

    @Test
    void secondInsertWithSameOwnerAndKeyViolatesUnique() {
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        repository.insert(IdempotencyRecord.create(owner, "key-1", "fp-1", UUID.randomUUID(), now));

        assertThatThrownBy(() -> repository.insert(
                IdempotencyRecord.create(owner, "key-1", "fp-2", UUID.randomUUID(), now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.find(owner, "key-1")).isPresent();
        assertThat(repository.find(owner, "key-1")).get()
                .extracting(IdempotencyRecord::requestFingerprint).isEqualTo("fp-1");
    }

    @Test
    void sameKeyDifferentOwnerIsIndependent() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        repository.insert(IdempotencyRecord.create(UUID.randomUUID(), "shared", "fp", UUID.randomUUID(), now));
        repository.insert(IdempotencyRecord.create(UUID.randomUUID(), "shared", "fp", UUID.randomUUID(), now));
        // no exception → owner-scoped
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=SubmitFingerprintTest,IdempotencyRepositoryIntegrationTest`
Expected: FAIL (compile errors — classes missing).

- [ ] **Step 3: Implement `IdempotencyRecord` (domain)**

```java
package com.hireai.domain.biz.apikey.model;

import java.time.Instant;
import java.util.UUID;

/** One idempotency record: a submit outcome keyed by (owner, idempotency key). */
public record IdempotencyRecord(UUID id, UUID ownerId, String idempotencyKey,
                                String requestFingerprint, UUID taskId, Instant createdAt) {

    public static IdempotencyRecord create(UUID ownerId, String idempotencyKey,
                                           String requestFingerprint, UUID taskId, Instant now) {
        return new IdempotencyRecord(UUID.randomUUID(), ownerId, idempotencyKey,
                requestFingerprint, taskId, now);
    }
}
```

- [ ] **Step 4: Implement `IdempotencyRepository` (domain)**

```java
package com.hireai.domain.biz.apikey.repository;

import com.hireai.domain.biz.apikey.model.IdempotencyRecord;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for idempotency records. insert() surfaces the UNIQUE violation to the caller. */
public interface IdempotencyRepository {

    /** Throws (DataIntegrityViolationException) if (ownerId, idempotencyKey) already exists. */
    void insert(IdempotencyRecord record);

    Optional<IdempotencyRecord> find(UUID ownerId, String idempotencyKey);
}
```

- [ ] **Step 5: Implement `SubmitFingerprint` (application)**

```java
package com.hireai.application.biz.task;

import com.hireai.utility.hash.Sha256;

import java.math.BigDecimal;

/**
 * Deterministic fingerprint of a submit payload. Fields are joined with a delimiter that cannot
 * appear un-escaped in the values' surrounding structure, then SHA-256-hashed. The budget is
 * normalized (trailing zeros stripped) so 10 and 10.00 fingerprint identically. Null fields
 * (schema/criteria may be null inside outputSpecJson upstream) are rendered as the literal "∅".
 */
public final class SubmitFingerprint {
    private static final String SEP = "␞"; // RECORD SEPARATOR symbol — not expected in payloads
    private SubmitFingerprint() {}

    public static String of(String title, String description, String category,
                            BigDecimal budget, String outputSpecJson) {
        String canonical = String.join(SEP,
                nz(title), nz(description), nz(category),
                budget == null ? "∅" : budget.stripTrailingZeros().toPlainString(),
                nz(outputSpecJson));
        return Sha256.hex(canonical);
    }

    private static String nz(String s) { return s == null ? "∅" : s; }
}
```

- [ ] **Step 6: Implement `IdempotencyRecordDO` (infra)**

```java
package com.hireai.infrastructure.repository.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for an idempotency_keys row. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecordDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "owner_id", nullable = false) private UUID ownerId;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false) private String requestFingerprint;
    @Column(name = "task_id") private UUID taskId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected IdempotencyRecordDO() {}

    public IdempotencyRecordDO(UUID id, UUID ownerId, String idempotencyKey,
                               String requestFingerprint, UUID taskId, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.taskId = taskId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getTaskId() { return taskId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 7: Implement `IdempotencyJpaRepository` (infra)**

```java
package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyJpaRepository extends JpaRepository<IdempotencyRecordDO, UUID> {
    Optional<IdempotencyRecordDO> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);
}
```

- [ ] **Step 8: Implement `IdempotencyRepositoryImpl` (infra)**

```java
package com.hireai.infrastructure.repository.apikey;

import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * insert() uses saveAndFlush so the UNIQUE(owner_id, idempotency_key) violation surfaces
 * synchronously (as DataIntegrityViolationException) inside the caller's transaction — the
 * orchestration service relies on that to detect a concurrent-retry race and roll back the freeze.
 */
@Repository
public class IdempotencyRepositoryImpl implements IdempotencyRepository {

    private final IdempotencyJpaRepository jpa;

    public IdempotencyRepositoryImpl(IdempotencyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void insert(IdempotencyRecord r) {
        jpa.saveAndFlush(new IdempotencyRecordDO(r.id(), r.ownerId(), r.idempotencyKey(),
                r.requestFingerprint(), r.taskId(), r.createdAt()));
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID ownerId, String idempotencyKey) {
        return jpa.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey).map(this::toRecord);
    }

    private IdempotencyRecord toRecord(IdempotencyRecordDO d) {
        return new IdempotencyRecord(d.getId(), d.getOwnerId(), d.getIdempotencyKey(),
                d.getRequestFingerprint(), d.getTaskId(), d.getCreatedAt());
    }
}
```

> Note: `SimpleJpaRepository` import above is unused — remove it (kept out of the final file). Only `saveAndFlush` on the injected `jpa` is needed.

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=SubmitFingerprintTest,IdempotencyRepositoryIntegrationTest`
Expected: PASS (fingerprint always; idempotency repo PASS with Docker, else SKIP).

- [ ] **Step 10: Commit**

```bash
git add backend/hireai-domain backend/hireai-application backend/hireai-repository backend/hireai-main
git commit -m "feat(apikey): idempotency persistence (UNIQUE guard) + submit fingerprint"
```

---

## Task 5: Attribution persistence + spend reads (`SpendReadPort`)

The `api_key_task` table (created in V25) gets its repository, and the two CQRS spend reads that back the spend-cap check.

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/repository/ApiKeyTaskRepository.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/port/query/SpendReadPort.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyTaskDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyTaskJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyTaskRepositoryImpl.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/JdbcSpendReadDao.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/apikey/SpendReadDaoIntegrationTest.java`

**Interfaces:**
- Produces:
  - `ApiKeyTaskRepository.attribute(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant now)`.
  - `SpendReadPort`:
    - `BigDecimal committedFor(UUID apiKeyId)` — SUM of the key's tasks NOT in a terminal money-released state (concurrent frozen escrow).
    - `BigDecimal dailySpendFor(UUID apiKeyId, Instant since)` — SUM of budgets attributed to the key since `since` (rolling-24h velocity, all outcomes).

- [ ] **Step 1: Write the failing integration test**

`SpendReadDaoIntegrationTest.java`:

```java
package com.hireai.apikey;

import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class SpendReadDaoIntegrationTest {

    static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApiKeyTaskRepository attribution;
    @Autowired SpendReadPort spendRead;
    @Autowired JdbcTemplate jdbc;

    private UUID seedUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@t.local");
        return id;
    }

    private UUID seedKey(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix, status, created_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', now())", id, user, id.toString(), "hk_live_x");
        return id;
    }

    /** Seeds a task row in the given status and attributes it to the key at created-at `at`. */
    private void seedAttributedTask(UUID user, UUID keyId, String status, String budget, Instant at) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, category, status, gmt_create) " +
                "VALUES (?, ?, 'T', 'd', ?, '{}'::jsonb, 'cat', ?, now())",
                taskId, user, new BigDecimal(budget), status);
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget, created_at) VALUES (?, ?, ?, ?)",
                taskId, keyId, new BigDecimal(budget), java.sql.Timestamp.from(at));
    }

    @Test
    void committedForSumsOnlyInFlightTasks() {
        UUID user = seedUser();
        UUID key = seedKey(user);
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        seedAttributedTask(user, key, "QUEUED", "20.00", now);      // in-flight → counts
        seedAttributedTask(user, key, "PENDING_REVIEW", "15.00", now); // in-flight → counts
        seedAttributedTask(user, key, "RESOLVED", "30.00", now);    // released → excluded
        seedAttributedTask(user, key, "CANCELLED", "12.00", now);   // released → excluded

        assertThat(spendRead.committedFor(key)).isEqualByComparingTo("35.00");
    }

    @Test
    void dailySpendForCountsAllOutcomesWithinWindowOnly() {
        UUID user = seedUser();
        UUID key = seedKey(user);
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        Instant since = now.minusSeconds(24 * 3600);
        seedAttributedTask(user, key, "RESOLVED", "40.00", now.minusSeconds(3600));   // within 24h → counts
        seedAttributedTask(user, key, "CANCELLED", "10.00", now.minusSeconds(7200));  // within 24h, any outcome → counts
        seedAttributedTask(user, key, "RESOLVED", "99.00", now.minusSeconds(90000));  // >24h ago → excluded

        assertThat(spendRead.dailySpendFor(key, since)).isEqualByComparingTo("50.00");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=SpendReadDaoIntegrationTest`
Expected: FAIL (compile errors — port/repo missing).

- [ ] **Step 3: Implement `ApiKeyTaskRepository` (domain)**

```java
package com.hireai.domain.biz.apikey.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Records which API key submitted which task, with its budget (attribution + spend reads). */
public interface ApiKeyTaskRepository {
    void attribute(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant now);
}
```

- [ ] **Step 4: Implement `SpendReadPort` (application)**

```java
package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side port backing the per-key spend-cap check. Both sums are computed from tasks/attribution,
 * never from a ledger (Invariant #2). Returns ZERO (not null) when a key has no attributed tasks.
 */
public interface SpendReadPort {

    /** Concurrent frozen escrow: SUM of the key's tasks NOT in a money-released terminal state. */
    BigDecimal committedFor(UUID apiKeyId);

    /** Rolling-24h velocity: SUM of budgets attributed to the key with created_at > since. */
    BigDecimal dailySpendFor(UUID apiKeyId, Instant since);
}
```

- [ ] **Step 5: Implement `ApiKeyTaskDO` + `ApiKeyTaskJpaRepository` (infra)**

`ApiKeyTaskDO.java`:

```java
package com.hireai.infrastructure.repository.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA persistence entity for an api_key_task row (one per key-submitted task). */
@Entity
@Table(name = "api_key_task")
public class ApiKeyTaskDO {

    @Id @Column(name = "task_id") private UUID taskId;
    @Column(name = "api_key_id", nullable = false) private UUID apiKeyId;
    @Column(name = "budget", nullable = false) private BigDecimal budget;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ApiKeyTaskDO() {}

    public ApiKeyTaskDO(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant createdAt) {
        this.taskId = taskId;
        this.apiKeyId = apiKeyId;
        this.budget = budget;
        this.createdAt = createdAt;
    }

    public UUID getTaskId() { return taskId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public BigDecimal getBudget() { return budget; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`ApiKeyTaskJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApiKeyTaskJpaRepository extends JpaRepository<ApiKeyTaskDO, UUID> {}
```

- [ ] **Step 6: Implement `ApiKeyTaskRepositoryImpl` (infra)**

```java
package com.hireai.infrastructure.repository.apikey;

import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Repository
public class ApiKeyTaskRepositoryImpl implements ApiKeyTaskRepository {

    private final ApiKeyTaskJpaRepository jpa;

    public ApiKeyTaskRepositoryImpl(ApiKeyTaskJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void attribute(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant now) {
        jpa.save(new ApiKeyTaskDO(taskId, apiKeyId, budget, now));
    }
}
```

- [ ] **Step 7: Implement `JdbcSpendReadDao` (infra)**

```java
package com.hireai.infrastructure.repository.apikey;

import com.hireai.application.port.query.SpendReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * CQRS spend reads. Terminal money-released states (escrow already left) are excluded from the
 * concurrent sum; the daily sum counts every attributed submission in the window regardless of
 * outcome (velocity control). Mirrors the JdbcMatchPreviewQueryDao style.
 */
@Repository
public class JdbcSpendReadDao implements SpendReadPort {

    private static final String COMMITTED_SQL = """
            SELECT COALESCE(SUM(akt.budget), 0)
            FROM api_key_task akt
            JOIN tasks t ON t.id = akt.task_id
            WHERE akt.api_key_id = :keyId
              AND t.status NOT IN ('RESOLVED','SPEC_VIOLATION','TIMED_OUT','FAILED','CANCELLED')
            """;

    private static final String DAILY_SQL = """
            SELECT COALESCE(SUM(budget), 0)
            FROM api_key_task
            WHERE api_key_id = :keyId AND created_at > :since
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSpendReadDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal committedFor(UUID apiKeyId) {
        return jdbc.queryForObject(COMMITTED_SQL,
                new MapSqlParameterSource("keyId", apiKeyId), BigDecimal.class);
    }

    @Override
    public BigDecimal dailySpendFor(UUID apiKeyId, Instant since) {
        var params = new MapSqlParameterSource()
                .addValue("keyId", apiKeyId)
                .addValue("since", Timestamp.from(since));
        return jdbc.queryForObject(DAILY_SQL, params, BigDecimal.class);
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=SpendReadDaoIntegrationTest`
Expected: PASS (or SKIP without Docker).

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-domain backend/hireai-application backend/hireai-repository backend/hireai-main
git commit -m "feat(apikey): api_key_task attribution + SpendReadPort (concurrent + daily reads)"
```

---

## Task 6: `ApiKeyAuthService` (hash → authenticate)

The application port the auth filter calls: hash the raw key, look up an ACTIVE key, throttle-bump `last_used_at`, and return the principal. No `SecurityContext` here — the filter (Task 7) owns that.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/apikey/ApiKeyPrincipal.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/apikey/ApiKeyAuthService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/apikey/impl/ApiKeyAuthServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/apikey/impl/ApiKeyAuthServiceImplTest.java`

**Interfaces:**
- Consumes: `ApiKeyRepository` (Task 3), `Sha256` (Task 2), `ApiKeyModel`.
- Produces:
  - `ApiKeyPrincipal(UUID userId, UUID keyId, BigDecimal spendCap, BigDecimal dailySpendCap)`.
  - `ApiKeyAuthService.authenticate(String rawKey) -> Optional<ApiKeyPrincipal>` (empty on null/blank/unknown/revoked).

- [ ] **Step 1: Write the failing test**

`ApiKeyAuthServiceImplTest.java`:

```java
package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthServiceImplTest {

    private final ApiKeyRepository repo = mock(ApiKeyRepository.class);
    private final Instant fixed = Instant.parse("2026-07-15T10:00:00Z");
    private final ApiKeyAuthService svc =
            new ApiKeyAuthServiceImpl(repo, Clock.fixed(fixed, ZoneOffset.UTC));

    private ApiKeyModel activeKey(UUID user, UUID id, String rawKey, Instant lastUsed) {
        return ApiKeyModel.rehydrate(id, user, Sha256.hex(rawKey), "hk_live_a1b2c3", "bot",
                new BigDecimal("100.00"), new BigDecimal("500.00"),
                com.hireai.domain.biz.apikey.model.ApiKeyStatus.ACTIVE, lastUsed,
                fixed.minusSeconds(86400), null);
    }

    @Test
    void resolvesActiveKeyToPrincipalAndBumpsStaleLastUsed() {
        UUID user = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        String raw = "hk_live_secret";
        // last used > 1 min ago → bump
        when(repo.findActiveByHash(eq(Sha256.hex(raw))))
                .thenReturn(Optional.of(activeKey(user, keyId, raw, fixed.minusSeconds(3600))));

        Optional<ApiKeyPrincipal> p = svc.authenticate(raw);

        assertThat(p).isPresent();
        assertThat(p.get().userId()).isEqualTo(user);
        assertThat(p.get().keyId()).isEqualTo(keyId);
        assertThat(p.get().spendCap()).isEqualByComparingTo("100.00");
        assertThat(p.get().dailySpendCap()).isEqualByComparingTo("500.00");
        verify(repo).touchLastUsed(eq(keyId), eq(fixed));
    }

    @Test
    void doesNotBumpWhenRecentlyUsed() {
        UUID user = UUID.randomUUID();
        String raw = "hk_live_secret";
        when(repo.findActiveByHash(any()))
                .thenReturn(Optional.of(activeKey(user, UUID.randomUUID(), raw, fixed.minusSeconds(5))));
        svc.authenticate(raw);
        verify(repo, never()).touchLastUsed(any(), any());
    }

    @Test
    void unknownOrBlankKeyIsEmpty() {
        when(repo.findActiveByHash(any())).thenReturn(Optional.empty());
        assertThat(svc.authenticate("hk_live_nope")).isEmpty();
        assertThat(svc.authenticate("")).isEmpty();
        assertThat(svc.authenticate(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyAuthServiceImplTest`
Expected: FAIL (compile errors — service missing).

- [ ] **Step 3: Implement `ApiKeyPrincipal`**

```java
package com.hireai.application.biz.apikey;

import java.math.BigDecimal;
import java.util.UUID;

/** Resolved identity of an API-key request. Caps are nullable (uncapped). */
public record ApiKeyPrincipal(UUID userId, UUID keyId, BigDecimal spendCap, BigDecimal dailySpendCap) {}
```

- [ ] **Step 4: Implement `ApiKeyAuthService`**

```java
package com.hireai.application.biz.apikey;

import java.util.Optional;

/** Authenticates a raw API key. Returns empty for any absent/invalid/revoked key (no leak). */
public interface ApiKeyAuthService {
    Optional<ApiKeyPrincipal> authenticate(String rawKey);
}
```

- [ ] **Step 5: Implement `ApiKeyAuthServiceImpl`**

```java
package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.utility.hash.Sha256;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves a raw API key to a principal: hash → findActiveByHash → principal. Best-effort throttled
 * last_used_at bump (only when null or older than 1 minute) to avoid a write per request. Runs in
 * one short transaction (the read + optional update); called from the auth filter, which has none.
 */
@Service
@Slf4j
public class ApiKeyAuthServiceImpl implements ApiKeyAuthService {

    private static final Duration TOUCH_THROTTLE = Duration.ofMinutes(1);

    private final ApiKeyRepository repository;
    private final Clock clock;

    public ApiKeyAuthServiceImpl(ApiKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKeyModel> found = repository.findActiveByHash(Sha256.hex(rawKey));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyModel key = found.get();
        Instant now = clock.instant();
        if (key.lastUsedAt() == null || key.lastUsedAt().isBefore(now.minus(TOUCH_THROTTLE))) {
            repository.touchLastUsed(key.id(), now);
        }
        return Optional.of(new ApiKeyPrincipal(key.userId(), key.id(),
                key.spendCap(), key.dailySpendCap()));
    }
}
```

> `Clock` must be a bean. It very likely already is (used elsewhere for sweepers); if not, add `@Bean Clock clock() { return Clock.systemUTC(); }` to an existing app-layer `@Configuration` (e.g. `DomainServiceConfig` or a `ClockConfig`) — confirm before adding a duplicate.

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyAuthServiceImplTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-application backend/hireai-main
git commit -m "feat(apikey): ApiKeyAuthService (hash lookup + throttled last-used bump)"
```

---

## Task 7: API-key auth filter, `CurrentApiKeyProvider` seam, and the security allow-list

Wire API-key authentication into the secured chain and lock `ROLE_API_CLIENT` down to submit/track/settle. This is the security-critical task — it earns a focused review.

**Files:**
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/config/ApiKeyContext.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/config/ApiKeyAuthenticationFilter.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/config/CurrentApiKeyProvider.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/config/HttpCurrentApiKeyProvider.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/config/NoApiKeyProvider.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java:65-82`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/config/ApiKeyAuthenticationFilterTest.java`

**Interfaces:**
- Consumes: `ApiKeyAuthService`, `ApiKeyPrincipal` (Task 6).
- Produces:
  - `ApiKeyContext(UUID keyId, BigDecimal spendCap, BigDecimal dailySpendCap)` — placed in `authentication.getDetails()`.
  - `CurrentApiKeyProvider.current() -> Optional<ApiKeyContext>` (empty for JWT requests).
  - Authority `ROLE_API_CLIENT` on API-key requests; the allow-list restricting it to submit/track/settle.

- [ ] **Step 1: Write the failing filter unit test**

`ApiKeyAuthenticationFilterTest.java` (mirrors `JwtAuthenticationFilterTest`):

```java
package com.hireai.controller.config;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyAuthService authService = mock(ApiKeyAuthService.class);
    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(authService);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void setsPrincipalAndApiKeyContextForValidKeyViaAuthorizationHeader() throws Exception {
        UUID user = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(authService.authenticate("hk_live_secret")).thenReturn(Optional.of(
                new ApiKeyPrincipal(user, keyId, new BigDecimal("100.00"), null)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "ApiKey hk_live_secret");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(user);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_API_CLIENT");
        assertThat(auth.getDetails()).isInstanceOf(ApiKeyContext.class);
        assertThat(((ApiKeyContext) auth.getDetails()).keyId()).isEqualTo(keyId);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void readsXApiKeyHeaderToo() throws Exception {
        UUID user = UUID.randomUUID();
        when(authService.authenticate("hk_live_x")).thenReturn(Optional.of(
                new ApiKeyPrincipal(user, UUID.randomUUID(), null, null)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "hk_live_x");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(user);
    }

    @Test
    void leavesContextEmptyForInvalidKeyOrNoHeader() throws Exception {
        when(authService.authenticate(any())).thenReturn(Optional.empty());
        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Authorization", "ApiKey nope");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(bad, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doesNotOverrideAnExistingJwtAuthentication() throws Exception {
        // A Bearer JWT filter ran first and set an authentication; the ApiKey filter must not clobber it.
        UUID jwtUser = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        jwtUser, null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer some.jwt.token"); // not an ApiKey scheme
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(jwtUser);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyAuthenticationFilterTest`
Expected: FAIL (compile errors — classes missing).

- [ ] **Step 3: Implement `ApiKeyContext`**

```java
package com.hireai.controller.config;

import java.math.BigDecimal;
import java.util.UUID;

/** Auth details for an API-key request: which key, and its (nullable) spend caps. */
public record ApiKeyContext(UUID keyId, BigDecimal spendCap, BigDecimal dailySpendCap) {}
```

- [ ] **Step 4: Implement `ApiKeyAuthenticationFilter`**

```java
package com.hireai.controller.config;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads an API key from {@code Authorization: ApiKey <raw>} or {@code X-API-Key: <raw>}, resolves it
 * via {@link ApiKeyAuthService}, and on success sets a {@link UsernamePasswordAuthenticationToken}
 * whose principal is the user id (UUID) — identical to {@link JwtAuthenticationFilter}, so every
 * downstream ownership check works unchanged (Invariant #5) — with a single {@code ROLE_API_CLIENT}
 * authority and {@link ApiKeyContext} as details. Never overrides an already-set authentication (a
 * JWT filter may have run first) and never writes a response. Invalid/absent key → context stays
 * empty → the chain returns 401 on protected routes.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_SCHEME = "ApiKey ";
    private static final String X_API_KEY = "X-API-Key";
    private static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";

    private final ApiKeyAuthService authService;

    public ApiKeyAuthenticationFilter(ApiKeyAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String rawKey = extractKey(request);
            if (rawKey != null) {
                Optional<ApiKeyPrincipal> principal = authService.authenticate(rawKey);
                principal.ifPresent(p -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            p.userId(), null, List.of(new SimpleGrantedAuthority(ROLE_API_CLIENT)));
                    auth.setDetails(new ApiKeyContext(p.keyId(), p.spendCap(), p.dailySpendCap()));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(API_KEY_SCHEME)) {
            return header.substring(API_KEY_SCHEME.length()).trim();
        }
        String xApiKey = request.getHeader(X_API_KEY);
        return (xApiKey != null && !xApiKey.isBlank()) ? xApiKey.trim() : null;
    }
}
```

- [ ] **Step 5: Implement the `CurrentApiKeyProvider` seam**

`CurrentApiKeyProvider.java`:

```java
package com.hireai.controller.config;

import java.util.Optional;

/**
 * Resolves the API-key context of the current request, if any. JWT (human) requests return empty.
 * Mirrors {@link CurrentUserProvider}; the prod impl reads the SecurityContext, the test impl is empty.
 */
public interface CurrentApiKeyProvider {
    Optional<ApiKeyContext> current();
}
```

`HttpCurrentApiKeyProvider.java`:

```java
package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Production seam: the {@link ApiKeyContext} is present iff {@link ApiKeyAuthenticationFilter} set it. */
@Component
@Profile("!test")
public class HttpCurrentApiKeyProvider implements CurrentApiKeyProvider {

    @Override
    public Optional<ApiKeyContext> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof ApiKeyContext ctx) {
            return Optional.of(ctx);
        }
        return Optional.empty();
    }
}
```

`NoApiKeyProvider.java`:

```java
package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Test seam: no API-key context (controller tests mock it explicitly when they need one). */
@Component
@Profile("test")
public class NoApiKeyProvider implements CurrentApiKeyProvider {

    @Override
    public Optional<ApiKeyContext> current() {
        return Optional.empty();
    }
}
```

- [ ] **Step 6: Wire the filter + allow-list into `SecurityConfig`**

In `SecurityConfig.java`, change `securedFilterChain` to take `ApiKeyAuthService`, replace `anyRequest().authenticated()` with the allow-list, and register the API-key filter before the JWT filter. Replace the method body (lines ~62-82) with:

```java
    @Bean
    @Order(2)
    @Profile("!test")
    public SecurityFilterChain securedFilterChain(HttpSecurity http, JwtService jwtService,
                                                  ApiKeyAuthService apiKeyAuthService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers("/api/agent-callbacks/**").permitAll()
                        .requestMatchers("/api/arbitration-callbacks/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Submit / track / settle: reachable by a human CLIENT or an API_CLIENT key.
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/tasks", "/api/tasks/direct").hasAnyRole("CLIENT", "API_CLIENT")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/tasks", "/api/tasks/*", "/api/tasks/*/result",
                                "/api/tasks/*/validation").hasAnyRole("CLIENT", "API_CLIENT")
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/tasks/*/accept", "/api/tasks/*/reject").hasAnyRole("CLIENT", "API_CLIENT")
                        // Key management is JWT-only (a leaked key cannot mint keys).
                        .requestMatchers("/api/keys/**").hasRole("CLIENT")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Default-deny for API keys: everything else needs a human role. Equivalent to
                        // authenticated() for JWT users (all hold >=1 of CLIENT/BUILDER/ADMIN); it only
                        // ADDS the API_CLIENT lockout.
                        .anyRequest().hasAnyRole("CLIENT", "BUILDER", "ADMIN"))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyAuthService),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
```

Add the import `import com.hireai.application.biz.apikey.ApiKeyAuthService;` at the top of `SecurityConfig.java`.

> Filter order note: Spring inserts each `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` immediately before that position; the **last** one added ends up first. Registering the API-key filter first then the JWT filter means JWT runs first — so a Bearer JWT wins, and the API-key filter (which no-ops when an authentication already exists, Step 4) only acts on `ApiKey`/`X-API-Key` requests. Both orders are correct because the schemes are disjoint, but this keeps JWT authoritative.

- [ ] **Step 7: Run the filter test + full context boot**

Run: `mvn -f backend/pom.xml -B -q -pl hireai-main -am test-compile` then `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyAuthenticationFilterTest,JwtAuthenticationFilterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile succeeds (context beans wire); filter unit tests green. (The end-to-end allow-list behavior — API key → 403 on `/api/keys` and `/api/wallet`, and full-context boot with the new beans — is asserted in Task 10's secured-profile integration test, which runs in **CI**; Docker is unavailable locally so it SKIPs here.)

- [ ] **Step 8: Commit**

```bash
git add backend/hireai-controller backend/hireai-main
git commit -m "feat(apikey): auth filter + CurrentApiKeyProvider seam + submit-scoped allow-list"
```

---

## Task 8: `SubmitOrchestrationAppService` (idempotency + spend-cap + attribution)

The thin transactional wrapper that ties the guards together around the *unchanged* submit services. This is the correctness heart of the phase — idempotency protects Invariant #1 under retries.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/SubmitContext.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/SubmitOrchestrationAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/SubmitOrchestrationAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/task/impl/SubmitOrchestrationAppServiceImplTest.java`

**Interfaces:**
- Consumes: `TaskWriteAppService.submit` and `DirectBookingAppService.book` (existing); `IdempotencyRepository`, `ApiKeyTaskRepository` (Tasks 4/5); `SpendReadPort` (Task 5); `SpendCaps`, `SubmitFingerprint` (Tasks 2/4); `TaskSubmitInfo`, `DirectBookingInfo`.
- Produces:
  - `SubmitContext(UUID ownerId, String idempotencyKey, UUID apiKeyId, BigDecimal spendCap, BigDecimal dailySpendCap)` — nullable fields for the non-key / no-idempotency cases; helper `boolean hasIdempotencyKey()`, `boolean isApiKey()`.
  - `SubmitOrchestrationAppService`:
    - `UUID submitRouted(SubmitContext ctx, TaskSubmitInfo info)`
    - `UUID submitDirect(SubmitContext ctx, DirectBookingInfo info)`

Both return the task id, dedup by `(owner, idempotencyKey)`, enforce spend caps, and attribute the task to the key.

- [ ] **Step 1: Write the failing test**

`SubmitOrchestrationAppServiceImplTest.java` — pure Mockito, no Spring:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitOrchestrationAppServiceImplTest {

    private final TaskWriteAppService write = mock(TaskWriteAppService.class);
    private final DirectBookingAppService direct = mock(DirectBookingAppService.class);
    private final IdempotencyRepository idem = mock(IdempotencyRepository.class);
    private final ApiKeyTaskRepository attribution = mock(ApiKeyTaskRepository.class);
    private final SpendReadPort spendRead = mock(SpendReadPort.class);
    private final Instant fixed = Instant.parse("2026-07-15T10:00:00Z");

    private final SubmitOrchestrationAppService svc = new SubmitOrchestrationAppServiceImpl(
            write, direct, idem, attribution, spendRead, Clock.fixed(fixed, ZoneOffset.UTC));

    private TaskSubmitInfo info(UUID owner, String budget) {
        return new TaskSubmitInfo(owner, "T", "desc", Money.of(budget),
                new OutputSpec(OutputFormat.JSON, "{}", "ok"), "cat");
    }

    // ---- idempotency ----

    @Test
    void firstSubmitWithKeyPersistsRecordAndAttributes() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, "idem-1", keyId, null, null);
        when(idem.find(owner, "idem-1")).thenReturn(Optional.empty());
        when(write.submit(any())).thenReturn(taskId);

        UUID result = svc.submitRouted(ctx, info(owner, "20.00"));

        assertThat(result).isEqualTo(taskId);
        verify(write).submit(any());
        verify(idem).insert(any(IdempotencyRecord.class));
        verify(attribution).attribute(eq(taskId), eq(keyId), any(), eq(fixed));
    }

    @Test
    void replayWithSameKeyAndFingerprintReturnsExistingTaskWithoutResubmitting() {
        UUID owner = UUID.randomUUID();
        UUID existing = UUID.randomUUID();
        TaskSubmitInfo info = info(owner, "20.00");
        // Stub find() to return a record whose fingerprint EQUALS what the service will compute
        // (fingerprintFor mirrors SubmitOrchestrationAppServiceImpl.fingerprint exactly).
        when(idem.find(eq(owner), eq("idem-1"))).thenReturn(Optional.of(
                new IdempotencyRecord(UUID.randomUUID(), owner, "idem-1",
                        fingerprintFor(info), existing, fixed)));
        SubmitContext ctx = new SubmitContext(owner, "idem-1", null, null, null);

        UUID result = svc.submitRouted(ctx, info);

        assertThat(result).isEqualTo(existing);
        verify(write, never()).submit(any());
        verify(idem, never()).insert(any());
    }

    @Test
    void replayWithSameKeyButDifferentFingerprintIs409() {
        UUID owner = UUID.randomUUID();
        when(idem.find(eq(owner), eq("idem-1"))).thenReturn(Optional.of(
                new IdempotencyRecord(UUID.randomUUID(), owner, "idem-1",
                        "a-totally-different-fingerprint", UUID.randomUUID(), fixed)));
        SubmitContext ctx = new SubmitContext(owner, "idem-1", null, null, null);

        assertThatThrownBy(() -> svc.submitRouted(ctx, info(owner, "20.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.IDEMPOTENCY_CONFLICT));
        verify(write, never()).submit(any());
    }

    @Test
    void concurrentInsertRaceReReadsWinnerAndReturnsItsTask() {
        UUID owner = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        TaskSubmitInfo info = info(owner, "20.00");
        SubmitContext ctx = new SubmitContext(owner, "idem-1", null, null, null);
        // Pre-check finds nothing; submit runs; insert loses the UNIQUE race; re-read finds the winner.
        when(idem.find(eq(owner), eq("idem-1")))
                .thenReturn(Optional.empty())                              // pre-check
                .thenReturn(Optional.of(new IdempotencyRecord(UUID.randomUUID(), owner, "idem-1",
                        fingerprintFor(info), winner, fixed)));            // re-read after violation
        when(write.submit(any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup"))
                .when(idem).insert(any());

        UUID result = svc.submitRouted(ctx, info);

        assertThat(result).isEqualTo(winner);
    }

    // ---- spend cap ----

    @Test
    void concurrentCapExceededIs409AndNeverSubmits() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, null, keyId, new BigDecimal("100.00"), null);
        when(spendRead.committedFor(keyId)).thenReturn(new BigDecimal("90.00"));

        assertThatThrownBy(() -> svc.submitRouted(ctx, info(owner, "20.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.SPEND_CAP_EXCEEDED));
        verify(write, never()).submit(any());
    }

    @Test
    void dailyCapExceededIs409() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, null, keyId, null, new BigDecimal("50.00"));
        when(spendRead.dailySpendFor(eq(keyId), eq(fixed.minusSeconds(86400))))
                .thenReturn(new BigDecimal("40.00"));

        assertThatThrownBy(() -> svc.submitRouted(ctx, info(owner, "20.00")))
                .isInstanceOf(DomainException.class);
        verify(write, never()).submit(any());
    }

    @Test
    void uncappedKeyNeverReadsSpendAndSubmits() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, null, keyId, null, null);
        when(write.submit(any())).thenReturn(taskId);

        assertThat(svc.submitRouted(ctx, info(owner, "20.00"))).isEqualTo(taskId);
        verify(spendRead, never()).committedFor(any());
        verify(spendRead, never()).dailySpendFor(any(), any());
    }

    // ---- direct booking uses the same guards ----

    @Test
    void submitDirectAppliesGuardsAndDelegatesToBooking() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        DirectBookingInfo info = new DirectBookingInfo(owner, "T", "d", Money.of("20.00"), UUID.randomUUID());
        SubmitContext ctx = new SubmitContext(owner, null, keyId, null, null);
        when(direct.book(any())).thenReturn(taskId);

        assertThat(svc.submitDirect(ctx, info)).isEqualTo(taskId);
        verify(direct).book(info);
        verify(attribution).attribute(eq(taskId), eq(keyId), any(), eq(fixed));
    }

    // Mirrors SubmitOrchestrationAppServiceImpl.fingerprint(TaskSubmitInfo) + specJson(OutputSpec)
    // BYTE-FOR-BYTE. If you change the impl's rendering, change this too.
    private static String fingerprintFor(TaskSubmitInfo i) {
        OutputSpec s = i.outputSpec();
        String specJson = s == null ? "∅"
                : s.format() + "|" + nz(s.schema()) + "|" + nz(s.acceptanceCriteria());
        return com.hireai.application.biz.task.SubmitFingerprint.of(
                i.title(), i.description(), i.category(), i.budget().value(), specJson);
    }

    private static String nz(String s) { return s == null ? "∅" : s; }
}
```

> The test's `fingerprintFor` mirrors the impl's `fingerprint(...)` + `specJson(...)` (Step 4) byte-for-byte. Keep them identical or the replay tests are meaningless (they'd pass for the wrong reason).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=SubmitOrchestrationAppServiceImplTest`
Expected: FAIL (compile errors).

- [ ] **Step 3: Implement `SubmitContext`**

```java
package com.hireai.application.biz.task;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything the submit orchestration needs beyond the domain carrier: the owner (from the principal),
 * an optional client-supplied idempotency key, and — when the request came via an API key — the key id
 * and its (nullable) spend caps. The controller assembles this; the app layer never touches the
 * SecurityContext (Invariant #5).
 */
public record SubmitContext(UUID ownerId, String idempotencyKey, UUID apiKeyId,
                            BigDecimal spendCap, BigDecimal dailySpendCap) {

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    public boolean isApiKey() {
        return apiKeyId != null;
    }
}
```

- [ ] **Step 4: Implement `SubmitOrchestrationAppService` + impl**

`SubmitOrchestrationAppService.java`:

```java
package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;

import java.util.UUID;

/**
 * Wraps the two submit use cases with three edge guards — idempotency dedup, per-key spend cap, and
 * key→task attribution — around the UNCHANGED submit/escrow/routing core. Idempotency's same-tx insert
 * protects Invariant #1 under retries: a duplicate rolls the whole submit back, undoing the freeze.
 */
public interface SubmitOrchestrationAppService {

    UUID submitRouted(SubmitContext ctx, TaskSubmitInfo info);

    UUID submitDirect(SubmitContext ctx, DirectBookingInfo info);
}
```

`impl/SubmitOrchestrationAppServiceImpl.java`:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitFingerprint;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.model.SpendCaps;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The submit edge. Each public method runs in one transaction (REQUIRED): the delegated submit joins
 * it, so a later idempotency-insert failure rolls back the escrow freeze too (no double-freeze). The
 * concurrent-race re-read runs in a SEPARATE transaction (the outer one is doomed after the
 * constraint violation), so it can see the winner's committed row.
 */
@Service
@Slf4j
public class SubmitOrchestrationAppServiceImpl implements SubmitOrchestrationAppService {

    private static final Duration DAY = Duration.ofHours(24);

    private final TaskWriteAppService taskWriteAppService;
    private final DirectBookingAppService directBookingAppService;
    private final IdempotencyRepository idempotencyRepository;
    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final SpendReadPort spendReadPort;
    private final Clock clock;

    public SubmitOrchestrationAppServiceImpl(TaskWriteAppService taskWriteAppService,
                                             DirectBookingAppService directBookingAppService,
                                             IdempotencyRepository idempotencyRepository,
                                             ApiKeyTaskRepository apiKeyTaskRepository,
                                             SpendReadPort spendReadPort,
                                             Clock clock) {
        this.taskWriteAppService = taskWriteAppService;
        this.directBookingAppService = directBookingAppService;
        this.idempotencyRepository = idempotencyRepository;
        this.apiKeyTaskRepository = apiKeyTaskRepository;
        this.spendReadPort = spendReadPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID submitRouted(SubmitContext ctx, TaskSubmitInfo info) {
        return orchestrate(ctx, fingerprint(info), info.budget().value(),
                () -> taskWriteAppService.submit(info));
    }

    @Override
    @Transactional
    public UUID submitDirect(SubmitContext ctx, DirectBookingInfo info) {
        return orchestrate(ctx, fingerprintDirect(info), info.budget().value(),
                () -> directBookingAppService.book(info));
    }

    /**
     * Shared flow: idempotency pre-check → spend-cap check → submit (joins this tx) → attribution →
     * idempotency insert (UNIQUE guard). A concurrent-retry UNIQUE violation dooms this tx (undoing
     * the freeze); it is caught and resolved by re-reading the winner in a new tx.
     */
    private UUID orchestrate(SubmitContext ctx, String fingerprint, BigDecimal budget,
                             Supplier<UUID> submit) {
        if (ctx.hasIdempotencyKey()) {
            Optional<IdempotencyRecord> existing =
                    idempotencyRepository.find(ctx.ownerId(), ctx.idempotencyKey());
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), fingerprint);
            }
        }
        checkSpendCap(ctx, budget);

        UUID taskId = submit.get();

        if (ctx.isApiKey()) {
            apiKeyTaskRepository.attribute(taskId, ctx.apiKeyId(), budget, clock.instant());
        }
        if (ctx.hasIdempotencyKey()) {
            try {
                idempotencyRepository.insert(IdempotencyRecord.create(
                        ctx.ownerId(), ctx.idempotencyKey(), fingerprint, taskId, clock.instant()));
            } catch (DataIntegrityViolationException race) {
                // A concurrent identical retry won the UNIQUE. This tx is doomed → the freeze rolls
                // back (no double-freeze). Re-read the winner in a fresh tx and return its task.
                log.info("Idempotency race on ({}, {}); re-reading winner", ctx.ownerId(), ctx.idempotencyKey());
                throw new IdempotencyRaceException(fingerprint);
            }
        }
        return taskId;
    }

    private UUID resolveExisting(IdempotencyRecord record, String fingerprint) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new DomainException(ResultCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key reused with a different request payload");
        }
        return record.taskId();
    }

    private void checkSpendCap(SubmitContext ctx, BigDecimal budget) {
        if (!ctx.isApiKey() || (ctx.spendCap() == null && ctx.dailySpendCap() == null)) {
            return; // human request, or uncapped key → nothing to read
        }
        BigDecimal committed = ctx.spendCap() == null
                ? BigDecimal.ZERO : spendReadPort.committedFor(ctx.apiKeyId());
        BigDecimal daily = ctx.dailySpendCap() == null
                ? BigDecimal.ZERO : spendReadPort.dailySpendFor(ctx.apiKeyId(), clock.instant().minus(DAY));
        SpendCaps.of(ctx.spendCap(), ctx.dailySpendCap()).checkOrThrow(committed, daily, budget);
    }

    private String fingerprint(TaskSubmitInfo info) {
        return SubmitFingerprint.of(info.title(), info.description(), info.category(),
                info.budget().value(), specJson(info.outputSpec()));
    }

    private String fingerprintDirect(DirectBookingInfo info) {
        // Direct booking adopts the agent's spec later; fingerprint the client-supplied fields + agent id.
        return SubmitFingerprint.of(info.title(), info.description(),
                info.agentId().toString(), info.budget().value(), "direct");
    }

    /** Stable canonical rendering of the spec for the fingerprint (must match the test helper). */
    private String specJson(OutputSpec spec) {
        return spec == null ? "∅"
                : spec.format() + "|" + nz(spec.schema()) + "|" + nz(spec.acceptanceCriteria());
    }

    private static String nz(String s) { return s == null ? "∅" : s; }

    /** Signals a lost idempotency race so the boundary (Task 9's controller path) re-reads the winner. */
    public static final class IdempotencyRaceException extends RuntimeException {
        private final String fingerprint;
        public IdempotencyRaceException(String fingerprint) { this.fingerprint = fingerprint; }
        public String fingerprint() { return fingerprint; }
    }

    /**
     * New-transaction re-read of the winner after a lost race. Called by the controller path once the
     * doomed outer tx has rolled back. Returns the winning task (fingerprint match) or 409.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public UUID resolveRaceWinner(UUID ownerId, String idempotencyKey, String fingerprint) {
        return idempotencyRepository.find(ownerId, idempotencyKey)
                .map(r -> resolveExisting(r, fingerprint))
                .orElseThrow(() -> new DomainException(ResultCode.IDEMPOTENCY_CONFLICT,
                        "Idempotency race unresolved"));
    }
}
```

> **Design note on the race path.** The clean, self-contained way to handle the doomed-transaction re-read is: the `@Transactional` `submitRouted`/`submitDirect` methods throw `IdempotencyRaceException` (rolling their tx back), and a **tiny wrapper** re-reads in a new tx. Because self-invocation bypasses Spring's proxy, `resolveRaceWinner` must be called from *outside* the doomed method. Implement the wrapper as the two public entry points delegating to a private `@Transactional` core, with the catch in the public method:
>
> ```java
> @Override
> public UUID submitRouted(SubmitContext ctx, TaskSubmitInfo info) {
>     try { return self.submitRoutedTx(ctx, info); }
>     catch (IdempotencyRaceException e) {
>         return self.resolveRaceWinner(ctx.ownerId(), ctx.idempotencyKey(), e.fingerprint());
>     }
> }
> ```
>
> where `self` is the proxied bean (inject `@Lazy SubmitOrchestrationAppService self` or use `ApplicationContext`), and `submitRoutedTx`/`submitDirectTx` carry the `@Transactional` + `orchestrate(...)` body. Pick the `@Lazy self` injection (simplest, already used elsewhere in Spring codebases). Keep the public methods free of `@Transactional`. Adjust the class above accordingly during implementation; the test (Step 1) exercises the behavior, not the wiring, so it stays valid as long as `submitRouted` returns the winner on a `DataIntegrityViolationException`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=SubmitOrchestrationAppServiceImplTest`
Expected: PASS (all idempotency + spend-cap + attribution cases green).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-main
git commit -m "feat(apikey): SubmitOrchestrationAppService — idempotency + spend-cap + attribution"
```

---

## Task 9: Key management app service + `ApiKeyController` (JWT-only)

The client-facing CRUD surface: create (reveal raw once), list, revoke — all owner-scoped (Invariant #5).

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/apikey/ApiKeyManagementAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/apikey/impl/ApiKeyManagementAppServiceImpl.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/apikey/dto/CreateApiKeyRequest.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/apikey/dto/ApiKeyDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/apikey/dto/CreatedApiKeyDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/apikey/ApiKey2DTOConverter.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/apikey/ApiKeyController.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/config/DomainServiceConfig.java` — register `ApiKeyIssueDomainService` bean.
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/apikey/impl/ApiKeyManagementAppServiceImplTest.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/biz/apikey/ApiKeyControllerTest.java`

**Interfaces:**
- Consumes: `ApiKeyIssueDomainService` (Task 2), `ApiKeyRepository` (Task 3), `CurrentUserProvider` (existing).
- Produces:
  - `ApiKeyManagementAppService`:
    - `IssuedApiKey create(UUID ownerId, String name, BigDecimal spendCap, BigDecimal dailySpendCap)`
    - `List<ApiKeyModel> list(UUID ownerId)`
    - `ApiKeyModel revoke(UUID keyId, UUID ownerId)` — throws `NOT_FOUND` for a non-owned key.
  - `POST /api/keys`, `GET /api/keys`, `POST /api/keys/{id}/revoke`.

- [ ] **Step 1: Write the failing app-service test**

`ApiKeyManagementAppServiceImplTest.java`:

```java
package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.domain.biz.apikey.service.impl.ApiKeyIssueDomainServiceImpl;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyManagementAppServiceImplTest {

    private final ApiKeyRepository repo = mock(ApiKeyRepository.class);
    private final ApiKeyIssueDomainService issue = new ApiKeyIssueDomainServiceImpl(new SecureRandom());
    private final Instant fixed = Instant.parse("2026-07-15T10:00:00Z");
    private final ApiKeyManagementAppService svc =
            new ApiKeyManagementAppServiceImpl(issue, repo, Clock.fixed(fixed, ZoneOffset.UTC));

    @Test
    void createIssuesAndPersistsAndReturnsRawOnce() {
        UUID owner = UUID.randomUUID();
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        IssuedApiKey issued = svc.create(owner, "ci-bot", null, null);
        assertThat(issued.rawKey()).startsWith("hk_live_");
        assertThat(issued.model().userId()).isEqualTo(owner);
        verify(repo).save(any(ApiKeyModel.class));
    }

    @Test
    void revokeOwnedKeyTransitionsAndSaves() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        ApiKeyModel key = ApiKeyModel.issue(owner, "h", "hk_live_a1b2c3", "bot", null, null, fixed);
        when(repo.findById(keyId)).thenReturn(Optional.of(
                ApiKeyModel.rehydrate(keyId, owner, "h", "hk_live_a1b2c3", "bot", null, null,
                        com.hireai.domain.biz.apikey.model.ApiKeyStatus.ACTIVE, null, fixed, null)));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        ApiKeyModel revoked = svc.revoke(keyId, owner);
        assertThat(revoked.status()).isEqualTo(com.hireai.domain.biz.apikey.model.ApiKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(fixed);
    }

    @Test
    void revokeNonOwnedKeyThrowsNotFoundAndDoesNotSave() {
        UUID keyId = UUID.randomUUID();
        when(repo.findById(keyId)).thenReturn(Optional.of(
                ApiKeyModel.rehydrate(keyId, UUID.randomUUID(), "h", "hk_live_a1b2c3", "bot", null, null,
                        com.hireai.domain.biz.apikey.model.ApiKeyStatus.ACTIVE, null, fixed, null)));

        assertThatThrownBy(() -> svc.revoke(keyId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode()).isEqualTo(ResultCode.NOT_FOUND));
        verify(repo, never()).save(any());
    }

    @Test
    void revokeMissingKeyThrowsNotFound() {
        when(repo.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.revoke(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Write the failing controller test**

`ApiKeyControllerTest.java` (`@WebMvcTest`, mirrors `TaskControllerTest`):

```java
package com.hireai.controller.biz.apikey;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class ApiKeyControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ApiKeyManagementAppService managementAppService;
    @MockBean CurrentUserProvider currentUserProvider;

    private ApiKeyModel key(UUID id, UUID owner) {
        return ApiKeyModel.rehydrate(id, owner, "hash", "hk_live_a1b2c3", "ci-bot",
                new java.math.BigDecimal("100.00"), null, ApiKeyStatus.ACTIVE, null,
                Instant.parse("2026-07-15T10:00:00Z"), null);
    }

    @Test
    void createReturnsRawKeyOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(owner);
        when(managementAppService.create(eq(owner), eq("ci-bot"), any(), isNull()))
                .thenReturn(new IssuedApiKey(key(keyId, owner), "hk_live_RAWSECRET"));

        mockMvc.perform(post("/api/keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-bot\",\"spendCap\":\"100.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawKey").value("hk_live_RAWSECRET"))
                .andExpect(jsonPath("$.data.displayPrefix").value("hk_live_a1b2c3"));
    }

    @Test
    void listOmitsRawKey() throws Exception {
        UUID owner = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(owner);
        when(managementAppService.list(owner)).thenReturn(List.of(key(UUID.randomUUID(), owner)));

        mockMvc.perform(get("/api/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayPrefix").value("hk_live_a1b2c3"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].rawKey").doesNotExist());
    }

    @Test
    void revokeReturnsUpdatedKey() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(owner);
        ApiKeyModel revoked = key(keyId, owner).revoke(Instant.parse("2026-07-15T11:00:00Z"));
        when(managementAppService.revoke(keyId, owner)).thenReturn(revoked);

        mockMvc.perform(post("/api/keys/{id}/revoke", keyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyManagementAppServiceImplTest,ApiKeyControllerTest`
Expected: FAIL (compile errors).

- [ ] **Step 4: Implement `ApiKeyManagementAppService` + impl**

`ApiKeyManagementAppService.java`:

```java
package com.hireai.application.biz.apikey;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** JWT-scoped key management. All ops are owner-scoped (Invariant #5). */
public interface ApiKeyManagementAppService {

    IssuedApiKey create(UUID ownerId, String name, BigDecimal spendCap, BigDecimal dailySpendCap);

    List<ApiKeyModel> list(UUID ownerId);

    ApiKeyModel revoke(UUID keyId, UUID ownerId);
}
```

`impl/ApiKeyManagementAppServiceImpl.java`:

```java
package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ApiKeyManagementAppServiceImpl implements ApiKeyManagementAppService {

    private final ApiKeyIssueDomainService issueDomainService;
    private final ApiKeyRepository repository;
    private final Clock clock;

    public ApiKeyManagementAppServiceImpl(ApiKeyIssueDomainService issueDomainService,
                                          ApiKeyRepository repository, Clock clock) {
        this.issueDomainService = issueDomainService;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public IssuedApiKey create(UUID ownerId, String name, BigDecimal spendCap, BigDecimal dailySpendCap) {
        IssuedApiKey issued = issueDomainService.issue(ownerId, name, spendCap, dailySpendCap, clock.instant());
        repository.save(issued.model());
        log.info("API key {} created for user {}", issued.model().id(), ownerId);
        return issued;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyModel> list(UUID ownerId) {
        return repository.findByUserId(ownerId);
    }

    @Override
    @Transactional
    public ApiKeyModel revoke(UUID keyId, UUID ownerId) {
        ApiKeyModel key = repository.findById(keyId)
                .filter(k -> k.userId().equals(ownerId))   // owner-scoped: non-owner → NOT_FOUND (no leak)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "API key not found"));
        ApiKeyModel revoked = key.revoke(clock.instant());
        repository.save(revoked);
        log.info("API key {} revoked by user {}", keyId, ownerId);
        return revoked;
    }
}
```

- [ ] **Step 5: Implement the DTOs + converter**

`dto/CreateApiKeyRequest.java`:

```java
package com.hireai.controller.biz.apikey.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Create a key: a human label, plus two optional per-key caps. */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 100) String name,
        @DecimalMin(value = "0.01", message = "spendCap must be positive")
        @Digits(integer = 16, fraction = 2) BigDecimal spendCap,
        @DecimalMin(value = "0.01", message = "dailySpendCap must be positive")
        @Digits(integer = 16, fraction = 2) BigDecimal dailySpendCap
) {}
```

`dto/ApiKeyDTO.java`:

```java
package com.hireai.controller.biz.apikey.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A key as shown in the management list. NEVER carries the raw key. */
public record ApiKeyDTO(String id, String name, String displayPrefix, BigDecimal spendCap,
                        BigDecimal dailySpendCap, String status, Instant lastUsedAt, Instant createdAt) {}
```

`dto/CreatedApiKeyDTO.java`:

```java
package com.hireai.controller.biz.apikey.dto;

import java.math.BigDecimal;

/** The create response — the ONLY place the raw key is ever returned. */
public record CreatedApiKeyDTO(String id, String name, String displayPrefix,
                               BigDecimal spendCap, BigDecimal dailySpendCap, String rawKey) {}
```

`ApiKey2DTOConverter.java`:

```java
package com.hireai.controller.biz.apikey;

import com.hireai.controller.biz.apikey.dto.ApiKeyDTO;
import com.hireai.controller.biz.apikey.dto.CreatedApiKeyDTO;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;

/** Maps API-key aggregates to their HTTP DTOs. */
public final class ApiKey2DTOConverter {
    private ApiKey2DTOConverter() {}

    public static ApiKeyDTO toDTO(ApiKeyModel k) {
        return new ApiKeyDTO(k.id().toString(), k.name(), k.displayPrefix(), k.spendCap(),
                k.dailySpendCap(), k.status().name(), k.lastUsedAt(), k.createdAt());
    }

    public static CreatedApiKeyDTO toCreatedDTO(IssuedApiKey issued) {
        ApiKeyModel k = issued.model();
        return new CreatedApiKeyDTO(k.id().toString(), k.name(), k.displayPrefix(),
                k.spendCap(), k.dailySpendCap(), issued.rawKey());
    }
}
```

- [ ] **Step 6: Implement `ApiKeyController`**

```java
package com.hireai.controller.biz.apikey;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.apikey.dto.ApiKeyDTO;
import com.hireai.controller.biz.apikey.dto.CreateApiKeyRequest;
import com.hireai.controller.biz.apikey.dto.CreatedApiKeyDTO;
import com.hireai.controller.config.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * API-key management surface. JWT-only (the security allow-list restricts /api/keys/** to ROLE_CLIENT —
 * an API key cannot mint keys). Identity comes from {@link CurrentUserProvider}; ownership is enforced
 * in the app service (Invariant #5). The raw key is returned exactly once, from POST.
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController extends BaseController {

    private final ApiKeyManagementAppService managementAppService;
    private final CurrentUserProvider currentUser;

    public ApiKeyController(ApiKeyManagementAppService managementAppService,
                            CurrentUserProvider currentUser) {
        this.managementAppService = managementAppService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<CreatedApiKeyDTO> create(@Valid @RequestBody CreateApiKeyRequest request) {
        UUID ownerId = currentUser.currentUserId();
        return ok(ApiKey2DTOConverter.toCreatedDTO(managementAppService.create(
                ownerId, request.name(), request.spendCap(), request.dailySpendCap())));
    }

    @GetMapping
    public WebResult<List<ApiKeyDTO>> list() {
        UUID ownerId = currentUser.currentUserId();
        return ok(managementAppService.list(ownerId).stream()
                .map(ApiKey2DTOConverter::toDTO).toList());
    }

    @PostMapping("/{id}/revoke")
    public WebResult<ApiKeyDTO> revoke(@PathVariable("id") UUID id) {
        UUID ownerId = currentUser.currentUserId();
        return ok(ApiKey2DTOConverter.toDTO(managementAppService.revoke(id, ownerId)));
    }
}
```

- [ ] **Step 7: Register the issuance domain-service bean**

The management app service depends on `ApiKeyIssueDomainService` (a framework-free domain service). Register it in `DomainServiceConfig.java` (add a bean method + the two imports, mirroring the existing bean methods). It needs a `SecureRandom` — construct one inline, exactly like `routingMatchDomainService` does:

```java
    @Bean
    public ApiKeyIssueDomainService apiKeyIssueDomainService() {
        return new ApiKeyIssueDomainServiceImpl(new java.security.SecureRandom());
    }
```

Imports to add:
```java
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.domain.biz.apikey.service.impl.ApiKeyIssueDomainServiceImpl;
```

> Also confirm a `Clock` bean exists in the application context (needed by `ApiKeyAuthServiceImpl`, `ApiKeyManagementAppServiceImpl`, `SubmitOrchestrationAppServiceImpl`). If not, add one to an application `@Configuration`:
> ```java
> @Bean public java.time.Clock clock() { return java.time.Clock.systemUTC(); }
> ```

- [ ] **Step 8: Run tests to verify they pass**

Run: `mvn -f backend/pom.xml -B -q -pl hireai-main -am test-compile` then `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=ApiKeyManagementAppServiceImplTest,ApiKeyControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile succeeds (the new `apiKeyIssueDomainService` + `Clock` beans wire); both unit/WebMvcTest classes green. (Full-context boot is a CI check — Docker unavailable locally.)

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-application backend/hireai-controller backend/hireai-main
git commit -m "feat(apikey): key management app service + ApiKeyController (create/list/revoke, JWT-only)"
```

---

## Task 10: Route task-submit endpoints through the orchestration + end-to-end proof

Rewire `TaskController`'s two submit endpoints through `SubmitOrchestrationAppService`, reading the `Idempotency-Key` header and the API-key context. Then prove the whole spine under the **secured** profile (real filter + allow-list).

**Files:**
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/TaskController.java:77-100` (the two submit methods + constructor).
- Modify: `backend/hireai-main/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java` (new `@MockBean`s + Idempotency-Key case).
- Test: `backend/hireai-main/src/test/java/com/hireai/apikey/ProgrammaticSubmissionIntegrationTest.java`

**Interfaces:**
- Consumes: `SubmitOrchestrationAppService`, `SubmitContext` (Task 8); `CurrentApiKeyProvider`, `ApiKeyContext` (Task 7); `CurrentUserProvider` (existing).
- Produces: `POST /api/tasks` and `POST /api/tasks/direct` now honor `Idempotency-Key` and enforce spend caps for API-key callers; behavior for JWT callers is unchanged (no header → no dedup).

- [ ] **Step 1: Write the failing integration test (secured profile)**

`ProgrammaticSubmissionIntegrationTest.java` — mirrors `AuthIntegrationTest` (RANDOM_PORT, no `@ActiveProfiles("test")`, `RoutingAppService` mocked so no RabbitMQ):

```java
package com.hireai.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.routing.RoutingAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end programmatic spine under the DEFAULT (secured) profile: real ApiKeyAuthenticationFilter,
 * real allow-list. Logs in as the seeded client for a JWT, mints a key via /api/keys, then drives the
 * submit endpoints with the raw key. Routing is mocked (no RabbitMQ). Proves: submit-via-key freezes
 * escrow; idempotent retry returns the same task with no second freeze; a revoked key → 401; an API
 * key is 403 on /api/keys and /api/wallet; spend-cap rejection at the boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class ProgrammaticSubmissionIntegrationTest {

    static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockBean RoutingAppService routingAppService;

    private String url(String path) { return "http://localhost:" + port + path; }

    private String login() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity(url("/api/auth/login"),
                new HttpEntity<>("{\"email\":\"client@hireai.local\",\"password\":\"DemoPass123!\"}", h),
                String.class);
        return objectMapper.readTree(r.getBody()).path("data").path("token").asText();
    }

    private HttpHeaders bearer(String jwt) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt);
        return h;
    }

    private HttpHeaders apiKey(String rawKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "ApiKey " + rawKey);
        return h;
    }

    /** Mints a key (optionally capped) and returns its raw value. */
    private String createKey(String jwt, String capJsonFieldOrEmpty) throws Exception {
        String body = "{\"name\":\"ci\"" + capJsonFieldOrEmpty + "}";
        ResponseEntity<String> r = rest.postForEntity(url("/api/keys"),
                new HttpEntity<>(body, bearer(jwt)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).path("data").path("rawKey").asText();
    }

    private String submitBody() {
        return "{\"title\":\"T\",\"description\":\"desc\",\"category\":\"summarisation\",\"budget\":\"10.00\","
                + "\"outputSpec\":{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"ok\"}}";
    }

    private void topUpClient() {
        // client@hireai.local is seeded; ensure it has balance for the freeze.
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 1000 " +
                "WHERE user_id = (SELECT id FROM users WHERE email = 'client@hireai.local')");
    }

    @Test
    void submitViaKeyFreezesEscrowAndIdempotentRetryReturnsSameTaskNoSecondFreeze() throws Exception {
        String jwt = login();
        topUpClient();
        String rawKey = createKey(jwt, "");

        HttpHeaders h = apiKey(rawKey);
        h.set("Idempotency-Key", "retry-1");

        ResponseEntity<String> first = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), h), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = objectMapper.readTree(first.getBody()).path("data").path("id").asText();

        Integer escrowRowsAfterFirst = jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE id = ?::uuid", Integer.class, taskId);
        assertThat(escrowRowsAfterFirst).isEqualTo(1);

        // identical retry with the same Idempotency-Key → same task, no new task row
        ResponseEntity<String> retry = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), h), String.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        String retryTaskId = objectMapper.readTree(retry.getBody()).path("data").path("id").asText();
        assertThat(retryTaskId).isEqualTo(taskId);

        Integer totalTasks = jdbc.queryForObject(
                "SELECT count(*) FROM api_key_task", Integer.class);
        assertThat(totalTasks).isEqualTo(1); // exactly one attribution → no double submit/freeze
    }

    @Test
    void revokedKeyIsRejectedWith401() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");
        // revoke it
        String keyId = jdbc.queryForObject(
                "SELECT id FROM api_keys ORDER BY created_at DESC LIMIT 1", String.class);
        rest.postForEntity(url("/api/keys/" + keyId + "/revoke"),
                new HttpEntity<>(null, bearer(jwt)), String.class);

        ResponseEntity<String> resp = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), apiKey(rawKey)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKeyIsForbiddenOnKeyManagementAndWallet() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");

        ResponseEntity<String> keys = rest.exchange(url("/api/keys"), HttpMethod.GET,
                new HttpEntity<>(apiKey(rawKey)), String.class);
        assertThat(keys.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> wallet = rest.exchange(url("/api/wallet"), HttpMethod.GET,
                new HttpEntity<>(apiKey(rawKey)), String.class);
        assertThat(wallet.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void spendCapExceededIsRejectedAtBoundary() throws Exception {
        String jwt = login();
        topUpClient();
        String rawKey = createKey(jwt, ",\"spendCap\":\"15.00\""); // cap below 2×10.00

        ResponseEntity<String> first = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), apiKey(rawKey)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK); // 10 <= 15

        ResponseEntity<String> second = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), apiKey(rawKey)), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // 10+10 > 15
        assertThat(objectMapper.readTree(second.getBody()).path("code").asText())
                .isEqualTo("SPEND_CAP_EXCEEDED");
    }
}
```

> Verify the seed user's wallet table/column names against `V5`/the wallet schema before finalizing `topUpClient()` (`wallets.available_balance` / `user_id` — adjust to the actual column names from `docs/details/data-model.md`). If the seeded client has no wallet row, insert one instead of updating.

- [ ] **Step 2: Add the new `@MockBean`s + Idempotency-Key case to `TaskControllerTest`**

In `TaskControllerTest.java` add:

```java
    @MockBean SubmitOrchestrationAppService submitOrchestrationAppService;
    @MockBean com.hireai.controller.config.CurrentApiKeyProvider currentApiKeyProvider;
```

Add imports for `SubmitOrchestrationAppService` and, in a `@BeforeEach` (or per test), default the provider to empty:

```java
    @org.junit.jupiter.api.BeforeEach
    void noApiKeyByDefault() {
        when(currentApiKeyProvider.current()).thenReturn(java.util.Optional.empty());
    }
```

Replace the submit-happy-path stubbing so `submit` goes through the orchestration, and add an Idempotency-Key test:

```java
    @Test
    void submitWithIdempotencyKeyPassesItThroughToOrchestration() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(submitOrchestrationAppService.submitRouted(any(), any())).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(submittedTask(taskId, clientId));

        mockMvc.perform(post("/api/tasks").header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"T","description":"d","category":"summarisation","budget":"20.00",
                                 "outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(
                com.hireai.application.biz.task.SubmitContext.class);
        verify(submitOrchestrationAppService).submitRouted(ctxCaptor.capture(), any());
        assertThat(ctxCaptor.getValue().idempotencyKey()).isEqualTo("abc-123");
    }
```

Update the existing `bookDirectReturns200WithTaskDTO` / direct-booking tests to stub `submitOrchestrationAppService.submitDirect(any(), any())` instead of `directBookingAppService.book(any())` (the controller now calls the orchestration for both endpoints). Add `import static org.assertj.core.api.Assertions.assertThat;` and `org.mockito.ArgumentCaptor` if missing.

- [ ] **Step 3: Run the controller test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=TaskControllerTest`
Expected: FAIL — `TaskController` doesn't yet depend on `SubmitOrchestrationAppService`/`CurrentApiKeyProvider` (bean wiring / method signature mismatch).

- [ ] **Step 4: Rewire `TaskController`**

Add the two new constructor dependencies and rewrite the two submit methods. In `TaskController.java`:

Add fields + constructor params:
```java
    private final SubmitOrchestrationAppService submitOrchestrationAppService;
    private final CurrentApiKeyProvider currentApiKey;
```
(add both to the constructor and assign; add imports `com.hireai.application.biz.task.SubmitOrchestrationAppService`, `com.hireai.application.biz.task.SubmitContext`, `com.hireai.controller.config.CurrentApiKeyProvider`, `com.hireai.controller.config.ApiKeyContext`, `org.springframework.web.bind.annotation.RequestHeader`, `java.util.Optional`).

Replace `submit(...)`:
```java
    @PostMapping
    public WebResult<TaskDTO> submit(@Valid @RequestBody SubmitTaskRequest request,
                                     @RequestHeader(value = "Idempotency-Key", required = false)
                                     String idempotencyKey) {
        UUID clientId = currentUser.currentUserId();
        SubmitTaskRequest.OutputSpecRequest specRequest = request.outputSpec();
        TaskSubmitInfo info = new TaskSubmitInfo(
                clientId, request.title(), request.description(), Money.of(request.budget()),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()),
                request.category());
        UUID taskId = submitOrchestrationAppService.submitRouted(
                submitContext(clientId, idempotencyKey), info);
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId)));
    }
```

Replace `bookDirect(...)`:
```java
    @PostMapping("/direct")
    public WebResult<TaskDTO> bookDirect(@Valid @RequestBody DirectBookRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                         String idempotencyKey) {
        UUID clientId = currentUser.currentUserId();
        UUID taskId = submitOrchestrationAppService.submitDirect(
                submitContext(clientId, idempotencyKey),
                new DirectBookingInfo(clientId, request.title(), request.description(),
                        Money.of(request.budget()), request.agentId()));
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId)));
    }

    /** Assembles the submit context from the JWT user, the optional idempotency header, and — if the
     *  request was API-key authenticated — the key id + caps. The app layer never touches security. */
    private SubmitContext submitContext(UUID ownerId, String idempotencyKey) {
        Optional<ApiKeyContext> apiKey = currentApiKey.current();
        return new SubmitContext(ownerId, idempotencyKey,
                apiKey.map(ApiKeyContext::keyId).orElse(null),
                apiKey.map(ApiKeyContext::spendCap).orElse(null),
                apiKey.map(ApiKeyContext::dailySpendCap).orElse(null));
    }
```

`TaskController` no longer calls `directBookingAppService`/`writeAppService` for submit — but leave those fields if other methods use them (they don't after this change; remove the now-unused `writeAppService` and `directBookingAppService` fields + constructor params + imports to keep the compiler-warning-free, and update `TaskControllerTest`'s `@MockBean`s accordingly — drop `TaskWriteAppService`/`DirectBookingAppService` if nothing else references them). Verify with a compile.

- [ ] **Step 5: Run controller test + integration test**

Run: `mvn -f backend/pom.xml -B test -pl hireai-main -am -Dtest=TaskControllerTest,ProgrammaticSubmissionIntegrationTest`
Expected: PASS (integration test SKIPs without Docker; CI runs it).

- [ ] **Step 6: Run the full backend suite**

Run: `mvn -f backend/pom.xml -B test`
Expected: PASS — the whole suite green (no regressions from the `TaskController` rewiring or the security allow-list).

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-controller backend/hireai-main
git commit -m "feat(apikey): route task submit endpoints through orchestration + end-to-end spine test"
```

---

## Task 11: Frontend `/client/keys` page

The client-facing key management surface: list keys, create (reveal-once modal with copy + warning), revoke. Reuses the Mission Control kit and the `api()` client.

**Files:**
- Modify: `frontend/lib/types.ts` — three new types.
- Create: `frontend/app/client/keys/page.tsx`
- Modify: `frontend/components/Nav.tsx:41-53` — add the CLIENT-surface "API keys" link.
- Test: `frontend/app/client/keys/page.test.tsx`

**Interfaces:**
- Consumes: `api()` (`GET /api/keys`, `POST /api/keys`, `POST /api/keys/{id}/revoke`), `Modal`, `Button`, `Card`, `Input`, `Field`, `Badge`.
- Produces: `ApiKeyDTO`, `CreateApiKeyRequest`, `CreatedApiKeyDTO` in `lib/types.ts`; the `/client/keys` route.

- [ ] **Step 1: Add the types**

Append to `frontend/lib/types.ts` (after the validation-report block):

```typescript
// ── API keys (programmatic submission) ──────────────────────────────────────

export interface ApiKeyDTO {
  id: string;
  name: string;
  displayPrefix: string;
  spendCap: number | null;
  dailySpendCap: number | null;
  status: "ACTIVE" | "REVOKED";
  lastUsedAt: string | null;
  createdAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  spendCap?: number | null;
  dailySpendCap?: number | null;
}

/** Returned ONLY from POST /api/keys — the one place `rawKey` ever appears. */
export interface CreatedApiKeyDTO {
  id: string;
  name: string;
  displayPrefix: string;
  spendCap: number | null;
  dailySpendCap: number | null;
  rawKey: string;
}
```

- [ ] **Step 2: Write the failing test**

`frontend/app/client/keys/page.test.tsx` (Vitest + RTL + MSW; seed both `hireai.token` and `hireai.auth`, per the auth-test convention):

```tsx
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import Page from "./page";

const server = setupServer(
  http.get("/api/keys", () =>
    HttpResponse.json({
      success: true, code: "OK", message: null,
      data: [{ id: "k1", name: "existing", displayPrefix: "hk_live_aaa111",
               spendCap: 100, dailySpendCap: null, status: "ACTIVE",
               lastUsedAt: null, createdAt: "2026-07-15T10:00:00Z" }],
    }),
  ),
  http.post("/api/keys", () =>
    HttpResponse.json({
      success: true, code: "OK", message: null,
      data: { id: "k2", name: "new-bot", displayPrefix: "hk_live_bbb222",
              spendCap: null, dailySpendCap: null, rawKey: "hk_live_THISISRAWSECRET" },
    }),
  ),
);

beforeEach(() => {
  localStorage.setItem("hireai.token", "jwt");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u1", roles: ["CLIENT"] }));
  server.listen();
});
afterEach(() => { server.resetHandlers(); server.close(); localStorage.clear(); });

describe("client keys page", () => {
  it("lists existing keys by prefix", async () => {
    render(<Page />);
    expect(await screen.findByText("hk_live_aaa111")).toBeInTheDocument();
    expect(screen.getByText("existing")).toBeInTheDocument();
  });

  it("creates a key and reveals the raw value once in a modal", async () => {
    const user = userEvent.setup();
    render(<Page />);
    await screen.findByText("hk_live_aaa111");

    await user.click(screen.getByRole("button", { name: /create key/i }));
    await user.type(screen.getByLabelText(/name/i), "new-bot");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    // reveal-once modal shows the raw key + the warning
    expect(await screen.findByText("hk_live_THISISRAWSECRET")).toBeInTheDocument();
    expect(screen.getByText(/won't see it again/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run (in `frontend/`): `npx vitest run app/client/keys/page.test.tsx`
Expected: FAIL — `./page` does not exist.

- [ ] **Step 4: Implement the page**

`frontend/app/client/keys/page.tsx`:

```tsx
"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { ApiKeyDTO, CreateApiKeyRequest, CreatedApiKeyDTO } from "@/lib/types";
import { Badge, Button, Field, Input } from "@/components/ui";
import { Modal } from "@/components/ui/Modal";

function ClientKeys() {
  const [keys, setKeys] = useState<ApiKeyDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [spendCap, setSpendCap] = useState("");
  const [dailyCap, setDailyCap] = useState("");
  const [creating, setCreating] = useState(false);
  const [revealed, setRevealed] = useState<CreatedApiKeyDTO | null>(null);
  const [copied, setCopied] = useState(false);

  function load() {
    api<ApiKeyDTO[]>("/keys")
      .then(setKeys)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load keys"));
  }
  useEffect(load, []);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setCreating(true);
    try {
      const body: CreateApiKeyRequest = {
        name,
        spendCap: spendCap ? Number(spendCap) : null,
        dailySpendCap: dailyCap ? Number(dailyCap) : null,
      };
      const created = await api<CreatedApiKeyDTO>("/keys", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setCreateOpen(false);
      setName("");
      setSpendCap("");
      setDailyCap("");
      setRevealed(created);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Create failed");
    } finally {
      setCreating(false);
    }
  }

  async function revoke(id: string) {
    setError(null);
    try {
      await api<ApiKeyDTO>(`/keys/${id}/revoke`, { method: "POST" });
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Revoke failed");
    }
  }

  async function copyRaw() {
    if (!revealed) return;
    await navigator.clipboard.writeText(revealed.rawKey);
    setCopied(true);
  }

  return (
    <div className="space-y-10">
      <header className="flex items-end justify-between">
        <div>
          <p className="eyebrow flex items-center gap-2">
            <span className="inline-block h-px w-6 bg-accent" />
            Client console
          </p>
          <h1 className="mt-3 text-3xl font-extrabold tracking-tight">API keys</h1>
          <p className="mt-2 text-sm text-muted">
            Submit and track tasks programmatically. Keys are shown once — store them securely.
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>+ Create key</Button>
      </header>

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
          {error}
        </p>
      )}

      {keys === null ? (
        <p className="font-mono text-sm text-dim">Loading keys…</p>
      ) : keys.length === 0 ? (
        <div className="panel p-10 text-center">
          <p className="font-mono text-sm text-muted">No API keys yet.</p>
          <p className="mt-1 font-mono text-xs text-dim">Create one to submit tasks over the API.</p>
        </div>
      ) : (
        <ul className="overflow-hidden rounded-xl border border-line">
          {keys.map((k, i) => (
            <li
              key={k.id}
              className={`flex flex-wrap items-center justify-between gap-4 bg-surface px-5 py-4 ${
                i > 0 ? "border-t border-line" : ""
              }`}
            >
              <div className="min-w-0">
                <div className="flex items-center gap-3">
                  <span className="font-medium text-fg">{k.name}</span>
                  <Badge status={k.status === "ACTIVE" ? "RESOLVED" : "CANCELLED"}>{k.status}</Badge>
                </div>
                <p className="mt-1 font-mono text-xs text-dim">
                  {k.displayPrefix}… ·{" "}
                  {k.spendCap != null ? `${k.spendCap} cr concurrent` : "uncapped"} ·{" "}
                  {k.dailySpendCap != null ? `${k.dailySpendCap} cr/day` : "no daily cap"}
                </p>
              </div>
              {k.status === "ACTIVE" && (
                <Button variant="ghost" onClick={() => revoke(k.id)}>
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {/* create modal */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} ariaLabel="Create API key">
        <form onSubmit={create} className="space-y-5 p-6">
          <h2 className="text-xl font-extrabold tracking-tight">Create API key</h2>
          <Field label="Name" htmlFor="key-name">
            <Input id="key-name" value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
          <Field label="Concurrent spend cap (credits, optional)" htmlFor="key-cap">
            <Input id="key-cap" type="number" min={0} value={spendCap}
                   onChange={(e) => setSpendCap(e.target.value)} placeholder="uncapped" />
          </Field>
          <Field label="Daily spend cap (credits / 24h, optional)" htmlFor="key-daily">
            <Input id="key-daily" type="number" min={0} value={dailyCap}
                   onChange={(e) => setDailyCap(e.target.value)} placeholder="no daily cap" />
          </Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={creating || !name}>
              {creating ? "…" : "Create"}
            </Button>
          </div>
        </form>
      </Modal>

      {/* reveal-once modal */}
      <Modal open={revealed !== null} onClose={() => { setRevealed(null); setCopied(false); }}
             ariaLabel="Your new API key">
        <div className="space-y-5 p-6">
          <h2 className="text-xl font-extrabold tracking-tight">Key created</h2>
          <p className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2 font-mono text-xs text-amber">
            Copy it now — you won&apos;t see it again.
          </p>
          <div className="flex items-center gap-2">
            <code className="flex-1 truncate rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-accent">
              {revealed?.rawKey}
            </code>
            <Button type="button" onClick={copyRaw}>{copied ? "Copied ✓" : "Copy"}</Button>
          </div>
          <div className="flex justify-end">
            <Button type="button" variant="ghost" onClick={() => { setRevealed(null); setCopied(false); }}>
              Done
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <ClientKeys />
      </RoleGuard>
    </AppShell>
  );
}
```

> Confirm the `Field` component's prop shape (`label` / `htmlFor` vs `children`) against an existing usage (e.g. `app/client/tasks/new/page.tsx`) and match it exactly. If `Field` renders its own `<label htmlFor>`, keep the `Input id` in sync so `getByLabelText(/name/i)` in the test resolves.

- [ ] **Step 5: Add the nav link**

In `frontend/components/Nav.tsx`, extend the CLIENT-surface link array (lines ~42-45) to include API keys:

```tsx
                {[
                  { href: "/client", label: "Marketplace" },
                  { href: "/client/tasks", label: "My tasks" },
                  { href: "/client/keys", label: "API keys" },
                ].map((l) => (
```

- [ ] **Step 6: Run test to verify it passes**

Run (in `frontend/`): `npx vitest run app/client/keys/page.test.tsx`
Expected: PASS (list renders; create reveals the raw key once).

- [ ] **Step 7: Run the full frontend gate**

Run (in `frontend/`): `npx vitest run` then `npm run lint` then `npx tsc --noEmit` then `npm run build`
Expected: all clean — full suite green, no eslint errors, no type errors, production build succeeds. (If a `useEffect(load, [])` triggers a `react-hooks/exhaustive-deps` warning, follow the repo precedent — an `eslint-disable-next-line` with a one-line justification, matching `lib/auth.tsx`.)

- [ ] **Step 8: Commit**

```bash
git add frontend/lib/types.ts frontend/app/client/keys frontend/components/Nav.tsx
git commit -m "feat(frontend): /client/keys API-key management (create/reveal-once/revoke) + nav link"
```

---

## Task 12: Docs, security-review gate, and PR

Update the docs to reflect the built spine, run the mandatory security review, and open the PR (not merged).

**Files:**
- Modify: `CLAUDE.md` — one line in the backend + frontend build-status paragraphs.
- Modify: `docs/details/data-model.md` — the three new tables.
- Modify: `docs/details/frontend.md` — the `/client/keys` route + `api()` key header note.
- Modify: `docs/details/identity-and-authz.md` — the API-key filter, the `ROLE_API_CLIENT` allow-list, `CurrentApiKeyProvider`.
- Modify: `docs/programmatic-task-submission.md` — mark the Phase-3 spine as built.

- [ ] **Step 1: Update the docs**

- `CLAUDE.md` backend paragraph: add a sentence — *"Programmatic submission spine built (Phase 3): API-key auth filter (`ROLE_API_CLIENT`, submit-scoped allow-list), owner-scoped idempotency (`Idempotency-Key`, `V25`), two per-key spend caps (concurrent frozen escrow + rolling-24h daily), key management (`/api/keys`, JWT-only); no money-path change."*
- `docs/details/data-model.md`: document `api_keys`, `idempotency_keys`, `api_key_task` (columns, the `UNIQUE(owner_id, idempotency_key)` guard, the soft `task_id` attribution ref).
- `docs/details/frontend.md`: add `/client/keys` to the route map; note that `api()` is unchanged (the key header is only used by external agents, not the browser).
- `docs/details/identity-and-authz.md`: describe the API-key filter setting the same UUID principal, `ROLE_API_CLIENT`, the allow-list, `CurrentApiKeyProvider`, and that key management is JWT-only.
- `docs/programmatic-task-submission.md`: mark the spine (Phase 3) as built; Phase 4 (webhooks) / Phase 5 (MCP+OpenAPI) still deferred.

- [ ] **Step 2: Commit the docs**

```bash
git add CLAUDE.md docs/
git commit -m "docs: reflect the built programmatic submission spine (Phase 3)"
```

- [ ] **Step 3: Mandatory security-reviewer pass**

Run the `security-reviewer` agent over the whole branch diff (`git diff main...HEAD`). It MUST specifically check:
- Keys are never logged, returned (except the create response), or stored in plaintext; only the SHA-256 hex hash + 14-char prefix persist.
- Invalid/revoked/unknown keys yield a generic 401 with no enumeration signal (timing/response differences).
- The allow-list truly confines `ROLE_API_CLIENT` to submit/track/settle — no reachable wallet/storefront/key-mgmt/admin route.
- Idempotency's same-tx insert genuinely rolls back the escrow freeze on a duplicate (no path leaves a task without a matching freeze, or double-freezes) — Invariant #1 under retries.
- The spend-cap read cannot be bypassed and its two-concurrent-submit overshoot is the only documented, accepted gap.
- Ownership checks on `/api/keys/{id}/revoke` (non-owner → NOT_FOUND, Invariant #5).

Address every CRITICAL/HIGH finding before proceeding; re-run until clean.

- [ ] **Step 4: Full green gate**

Run:
- `mvn -f backend/pom.xml -B test` → all green.
- In `frontend/`: `npx vitest run && npm run lint && npx tsc --noEmit && npm run build` → all green.

- [ ] **Step 5: Push + open the PR (do NOT merge)**

```bash
git push -u origin feat/programmatic-spine
```
Open a PR `feat/programmatic-spine → main` with a summary covering the three tables (V25), the auth filter + allow-list, idempotency, the two spend caps, key management, and the frontend surface; a test plan; and an explicit note that a `security-reviewer` pass ran clean. **Stop and ask for an explicit merge go-ahead** — do not merge (per the standing constraint).

---

## Notes for the implementer

- **Layer purity is compiler-enforced.** If a domain-module file imports anything from `org.springframework.*`, the build fails. Keep domain services/models framework-free; wire them in `DomainServiceConfig`.
- **`ddl-auto: validate`** means every mapped `@Column` must match `V25` exactly (name, nullability). A mismatch fails context load — but there is **no context-load test that runs without Docker**, so this is caught in CI (or by careful static comparison of each `@Column(name=...)` against the V25 DDL). Do the static comparison every time you add or change an entity.
- **Docker is unavailable in this environment; every `*IntegrationTest` (Testcontainers, `dockerAvailable()`) SKIPs locally — that is expected, not a failure.** Run targeted tests with `-Dsurefire.failIfNoSpecifiedTests=false` (so a run that names only skipped ITs doesn't fail the build). Locally, verify: (1) `test-compile` succeeds, and (2) the unit + `@WebMvcTest` tests pass. The integration + ddl-validate paths are verified in CI (which has Docker) — the final review gates merge on that CI run, exactly as Phase 1/2 did.
- **Fingerprint parity.** The `SubmitOrchestrationAppServiceImpl.specJson(...)` rendering and the test's `fingerprintFor(...)` helper MUST be byte-identical, or the replay tests silently pass for the wrong reason. Keep them in lockstep.
- **The race path** (Task 8) is the subtlest piece — the `@Lazy self` proxy call is what makes the new-transaction re-read actually open a new transaction. Confirm the doomed outer tx has rolled back before the re-read runs.
