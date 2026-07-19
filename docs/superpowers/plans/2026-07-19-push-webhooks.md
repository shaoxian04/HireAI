# Push Webhooks + Deterministic Programmatic Settlement (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the programmatic (API-key) channel deterministic auto-settlement on validation, and push signed, at-least-once webhook events (`task.completed` / `task.failed`) to a client-registered callback URL via a transactional outbox + scheduled sweeper, with SSRF/HMAC protection and a poll/list/redeliver reconciliation surface.

**Architecture:** New edge adapters over the unchanged submit/escrow/routing/validation core. An API-submitted task auto-settles immediately in the validation gate (pass → 85/15 payout → `RESOLVED`; fail → refund), reusing the existing accept-path settlement. At each terminal transition a `webhook_deliveries` row is inserted **in the same transaction** (outbox). A `@Scheduled WebhookDeliverySweeper` delivers due rows per-id (own transaction, `FOR UPDATE SKIP LOCKED`), SSRF-guarded and Stripe-style HMAC-signed. The webhook is a thin "doorbell"; the client fetches the result via the existing `GET /api/tasks/{id}/result`. Disputes stay human-channel only.

**Tech Stack:** Java 21, Spring Boot, COLA multi-module Maven reactor (`utility → domain → application → {repository, infrastructure, controller} → main`), PostgreSQL + Flyway, `RestClient` for outbound HTTP, `javax.crypto.Mac` (HmacSHA256), Testcontainers (Docker-gated). Frontend: Next.js 16 + TypeScript + Tailwind + vitest.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-19-push-webhooks-design.md` — authoritative. Read it before starting.
- **DDD layering (compiler-enforced):** `domain`/`utility` carry **no Spring**. Persistence DOs/repos live in `hireai-repository`; sweepers/clients/security impls in `hireai-infrastructure`; controllers in `hireai-controller`. App services = interface + `impl/`. Domain services framework-free, wired in `DomainServiceConfig`.
- **Immutability:** aggregate transitions return **new** instances (mirror `TaskModel.accept()`); never mutate in place.
- **Hard invariants (never compromise):** #1 escrow settles only via a recorded settlement; #2 money/audit tables append-only (webhook tables are **not** money tables — no ledger writes on the delivery path); #3 deterministic money path (no LLM/human in the API settlement); #5 identity from principal + explicit ownership check (subscriptions/deliveries owner-scoped; foreign = `NOT_FOUND`, not 403); #6 signed + HTTPS-only outbound (+ new SSRF guard).
- **Migration:** next version is **`V26`** (last shipped is `V25`). Additive only.
- **`SecurityConfig` change ⚠️:** the full app returns **401 (not 403)** for authenticated-but-forbidden (no `accessDeniedHandler`). Assert denied-status against the **full app**, expect **401**. After ANY `SecurityConfig` change **run the entire backend suite** — slice tests that load the secured chain need `@MockBean` for every filter dependency. See `docs/post-mortem/2026-07-17-api-key-lockout-401-vs-403.md`.
- **No-Docker note:** `*IntegrationTest` (Testcontainers) **skip locally** and run in CI. Unit + `@WebMvcTest` slices run locally. Verify targeted tests with `-Dsurefire.failIfNoSpecifiedTests=false`.
- **Test build target:** the whole suite lives in `hireai-main`. Build/test with `mvn -f backend/pom.xml -q -B test` (or `-pl hireai-main -am`).
- **Commits:** conventional-commit messages, one per task (attribution disabled globally).

## File Structure

**Utility (`hireai-utility`)**
- Create `com/hireai/utility/hash/HmacSha256.java` — `hexOf(secret, message)` HMAC-SHA256 → lowercase hex.

**Domain (`hireai-domain`, framework-free)**
- Create `com/hireai/domain/biz/webhook/enums/WebhookEventType.java` — `TASK_COMPLETED("task.completed")`, `TASK_FAILED("task.failed")`.
- Create `com/hireai/domain/biz/webhook/enums/WebhookDeliveryStatus.java` — `PENDING, DELIVERED, DEAD`.
- Create `com/hireai/domain/biz/webhook/WebhookSignature.java` — pure: build `t=<ts>,v1=<hmac>` over `"{ts}.{body}"`.
- Create `com/hireai/domain/biz/webhook/WebhookBackoffPolicy.java` — pure: exponential-with-cap next-attempt + exhaustion.
- Create `com/hireai/domain/biz/webhook/IpClassifier.java` — pure: `isBlocked(InetAddress)` (loopback/private/link-local/reserved/…).
- Create `com/hireai/domain/biz/webhook/WebhookPayloads.java` — pure: thin `task.completed` / `task.failed` JSON strings.
- Create `com/hireai/domain/biz/webhook/model/WebhookSubscriptionModel.java` — aggregate (create/rotateSecret/deactivate/rehydrate).
- Create `com/hireai/domain/biz/webhook/model/WebhookDeliveryModel.java` — aggregate (enqueue/markDelivered/recordFailure/requeue/rehydrate).
- Create `com/hireai/domain/biz/webhook/service/WebhookSecretGenerator.java` — domain service: `whsec_` + random secret.
- Create `com/hireai/domain/biz/webhook/repository/WebhookSubscriptionRepository.java` — interface.
- Create `com/hireai/domain/biz/webhook/repository/WebhookDeliveryRepository.java` — interface.
- Modify `com/hireai/domain/biz/apikey/repository/ApiKeyTaskRepository.java` — add `Optional<UUID> findApiKeyIdByTask(UUID taskId)`.

**Application (`hireai-application`)**
- Create `com/hireai/application/port/webhook/WebhookUrlValidatorPort.java` — `assertDeliverable(String url)`.
- Create `com/hireai/application/port/webhook/WebhookSenderPort.java` + `WebhookSendResult.java` — outbound send.
- Create `com/hireai/application/biz/webhook/WebhookOutboxAppService.java` (+ `impl/`) — enqueue completed/failed.
- Create `com/hireai/application/biz/webhook/WebhookSubscriptionAppService.java` (+ `impl/`) — register/get/rotate/deactivate.
- Create `com/hireai/application/biz/webhook/WebhookDeliveryAppService.java` (+ `impl/`) — sweepOnce/attemptDelivery/list/redeliver.
- Modify `com/hireai/application/biz/adjudication/validation/impl/ValidationAppServiceImpl.java` — auto-settle branch + enqueue.
- Modify `com/hireai/application/biz/task/callback/impl/AgentCallbackAppServiceImpl.java` — enqueueFailed(FAILED).
- Modify `com/hireai/application/biz/task/reliability/impl/TaskReliabilityAppServiceImpl.java` — enqueueFailed(TIMED_OUT).
- Modify `com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java` — enqueueFailed(CANCELLED).

**Persistence (`hireai-repository`)**
- Create `WebhookSubscriptionDO` + `WebhookSubscriptionJpaRepository` + `WebhookSubscriptionRepositoryImpl`.
- Create `WebhookDeliveryDO` + `WebhookDeliveryJpaRepository` (native claim) + `WebhookDeliveryRepositoryImpl`.
- Modify `apikey/ApiKeyTaskJpaRepository.java` + `apikey/ApiKeyTaskRepositoryImpl.java` — the `findApiKeyIdByTask` query.

**Infrastructure (`hireai-infrastructure`)**
- Create `webhook/WebhookUrlValidator.java` — SSRF impl of the port.
- Create `webhook/WebhookSender.java` — RestClient impl of the port.
- Create `messaging/WebhookDeliverySweeper.java` — `@Scheduled` driver.
- Modify `config/DomainServiceConfig.java` — register `WebhookSecretGenerator`.

**Controller (`hireai-controller`)**
- Create `webhook/WebhookSubscriptionController.java` + DTOs + converter.
- Create `webhook/WebhookDeliveryController.java` + DTOs.
- Modify `config/SecurityConfig.java` — remove accept/reject from API allow-list; add webhook endpoints.

**Main (`hireai-main`)**
- Create `resources/db/migration/V26__webhooks.sql`.
- Modify `resources/application.yml` — `hireai.webhooks.*`.

**Frontend (`frontend`)**
- Modify `lib/types.ts` — `WebhookSubscriptionDTO`, `WebhookDeliveryDTO`.
- Create `app/client/webhooks/page.tsx` + `page.test.tsx`.
- Create `components/WebhookDeliveryStatus.tsx` + test; wire into the task-detail page.
- Modify `components/Nav.tsx` — CLIENT nav link.

---

### Task 1: `HmacSha256` utility + `WebhookSignature`

**Files:**
- Create: `backend/hireai-utility/src/main/java/com/hireai/utility/hash/HmacSha256.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/WebhookSignature.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookSignatureTest.java`

**Interfaces:**
- Produces: `HmacSha256.hexOf(String secret, String message) -> String` (lowercase hex);
  `WebhookSignature.header(String secret, long tsEpochSeconds, String body) -> String` returning
  `"t=<ts>,v1=<hex>"` where the signed payload is `tsEpochSeconds + "." + body`.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookSignature;
import com.hireai.utility.hash.HmacSha256;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    // Known HMAC-SHA256("secret", "1614556800.{\"a\":1}") vector (lowercase hex).
    @Test void hmacHexIsDeterministicAndLowercase() {
        String h = HmacSha256.hexOf("secret", "hello");
        assertThat(h).isEqualTo("88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b");
        assertThat(h).matches("[0-9a-f]{64}");
    }

    @Test void headerBindsTimestampThenBody() {
        String header = WebhookSignature.header("secret", 1614556800L, "{\"a\":1}");
        String v1 = HmacSha256.hexOf("secret", "1614556800.{\"a\":1}");
        assertThat(header).isEqualTo("t=1614556800,v1=" + v1);
    }

    @Test void differentBodyChangesSignature() {
        assertThat(WebhookSignature.header("s", 1L, "{\"a\":1}"))
                .isNotEqualTo(WebhookSignature.header("s", 1L, "{\"a\":2}"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSignatureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `HmacSha256` / `WebhookSignature` do not exist (compilation error).

- [ ] **Step 3: Implement `HmacSha256`**

```java
package com.hireai.utility.hash;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Deterministic HMAC-SHA256 → lowercase hex. Shared by webhook signing (mirrors {@link Sha256}). */
public final class HmacSha256 {
    private static final String ALG = "HmacSHA256";
    private HmacSha256() {}

    public static String hexOf(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALG));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(sig.length * 2);
            for (byte b : sig) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                 .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // never on a standard JRE
        }
    }
}
```

- [ ] **Step 4: Implement `WebhookSignature`**

```java
package com.hireai.domain.biz.webhook;

import com.hireai.utility.hash.HmacSha256;

/** Stripe-style webhook signature: HMAC-SHA256 over "{ts}.{body}", header "t=<ts>,v1=<hex>". */
public final class WebhookSignature {
    private WebhookSignature() {}

    public static String header(String secret, long tsEpochSeconds, String body) {
        String v1 = HmacSha256.hexOf(secret, tsEpochSeconds + "." + body);
        return "t=" + tsEpochSeconds + ",v1=" + v1;
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSignatureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. (If the vector in Step 1 differs from your JRE output, replace it with the actual computed value — it must be a fixed 64-char lowercase hex string.)

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-utility backend/hireai-domain backend/hireai-main
git commit -m "feat(webhook): HmacSha256 util + Stripe-style WebhookSignature"
```

---

### Task 2: `WebhookBackoffPolicy`

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/WebhookBackoffPolicy.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookBackoffPolicyTest.java`

**Interfaces:**
- Produces: `new WebhookBackoffPolicy(long baseSeconds, long capSeconds, int maxAttempts)`;
  `boolean exhausted(int attempts)`; `Instant nextAttempt(int attempts, Instant now)` (exponential
  `base * 2^(attempts-1)` capped at `capSeconds`; `attempts` is the number of failures so far, 1-based).

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookBackoffPolicyTest {
    private final WebhookBackoffPolicy p = new WebhookBackoffPolicy(10, 3600, 28);
    private final Instant now = Instant.parse("2026-07-19T00:00:00Z");

    @Test void firstFailureWaitsBase() {
        assertThat(p.nextAttempt(1, now)).isEqualTo(now.plusSeconds(10));
    }
    @Test void backoffIsExponential() {
        assertThat(p.nextAttempt(2, now)).isEqualTo(now.plusSeconds(20));
        assertThat(p.nextAttempt(3, now)).isEqualTo(now.plusSeconds(40));
    }
    @Test void backoffIsCappedAndDoesNotOverflow() {
        assertThat(p.nextAttempt(9, now)).isEqualTo(now.plusSeconds(2560));
        assertThat(p.nextAttempt(10, now)).isEqualTo(now.plusSeconds(3600)); // capped
        assertThat(p.nextAttempt(100, now)).isEqualTo(now.plusSeconds(3600)); // no shift overflow
    }
    @Test void exhaustedAtMaxAttempts() {
        assertThat(p.exhausted(27)).isFalse();
        assertThat(p.exhausted(28)).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookBackoffPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement**

```java
package com.hireai.domain.biz.webhook;

import java.time.Instant;

/** Exponential-with-cap retry schedule for webhook deliveries. Pure; config supplies the bounds. */
public final class WebhookBackoffPolicy {
    private final long baseSeconds;
    private final long capSeconds;
    private final int maxAttempts;

    public WebhookBackoffPolicy(long baseSeconds, long capSeconds, int maxAttempts) {
        this.baseSeconds = baseSeconds;
        this.capSeconds = capSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** True once {@code attempts} failures have occurred and no more retries remain. */
    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Delay after the {@code attempts}-th failure (1-based), capped; overflow-safe. */
    public Instant nextAttempt(int attempts, Instant now) {
        int shift = Math.max(0, attempts - 1);
        long delay = (shift >= 20) ? capSeconds : Math.min(capSeconds, baseSeconds << shift);
        return now.plusSeconds(delay);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookBackoffPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain backend/hireai-main
git commit -m "feat(webhook): exponential-with-cap WebhookBackoffPolicy"
```

---

### Task 3: `IpClassifier` (SSRF rules)

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/IpClassifier.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/IpClassifierTest.java`

**Interfaces:**
- Produces: `IpClassifier.isBlocked(InetAddress ip) -> boolean` — true for loopback, any-local,
  link-local, site-local (private), multicast, and the AWS metadata IP `169.254.169.254`.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.domain.biz.webhook.IpClassifier;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.assertThat;

class IpClassifierTest {
    private InetAddress ip(String s) throws Exception { return InetAddress.getByName(s); } // literal → no DNS

    @Test void blocksLoopbackPrivateLinkLocalAndMetadata() throws Exception {
        for (String bad : new String[]{"127.0.0.1","10.0.0.5","172.16.0.1","192.168.1.1",
                "169.254.169.254","0.0.0.0","::1"}) {
            assertThat(IpClassifier.isBlocked(ip(bad))).as(bad).isTrue();
        }
    }
    @Test void allowsPublicAddresses() throws Exception {
        assertThat(IpClassifier.isBlocked(ip("93.184.216.34"))).isFalse(); // example.com range
        assertThat(IpClassifier.isBlocked(ip("8.8.8.8"))).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=IpClassifierTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement**

```java
package com.hireai.domain.biz.webhook;

import java.net.InetAddress;

/** Pure SSRF address rules: true if an IP must never be a webhook target. */
public final class IpClassifier {
    private IpClassifier() {}

    public static boolean isBlocked(InetAddress ip) {
        return ip.isAnyLocalAddress()      // 0.0.0.0, ::
            || ip.isLoopbackAddress()      // 127/8, ::1
            || ip.isLinkLocalAddress()     // 169.254/16 (incl. metadata), fe80::/10
            || ip.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
            || ip.isMulticastAddress();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=IpClassifierTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain backend/hireai-main
git commit -m "feat(webhook): IpClassifier SSRF address rules"
```

---

### Task 4: `WebhookPayloads` + `WebhookEventType`

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/enums/WebhookEventType.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/enums/WebhookDeliveryStatus.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/WebhookPayloads.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookPayloadsTest.java`

**Interfaces:**
- Produces: `WebhookEventType.TASK_COMPLETED("task.completed") | TASK_FAILED("task.failed")`, `.wire()`;
  `WebhookDeliveryStatus.PENDING|DELIVERED|DEAD`;
  `WebhookPayloads.completed(UUID eventId, UUID taskId, Instant at) -> String`;
  `WebhookPayloads.failed(UUID eventId, UUID taskId, String reason, String refundedAmount, Instant at) -> String`.
  All inputs are UUID/enum/number/ISO — no free text — so manual JSON is injection-safe.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookPayloads;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadsTest {
    private final UUID ev = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private final UUID task = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private final Instant at = Instant.parse("2026-07-19T09:12:00Z");

    @Test void wireStrings() {
        assertThat(WebhookEventType.TASK_COMPLETED.wire()).isEqualTo("task.completed");
        assertThat(WebhookEventType.TASK_FAILED.wire()).isEqualTo("task.failed");
    }
    @Test void completedIsThin() {
        assertThat(WebhookPayloads.completed(ev, task, at)).isEqualTo(
            "{\"event_id\":\"" + ev + "\",\"type\":\"task.completed\",\"task_id\":\"" + task
            + "\",\"occurred_at\":\"2026-07-19T09:12:00Z\"}");
    }
    @Test void failedCarriesReasonAndRefund() {
        assertThat(WebhookPayloads.failed(ev, task, "SPEC_VIOLATION", "120", at)).isEqualTo(
            "{\"event_id\":\"" + ev + "\",\"type\":\"task.failed\",\"task_id\":\"" + task
            + "\",\"reason\":\"SPEC_VIOLATION\",\"refunded\":120,\"occurred_at\":\"2026-07-19T09:12:00Z\"}");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookPayloadsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement the enums**

```java
// WebhookEventType.java
package com.hireai.domain.biz.webhook.enums;
public enum WebhookEventType {
    TASK_COMPLETED("task.completed"), TASK_FAILED("task.failed");
    private final String wire;
    WebhookEventType(String wire) { this.wire = wire; }
    public String wire() { return wire; }
}
```
```java
// WebhookDeliveryStatus.java
package com.hireai.domain.biz.webhook.enums;
public enum WebhookDeliveryStatus { PENDING, DELIVERED, DEAD }
```

- [ ] **Step 4: Implement `WebhookPayloads`**

```java
package com.hireai.domain.biz.webhook;

import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import java.time.Instant;
import java.util.UUID;

/** Thin webhook JSON bodies. All fields are safe types (UUID/enum/number/ISO) — manual JSON is safe. */
public final class WebhookPayloads {
    private WebhookPayloads() {}

    public static String completed(UUID eventId, UUID taskId, Instant at) {
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"" + WebhookEventType.TASK_COMPLETED.wire()
             + "\",\"task_id\":\"" + taskId + "\",\"occurred_at\":\"" + at + "\"}";
    }

    public static String failed(UUID eventId, UUID taskId, String reason, String refundedAmount, Instant at) {
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"" + WebhookEventType.TASK_FAILED.wire()
             + "\",\"task_id\":\"" + taskId + "\",\"reason\":\"" + reason
             + "\",\"refunded\":" + refundedAmount + ",\"occurred_at\":\"" + at + "\"}";
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookPayloadsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. (`Instant.toString()` for a whole-second UTC instant renders `2026-07-19T09:12:00Z`.)

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-domain backend/hireai-main
git commit -m "feat(webhook): event-type/status enums + thin WebhookPayloads"
```

---

### Task 5: Domain aggregates + secret generator + repository interfaces

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/model/WebhookSubscriptionModel.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/model/WebhookDeliveryModel.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/service/WebhookSecretGenerator.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/repository/WebhookSubscriptionRepository.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/webhook/repository/WebhookDeliveryRepository.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookDeliveryModelTest.java`

**Interfaces:**
- Consumes: `WebhookEventType`, `WebhookDeliveryStatus`, `WebhookBackoffPolicy` (Tasks 2, 4).
- Produces:
  - `WebhookSubscriptionModel.create(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl, String secret, Instant now)`;
    `.rotateSecret(String newSecret, Instant now)`; `.deactivate(Instant now)`;
    `.rehydrate(id, apiKeyId, ownerId, callbackUrl, secret, active, createdAt, updatedAt)`; getters
    `id(), apiKeyId(), ownerId(), callbackUrl(), signingSecret(), active(), createdAt(), updatedAt()`.
  - `WebhookDeliveryModel.enqueue(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId, WebhookEventType type, String payload, String targetUrl, Instant now)`;
    `.markDelivered(Instant now)`; `.recordFailure(Instant now, String error, WebhookBackoffPolicy backoff)`;
    `.requeue(Instant now)`; `.rehydrate(...)`; getters
    `id(), taskId(), ownerId(), subscriptionId(), eventType(), payload(), targetUrl(), status(), attempts(), nextAttemptAt(), lastError(), createdAt(), deliveredAt()`.
  - `WebhookSecretGenerator.generate() -> String` (prefix `whsec_`).
  - `WebhookSubscriptionRepository`: `save(WebhookSubscriptionModel)`, `Optional<WebhookSubscriptionModel> findActiveByApiKeyId(UUID)`, `Optional<WebhookSubscriptionModel> findActiveByOwnerId(UUID)`, `Optional<WebhookSubscriptionModel> findById(UUID)`.
  - `WebhookDeliveryRepository`: `save(WebhookDeliveryModel)`, `List<UUID> findDueIds(Instant now, int limit)`, `Optional<WebhookDeliveryModel> claimForUpdate(UUID id, Instant now)`, `Optional<WebhookDeliveryModel> findById(UUID)`, `List<WebhookDeliveryModel> findForOwner(UUID ownerId, Instant since, String status, UUID taskId)`.

- [ ] **Step 1: Write the failing test (delivery lifecycle — the risk-bearing behavior)**

```java
package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryModelTest {
    private final WebhookBackoffPolicy backoff = new WebhookBackoffPolicy(10, 3600, 3);
    private final Instant t0 = Instant.parse("2026-07-19T00:00:00Z");

    private WebhookDeliveryModel pending() {
        return WebhookDeliveryModel.enqueue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), WebhookEventType.TASK_COMPLETED, "{}", "https://x/y", t0);
    }

    @Test void enqueueStartsPendingDueNow() {
        WebhookDeliveryModel d = pending();
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d.attempts()).isZero();
        assertThat(d.nextAttemptAt()).isEqualTo(t0);
    }
    @Test void markDeliveredIsTerminal() {
        WebhookDeliveryModel d = pending().markDelivered(t0.plusSeconds(1));
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(d.deliveredAt()).isEqualTo(t0.plusSeconds(1));
        assertThat(d.attempts()).isEqualTo(1);
    }
    @Test void failureBacksOffThenGoesDeadAtMax() {
        WebhookDeliveryModel d1 = pending().recordFailure(t0, "500", backoff);
        assertThat(d1.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d1.attempts()).isEqualTo(1);
        assertThat(d1.nextAttemptAt()).isEqualTo(t0.plusSeconds(10));
        assertThat(d1.lastError()).isEqualTo("500");

        WebhookDeliveryModel d2 = d1.recordFailure(t0, "500", backoff); // attempts -> 2
        assertThat(d2.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        WebhookDeliveryModel d3 = d2.recordFailure(t0, "timeout", backoff); // attempts -> 3 == max
        assertThat(d3.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(d3.lastError()).isEqualTo("timeout");
    }
    @Test void requeueResetsDeadToPendingWithFreshBudget() {
        WebhookDeliveryModel dead = pending().recordFailure(t0, "x", backoff)
                .recordFailure(t0, "x", backoff).recordFailure(t0, "x", backoff);
        WebhookDeliveryModel again = dead.requeue(t0.plusSeconds(100));
        assertThat(again.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(again.attempts()).isZero();
        assertThat(again.nextAttemptAt()).isEqualTo(t0.plusSeconds(100));
        assertThat(again.lastError()).isNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookDeliveryModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — model not found.

- [ ] **Step 3: Implement `WebhookDeliveryModel`** (immutable transitions, mirror `TaskModel`)

```java
package com.hireai.domain.biz.webhook.model;

import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import java.time.Instant;
import java.util.UUID;

public final class WebhookDeliveryModel {
    private final UUID id, taskId, ownerId, subscriptionId;
    private final WebhookEventType eventType;
    private final String payload, targetUrl;
    private final WebhookDeliveryStatus status;
    private final int attempts;
    private final Instant nextAttemptAt, createdAt, deliveredAt;
    private final String lastError;

    private WebhookDeliveryModel(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId,
            WebhookEventType eventType, String payload, String targetUrl, WebhookDeliveryStatus status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant deliveredAt) {
        this.id = id; this.taskId = taskId; this.ownerId = ownerId; this.subscriptionId = subscriptionId;
        this.eventType = eventType; this.payload = payload; this.targetUrl = targetUrl; this.status = status;
        this.attempts = attempts; this.nextAttemptAt = nextAttemptAt; this.lastError = lastError;
        this.createdAt = createdAt; this.deliveredAt = deliveredAt;
    }

    public static WebhookDeliveryModel enqueue(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId,
            WebhookEventType eventType, String payload, String targetUrl, Instant now) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                WebhookDeliveryStatus.PENDING, 0, now, null, now, null);
    }

    public WebhookDeliveryModel markDelivered(Instant now) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                WebhookDeliveryStatus.DELIVERED, attempts + 1, nextAttemptAt, lastError, createdAt, now);
    }

    public WebhookDeliveryModel recordFailure(Instant now, String error, WebhookBackoffPolicy backoff) {
        int a = attempts + 1;
        boolean dead = backoff.exhausted(a);
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                dead ? WebhookDeliveryStatus.DEAD : WebhookDeliveryStatus.PENDING, a,
                dead ? nextAttemptAt : backoff.nextAttempt(a, now), error, createdAt, null);
    }

    public WebhookDeliveryModel requeue(Instant now) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                WebhookDeliveryStatus.PENDING, 0, now, null, createdAt, null);
    }

    public static WebhookDeliveryModel rehydrate(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId,
            WebhookEventType eventType, String payload, String targetUrl, WebhookDeliveryStatus status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant deliveredAt) {
        return new WebhookDeliveryModel(id, taskId, ownerId, subscriptionId, eventType, payload, targetUrl,
                status, attempts, nextAttemptAt, lastError, createdAt, deliveredAt);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID ownerId() { return ownerId; }
    public UUID subscriptionId() { return subscriptionId; }
    public WebhookEventType eventType() { return eventType; }
    public String payload() { return payload; }
    public String targetUrl() { return targetUrl; }
    public WebhookDeliveryStatus status() { return status; }
    public int attempts() { return attempts; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public String lastError() { return lastError; }
    public Instant createdAt() { return createdAt; }
    public Instant deliveredAt() { return deliveredAt; }
}
```

- [ ] **Step 4: Implement `WebhookSubscriptionModel`**

```java
package com.hireai.domain.biz.webhook.model;

import java.time.Instant;
import java.util.UUID;

public final class WebhookSubscriptionModel {
    private final UUID id, apiKeyId, ownerId;
    private final String callbackUrl, signingSecret;
    private final boolean active;
    private final Instant createdAt, updatedAt;

    private WebhookSubscriptionModel(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String signingSecret, boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id; this.apiKeyId = apiKeyId; this.ownerId = ownerId; this.callbackUrl = callbackUrl;
        this.signingSecret = signingSecret; this.active = active; this.createdAt = createdAt; this.updatedAt = updatedAt;
    }

    public static WebhookSubscriptionModel create(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String secret, Instant now) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, secret, true, now, now);
    }
    public WebhookSubscriptionModel rotateSecret(String newSecret, Instant now) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, newSecret, active, createdAt, now);
    }
    public WebhookSubscriptionModel deactivate(Instant now) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, signingSecret, false, createdAt, now);
    }
    public static WebhookSubscriptionModel rehydrate(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String signingSecret, boolean active, Instant createdAt, Instant updatedAt) {
        return new WebhookSubscriptionModel(id, apiKeyId, ownerId, callbackUrl, signingSecret, active, createdAt, updatedAt);
    }

    public UUID id() { return id; }
    public UUID apiKeyId() { return apiKeyId; }
    public UUID ownerId() { return ownerId; }
    public String callbackUrl() { return callbackUrl; }
    public String signingSecret() { return signingSecret; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: Implement `WebhookSecretGenerator` (domain service) + repository interfaces**

```java
// WebhookSecretGenerator.java
package com.hireai.domain.biz.webhook.service;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Generates an unguessable webhook signing secret. Framework-free; wired in DomainServiceConfig. */
public class WebhookSecretGenerator {
    private final SecureRandom random = new SecureRandom();
    public String generate() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return "whsec_" + HexFormat.of().formatHex(buf);
    }
}
```
```java
// WebhookSubscriptionRepository.java
package com.hireai.domain.biz.webhook.repository;

import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository {
    WebhookSubscriptionModel save(WebhookSubscriptionModel sub);
    Optional<WebhookSubscriptionModel> findActiveByApiKeyId(UUID apiKeyId);
    Optional<WebhookSubscriptionModel> findActiveByOwnerId(UUID ownerId);
    Optional<WebhookSubscriptionModel> findById(UUID id);
}
```
```java
// WebhookDeliveryRepository.java
package com.hireai.domain.biz.webhook.repository;

import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository {
    WebhookDeliveryModel save(WebhookDeliveryModel delivery);
    /** Ids of PENDING rows whose next_attempt_at <= now, oldest first, up to limit. No lock. */
    List<UUID> findDueIds(Instant now, int limit);
    /** Row-lock a still-due PENDING row (FOR UPDATE SKIP LOCKED). Empty if taken/handled. */
    Optional<WebhookDeliveryModel> claimForUpdate(UUID id, Instant now);
    Optional<WebhookDeliveryModel> findById(UUID id);
    /** Owner-scoped log read; nullable filters (since/status/taskId). */
    List<WebhookDeliveryModel> findForOwner(UUID ownerId, Instant since, String status, UUID taskId);
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookDeliveryModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-domain backend/hireai-main
git commit -m "feat(webhook): subscription + delivery aggregates, secret generator, repo interfaces"
```

---

### Task 6: Migration `V26` + config + secret-generator wiring

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V26__webhooks.sql`
- Modify: `backend/hireai-main/src/main/resources/application.yml` (add `hireai.webhooks.*`)
- Modify: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/config/DomainServiceConfig.java`
- Test: none new (schema is exercised by the repository integration tests in Tasks 7–8; commit gates on the reactor compiling).

**Interfaces:**
- Produces: tables `client_webhook_subscriptions`, `webhook_deliveries`; a `WebhookSecretGenerator` bean;
  config keys `hireai.webhooks.sweep-interval`, `max-attempts`, `base-backoff`, `cap-backoff`,
  `batch-size`, `allow-insecure-localhost`, `connect-timeout`, `read-timeout`.

- [ ] **Step 1: Write the migration**

```sql
-- V26__webhooks.sql — Phase 4 push webhooks (additive; money tables untouched).

CREATE TABLE client_webhook_subscriptions (
    id             UUID PRIMARY KEY,
    api_key_id     UUID NOT NULL REFERENCES api_keys(id),
    owner_id       UUID NOT NULL REFERENCES users(id),
    callback_url   TEXT NOT NULL,
    signing_secret TEXT NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
-- At most one ACTIVE subscription per API key.
CREATE UNIQUE INDEX uq_webhook_sub_active_key ON client_webhook_subscriptions (api_key_id) WHERE active;
CREATE INDEX ix_webhook_sub_owner ON client_webhook_subscriptions (owner_id);

CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY,               -- doubles as the client-facing event_id
    task_id         UUID NOT NULL REFERENCES tasks(id),
    owner_id        UUID NOT NULL REFERENCES users(id),
    subscription_id UUID NOT NULL REFERENCES client_webhook_subscriptions(id),
    event_type      TEXT NOT NULL,                  -- task.completed | task.failed
    payload         TEXT NOT NULL,                  -- built/stored/sent whole as a string; never queried with jsonb operators (same rationale as V20 task_results.result_payload → TEXT). Avoids @JdbcTypeCode(JSON) mapping.
    target_url      TEXT NOT NULL,
    status          TEXT NOT NULL,                  -- PENDING | DELIVERED | DEAD
    attempts        INT  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ
);
-- Sweeper claim key.
CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (status, next_attempt_at);
CREATE INDEX ix_webhook_deliveries_owner ON webhook_deliveries (owner_id, created_at DESC);
CREATE INDEX ix_webhook_deliveries_task ON webhook_deliveries (task_id);
```

- [ ] **Step 2: Add config to `application.yml`** (under the existing `hireai:` block)

```yaml
hireai:
  webhooks:
    sweep-interval: PT5S
    batch-size: 50
    base-backoff-seconds: 10
    cap-backoff-seconds: 3600
    max-attempts: 28
    allow-insecure-localhost: false   # dev only; mirrors hireai.dispatch.allow-insecure-localhost
    connect-timeout: PT3S
    read-timeout: PT5S
```

- [ ] **Step 3: Register the secret generator bean** in `DomainServiceConfig` (add a `@Bean` method)

```java
// add inside DomainServiceConfig:
@org.springframework.context.annotation.Bean
public com.hireai.domain.biz.webhook.service.WebhookSecretGenerator webhookSecretGenerator() {
    return new com.hireai.domain.biz.webhook.service.WebhookSecretGenerator();
}
```

- [ ] **Step 4: Verify the reactor compiles**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-main backend/hireai-infrastructure
git commit -m "feat(webhook): V26 schema (subscriptions + deliveries), config, secret-generator bean"
```

---

### Task 7: `WebhookSubscription` persistence

**Files:**
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/webhook/WebhookSubscriptionDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/webhook/WebhookSubscriptionJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/webhook/WebhookSubscriptionRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookSubscriptionRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `WebhookSubscriptionModel`, `WebhookSubscriptionRepository` (Task 5).
- Produces: a Spring `@Repository` implementing `WebhookSubscriptionRepository` over the `client_webhook_subscriptions` table.

- [ ] **Step 1: Write the failing integration test** — copy the container boilerplate (`@Container` PostgreSQLContainer, `dockerAvailable()`, `@DynamicPropertySource`) **verbatim from** `backend/hireai-main/src/test/java/com/hireai/adjudication/DisputeRepositoryIntegrationTest.java`; only the body below differs.

```java
package com.hireai.webhook;

// ... same imports + @SpringBootTest @Testcontainers @ActiveProfiles("test") @EnabledIf("dockerAvailable")
// ... same @Container container + dockerAvailable() + @DynamicPropertySource as DisputeRepositoryIntegrationTest
// Plus:
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;

class WebhookSubscriptionRepositoryIntegrationTest {
    // (container boilerplate here — copied from DisputeRepositoryIntegrationTest)

    @Autowired WebhookSubscriptionRepository repo;
    @Autowired com.hireai.domain.biz.apikey.repository.ApiKeyRepository apiKeys; // to seed a FK-valid key

    private UUID seedKeyOwnedBy(UUID owner) {
        // Seed a users row + an api_keys row so FKs hold. Mirror how the api-key IT seeds users;
        // if simpler, reuse an existing seeded user id from the test data (client@hireai.local).
        // Return the api_key id.
        // ... (implementer: create via ApiKeyModel + apiKeys.save, or a JdbcTemplate insert)
        return /* apiKeyId */ null;
    }

    @Test void savesAndFindsActiveByApiKey() {
        UUID owner = /* seeded user id */ null;
        UUID keyId = seedKeyOwnedBy(owner);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        repo.save(WebhookSubscriptionModel.create(id, keyId, owner, "https://client.example.com/cb", "whsec_abc", now));

        var found = repo.findActiveByApiKeyId(keyId);
        assertThat(found).isPresent();
        assertThat(found.get().callbackUrl()).isEqualTo("https://client.example.com/cb");
        assertThat(found.get().signingSecret()).isEqualTo("whsec_abc");
        assertThat(found.get().active()).isTrue();
    }

    @Test void deactivatedIsNotFoundAsActive() {
        UUID owner = /* seeded user id */ null;
        UUID keyId = seedKeyOwnedBy(owner);
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        var sub = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner, "https://a/b", "s", now);
        repo.save(sub);
        repo.save(sub.deactivate(now.plusSeconds(1)));
        assertThat(repo.findActiveByApiKeyId(keyId)).isEmpty();
    }
}
```

> **Seeding note:** the two FKs (`api_key_id`, `owner_id`) must reference real rows. Reuse the seeded demo user (`client@hireai.local`) and create an `api_keys` row via `ApiKeyModel` + the `ApiKeyRepository` (Phase 3) so the test is self-contained. Look at any Phase-3 api-key integration test for the exact `ApiKeyModel.issue(...)` factory.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSubscriptionRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: locally **SKIPPED** (no Docker) — this test proves out in CI. To exercise logic locally, temporarily assert the mapper via a plain unit test, or rely on CI. Proceed on compile success.

- [ ] **Step 3: Implement the DO** (mirror `ApiKeyDO`)

```java
package com.hireai.infrastructure.repository.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "client_webhook_subscriptions")
public class WebhookSubscriptionDO {
    @Id @Column(name = "id") private UUID id;
    @Column(name = "api_key_id", nullable = false) private UUID apiKeyId;
    @Column(name = "owner_id", nullable = false) private UUID ownerId;
    @Column(name = "callback_url", nullable = false) private String callbackUrl;
    @Column(name = "signing_secret", nullable = false) private String signingSecret;
    @Column(name = "active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected WebhookSubscriptionDO() {}
    public WebhookSubscriptionDO(UUID id, UUID apiKeyId, UUID ownerId, String callbackUrl,
            String signingSecret, boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id; this.apiKeyId = apiKeyId; this.ownerId = ownerId; this.callbackUrl = callbackUrl;
        this.signingSecret = signingSecret; this.active = active; this.createdAt = createdAt; this.updatedAt = updatedAt;
    }
    public UUID getId() { return id; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getOwnerId() { return ownerId; }
    public String getCallbackUrl() { return callbackUrl; }
    public String getSigningSecret() { return signingSecret; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: Implement the JPA repository + `RepositoryImpl`**

```java
// WebhookSubscriptionJpaRepository.java
package com.hireai.infrastructure.repository.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionJpaRepository extends JpaRepository<WebhookSubscriptionDO, UUID> {
    Optional<WebhookSubscriptionDO> findByApiKeyIdAndActiveTrue(UUID apiKeyId);
    Optional<WebhookSubscriptionDO> findByOwnerIdAndActiveTrue(UUID ownerId);
}
```
```java
// WebhookSubscriptionRepositoryImpl.java
package com.hireai.infrastructure.repository.webhook;

import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WebhookSubscriptionRepositoryImpl implements WebhookSubscriptionRepository {
    private final WebhookSubscriptionJpaRepository jpa;
    public WebhookSubscriptionRepositoryImpl(WebhookSubscriptionJpaRepository jpa) { this.jpa = jpa; }

    @Override public WebhookSubscriptionModel save(WebhookSubscriptionModel s) {
        jpa.save(new WebhookSubscriptionDO(s.id(), s.apiKeyId(), s.ownerId(), s.callbackUrl(),
                s.signingSecret(), s.active(), s.createdAt(), s.updatedAt()));
        return s;
    }
    @Override public Optional<WebhookSubscriptionModel> findActiveByApiKeyId(UUID apiKeyId) {
        return jpa.findByApiKeyIdAndActiveTrue(apiKeyId).map(this::toModel);
    }
    @Override public Optional<WebhookSubscriptionModel> findActiveByOwnerId(UUID ownerId) {
        return jpa.findByOwnerIdAndActiveTrue(ownerId).map(this::toModel);
    }
    @Override public Optional<WebhookSubscriptionModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }
    private WebhookSubscriptionModel toModel(WebhookSubscriptionDO d) {
        return WebhookSubscriptionModel.rehydrate(d.getId(), d.getApiKeyId(), d.getOwnerId(),
                d.getCallbackUrl(), d.getSigningSecret(), d.isActive(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
```

- [ ] **Step 5: Run the full suite** (schema/mapping validated by Flyway + `ddl-auto: validate`)

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS (the new IT is skipped locally without Docker; nothing else breaks).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-repository backend/hireai-main
git commit -m "feat(webhook): subscription persistence (DO/JPA/repository impl)"
```

---

### Task 8: `WebhookDelivery` persistence (with `FOR UPDATE SKIP LOCKED` claim)

**Files:**
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/webhook/WebhookDeliveryDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/webhook/WebhookDeliveryJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/webhook/WebhookDeliveryRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookDeliveryRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `WebhookDeliveryModel`, `WebhookDeliveryRepository` (Task 5).
- Produces: `@Repository` implementing `WebhookDeliveryRepository`; JPA repo with a **native** claim query
  `SELECT ... WHERE id = :id AND status='PENDING' AND next_attempt_at <= :now FOR UPDATE SKIP LOCKED`.

- [ ] **Step 1: Write the failing integration test** (container boilerplate copied as in Task 7)

```java
// package com.hireai.webhook; (same IT scaffolding as Task 7)
// Body:
@Autowired WebhookDeliveryRepository repo;
// seed a task + subscription (FKs) — reuse a seeded task or insert via JdbcTemplate.

@Test void savesFindsDueClaimsAndFiltersByOwner() {
    Instant now = Instant.parse("2026-07-19T00:00:00Z");
    UUID owner = /* seeded */ null, task = /* seeded */ null, sub = /* seeded */ null;
    var d = WebhookDeliveryModel.enqueue(UUID.randomUUID(), task, owner, sub,
            WebhookEventType.TASK_COMPLETED, "{\"a\":1}", "https://x/y", now);
    repo.save(d);

    assertThat(repo.findDueIds(now.plusSeconds(1), 50)).contains(d.id());
    assertThat(repo.findDueIds(now.minusSeconds(1), 50)).doesNotContain(d.id()); // not yet due

    var claimed = repo.claimForUpdate(d.id(), now.plusSeconds(1));
    assertThat(claimed).isPresent();
    repo.save(claimed.get().markDelivered(now.plusSeconds(2)));
    assertThat(repo.claimForUpdate(d.id(), now.plusSeconds(3))).isEmpty(); // DELIVERED no longer claimable

    var byOwner = repo.findForOwner(owner, now.minusSeconds(60), null, null);
    assertThat(byOwner).extracting(WebhookDeliveryModel::id).contains(d.id());
    assertThat(repo.findForOwner(owner, now.minusSeconds(60), "DELIVERED", task)).isNotEmpty();
    assertThat(repo.findForOwner(owner, now.minusSeconds(60), "DEAD", null)).isEmpty();
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookDeliveryRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: SKIPPED locally (no Docker); compile must succeed once classes exist.

- [ ] **Step 3: Implement the DO**

```java
package com.hireai.infrastructure.repository.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
public class WebhookDeliveryDO {
    @Id @Column(name = "id") private UUID id;
    @Column(name = "task_id", nullable = false) private UUID taskId;
    @Column(name = "owner_id", nullable = false) private UUID ownerId;
    @Column(name = "subscription_id", nullable = false) private UUID subscriptionId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "payload", nullable = false) private String payload;
    @Column(name = "target_url", nullable = false) private String targetUrl;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "delivered_at") private Instant deliveredAt;

    protected WebhookDeliveryDO() {}
    public WebhookDeliveryDO(UUID id, UUID taskId, UUID ownerId, UUID subscriptionId, String eventType,
            String payload, String targetUrl, String status, int attempts, Instant nextAttemptAt,
            String lastError, Instant createdAt, Instant deliveredAt) {
        this.id = id; this.taskId = taskId; this.ownerId = ownerId; this.subscriptionId = subscriptionId;
        this.eventType = eventType; this.payload = payload; this.targetUrl = targetUrl; this.status = status;
        this.attempts = attempts; this.nextAttemptAt = nextAttemptAt; this.lastError = lastError;
        this.createdAt = createdAt; this.deliveredAt = deliveredAt;
    }
    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getTargetUrl() { return targetUrl; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
}
```

- [ ] **Step 4: Implement the JPA repo (native claim) + `RepositoryImpl`**

```java
// WebhookDeliveryJpaRepository.java
package com.hireai.infrastructure.repository.webhook;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryJpaRepository extends JpaRepository<WebhookDeliveryDO, UUID> {

    @Query(value = "SELECT id FROM webhook_deliveries WHERE status='PENDING' AND next_attempt_at <= :now "
                 + "ORDER BY next_attempt_at LIMIT :limit", nativeQuery = true)
    List<UUID> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    // Row-lock a single still-due PENDING row; SKIP LOCKED so parallel sweepers never block each other.
    @Query(value = "SELECT * FROM webhook_deliveries WHERE id = :id AND status='PENDING' "
                 + "AND next_attempt_at <= :now FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WebhookDeliveryDO> claimForUpdate(@Param("id") UUID id, @Param("now") Instant now);
}
```
```java
// WebhookDeliveryRepositoryImpl.java
package com.hireai.infrastructure.repository.webhook;

import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class WebhookDeliveryRepositoryImpl implements WebhookDeliveryRepository {
    private final WebhookDeliveryJpaRepository jpa;
    @PersistenceContext private EntityManager em;

    public WebhookDeliveryRepositoryImpl(WebhookDeliveryJpaRepository jpa) { this.jpa = jpa; }

    private WebhookEventType type(String s) {
        for (WebhookEventType t : WebhookEventType.values()) if (t.wire().equals(s) || t.name().equals(s)) return t;
        throw new IllegalStateException("Unknown event type " + s);
    }

    @Override public WebhookDeliveryModel save(WebhookDeliveryModel d) {
        jpa.save(new WebhookDeliveryDO(d.id(), d.taskId(), d.ownerId(), d.subscriptionId(),
                d.eventType().wire(), d.payload(), d.targetUrl(), d.status().name(), d.attempts(),
                d.nextAttemptAt(), d.lastError(), d.createdAt(), d.deliveredAt()));
        return d;
    }
    @Override public List<UUID> findDueIds(Instant now, int limit) { return jpa.findDueIds(now, limit); }

    @Override public Optional<WebhookDeliveryModel> claimForUpdate(UUID id, Instant now) {
        return jpa.claimForUpdate(id, now).map(this::toModel);
    }
    @Override public Optional<WebhookDeliveryModel> findById(UUID id) { return jpa.findById(id).map(this::toModel); }

    @Override public List<WebhookDeliveryModel> findForOwner(UUID ownerId, Instant since, String status, UUID taskId) {
        // Dynamic filter via a native query with optional predicates (nulls ignored).
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM webhook_deliveries WHERE owner_id = :owner AND created_at >= :since");
        if (status != null) sql.append(" AND status = :status");
        if (taskId != null) sql.append(" AND task_id = :taskId");
        sql.append(" ORDER BY created_at DESC");
        var q = em.createNativeQuery(sql.toString(), WebhookDeliveryDO.class)
                .setParameter("owner", ownerId).setParameter("since", since);
        if (status != null) q.setParameter("status", status);
        if (taskId != null) q.setParameter("taskId", taskId);
        @SuppressWarnings("unchecked")
        List<WebhookDeliveryDO> rows = q.getResultList();
        return rows.stream().map(this::toModel).collect(Collectors.toList());
    }

    private WebhookDeliveryModel toModel(WebhookDeliveryDO d) {
        return WebhookDeliveryModel.rehydrate(d.getId(), d.getTaskId(), d.getOwnerId(), d.getSubscriptionId(),
                type(d.getEventType()), d.getPayload(), d.getTargetUrl(),
                WebhookDeliveryStatus.valueOf(d.getStatus()), d.getAttempts(), d.getNextAttemptAt(),
                d.getLastError(), d.getCreatedAt(), d.getDeliveredAt());
    }
}
```

- [ ] **Step 5: Run the full suite**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-repository backend/hireai-main
git commit -m "feat(webhook): delivery persistence with FOR UPDATE SKIP LOCKED claim"
```

---

### Task 9: API-key → task attribution lookup

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/apikey/repository/ApiKeyTaskRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyTaskJpaRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/apikey/ApiKeyTaskRepositoryImpl.java`
- Test: fold into `WebhookOutboxAppService` unit test (Task 12) — this step just adds the query + a repo integration assertion.

**Interfaces:**
- Produces: `ApiKeyTaskRepository.findApiKeyIdByTask(UUID taskId) -> Optional<UUID>` — the submitting API
  key id, or empty for a WEB-submitted task. This is the **channel-detection** primitive (present ⇒ API)
  **and** the subscription-resolution key.

- [ ] **Step 1: Add the method to the domain interface**

```java
// ApiKeyTaskRepository.java — add:
java.util.Optional<java.util.UUID> findApiKeyIdByTask(java.util.UUID taskId);
```

- [ ] **Step 2: Add the JPA query** (inspect `ApiKeyTaskJpaRepository` / `ApiKeyTaskDO` for the column name — likely `task_id`, `api_key_id`)

```java
// ApiKeyTaskJpaRepository.java — add:
@org.springframework.data.jpa.repository.Query(
    value = "SELECT api_key_id FROM api_key_task WHERE task_id = :taskId LIMIT 1", nativeQuery = true)
java.util.Optional<java.util.UUID> findApiKeyIdByTask(@org.springframework.data.repository.query.Param("taskId") java.util.UUID taskId);
```

- [ ] **Step 3: Delegate in the `RepositoryImpl`**

```java
// ApiKeyTaskRepositoryImpl.java — add:
@Override public java.util.Optional<java.util.UUID> findApiKeyIdByTask(java.util.UUID taskId) {
    return jpa.findApiKeyIdByTask(taskId);
}
```

- [ ] **Step 4: Verify compile + full suite**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain backend/hireai-repository
git commit -m "feat(webhook): ApiKeyTaskRepository.findApiKeyIdByTask (channel detection + sub resolution)"
```

---

### Task 10: SSRF URL validator (`WebhookUrlValidatorPort` + infra impl)

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/port/webhook/WebhookUrlValidatorPort.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/webhook/WebhookUrlValidator.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookUrlValidatorTest.java`

**Interfaces:**
- Consumes: `IpClassifier` (Task 3).
- Produces: `WebhookUrlValidatorPort.assertDeliverable(String url)` — throws `DomainException(VALIDATION_ERROR)`
  for non-HTTPS, unparseable, unresolvable, or private/loopback/reserved-resolving URLs. HTTP `localhost`/
  `127.0.0.1` allowed only when `hireai.webhooks.allow-insecure-localhost=true`.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.infrastructure.webhook.WebhookUrlValidator;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebhookUrlValidatorTest {
    private final WebhookUrlValidator v = new WebhookUrlValidator(false); // allow-insecure-localhost=false

    @Test void rejectsNonHttps() {
        assertThatThrownBy(() -> v.assertDeliverable("http://example.com/cb")).isInstanceOf(DomainException.class);
    }
    @Test void rejectsPrivateAndLoopbackHosts() {
        assertThatThrownBy(() -> v.assertDeliverable("https://127.0.0.1/cb")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v.assertDeliverable("https://10.0.0.5/cb")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v.assertDeliverable("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(DomainException.class);
    }
    @Test void rejectsGarbage() {
        assertThatThrownBy(() -> v.assertDeliverable("not a url")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v.assertDeliverable("https://")).isInstanceOf(DomainException.class);
    }
    @Test void allowsAPublicHttpsHost() {
        // example.com resolves to public IPs; if the CI sandbox blocks DNS, swap for a literal public IP host.
        assertThatCode(() -> v.assertDeliverable("https://example.com/cb")).doesNotThrowAnyException();
    }
    @Test void allowsHttpsLiteralPublicIp() {
        assertThatCode(() -> v.assertDeliverable("https://93.184.216.34/cb")).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookUrlValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement the port**

```java
package com.hireai.application.port.webhook;

/** Validates that a client callback URL is safe to POST to (HTTPS + non-private). Throws on failure. */
public interface WebhookUrlValidatorPort {
    void assertDeliverable(String url);
}
```

- [ ] **Step 4: Implement the infra validator**

```java
package com.hireai.infrastructure.webhook;

import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.webhook.IpClassifier;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class WebhookUrlValidator implements WebhookUrlValidatorPort {

    private final boolean allowInsecureLocalhost;

    public WebhookUrlValidator(@Value("${hireai.webhooks.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    @Override
    public void assertDeliverable(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Malformed callback URL");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback URL must be an absolute http(s) URL");
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean devLocalhost = allowInsecureLocalhost && "http".equalsIgnoreCase(scheme)
                && ("localhost".equals(host) || "127.0.0.1".equals(host));
        if (!https && !devLocalhost) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback URL must use HTTPS");
        }
        if (devLocalhost) return; // explicit dev opt-in

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback host does not resolve");
        }
        for (InetAddress ip : resolved) {
            if (IpClassifier.isBlocked(ip)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback URL resolves to a private/blocked address");
            }
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookUrlValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. (If the sandbox has no DNS, the two "allows public host" tests may error on resolution — keep the literal-IP one, and mark the DNS one `@EnabledIf` on network, or drop it; the block-tests are the security-critical ones.)

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-infrastructure backend/hireai-main
git commit -m "feat(webhook): SSRF-guarding WebhookUrlValidator (HTTPS + resolve-and-reject-private)"
```

---

### Task 11: Outbound sender (`WebhookSenderPort` + infra `RestClient` impl)

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/port/webhook/WebhookSendResult.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/port/webhook/WebhookSenderPort.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/webhook/WebhookSender.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookSenderTest.java`

**Interfaces:**
- Produces: `WebhookSendResult` (record `boolean success, int statusCode, String error`);
  `WebhookSenderPort.send(String url, String body, String signatureHeader, String eventId, String eventType) -> WebhookSendResult`.
  The impl POSTs `body` with headers `X-HireAI-Signature`, `X-HireAI-Event-Id`, `X-HireAI-Event-Type`,
  `Content-Type: application/json`; 2xx ⇒ success; any exception/non-2xx ⇒ failure (never throws).

- [ ] **Step 1: Write the failing test** (MockRestServiceServer, like `AgentDispatchClientTest`)

```java
package com.hireai.webhook;

import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.infrastructure.webhook.WebhookSender;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WebhookSenderTest {

    private WebhookSender sender(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return new WebhookSender(builder);
    }

    @Test void postsSignedBodyAndReportsSuccessOn2xx() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        WebhookSender sender = sender(h);
        h[0].expect(requestTo("https://client.example.com/cb"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-HireAI-Signature", "t=1,v1=abc"))
            .andExpect(header("X-HireAI-Event-Id", "ev-1"))
            .andExpect(header("X-HireAI-Event-Type", "task.completed"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"a\":1}"))
            .andRespond(withSuccess());
        WebhookSendResult r = sender.send("https://client.example.com/cb", "{\"a\":1}", "t=1,v1=abc", "ev-1", "task.completed");
        assertThat(r.success()).isTrue();
        h[0].verify();
    }

    @Test void reportsFailureOn5xxWithoutThrowing() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        WebhookSender sender = sender(h);
        h[0].expect(requestTo("https://x/y")).andRespond(withServerError());
        WebhookSendResult r = sender.send("https://x/y", "{}", "t=1,v1=z", "ev-2", "task.failed");
        assertThat(r.success()).isFalse();
        assertThat(r.statusCode()).isEqualTo(500);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSenderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement the port + result**

```java
// WebhookSendResult.java
package com.hireai.application.port.webhook;
public record WebhookSendResult(boolean success, int statusCode, String error) {
    public static WebhookSendResult ok(int status) { return new WebhookSendResult(true, status, null); }
    public static WebhookSendResult fail(int status, String error) { return new WebhookSendResult(false, status, error); }
}
```
```java
// WebhookSenderPort.java
package com.hireai.application.port.webhook;
public interface WebhookSenderPort {
    WebhookSendResult send(String url, String body, String signatureHeader, String eventId, String eventType);
}
```

- [ ] **Step 4: Implement the sender** (mirror `AgentDispatchClient`; bounded timeouts come from the builder customizer — reuse the existing `DispatchRestClientConfig` customizer, which applies to all `RestClient.Builder` beans)

```java
package com.hireai.infrastructure.webhook;

import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.application.port.webhook.WebhookSenderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Component
@Slf4j
public class WebhookSender implements WebhookSenderPort {

    private final RestClient restClient;

    public WebhookSender(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public WebhookSendResult send(String url, String body, String signatureHeader, String eventId, String eventType) {
        try {
            var response = restClient.post()
                    .uri(URI.create(url))
                    .header("X-HireAI-Signature", signatureHeader)
                    .header("X-HireAI-Event-Id", eventId)
                    .header("X-HireAI-Event-Type", eventType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            return WebhookSendResult.ok(status);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return WebhookSendResult.fail(e.getStatusCode().value(), e.getMessage());
        } catch (Exception e) {
            log.warn("Webhook POST to {} failed: {}", url, e.toString());
            return WebhookSendResult.fail(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSenderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-infrastructure backend/hireai-main
git commit -m "feat(webhook): outbound WebhookSender (RestClient, signed headers, never throws)"
```

---

### Task 12: `WebhookOutboxAppService` — the atomic enqueue

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/webhook/WebhookOutboxAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/webhook/impl/WebhookOutboxAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookOutboxAppServiceImplTest.java`

**Interfaces:**
- Consumes: `ApiKeyTaskRepository.findApiKeyIdByTask` (Task 9), `WebhookSubscriptionRepository` (Task 5),
  `WebhookDeliveryRepository` (Task 5), `WebhookPayloads` (Task 4), `java.time.Clock` (existing bean),
  `TaskModel` (`.id()`, `.clientId()`, `.budget()` → `Money.value()`).
- Produces: `WebhookOutboxAppService.enqueueCompleted(TaskModel task)`, `.enqueueFailed(TaskModel task, String reason)`.
  Both no-op unless the task has an API-key attribution **and** that key has an active subscription. Called
  from **within** the caller's settlement transaction (propagation `REQUIRED`) — so the delivery row commits
  atomically with the state change (the outbox guarantee).

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.application.biz.webhook.impl.WebhookOutboxAppServiceImpl;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebhookOutboxAppServiceImplTest {

    private final ApiKeyTaskRepository apiKeyTasks = mock(ApiKeyTaskRepository.class);
    private final WebhookSubscriptionRepository subs = mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T09:12:00Z"), ZoneOffset.UTC);
    private final WebhookOutboxAppServiceImpl svc =
            new WebhookOutboxAppServiceImpl(apiKeyTasks, subs, deliveries, clock);

    private final UUID taskId = UUID.randomUUID(), clientId = UUID.randomUUID(),
            keyId = UUID.randomUUID(), subId = UUID.randomUUID();

    private TaskModel task() {
        TaskModel t = mock(TaskModel.class);
        when(t.id()).thenReturn(taskId);
        when(t.clientId()).thenReturn(clientId);
        when(t.budget()).thenReturn(Money.of("120"));
        return t;
    }
    private WebhookSubscriptionModel sub() {
        return WebhookSubscriptionModel.rehydrate(subId, keyId, clientId, "https://c/cb", "whsec_x",
                true, Instant.now(), Instant.now());
    }

    @Test void webTaskEnqueuesNothing() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.empty());
        svc.enqueueCompleted(task());
        verifyNoInteractions(deliveries);
    }
    @Test void apiTaskWithNoActiveSubEnqueuesNothing() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(keyId));
        when(subs.findActiveByApiKeyId(keyId)).thenReturn(Optional.empty());
        svc.enqueueCompleted(task());
        verifyNoInteractions(deliveries);
    }
    @Test void completedEnqueuesPendingTaskCompleted() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(keyId));
        when(subs.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(sub()));
        svc.enqueueCompleted(task());
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        WebhookDeliveryModel d = cap.getValue();
        assertThat(d.eventType()).isEqualTo(WebhookEventType.TASK_COMPLETED);
        assertThat(d.taskId()).isEqualTo(taskId);
        assertThat(d.ownerId()).isEqualTo(clientId);
        assertThat(d.subscriptionId()).isEqualTo(subId);
        assertThat(d.targetUrl()).isEqualTo("https://c/cb");
        assertThat(d.payload()).contains("\"type\":\"task.completed\"").contains(taskId.toString());
    }
    @Test void failedEnqueuesReasonAndRefund() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(keyId));
        when(subs.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(sub()));
        svc.enqueueFailed(task(), "SPEC_VIOLATION");
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().eventType()).isEqualTo(WebhookEventType.TASK_FAILED);
        assertThat(cap.getValue().payload())
                .contains("\"reason\":\"SPEC_VIOLATION\"").contains("\"refunded\":120.00");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookOutboxAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — service not found.

- [ ] **Step 3: Implement the interface**

```java
package com.hireai.application.biz.webhook;

import com.hireai.domain.biz.task.model.TaskModel;

/** Inserts a webhook delivery row (outbox) at a terminal transition — same transaction as the state change. */
public interface WebhookOutboxAppService {
    void enqueueCompleted(TaskModel task);
    void enqueueFailed(TaskModel task, String reason);
}
```

- [ ] **Step 4: Implement**

```java
package com.hireai.application.biz.webhook.impl;

import com.hireai.application.biz.webhook.WebhookOutboxAppService;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.webhook.WebhookPayloads;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Joins the caller's transaction (propagation REQUIRED) so the delivery row commits atomically with the
 * terminal state change — the transactional-outbox guarantee (no lost or phantom events). No-op for
 * WEB-submitted tasks (no api_key_task attribution) or API tasks whose key has no active subscription.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class WebhookOutboxAppServiceImpl implements WebhookOutboxAppService {

    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final Clock clock;

    @Override
    public void enqueueCompleted(TaskModel task) {
        subscriptionFor(task.id()).ifPresent(sub -> {
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            String payload = WebhookPayloads.completed(eventId, task.id(), now);
            enqueue(eventId, task, sub, WebhookEventType.TASK_COMPLETED, payload, now);
        });
    }

    @Override
    public void enqueueFailed(TaskModel task, String reason) {
        subscriptionFor(task.id()).ifPresent(sub -> {
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            String refunded = task.budget().value().toPlainString();
            String payload = WebhookPayloads.failed(eventId, task.id(), reason, refunded, now);
            enqueue(eventId, task, sub, WebhookEventType.TASK_FAILED, payload, now);
        });
    }

    private Optional<WebhookSubscriptionModel> subscriptionFor(UUID taskId) {
        return apiKeyTaskRepository.findApiKeyIdByTask(taskId)
                .flatMap(subscriptionRepository::findActiveByApiKeyId);
    }

    private void enqueue(UUID eventId, TaskModel task, WebhookSubscriptionModel sub,
                         WebhookEventType type, String payload, Instant now) {
        deliveryRepository.save(WebhookDeliveryModel.enqueue(
                eventId, task.id(), sub.ownerId(), sub.id(), type, payload, sub.callbackUrl(), now));
        log.info("Enqueued {} webhook for task {} -> subscription {}", type.wire(), task.id(), sub.id());
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookOutboxAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-main
git commit -m "feat(webhook): WebhookOutboxAppService — atomic in-transaction delivery enqueue"
```

---

### Task 13: Deterministic auto-settle in the validation gate (+ `task.completed`/`task.failed` enqueue) ⚠️ money-path

**Files:**
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/validation/impl/ValidationAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/adjudication/ValidationAppServiceImplTest.java` (existing — update)
- Test (integration, CI): extend `backend/hireai-main/src/test/java/com/hireai/adjudication/ValidationGateIntegrationTest.java`

**Interfaces:**
- Consumes: `ApiKeyTaskRepository.findApiKeyIdByTask` (Task 9), `AgentRepository.findOwnerByVersionId`,
  `SettlementWriteAppService.settleAccepted/settleRejected`, `WebhookOutboxAppService` (Task 12),
  `TaskModel.passValidation() → PENDING_REVIEW`, `.accept() → RESOLVED`, `.failValidation() → SPEC_VIOLATION`.
- Produces: after this task, an **API-submitted** task that passes validation is settled 85/15 and `RESOLVED`
  in the same transaction (WEB tasks still stop at `PENDING_REVIEW`); every terminal outcome enqueues a webhook.

> **⚠️ This changes money-path behavior.** Run the FULL backend suite after this task (`mvn -f backend/pom.xml -q -B test`). Reuses the exact `settleAccepted` the human accept path uses — no new money path (Invariant #3).

- [ ] **Step 1: Update the unit test** — the existing test constructs `ValidationAppServiceImpl` with 4 deps; it now needs 7 (append `agentRepository, apiKeyTaskRepository, webhookOutboxAppService`, in that order). Add the three mocks, stub `findApiKeyIdByTask(any())` → `Optional.empty()` in the existing WEB-path assertions, and add these new cases:

```java
// New mocks (add to the test's fields):
private final com.hireai.domain.biz.offering.agent.repository.AgentRepository agentRepository =
        mock(com.hireai.domain.biz.offering.agent.repository.AgentRepository.class);
private final com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository apiKeyTaskRepository =
        mock(com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository.class);
private final com.hireai.application.biz.webhook.WebhookOutboxAppService webhookOutbox =
        mock(com.hireai.application.biz.webhook.WebhookOutboxAppService.class);
// Constructor now:
// new ValidationAppServiceImpl(validationDomainService, reportRepository, taskRepository,
//         settlementWriteAppService, agentRepository, apiKeyTaskRepository, webhookOutbox);

@Test void apiTaskPassAutoSettlesAndEnqueuesCompleted() {
    UUID taskId = UUID.randomUUID(), clientId = UUID.randomUUID(),
         versionId = UUID.randomUUID(), builderId = UUID.randomUUID();
    // task at RESULT_RECEIVED, api-attributed; report passes.
    TaskModel task = mock(TaskModel.class);
    when(task.id()).thenReturn(taskId);
    when(task.clientId()).thenReturn(clientId);
    when(task.agentVersionId()).thenReturn(versionId);
    when(task.budget()).thenReturn(com.hireai.domain.shared.model.Money.of("100"));
    TaskModel pending = mock(TaskModel.class);
    TaskModel resolved = mock(TaskModel.class);
    when(task.passValidation()).thenReturn(pending);
    when(pending.accept()).thenReturn(resolved);
    when(validationDomainService.validate(any(), any(), anyInt()))
            .thenReturn(passingReport()); // reuse the existing helper / a report whose isPass()==true
    when(apiKeyTaskRepository.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(UUID.randomUUID()));
    when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));

    TaskModel out = service.validateAndGate(task);

    assertThat(out).isSameAs(resolved);
    verify(settlementWriteAppService).settleAccepted(eq(taskId), eq(clientId), eq(builderId), any());
    verify(taskRepository).save(resolved);
    verify(webhookOutbox).enqueueCompleted(resolved);
    verify(taskRepository, never()).save(pending); // did NOT stop at PENDING_REVIEW
}

@Test void failEnqueuesFailedSpecViolation() {
    // ... existing FAIL setup; additionally:
    when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());
    // after service.validateAndGate(task):
    verify(webhookOutbox).enqueueFailed(any(), eq("SPEC_VIOLATION"));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=ValidationAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (compile — constructor arity; then assertion failures).

- [ ] **Step 3: Implement the branch** — replace the body of `validateAndGate` and add the 3 fields (after `settlementWriteAppService`, so `@RequiredArgsConstructor` order matches Step 1) plus imports (`AgentRepository`, `ApiKeyTaskRepository`, `WebhookOutboxAppService`, `DomainException`, `ResultCode`, `java.util.UUID`).

```java
private final ValidationDomainService validationDomainService;
private final ValidationReportRepository reportRepository;
private final TaskRepository taskRepository;
private final SettlementWriteAppService settlementWriteAppService;
private final com.hireai.domain.biz.offering.agent.repository.AgentRepository agentRepository;
private final com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository apiKeyTaskRepository;
private final com.hireai.application.biz.webhook.WebhookOutboxAppService webhookOutboxAppService;

@Override
public TaskModel validateAndGate(TaskModel task) {
    ValidationReportModel report =
            validationDomainService.validate(task.outputSpec(), task.result(), FIRST_ATTEMPT);
    reportRepository.save(report);

    if (report.isPass()) {
        TaskModel gated = task.passValidation(); // -> PENDING_REVIEW
        boolean apiSubmitted = apiKeyTaskRepository.findApiKeyIdByTask(task.id()).isPresent();
        if (apiSubmitted) {
            // Programmatic channel: deterministic immediate auto-settle. Reuse the accept settlement
            // (Invariant #3 — same money path, no LLM/human). Disputes stay human-channel only.
            UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                    .orElseThrow(() -> new com.hireai.utility.exception.DomainException(
                            com.hireai.utility.result.ResultCode.NOT_FOUND,
                            "No agent owner for version " + task.agentVersionId()));
            TaskModel resolved = gated.accept(); // PENDING_REVIEW -> RESOLVED
            settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());
            taskRepository.save(resolved);
            webhookOutboxAppService.enqueueCompleted(resolved);
            log.info("API task {} passed validation -> auto-settled RESOLVED (payout to builder {})",
                    task.id(), builderId);
            return resolved;
        }
        taskRepository.save(gated);
        log.info("Task {} passed validation -> PENDING_REVIEW", task.id());
        return gated;
    }
    TaskModel gated = task.failValidation();
    taskRepository.save(gated);
    settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
    webhookOutboxAppService.enqueueFailed(gated, "SPEC_VIOLATION");
    log.info("Task {} failed validation -> SPEC_VIOLATION (refunded)", task.id());
    return gated;
}
```

- [ ] **Step 4: Add an integration assertion** to `ValidationGateIntegrationTest` (CI-gated): an **API-submitted** task (seed an `api_key_task` row for it) that passes validation ends `RESOLVED` with a settlement recorded and one `PENDING task.completed` delivery row; a WEB task still ends `PENDING_REVIEW` with no delivery row. (Mirror the existing gate-integration setup; add the `api_key_task` seed via the Phase-3 attribution.)

- [ ] **Step 5: Run the FULL suite**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS (all existing tests green; new ones pass; ITs skip locally).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-main
git commit -m "feat(webhook): deterministic auto-settle for API tasks on validation pass + completed/failed enqueue"
```

---

### Task 14: Enqueue `task.failed` at the remaining terminal transitions

**Files:**
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/callback/impl/AgentCallbackAppServiceImpl.java` (non-COMPLETED → `FAILED`)
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/reliability/impl/TaskReliabilityAppServiceImpl.java` (`timeoutOne` → `TIMED_OUT`)
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java` (re-match exhaustion → `CANCELLED`)
- Test: update each service's existing `*Test` to verify the enqueue call.

**Interfaces:**
- Consumes: `WebhookOutboxAppService.enqueueFailed(TaskModel, String)` (Task 12).
- Produces: `task.failed` webhooks (reasons `FAILED`, `TIMED_OUT`, `CANCELLED`) enqueued **in the same
  transaction** as their existing `settleRejected` refund.

- [ ] **Step 1: Wire `AgentCallbackAppServiceImpl`** — inject `WebhookOutboxAppService`; after the non-COMPLETED `settleRejected`, enqueue:

```java
// field (added to the @RequiredArgsConstructor list, after settlementWriteAppService):
private final com.hireai.application.biz.webhook.WebhookOutboxAppService webhookOutboxAppService;

// inside the non-COMPLETED branch, immediately after settlementWriteAppService.settleRejected(...):
webhookOutboxAppService.enqueueFailed(failed, "FAILED");
```

- [ ] **Step 2: Wire `TaskReliabilityAppServiceImpl.timeoutOne`** — inject the outbox; after its `settleRejected` (the `timedOut` line):

```java
private final com.hireai.application.biz.webhook.WebhookOutboxAppService webhookOutboxAppService;
// after settlementWriteAppService.settleRejected(taskId, timedOut.clientId(), timedOut.budget());
webhookOutboxAppService.enqueueFailed(timedOut, "TIMED_OUT");
```

- [ ] **Step 3: Wire `TaskWriteAppServiceImpl`** (re-match exhaustion) — inject the outbox; after its `settleRejected` (the `cancelled` line ~110):

```java
private final com.hireai.application.biz.webhook.WebhookOutboxAppService webhookOutboxAppService;
// after settlementWriteAppService.settleRejected(taskId, cancelled.clientId(), cancelled.budget());
webhookOutboxAppService.enqueueFailed(cancelled, "CANCELLED");
```

- [ ] **Step 4: Update the three services' tests** — add a `@Mock WebhookOutboxAppService` (adjust each constructor's arg list to match the new field order) and, in the existing timeout/cancel/failed cases, assert `verify(webhookOutbox).enqueueFailed(any(), eq("<REASON>"))`. Where a test constructs the service directly, append the mock in declaration order.

- [ ] **Step 5: Run the FULL suite**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-main
git commit -m "feat(webhook): enqueue task.failed on FAILED/TIMED_OUT/CANCELLED terminal transitions"
```

---

### Task 15: Delivery app service (claim → SSRF → sign → send → backoff) + sweeper

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/webhook/WebhookDeliveryAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/webhook/impl/WebhookDeliveryAppServiceImpl.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/WebhookDeliverySweeper.java`
- Modify: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/config/DomainServiceConfig.java` (add `WebhookBackoffPolicy` bean)
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookDeliveryAppServiceImplTest.java`

**Interfaces:**
- Consumes: `WebhookDeliveryRepository`, `WebhookSubscriptionRepository`, `WebhookUrlValidatorPort` (Task 10),
  `WebhookSenderPort` (Task 11), `WebhookSignature` (Task 1), `WebhookBackoffPolicy` (Task 2), `Clock`.
- Produces:
  - `List<UUID> dueDeliveryIds()`;
  - `void attemptDelivery(UUID id)` — own transaction; claims the row (`FOR UPDATE SKIP LOCKED`), no-op if
    not claimable; SSRF-re-checks, signs, sends; 2xx → `DELIVERED`, else → backoff/`DEAD`;
  - `List<WebhookDeliveryModel> listForOwner(UUID ownerId, Instant since, String status, UUID taskId)`;
  - `void redeliver(UUID ownerId, UUID deliveryId)` — owner-scoped (`NOT_FOUND` if foreign), requeues to `PENDING`.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.application.biz.webhook.impl.WebhookDeliveryAppServiceImpl;
import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.application.port.webhook.WebhookSenderPort;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryAppServiceImplTest {
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookSubscriptionRepository subs = mock(WebhookSubscriptionRepository.class);
    private final WebhookUrlValidatorPort validator = mock(WebhookUrlValidatorPort.class);
    private final WebhookSenderPort sender = mock(WebhookSenderPort.class);
    private final WebhookBackoffPolicy backoff = new WebhookBackoffPolicy(10, 3600, 3);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);
    private final WebhookDeliveryAppServiceImpl svc =
            new WebhookDeliveryAppServiceImpl(deliveries, subs, validator, sender, backoff, clock, 50);

    private final UUID id = UUID.randomUUID(), subId = UUID.randomUUID(), owner = UUID.randomUUID();

    private WebhookDeliveryModel pending() {
        return WebhookDeliveryModel.enqueue(id, UUID.randomUUID(), owner, subId,
                WebhookEventType.TASK_COMPLETED, "{\"x\":1}", "https://c/cb", clock.instant());
    }
    private WebhookSubscriptionModel sub() {
        return WebhookSubscriptionModel.rehydrate(subId, UUID.randomUUID(), owner, "https://c/cb", "whsec_s",
                true, clock.instant(), clock.instant());
    }

    @Test void notClaimableIsNoOp() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.empty());
        svc.attemptDelivery(id);
        verifyNoInteractions(sender);
        verify(deliveries, never()).save(any());
    }

    @Test void successMarksDelivered() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.of(pending()));
        when(subs.findById(subId)).thenReturn(Optional.of(sub()));
        when(sender.send(eq("https://c/cb"), eq("{\"x\":1}"), startsWith("t="), eq(id.toString()), eq("task.completed")))
                .thenReturn(WebhookSendResult.ok(200));
        svc.attemptDelivery(id);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test void serverErrorBacksOff() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.of(pending()));
        when(subs.findById(subId)).thenReturn(Optional.of(sub()));
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(WebhookSendResult.fail(500, "boom"));
        svc.attemptDelivery(id);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().attempts()).isEqualTo(1);
        assertThat(cap.getValue().lastError()).contains("500");
    }

    @Test void ssrfRejectionRecordsFailureWithoutSending() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.of(pending()));
        when(subs.findById(subId)).thenReturn(Optional.of(sub()));
        doThrow(new DomainException(ResultCode.VALIDATION_ERROR, "private")).when(validator).assertDeliverable(any());
        svc.attemptDelivery(id);
        verifyNoInteractions(sender);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().lastError()).contains("SSRF");
    }

    @Test void redeliverIsOwnerScoped() {
        WebhookDeliveryModel foreign = WebhookDeliveryModel.enqueue(id, UUID.randomUUID(),
                UUID.randomUUID(), subId, WebhookEventType.TASK_FAILED, "{}", "https://c/cb", clock.instant());
        when(deliveries.findById(id)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> svc.redeliver(owner, id))
                .isInstanceOf(DomainException.class); // owner mismatch -> NOT_FOUND
    }

    @Test void redeliverRequeuesOwnedRow() {
        when(deliveries.findById(id)).thenReturn(Optional.of(pending()));
        svc.redeliver(owner, id);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().attempts()).isZero();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookDeliveryAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — service not found.

- [ ] **Step 3: Implement the interface**

```java
package com.hireai.application.biz.webhook;

import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryAppService {
    List<UUID> dueDeliveryIds();
    void attemptDelivery(UUID id);
    List<WebhookDeliveryModel> listForOwner(UUID ownerId, Instant since, String status, UUID taskId);
    void redeliver(UUID ownerId, UUID deliveryId);
}
```

- [ ] **Step 4: Implement**

```java
package com.hireai.application.biz.webhook.impl;

import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.application.port.webhook.WebhookSenderPort;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.WebhookSignature;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class WebhookDeliveryAppServiceImpl implements WebhookDeliveryAppService {

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookUrlValidatorPort urlValidator;
    private final WebhookSenderPort sender;
    private final WebhookBackoffPolicy backoff;
    private final Clock clock;
    private final int batchSize;

    public WebhookDeliveryAppServiceImpl(WebhookDeliveryRepository deliveries,
            WebhookSubscriptionRepository subscriptions, WebhookUrlValidatorPort urlValidator,
            WebhookSenderPort sender, WebhookBackoffPolicy backoff, Clock clock,
            @Value("${hireai.webhooks.batch-size:50}") int batchSize) {
        this.deliveries = deliveries; this.subscriptions = subscriptions; this.urlValidator = urlValidator;
        this.sender = sender; this.backoff = backoff; this.clock = clock; this.batchSize = batchSize;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dueDeliveryIds() {
        return deliveries.findDueIds(clock.instant(), batchSize);
    }

    @Override
    @Transactional
    public void attemptDelivery(UUID id) {
        Instant now = clock.instant();
        var claimed = deliveries.claimForUpdate(id, now);
        if (claimed.isEmpty()) return; // taken by another sweeper / already handled
        WebhookDeliveryModel d = claimed.get();

        WebhookSubscriptionModel sub = subscriptions.findById(d.subscriptionId()).orElse(null);
        if (sub == null || !sub.active()) {
            deliveries.save(d.recordFailure(now, "subscription inactive", backoff));
            return;
        }
        try {
            urlValidator.assertDeliverable(d.targetUrl());
        } catch (DomainException e) {
            deliveries.save(d.recordFailure(now, "SSRF: " + e.getMessage(), backoff));
            return;
        }
        String header = WebhookSignature.header(sub.signingSecret(), now.getEpochSecond(), d.payload());
        WebhookSendResult r = sender.send(d.targetUrl(), d.payload(), header,
                d.id().toString(), d.eventType().wire());
        if (r.success()) {
            deliveries.save(d.markDelivered(now));
        } else {
            String err = r.error() != null ? r.error() : ("HTTP " + r.statusCode());
            deliveries.save(d.recordFailure(now, err, backoff));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryModel> listForOwner(UUID ownerId, Instant since, String status, UUID taskId) {
        return deliveries.findForOwner(ownerId, since, status, taskId);
    }

    @Override
    @Transactional
    public void redeliver(UUID ownerId, UUID deliveryId) {
        WebhookDeliveryModel d = deliveries.findById(deliveryId)
                .filter(x -> x.ownerId().equals(ownerId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Delivery not found: " + deliveryId));
        deliveries.save(d.requeue(clock.instant()));
    }
}
```

- [ ] **Step 5: Add the `WebhookBackoffPolicy` bean** to `DomainServiceConfig`

```java
@org.springframework.context.annotation.Bean
public com.hireai.domain.biz.webhook.WebhookBackoffPolicy webhookBackoffPolicy(
        @org.springframework.beans.factory.annotation.Value("${hireai.webhooks.base-backoff-seconds:10}") long base,
        @org.springframework.beans.factory.annotation.Value("${hireai.webhooks.cap-backoff-seconds:3600}") long cap,
        @org.springframework.beans.factory.annotation.Value("${hireai.webhooks.max-attempts:28}") int max) {
    return new com.hireai.domain.biz.webhook.WebhookBackoffPolicy(base, cap, max);
}
```

- [ ] **Step 6: Implement the sweeper** (mirror `RulingAcceptSweeper` — separate bean → each `attemptDelivery` is its own transaction)

```java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Delivers due webhook rows. Each {@code attemptDelivery(id)} is a cross-bean call (own transaction),
 * so one poisoned row can't roll back others in the same pass, and the row-level FOR UPDATE SKIP LOCKED
 * makes it safe under multiple instances. Delivery never touches money/task tables.
 */
@Slf4j
@Component
@Profile("!test")
public class WebhookDeliverySweeper {

    private final WebhookDeliveryAppService deliveryAppService;

    public WebhookDeliverySweeper(WebhookDeliveryAppService deliveryAppService) {
        this.deliveryAppService = deliveryAppService;
    }

    @Scheduled(fixedDelayString = "${hireai.webhooks.sweep-interval:PT5S}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one delivery pass. */
    void sweep() {
        List<UUID> due = deliveryAppService.dueDeliveryIds();
        for (UUID id : due) {
            try {
                deliveryAppService.attemptDelivery(id);
            } catch (Exception e) {
                log.warn("Webhook sweeper: delivery {} failed unexpectedly", id, e);
            }
        }
        if (!due.isEmpty()) log.info("Webhook sweeper: attempted {} delivery(ies)", due.size());
    }
}
```

- [ ] **Step 7: Run to verify it passes + full suite**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookDeliveryAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Then: `mvn -f backend/pom.xml -q -B test`
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/hireai-application backend/hireai-infrastructure backend/hireai-main
git commit -m "feat(webhook): delivery app service (claim/sign/send/backoff, list, redeliver) + sweeper"
```

---

### Task 16: Subscription management app service

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/webhook/WebhookSubscriptionAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/webhook/impl/WebhookSubscriptionAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookSubscriptionAppServiceImplTest.java`

**Interfaces:**
- Consumes: `WebhookSubscriptionRepository` (Task 5), `WebhookSecretGenerator` (Task 5),
  `WebhookUrlValidatorPort` (Task 10), `ApiKeyRepository.findById` (Phase 3, `ApiKeyModel.userId()`), `Clock`.
- Produces (all owner-scoped; a foreign key → `NOT_FOUND`, Invariant #5):
  - `WebhookSubscriptionModel register(UUID ownerId, UUID apiKeyId, String callbackUrl)` — validates the URL,
    asserts the key belongs to the owner, deactivates any existing active sub for that key, creates a new one
    (fresh secret). Returns the model **including the secret**.
  - `WebhookSubscriptionModel get(UUID ownerId, UUID apiKeyId)`;
  - `WebhookSubscriptionModel rotateSecret(UUID ownerId, UUID apiKeyId)`;
  - `void deactivate(UUID ownerId, UUID apiKeyId)`.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.webhook;

import com.hireai.application.biz.webhook.impl.WebhookSubscriptionAppServiceImpl;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.domain.biz.webhook.service.WebhookSecretGenerator;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookSubscriptionAppServiceImplTest {
    private final WebhookSubscriptionRepository repo = mock(WebhookSubscriptionRepository.class);
    private final WebhookSecretGenerator secrets = mock(WebhookSecretGenerator.class);
    private final WebhookUrlValidatorPort validator = mock(WebhookUrlValidatorPort.class);
    private final ApiKeyRepository apiKeys = mock(ApiKeyRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);
    private final WebhookSubscriptionAppServiceImpl svc =
            new WebhookSubscriptionAppServiceImpl(repo, secrets, validator, apiKeys, clock);

    private final UUID owner = UUID.randomUUID(), keyId = UUID.randomUUID();

    private void keyOwnedBy(UUID ownerId) {
        ApiKeyModel k = mock(ApiKeyModel.class);
        when(k.userId()).thenReturn(ownerId);
        when(apiKeys.findById(keyId)).thenReturn(Optional.of(k));
    }

    @Test void registerValidatesUrlAssertsOwnershipAndReturnsSecret() {
        keyOwnedBy(owner);
        when(secrets.generate()).thenReturn("whsec_new");
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        WebhookSubscriptionModel out = svc.register(owner, keyId, "https://c/cb");

        verify(validator).assertDeliverable("https://c/cb");
        assertThat(out.signingSecret()).isEqualTo("whsec_new");
        assertThat(out.callbackUrl()).isEqualTo("https://c/cb");
        assertThat(out.active()).isTrue();
    }

    @Test void registerRejectsForeignKey() {
        keyOwnedBy(UUID.randomUUID()); // someone else's key
        assertThatThrownBy(() -> svc.register(owner, keyId, "https://c/cb"))
                .isInstanceOf(DomainException.class);
        verify(repo, never()).save(any());
    }

    @Test void registerDeactivatesExistingActiveForKey() {
        keyOwnedBy(owner);
        when(secrets.generate()).thenReturn("whsec_new");
        WebhookSubscriptionModel existing = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner,
                "https://old", "whsec_old", clock.instant());
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.register(owner, keyId, "https://c/cb");

        // saved twice: the deactivated old + the new active
        verify(repo, times(2)).save(any());
    }

    @Test void rotateSecretReplacesSecret() {
        keyOwnedBy(owner);
        WebhookSubscriptionModel active = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner,
                "https://c/cb", "whsec_old", clock.instant());
        when(repo.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(active));
        when(secrets.generate()).thenReturn("whsec_rotated");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        WebhookSubscriptionModel out = svc.rotateSecret(owner, keyId);
        assertThat(out.signingSecret()).isEqualTo("whsec_rotated");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSubscriptionAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — service not found.

- [ ] **Step 3: Implement the interface**

```java
package com.hireai.application.biz.webhook;

import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import java.util.UUID;

public interface WebhookSubscriptionAppService {
    WebhookSubscriptionModel register(UUID ownerId, UUID apiKeyId, String callbackUrl);
    WebhookSubscriptionModel get(UUID ownerId, UUID apiKeyId);
    WebhookSubscriptionModel rotateSecret(UUID ownerId, UUID apiKeyId);
    void deactivate(UUID ownerId, UUID apiKeyId);
}
```

- [ ] **Step 4: Implement**

```java
package com.hireai.application.biz.webhook.impl;

import com.hireai.application.biz.webhook.WebhookSubscriptionAppService;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.domain.biz.webhook.service.WebhookSecretGenerator;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class WebhookSubscriptionAppServiceImpl implements WebhookSubscriptionAppService {

    private final WebhookSubscriptionRepository repository;
    private final WebhookSecretGenerator secretGenerator;
    private final WebhookUrlValidatorPort urlValidator;
    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    @Override
    public WebhookSubscriptionModel register(UUID ownerId, UUID apiKeyId, String callbackUrl) {
        urlValidator.assertDeliverable(callbackUrl);
        assertKeyOwned(ownerId, apiKeyId);
        Instant now = clock.instant();
        repository.findActiveByApiKeyId(apiKeyId).ifPresent(s -> repository.save(s.deactivate(now)));
        WebhookSubscriptionModel sub = WebhookSubscriptionModel.create(
                UUID.randomUUID(), apiKeyId, ownerId, callbackUrl, secretGenerator.generate(), now);
        return repository.save(sub);
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscriptionModel get(UUID ownerId, UUID apiKeyId) {
        assertKeyOwned(ownerId, apiKeyId);
        return activeForKey(apiKeyId);
    }

    @Override
    public WebhookSubscriptionModel rotateSecret(UUID ownerId, UUID apiKeyId) {
        assertKeyOwned(ownerId, apiKeyId);
        WebhookSubscriptionModel active = activeForKey(apiKeyId);
        return repository.save(active.rotateSecret(secretGenerator.generate(), clock.instant()));
    }

    @Override
    public void deactivate(UUID ownerId, UUID apiKeyId) {
        assertKeyOwned(ownerId, apiKeyId);
        WebhookSubscriptionModel active = activeForKey(apiKeyId);
        repository.save(active.deactivate(clock.instant()));
    }

    private WebhookSubscriptionModel activeForKey(UUID apiKeyId) {
        return repository.findActiveByApiKeyId(apiKeyId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No active webhook subscription"));
    }

    private void assertKeyOwned(UUID ownerId, UUID apiKeyId) {
        boolean owned = apiKeyRepository.findById(apiKeyId)
                .map(k -> k.userId().equals(ownerId)).orElse(false);
        if (!owned) throw new DomainException(ResultCode.NOT_FOUND, "API key not found: " + apiKeyId);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main test -Dtest=WebhookSubscriptionAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application backend/hireai-main
git commit -m "feat(webhook): subscription management app service (register/get/rotate/deactivate, owner-scoped)"
```

---

### Task 17: Controllers + DTOs + `SecurityConfig` allow-list ⚠️ security-config

**Files:**
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/webhook/WebhookSubscriptionController.java` (+ DTOs)
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/webhook/WebhookDeliveryController.java` (+ DTOs + converter)
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/config/SecurityConfig.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/webhook/WebhookControllerSliceTest.java` (`@WebMvcTest`)
- Test (full-app, CI): `backend/hireai-main/src/test/java/com/hireai/apikey/ProgrammaticSubmissionIntegrationTest.java` (extend — the authoritative allow-list check)

**Interfaces:**
- Consumes: `WebhookSubscriptionAppService` (Task 16), `WebhookDeliveryAppService` (Task 15),
  `CurrentUserProvider.currentUserId()`, `BaseController.ok(...)` (extend `BaseController`, mirror `DisputeController`).
- Produces the endpoints in spec §9; the API-key allow-list no longer includes accept/reject.

> **⚠️ SecurityConfig change** — see the post-mortem. The full app returns **401** for authenticated-but-forbidden. **Run the FULL backend suite** after this task. The `securedFilterChain` bean constructor is unchanged (`JwtService`, `ApiKeyAuthService`), so existing slices need no new `@MockBean`.

- [ ] **Step 1: Edit `SecurityConfig.securedFilterChain`** — remove accept/reject from the API-key line; add the webhook rules. Replace the relevant matchers:

```java
// BEFORE (remove accept/reject from this line):
//   .requestMatchers(HttpMethod.POST, "/api/tasks/*/accept", "/api/tasks/*/reject").hasAnyRole("CLIENT","API_CLIENT")
// AFTER — accept/reject become human-only (they fall through to anyRequest hasAnyRole CLIENT/BUILDER/ADMIN):
.requestMatchers(org.springframework.http.HttpMethod.POST,
        "/api/tasks/*/accept", "/api/tasks/*/reject").hasAnyRole("CLIENT", "BUILDER", "ADMIN")
// Subscription management is JWT-only (a leaked key cannot repoint the callback):
.requestMatchers("/api/webhooks/subscription/**").hasRole("CLIENT")
// Delivery log + redeliver: reachable by a human CLIENT or an API_CLIENT key (reconcile/replay headless):
.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/webhooks/deliveries").hasAnyRole("CLIENT", "API_CLIENT")
.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/webhooks/deliveries/*/redeliver").hasAnyRole("CLIENT", "API_CLIENT")
```

> Note: the explicit accept/reject line above is equivalent to letting them fall through to `anyRequest().hasAnyRole("CLIENT","BUILDER","ADMIN")`. Keeping the line explicit documents intent. Either is correct — an API_CLIENT is denied (401) at the full app.

- [ ] **Step 2: Write the DTOs + controllers**

```java
// WebhookSubscriptionController.java
package com.hireai.controller.biz.webhook;

import com.hireai.application.biz.webhook.WebhookSubscriptionAppService;
import com.hireai.application.port.security.CurrentUserProvider;
import com.hireai.controller.base.BaseController;   // adjust import to the real BaseController package
import com.hireai.controller.base.WebResult;        // adjust to the real WebResult package
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

record RegisterSubscriptionRequest(UUID apiKeyId, String callbackUrl) {}
record SubscriptionDTO(UUID id, UUID apiKeyId, String callbackUrl, String signingSecret,
                       boolean active, Instant createdAt, Instant updatedAt) {
    static SubscriptionDTO of(WebhookSubscriptionModel s) {
        return new SubscriptionDTO(s.id(), s.apiKeyId(), s.callbackUrl(), s.signingSecret(),
                s.active(), s.createdAt(), s.updatedAt());
    }
}

@RestController
@RequestMapping("/api/webhooks/subscription")
public class WebhookSubscriptionController extends BaseController {
    private final WebhookSubscriptionAppService service;
    private final CurrentUserProvider currentUser;
    public WebhookSubscriptionController(WebhookSubscriptionAppService service, CurrentUserProvider currentUser) {
        this.service = service; this.currentUser = currentUser;
    }
    @PostMapping
    public WebResult<SubscriptionDTO> register(@RequestBody RegisterSubscriptionRequest req) {
        return ok(SubscriptionDTO.of(service.register(currentUser.currentUserId(), req.apiKeyId(), req.callbackUrl())));
    }
    @GetMapping
    public WebResult<SubscriptionDTO> get(@RequestParam("apiKeyId") UUID apiKeyId) {
        return ok(SubscriptionDTO.of(service.get(currentUser.currentUserId(), apiKeyId)));
    }
    @PostMapping("/rotate-secret")
    public WebResult<SubscriptionDTO> rotate(@RequestParam("apiKeyId") UUID apiKeyId) {
        return ok(SubscriptionDTO.of(service.rotateSecret(currentUser.currentUserId(), apiKeyId)));
    }
    @PostMapping("/deactivate")
    public WebResult<Void> deactivate(@RequestParam("apiKeyId") UUID apiKeyId) {
        service.deactivate(currentUser.currentUserId(), apiKeyId);
        return ok(null);
    }
}
```
```java
// WebhookDeliveryController.java
package com.hireai.controller.biz.webhook;

import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import com.hireai.application.port.security.CurrentUserProvider;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

record DeliveryDTO(UUID eventId, UUID taskId, String eventType, String status, int attempts,
                   Instant nextAttemptAt, Instant createdAt, Instant deliveredAt, String lastError) {
    static DeliveryDTO of(WebhookDeliveryModel d) {
        return new DeliveryDTO(d.id(), d.taskId(), d.eventType().wire(), d.status().name(), d.attempts(),
                d.nextAttemptAt(), d.createdAt(), d.deliveredAt(), d.lastError());
    }
}

@RestController
@RequestMapping("/api/webhooks/deliveries")
public class WebhookDeliveryController extends BaseController {
    private final WebhookDeliveryAppService service;
    private final CurrentUserProvider currentUser;
    public WebhookDeliveryController(WebhookDeliveryAppService service, CurrentUserProvider currentUser) {
        this.service = service; this.currentUser = currentUser;
    }
    @GetMapping
    public WebResult<List<DeliveryDTO>> list(
            @RequestParam(value = "since", required = false) Instant since,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "taskId", required = false) UUID taskId) {
        Instant from = since != null ? since : Instant.now().minus(7, ChronoUnit.DAYS);
        List<DeliveryDTO> rows = service.listForOwner(currentUser.currentUserId(), from, status, taskId)
                .stream().map(DeliveryDTO::of).toList();
        return ok(rows);
    }
    @PostMapping("/{id}/redeliver")
    public WebResult<Void> redeliver(@PathVariable("id") UUID id) {
        service.redeliver(currentUser.currentUserId(), id);
        return ok(null);
    }
}
```

> **Import note:** confirm the real packages of `BaseController`, `WebResult`, `CurrentUserProvider` by looking at `DisputeController.java` (Task-2 read shows `extends BaseController`, `import ...port.security.CurrentUserProvider`, `ok(...)`). Fix the imports above to match.

- [ ] **Step 3: Write the slice test** (`@WebMvcTest` — mirror an existing controller slice test, e.g. the api-key or dispute controller test, for the `@WebMvcTest` + `@MockBean` + `@ActiveProfiles`/security setup)

```java
// WebhookControllerSliceTest.java — @WebMvcTest(controllers = {WebhookSubscriptionController.class, WebhookDeliveryController.class})
// @MockBean WebhookSubscriptionAppService, WebhookDeliveryAppService, JwtService, ApiKeyAuthService (+ CurrentUserProvider as the test profile provides)
// Assert:
//  - POST /api/webhooks/subscription with a CLIENT returns 200 and echoes the secret
//  - GET /api/webhooks/deliveries with a CLIENT returns 200 with the mapped rows
//  - POST /api/webhooks/deliveries/{id}/redeliver delegates to service.redeliver(ownerId, id)
```

- [ ] **Step 4: Extend `ProgrammaticSubmissionIntegrationTest`** (full-app, CI-gated) — the authoritative allow-list check:

```java
// Add assertions (an API-key authenticated request):
//  - POST /api/tasks/{id}/accept  -> 401 (removed from the API-key allow-list)
//  - POST /api/tasks/{id}/reject  -> 401
//  - GET  /api/webhooks/deliveries -> 200 (reachable by the API key)
//  - POST /api/webhooks/subscription -> 401 (JWT-only)
// Expect 401 (NOT 403) for the forbidden ones — full-app convention (post-mortem).
```

- [ ] **Step 5: Run the FULL suite** (mandatory after a SecurityConfig change)

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS — all existing tests green (no slice needed a new `@MockBean`; the secured-chain constructor is unchanged).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-controller backend/hireai-main
git commit -m "feat(webhook): subscription + delivery controllers; drop accept/reject from API allow-list"
```

---

### Task 18: Frontend — `/client/webhooks` console

**Files:**
- Modify: `frontend/lib/types.ts` — add `WebhookSubscriptionDTO`, `WebhookDeliveryDTO`.
- Create: `frontend/app/client/webhooks/page.tsx`
- Create: `frontend/app/client/webhooks/page.test.tsx`
- Modify: `frontend/components/Nav.tsx` — CLIENT nav link "Webhooks" next to "API keys".

**Interfaces:**
- Consumes: the `api()` client (JWT-bearing), the `Modal` + UI kit, the auth context (mirror
  `frontend/app/client/keys/page.tsx` — reveal-once pattern, `localStorage`, `useCallback` for effect deps).
- Produces: a page to pick an API key, register/replace a callback URL, reveal + rotate the signing secret,
  deactivate, and view the delivery log with a **Resend** button per row.

- [ ] **Step 1: Add the DTO types** (`frontend/lib/types.ts`)

```typescript
export type WebhookSubscriptionDTO = {
  id: string; apiKeyId: string; callbackUrl: string; signingSecret: string;
  active: boolean; createdAt: string; updatedAt: string;
};
export type WebhookDeliveryDTO = {
  eventId: string; taskId: string; eventType: "task.completed" | "task.failed";
  status: "PENDING" | "DELIVERED" | "DEAD"; attempts: number;
  nextAttemptAt: string; createdAt: string; deliveredAt: string | null; lastError: string | null;
};
```

- [ ] **Step 2: Write the failing test** (`page.test.tsx`) — mirror `frontend/app/client/keys/page.test.tsx` for the `api()` mock + render setup.

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi, describe, it, expect, beforeEach } from "vitest";
import WebhooksPage from "./page";
// mock ../../../lib/api the same way the keys page test does

describe("WebhooksPage", () => {
  beforeEach(() => vi.clearAllMocks());

  it("registers a callback and reveals the signing secret", async () => {
    // api() POST /api/webhooks/subscription -> { id, apiKeyId, callbackUrl, signingSecret: "whsec_shown", active:true }
    render(<WebhooksPage />);
    await userEvent.type(screen.getByLabelText(/callback url/i), "https://c.example.com/cb");
    await userEvent.click(screen.getByRole("button", { name: /register|save/i }));
    await waitFor(() => expect(screen.getByText(/whsec_shown/)).toBeInTheDocument());
  });

  it("renders the delivery log with a Resend button for a failed row", async () => {
    // api() GET /api/webhooks/deliveries -> [{ eventId, taskId, eventType:"task.failed", status:"DEAD", attempts:28, ... }]
    render(<WebhooksPage />);
    await waitFor(() => expect(screen.getByText(/DEAD/)).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /resend/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run (in `frontend/`): `npx vitest run app/client/webhooks/page.test.tsx`
Expected: FAIL — page not found.

- [ ] **Step 4: Implement `page.tsx`** — mirror `/client/keys` structure. Sections: (a) a key selector (list the owner's keys via the existing keys endpoint) + a callback-URL form → `POST /api/webhooks/subscription`; on success show the returned `signingSecret` (retrievable, so also shown via `GET /api/webhooks/subscription?apiKeyId=`), a **Rotate secret** button (`POST /api/webhooks/subscription/rotate-secret`), a **Deactivate** button; (b) a delivery-log table from `GET /api/webhooks/deliveries` (status badge, event type, task id, attempts, last error, time) with a **Resend** button → `POST /api/webhooks/deliveries/{eventId}/redeliver`; a health banner when any recent row is `DEAD`. Use `useCallback` for any fetch used in `useEffect` (lint: exhaustive-deps — see `frontend-verify-run-lint`).

- [ ] **Step 5: Add the nav link** in `components/Nav.tsx` — a CLIENT-surface entry `Webhooks → /client/webhooks` beside "API keys".

- [ ] **Step 6: Run tests + lint + build**

Run (in `frontend/`): `npx vitest run app/client/webhooks/page.test.tsx` then `npm run lint` then `npm run build`
Expected: PASS / 0 lint errors / build OK.

- [ ] **Step 7: Commit**

```bash
git add frontend/lib/types.ts frontend/app/client/webhooks frontend/components/Nav.tsx
git commit -m "feat(frontend): /client/webhooks console (register, reveal/rotate secret, delivery log, resend)"
```

---

### Task 19: Frontend — delivery status on the task views

**Files:**
- Create: `frontend/components/WebhookDeliveryStatus.tsx`
- Create: `frontend/components/WebhookDeliveryStatus.test.tsx`
- Modify: the client **task-detail** page (find it: `frontend/app/client/tasks/[id]/page.tsx` or the equivalent that renders a single task) — mount `<WebhookDeliveryStatus taskId={id} />`.

**Interfaces:**
- Consumes: `api()` client, `WebhookDeliveryDTO` (Task 18), backed by `GET /api/webhooks/deliveries?taskId={id}`.
- Produces: a compact indicator — *Delivered* / *Pending* / **Failed** (+ a **Resend** action when failed) —
  that renders **nothing** when the task has no deliveries (WEB task / no subscription).

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import WebhookDeliveryStatus from "./WebhookDeliveryStatus";
// mock ../lib/api

describe("WebhookDeliveryStatus", () => {
  beforeEach(() => vi.clearAllMocks());

  it("renders nothing when there are no deliveries", async () => {
    // api() GET /api/webhooks/deliveries?taskId=t1 -> []
    const { container } = render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("shows Failed + Resend when the latest delivery is DEAD", async () => {
    // api() GET ...?taskId=t1 -> [{ eventId:"e1", status:"DEAD", eventType:"task.completed", ... }]
    render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(screen.getByText(/failed/i)).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /resend/i })).toBeInTheDocument();
  });

  it("shows Delivered with no action", async () => {
    // api() GET ...?taskId=t1 -> [{ eventId:"e1", status:"DELIVERED", ... }]
    render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(screen.getByText(/delivered/i)).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /resend/i })).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (in `frontend/`): `npx vitest run components/WebhookDeliveryStatus.test.tsx`
Expected: FAIL — component not found.

- [ ] **Step 3: Implement `WebhookDeliveryStatus.tsx`** — fetch `GET /api/webhooks/deliveries?taskId={taskId}` in a `useEffect` (wrap the fetch in `useCallback`); if the list is empty, return `null`; otherwise take the most recent row and render a small labeled badge (`DELIVERED`→"Delivered", `PENDING`→"Pending", `DEAD`→"Failed"); when `DEAD`, render a **Resend** button → `POST /api/webhooks/deliveries/{eventId}/redeliver`, then re-fetch. Reuse the badge styling from the kit (do **not** borrow the RESOLVED/CANCELLED task colors — use a delivery-specific tone).

- [ ] **Step 4: Mount it on the task-detail page** — add `<WebhookDeliveryStatus taskId={task.id} />` near the task status/result section. Renders nothing for non-webhook tasks, so it is safe on every task.

- [ ] **Step 5: Run tests + lint + build**

Run (in `frontend/`): `npx vitest run components/WebhookDeliveryStatus.test.tsx` then `npm run lint` then `npm run build`
Expected: PASS / 0 lint / build OK.

- [ ] **Step 6: Commit**

```bash
git add frontend/components/WebhookDeliveryStatus.tsx frontend/components/WebhookDeliveryStatus.test.tsx frontend/app
git commit -m "feat(frontend): per-task webhook delivery indicator + resend on the task view"
```

---

## Final verification (after all tasks)

- [ ] **Backend full suite:** `mvn -f backend/pom.xml -q -B test` → BUILD SUCCESS (ITs skip locally; run in CI).
- [ ] **Frontend gate:** in `frontend/` — `npx vitest run` && `npm run lint` && `npm run build`.
- [ ] **Live E2E (per [[verify-with-real-e2e]]):** boot the backend against Supabase; register a webhook pointing at a local HTTPS receiver (tunnel/request-bin); submit a task via API key; confirm a **valid signed** `task.completed` arrives and the fetched result matches; kill the receiver to force `DEAD`, then confirm poll + `redeliver` recover it. **Assert 401 (not 403)** for the removed accept/reject over the API key — full-app convention (post-mortem).
- [ ] **Security review:** run the `security-reviewer` agent over the branch before opening the PR (auth/credential + outbound-HTTP + SSRF surface). Verify: no signing secret logged; SSRF re-checked at send; allow-list matches the spec; delivery path writes no money tables.
- [ ] **Spec parity:** re-read `docs/superpowers/specs/2026-07-19-push-webhooks-design.md` §4 decision ledger against the built behavior.

---

## Self-Review (plan author — completed)

**Spec coverage:** deterministic auto-settle (Task 13) · WEB unchanged (Task 13) · disputes stay human-only (no API dispute endpoints added — Task 17 removes accept/reject) · `task.completed`/`task.failed` thin events (Tasks 4, 12) · outbox atomic enqueue (Task 12) · sweeper claim/SSRF/HMAC/backoff/DEAD (Tasks 10, 11, 15) · Stripe-style signing (Task 1) · retrievable secret + rotate (Tasks 5, 16) · per-key subscription (Tasks 5, 7, 16) · V26 schema (Task 6) · reconciliation: poll (existing) + deliveries-list + redeliver + long retry (Tasks 15, 17) + dashboard/task-view visibility (Tasks 18, 19) · allow-list change (Task 17) · invariants preserved (money tables untouched; delivery path writes no ledger). **All spec sections map to a task.**

**Placeholder scan:** no "TBD"/"handle edge cases". The few "mirror existing file X" notes point to concrete named files (`DisputeRepositoryIntegrationTest`, `/client/keys/page.tsx`, `DisputeController`) for **project test/UI infrastructure**, with the task-specific logic given in full — these are boilerplate-reuse pointers, not missing plan content.

**Type consistency:** `WebhookOutboxAppService.enqueueCompleted/enqueueFailed` (Task 12) match their call sites (Tasks 13, 14). `WebhookDeliveryModel` transition names (`enqueue/markDelivered/recordFailure/requeue/rehydrate`) are consistent across Tasks 5, 8, 12, 15. `findApiKeyIdByTask` (Task 9) is consumed by Task 12. `settleAccepted(taskId, clientId, builderId, budget)` (Task 13) matches the verified `SettlementWriteAppService` signature. `WebhookSendResult(success, statusCode, error)` (Task 11) matches its use in Task 15.
