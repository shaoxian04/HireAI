# Agent Registration (Module 2, Track A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Agent Registration aggregate (register an Agent + its first version, activate it, list/get owned Agents) end-to-end, mirroring the existing Task aggregate exactly, and expose the `findActiveCandidates` read the routing module consumes.

**Architecture:** A new DDD bounded context `agent` spanning all four layers (`controller → application → domain ← infrastructure`). `AgentModel` (root) owns an `AgentVersionModel` child plus `OutputSpec`/`Pricing` value objects; state transitions are immutable guarded methods returning new copies. Two framework-free domain services (`AgentRegisterDomainService`, `AgentActivateDomainService`) are registered in `DomainServiceConfig`; CQRS write/read app services orchestrate persistence; one repository per root (interface in domain, JPA impl in infrastructure) including a GIN-array-overlap `findActiveCandidates` query. Identity comes server-side from `CurrentUserProvider`; HTTPS-only webhook is enforced in the domain.

**Tech Stack:** Java 21, Spring Boot 3.x, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + Testcontainers.

---

## Conventions this plan follows (verified against the existing Task/Wallet code)

- DDD layering `controller → application → domain ← infrastructure`. The domain layer has **zero** framework imports (the single allowed exception, mirrored from Task/Wallet, is importing `com.hireai.controller.base.ResultCode` solely to construct a `DomainException` — see `DomainException` Javadoc).
- Every service class = **interface + `impl/`**. App-service interface: `@Validated` + JSpecify `@NonNull` params. App-service impl: `@Service @Slf4j @RequiredArgsConstructor @Transactional`. Domain service: interface + `impl/`, **no Spring annotations**, registered in `DomainServiceConfig`.
- One repository per aggregate root (interface in `domain/.../repository`, impl in `infrastructure/repository/...`). Children are persisted through the root.
- Aggregate state transitions are **immutable**: each returns a NEW `AgentModel`; illegal transitions throw `DomainException`.
- JSONB columns: `String` field + `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"` on the JPA entity (REQUIRED — never remove).
- `TEXT[]` columns: `List<String>` field + `@JdbcTypeCode(SqlTypes.ARRAY)` + `columnDefinition = "text[]"` on the JPA entity.
- Money is `BigDecimal` / `NUMERIC`, never float (use the existing `Money` VO where a domain amount is modelled; price uses `BigDecimal` directly since the contract record `AgentCandidate.price` is `BigDecimal`).
- Every table has `gmt_create` / `gmt_modified`.
- Owner identity from `CurrentUserProvider.currentUserId()` (NEVER from path/body) + explicit owner checks.
- Tests: JUnit 5 + AssertJ. Unit tests run via `mvn -f backend/pom.xml -B test`. Integration tests named `*IntegrationTest` use Testcontainers and auto-skip without Docker (`@EnabledIf("dockerAvailable")`).
- Conventional-commit messages. Do NOT add `Co-Authored-By` lines.

## Package map (everything this plan creates)

```
domain/biz/agent/
  enums/AgentStatus.java
  model/Pricing.java
  model/AgentVersionModel.java
  model/AgentModel.java
  info/AgentRegisterInfo.java
  info/AgentCandidate.java                 (SHARED contract type — Plan 0 may have created it; this plan creates it if absent)
  event/AgentRegisteredDomainEvent.java
  event/AgentActivatedDomainEvent.java
  repository/AgentQuery.java
  repository/AgentRepository.java
  service/AgentRegisterDomainService.java
  service/impl/AgentRegisterDomainServiceImpl.java
  service/AgentActivateDomainService.java
  service/impl/AgentActivateDomainServiceImpl.java
application/biz/agent/
  AgentWriteAppService.java
  impl/AgentWriteAppServiceImpl.java
  AgentReadAppService.java
  impl/AgentReadAppServiceImpl.java
application/config/DomainServiceConfig.java   (MODIFY — register the two new domain services)
infrastructure/repository/agent/
  AgentJpaEntity.java
  AgentVersionJpaEntity.java
  AgentJpaRepository.java
  AgentVersionJpaRepository.java
  OutputSpecJsonMapper.java                  (agent-local copy; Task's lives in infrastructure/repository/task and is package-private to that track)
  AgentRepositoryImpl.java
controller/biz/agent/
  dto/RegisterAgentRequest.java
  dto/AgentDTO.java
  converter/AgentModel2DTOConverter.java
  AgentController.java
resources/db/migration/V3__agents.sql
```

> **`OutputSpec` reuse note:** The binding output spec VO `com.hireai.domain.biz.task.model.OutputSpec` already exists and is reused verbatim by the Agent version (an Agent declares the same `{format, schema, acceptanceCriteria}` contract shape). This plan does NOT create a new OutputSpec type; it imports the Task one. The JSONB (de)serialisation helper, however, is duplicated locally under `infrastructure/repository/agent` so Track A owns no files outside its isolation set.

---

## Task 1 — `AgentStatus` enum

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/enums/AgentStatus.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/enums/AgentStatusTest.java`

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStatusTest {

    @Test
    void declaresTheFourLifecycleStates() {
        assertThat(AgentStatus.values()).containsExactly(
                AgentStatus.PENDING_VERIFICATION,
                AgentStatus.ACTIVE,
                AgentStatus.SUSPENDED,
                AgentStatus.DEACTIVATED);
    }
}
```

- [ ] **Run it (expected FAIL — `AgentStatus` does not compile/exist):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentStatusTest
```

- [ ] **Minimal implementation.**

```java
package com.hireai.domain.biz.agent.enums;

/**
 * Agent lifecycle. Only PENDING_VERIFICATION -> ACTIVE is implemented in this slice;
 * SUSPENDED and DEACTIVATED are declared for forward-compatibility and land with the
 * Builder dashboard / moderation module. Only ACTIVE agents are routable.
 */
public enum AgentStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentStatusTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/enums/AgentStatus.java backend/src/test/java/com/hireai/domain/biz/agent/enums/AgentStatusTest.java
git commit -m "feat: add AgentStatus enum for agent lifecycle"
```

---

## Task 2 — `Pricing` value object

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/model/Pricing.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/model/PricingTest.java`

`Pricing` wraps the Agent version price as a non-negative `BigDecimal` (the contract `AgentCandidate.price` is `BigDecimal`, so we keep `BigDecimal` rather than `Money` to avoid a lossy round-trip at the boundary).

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingTest {

    @Test
    void buildsFromNonNegativeAmountAndNormalisesScale() {
        Pricing pricing = Pricing.of(new BigDecimal("10"));
        assertThat(pricing.price()).isEqualByComparingTo("10.00");
    }

    @Test
    void acceptsZero() {
        assertThat(Pricing.of(BigDecimal.ZERO).price()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Pricing.of(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegative() {
        assertThatThrownBy(() -> Pricing.of(new BigDecimal("-1"))).isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=PricingTest
```

- [ ] **Minimal implementation.**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pricing value object for an Agent version: the per-task price charged to the client,
 * a non-negative amount normalised to 2 decimal places. Immutable. Kept as BigDecimal
 * (not Money) because the routing read-model carries price as a raw BigDecimal.
 */
public record Pricing(BigDecimal price) {

    public static Pricing of(BigDecimal amount) {
        if (amount == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Price is required");
        }
        if (amount.signum() < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Price must be non-negative");
        }
        return new Pricing(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=PricingTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/model/Pricing.java backend/src/test/java/com/hireai/domain/biz/agent/model/PricingTest.java
git commit -m "feat: add Pricing value object for agent versions"
```

---

## Task 3 — `AgentVersionModel` child entity

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/model/AgentVersionModel.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/model/AgentVersionModelTest.java`

The child carries the routable contract: `outputSpec` (reused `com.hireai.domain.biz.task.model.OutputSpec`), `capabilityCategories` (≥1, non-blank), `webhookUrl` (must be HTTPS — Invariant #6), `maxExecutionSeconds` (> 0), `pricing`. Its factory enforces those invariants. Categories are normalised (trimmed, lower-cased) so matching is case-insensitive.

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentVersionModelTest {

    private OutputSpec spec() {
        return new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON");
    }

    @Test
    void createBuildsVersionOneAndNormalisesCategories() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel v = AgentVersionModel.create(agentId, 1, spec(),
                List.of(" Summarisation ", "TRANSLATION"), "https://agent.example.com/hook",
                120, Pricing.of(new BigDecimal("5.00")));

        assertThat(v.id()).isNotNull();
        assertThat(v.agentId()).isEqualTo(agentId);
        assertThat(v.versionNumber()).isEqualTo(1);
        assertThat(v.capabilityCategories()).containsExactly("summarisation", "translation");
        assertThat(v.webhookUrl()).isEqualTo("https://agent.example.com/hook");
        assertThat(v.maxExecutionSeconds()).isEqualTo(120);
        assertThat(v.pricing().price()).isEqualByComparingTo("5.00");
    }

    @Test
    void rejectsNonHttpsWebhook() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "http://agent.example.com/hook", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankWebhook() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "  ", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEmptyCategories() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of(), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankCategory() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("ok", "  "), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveMaxExecutionSeconds() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 0, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullOutputSpec() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, null,
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentVersionModelTest
```

- [ ] **Minimal implementation.**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Child entity of the {@link AgentModel} aggregate: one immutable, versioned snapshot of
 * the routable contract an Agent exposes — its output spec, the capability categories it
 * serves, the HTTPS webhook it is dispatched to, an execution-time ceiling, and pricing.
 * Persisted only through the root. The HTTPS rule enforces Hard Invariant #6.
 */
public final class AgentVersionModel {

    private final UUID id;
    private final UUID agentId;
    private final int versionNumber;
    private final OutputSpec outputSpec;
    private final List<String> capabilityCategories;
    private final String webhookUrl;
    private final int maxExecutionSeconds;
    private final Pricing pricing;
    private final Instant createdAt;

    public AgentVersionModel(UUID id, UUID agentId, int versionNumber, OutputSpec outputSpec,
                             List<String> capabilityCategories, String webhookUrl,
                             int maxExecutionSeconds, Pricing pricing, Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.capabilityCategories = List.copyOf(capabilityCategories);
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.pricing = pricing;
        this.createdAt = createdAt;
    }

    /** Factory: validates the contract and builds a fresh version snapshot. */
    public static AgentVersionModel create(UUID agentId, int versionNumber, OutputSpec outputSpec,
                                           List<String> capabilityCategories, String webhookUrl,
                                           int maxExecutionSeconds, Pricing pricing) {
        requirePresent(agentId, "agent id");
        requirePresent(outputSpec, "output spec");
        requirePresent(pricing, "pricing");
        List<String> normalisedCategories = normaliseCategories(capabilityCategories);
        requireHttps(webhookUrl);
        if (maxExecutionSeconds <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxExecutionSeconds must be positive");
        }
        return new AgentVersionModel(UUID.randomUUID(), agentId, versionNumber, outputSpec,
                normalisedCategories, webhookUrl.trim(), maxExecutionSeconds, pricing, Instant.now());
    }

    private static List<String> normaliseCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "At least one capability category is required");
        }
        return categories.stream()
                .map(category -> {
                    if (category == null || category.isBlank()) {
                        throw new DomainException(ResultCode.VALIDATION_ERROR, "Capability category must not be blank");
                    }
                    return category.trim().toLowerCase();
                })
                .toList();
    }

    private static void requireHttps(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL is required");
        }
        if (!webhookUrl.trim().toLowerCase().startsWith("https://")) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL must be HTTPS");
        }
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    public UUID id() { return id; }
    public UUID agentId() { return agentId; }
    public int versionNumber() { return versionNumber; }
    public OutputSpec outputSpec() { return outputSpec; }
    public List<String> capabilityCategories() { return capabilityCategories; }
    public String webhookUrl() { return webhookUrl; }
    public int maxExecutionSeconds() { return maxExecutionSeconds; }
    public Pricing pricing() { return pricing; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentVersionModelTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/model/AgentVersionModel.java backend/src/test/java/com/hireai/domain/biz/agent/model/AgentVersionModelTest.java
git commit -m "feat: add AgentVersionModel child entity with HTTPS webhook invariant"
```

---

## Task 4 — `AgentModel` aggregate root (register + activate transitions)

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/model/AgentModel.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/model/AgentModelTest.java`

`AgentModel` is the root. `register(...)` is the factory: it creates a `PENDING_VERIFICATION` agent, builds version v1 via `AgentVersionModel.create`, and holds the version as a child + `null` `currentVersionId`. `activate()` is an **immutable** guarded transition: only legal from `PENDING_VERIFICATION`, requires the v1 version present, returns a NEW `AgentModel` with status `ACTIVE` and `currentVersionId = version.id()`. Mirrors `TaskModel` (factory + guarded transition) and `WalletModel` (root owns a child).

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModelTest {

    private OutputSpec spec() {
        return new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON");
    }

    private AgentModel registered(UUID ownerId) {
        return AgentModel.register(ownerId, "Summariser Bot", spec(),
                List.of("summarisation"), "https://agent.example.com/hook", 120,
                Pricing.of(new BigDecimal("5.00")));
    }

    @Test
    void registerBuildsPendingAgentWithVersionOne() {
        UUID ownerId = UUID.randomUUID();
        AgentModel agent = registered(ownerId);

        assertThat(agent.id()).isNotNull();
        assertThat(agent.ownerId()).isEqualTo(ownerId);
        assertThat(agent.name()).isEqualTo("Summariser Bot");
        assertThat(agent.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(agent.currentVersionId()).isNull();
        assertThat(agent.reputationScore()).isEqualByComparingTo("50.00");
        assertThat(agent.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(agent.currentVersion().agentId()).isEqualTo(agent.id());
    }

    @Test
    void registerTrimsName() {
        AgentModel agent = AgentModel.register(UUID.randomUUID(), "  Bot  ", spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
        assertThat(agent.name()).isEqualTo("Bot");
    }

    @Test
    void registerRejectsBlankName() {
        assertThatThrownBy(() -> AgentModel.register(UUID.randomUUID(), "  ", spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void registerRejectsNullOwner() {
        assertThatThrownBy(() -> AgentModel.register(null, "Bot", spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void activateTransitionsToActiveAndSetsCurrentVersionImmutably() {
        AgentModel pending = registered(UUID.randomUUID());

        AgentModel active = pending.activate();

        assertThat(active.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(active.currentVersionId()).isEqualTo(pending.currentVersion().id());
        assertThat(active.id()).isEqualTo(pending.id());
        // original is unchanged (immutability)
        assertThat(pending.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(pending.currentVersionId()).isNull();
    }

    @Test
    void activateRejectsAlreadyActiveAgent() {
        AgentModel active = registered(UUID.randomUUID()).activate();
        assertThatThrownBy(active::activate).isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentModelTest
```

- [ ] **Minimal implementation.**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agent aggregate root. An Agent is a registered third-party executor owned by a Builder.
 * It owns a single {@link AgentVersionModel} (v1 in this slice) carrying the routable
 * contract. Behaviour lives here, not in setters; transitions are immutable (each returns
 * a new copy). Only ACTIVE agents are routable. Default reputation is 50.00.
 */
public final class AgentModel {

    public static final BigDecimal DEFAULT_REPUTATION = new BigDecimal("50.00");

    private final UUID id;
    private final UUID ownerId;
    private final String name;
    private final AgentStatus status;
    private final UUID currentVersionId;
    private final BigDecimal reputationScore;
    private final AgentVersionModel currentVersion;
    private final Instant createdAt;

    public AgentModel(UUID id, UUID ownerId, String name, AgentStatus status, UUID currentVersionId,
                      BigDecimal reputationScore, AgentVersionModel currentVersion, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.status = status;
        this.currentVersionId = currentVersionId;
        this.reputationScore = reputationScore;
        this.currentVersion = currentVersion;
        this.createdAt = createdAt;
    }

    /** Factory for the REGISTER transition: builds a PENDING_VERIFICATION agent + version v1. */
    public static AgentModel register(UUID ownerId, String name, OutputSpec outputSpec,
                                      List<String> capabilityCategories, String webhookUrl,
                                      int maxExecutionSeconds, Pricing pricing) {
        requirePresent(ownerId, "owner id");
        requireText(name, "name");
        UUID agentId = UUID.randomUUID();
        AgentVersionModel version = AgentVersionModel.create(agentId, 1, outputSpec,
                capabilityCategories, webhookUrl, maxExecutionSeconds, pricing);
        return new AgentModel(agentId, ownerId, name.trim(), AgentStatus.PENDING_VERIFICATION,
                null, DEFAULT_REPUTATION, version, Instant.now());
    }

    /** Immutable ACTIVATE transition: PENDING_VERIFICATION -> ACTIVE, sets current_version_id. */
    public AgentModel activate() {
        if (status != AgentStatus.PENDING_VERIFICATION) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only a PENDING_VERIFICATION agent can be activated; was " + status);
        }
        if (currentVersion == null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Agent has no version to activate");
        }
        return new AgentModel(id, ownerId, name, AgentStatus.ACTIVE, currentVersion.id(),
                reputationScore, currentVersion, createdAt);
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public String name() { return name; }
    public AgentStatus status() { return status; }
    public UUID currentVersionId() { return currentVersionId; }
    public BigDecimal reputationScore() { return reputationScore; }
    public AgentVersionModel currentVersion() { return currentVersion; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentModelTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/model/AgentModel.java backend/src/test/java/com/hireai/domain/biz/agent/model/AgentModelTest.java
git commit -m "feat: add AgentModel aggregate root with register and activate transitions"
```

---

## Task 5 — Shared contract types: `AgentCandidate`, domain events, `AgentRegisterInfo`, `AgentQuery`

These are framework-free carriers with no behaviour, so one task covers all four (each gets a trivial direct test). `AgentCandidate` is a SHARED contract type — if Plan 0 already committed it to the base branch, **skip creating it** (the file already compiles) and keep only its `AgentCandidateTest`; otherwise create it exactly as below. Field names/types are authoritative from the CONTRACTS block.

**Files:**
- Create (if absent): `backend/src/main/java/com/hireai/domain/biz/agent/info/AgentCandidate.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/info/AgentRegisterInfo.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/event/AgentRegisteredDomainEvent.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/event/AgentActivatedDomainEvent.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentQuery.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/info/AgentContractTypesTest.java`

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.info;

import com.hireai.domain.biz.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContractTypesTest {

    @Test
    void agentCandidateCarriesRoutingFields() {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = new AgentCandidate(agentId, versionId,
                List.of("summarisation"), new BigDecimal("5.00"),
                "https://a.example.com", 120, new BigDecimal("50.00"));

        assertThat(candidate.agentId()).isEqualTo(agentId);
        assertThat(candidate.agentVersionId()).isEqualTo(versionId);
        assertThat(candidate.capabilityCategories()).containsExactly("summarisation");
        assertThat(candidate.price()).isEqualByComparingTo("5.00");
        assertThat(candidate.webhookUrl()).isEqualTo("https://a.example.com");
        assertThat(candidate.maxExecutionSeconds()).isEqualTo(120);
        assertThat(candidate.reputationScore()).isEqualByComparingTo("50.00");
    }

    @Test
    void registerInfoCarriesAllRegistrationFields() {
        UUID ownerId = UUID.randomUUID();
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, null, null);
        AgentRegisterInfo info = new AgentRegisterInfo(ownerId, "Bot", spec,
                List.of("summarisation"), "https://a.example.com", 60, new BigDecimal("1.00"));

        assertThat(info.ownerId()).isEqualTo(ownerId);
        assertThat(info.name()).isEqualTo("Bot");
        assertThat(info.outputSpec()).isEqualTo(spec);
        assertThat(info.capabilityCategories()).containsExactly("summarisation");
        assertThat(info.webhookUrl()).isEqualTo("https://a.example.com");
        assertThat(info.maxExecutionSeconds()).isEqualTo(60);
        assertThat(info.price()).isEqualByComparingTo("1.00");
    }

    @Test
    void domainEventsCarryIdentifiers() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        AgentRegisteredDomainEvent registered =
                new AgentRegisteredDomainEvent(agentId, ownerId, versionId, now);
        AgentActivatedDomainEvent activated =
                new AgentActivatedDomainEvent(agentId, ownerId, versionId, now);

        assertThat(registered.agentId()).isEqualTo(agentId);
        assertThat(registered.ownerId()).isEqualTo(ownerId);
        assertThat(registered.versionId()).isEqualTo(versionId);
        assertThat(activated.agentId()).isEqualTo(agentId);
        assertThat(activated.currentVersionId()).isEqualTo(versionId);
    }

    @Test
    void agentQueryClampsPageAndSize() {
        AgentQuery q = new AgentQuery(-1, 0);
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(50);
        assertThat(AgentQuery.firstPage().size()).isEqualTo(50);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentContractTypesTest
```

- [ ] **Minimal implementation.**

`AgentCandidate.java` (create only if Plan 0 did not already commit it):

```java
package com.hireai.domain.biz.agent.info;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Framework-free read-model the routing module matches against. One per ACTIVE agent
 * version. Produced by {@code AgentRepository.findActiveCandidates}; consumed by the
 * routing matcher. Shared seam type (defined contracts-first).
 */
public record AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                             BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                             BigDecimal reputationScore) {
}
```

`AgentRegisterInfo.java`:

```java
package com.hireai.domain.biz.agent.info;

import com.hireai.domain.biz.task.model.OutputSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Domain-layer carrier for the register use case. Assembled by the controller from a
 * validated request plus the server-side owner id; passed to the application layer.
 */
public record AgentRegisterInfo(UUID ownerId, String name, OutputSpec outputSpec,
                                List<String> capabilityCategories, String webhookUrl,
                                int maxExecutionSeconds, BigDecimal price) {
}
```

`AgentRegisteredDomainEvent.java`:

```java
package com.hireai.domain.biz.agent.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an Agent and its first version are registered. No consumer required in
 * this slice; declared as a seam for the Builder dashboard / verification workflow.
 */
public record AgentRegisteredDomainEvent(UUID agentId, UUID ownerId, UUID versionId, Instant occurredAt) {
}
```

`AgentActivatedDomainEvent.java`:

```java
package com.hireai.domain.biz.agent.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an Agent is activated (PENDING_VERIFICATION -> ACTIVE) and becomes
 * routable. No consumer required in this slice.
 */
public record AgentActivatedDomainEvent(UUID agentId, UUID ownerId, UUID currentVersionId, Instant occurredAt) {
}
```

`AgentQuery.java`:

```java
package com.hireai.domain.biz.agent.repository;

/**
 * Query object for paginated agent reads. Page is zero-based; size is clamped to a sane
 * range. Mirrors TaskQuery so the read paths look the same.
 */
public record AgentQuery(int page, int size) {

    public AgentQuery {
        if (page < 0) {
            page = 0;
        }
        if (size < 1 || size > 100) {
            size = 50;
        }
    }

    public static AgentQuery firstPage() {
        return new AgentQuery(0, 50);
    }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentContractTypesTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/info/ backend/src/main/java/com/hireai/domain/biz/agent/event/ backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentQuery.java backend/src/test/java/com/hireai/domain/biz/agent/info/AgentContractTypesTest.java
git commit -m "feat: add agent contract types (AgentCandidate, register info, events, query)"
```

---

## Task 6 — `AgentRepository` interface (domain port)

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentRepository.java`

No behaviour to unit-test in isolation (it is a pure interface); it is exercised by the integration test in Task 12. This task only adds the port so later layers compile. Signatures are authoritative from the CONTRACTS block (`findActiveCandidates` exactly).

- [ ] **Create the interface.**

```java
package com.hireai.domain.biz.agent.repository;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.model.AgentModel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Agent aggregate. One repository per aggregate root.
 * The interface lives in the domain layer and carries no framework imports; the JPA
 * implementation lives in infrastructure. The agent version (child) is persisted and
 * loaded through the root. {@link #findActiveCandidates} is the routing read used by the
 * Routing module (returns one candidate per ACTIVE agent's current version).
 */
public interface AgentRepository {

    AgentModel save(AgentModel agent);

    Optional<AgentModel> findById(UUID agentId);

    List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query);

    List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice);
}
```

- [ ] **Verify it compiles (no dedicated test; build the module):**

```
mvn -f backend/pom.xml -B test-compile
```

Expected: BUILD SUCCESS (the interface references only already-defined types).

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentRepository.java
git commit -m "feat: add AgentRepository domain port with findActiveCandidates"
```

---

## Task 7 — `AgentRegisterDomainService` (interface + impl, framework-free)

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/service/AgentRegisterDomainService.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/service/impl/AgentRegisterDomainServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/service/impl/AgentRegisterDomainServiceImplTest.java`

Mirrors `TaskSubmitDomainService` (delegates to the aggregate factory, which owns invariants). The impl carries no Spring annotations; it is registered in `DomainServiceConfig` in Task 11.

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.service.impl;

import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegisterDomainServiceImplTest {

    private final AgentRegisterDomainServiceImpl service = new AgentRegisterDomainServiceImpl();

    private AgentRegisterInfo info(String webhookUrl) {
        return new AgentRegisterInfo(UUID.randomUUID(), "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of("summarisation"), webhookUrl, 120, new BigDecimal("5.00"));
    }

    @Test
    void registersPendingAgentWithVersionOne() {
        AgentModel agent = service.register(info("https://agent.example.com/hook"));

        assertThat(agent.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(agent.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(agent.currentVersion().capabilityCategories()).containsExactly("summarisation");
    }

    @Test
    void rejectsNonHttpsWebhook() {
        assertThatThrownBy(() -> service.register(info("http://agent.example.com/hook")))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentRegisterDomainServiceImplTest
```

- [ ] **Minimal implementation.**

Interface:

```java
package com.hireai.domain.biz.agent.service;

import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;

/**
 * Domain service for the agent REGISTER state transition. Framework-free; delegates to the
 * aggregate factory, which owns the invariants (HTTPS webhook, non-blank name, >=1 category,
 * positive max_execution_seconds, non-negative price). The bean is registered in DomainServiceConfig.
 */
public interface AgentRegisterDomainService {

    AgentModel register(AgentRegisterInfo info);
}
```

Impl:

```java
package com.hireai.domain.biz.agent.service.impl;

import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.agent.service.AgentRegisterDomainService;

/** Stateless implementation of the register transition; delegates to the aggregate factory. */
public class AgentRegisterDomainServiceImpl implements AgentRegisterDomainService {

    @Override
    public AgentModel register(AgentRegisterInfo info) {
        return AgentModel.register(info.ownerId(), info.name(), info.outputSpec(),
                info.capabilityCategories(), info.webhookUrl(), info.maxExecutionSeconds(),
                Pricing.of(info.price()));
    }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentRegisterDomainServiceImplTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/service/AgentRegisterDomainService.java backend/src/main/java/com/hireai/domain/biz/agent/service/impl/AgentRegisterDomainServiceImpl.java backend/src/test/java/com/hireai/domain/biz/agent/service/impl/AgentRegisterDomainServiceImplTest.java
git commit -m "feat: add AgentRegisterDomainService"
```

---

## Task 8 — `AgentActivateDomainService` (interface + impl, framework-free)

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/service/AgentActivateDomainService.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/service/impl/AgentActivateDomainServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/domain/biz/agent/service/impl/AgentActivateDomainServiceImplTest.java`

Delegates to `AgentModel.activate()` (the guarded transition owns the legal-transition + version-present check).

- [ ] **Write the failing test.**

```java
package com.hireai.domain.biz.agent.service.impl;

import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActivateDomainServiceImplTest {

    private final AgentActivateDomainServiceImpl service = new AgentActivateDomainServiceImpl();

    private AgentModel registered() {
        return AgentModel.register(UUID.randomUUID(), "Bot",
                new OutputSpec(OutputFormat.JSON, null, null),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
    }

    @Test
    void activatesPendingAgent() {
        AgentModel active = service.activate(registered());
        assertThat(active.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(active.currentVersionId()).isNotNull();
    }

    @Test
    void rejectsAlreadyActiveAgent() {
        AgentModel active = service.activate(registered());
        assertThatThrownBy(() -> service.activate(active)).isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentActivateDomainServiceImplTest
```

- [ ] **Minimal implementation.**

Interface:

```java
package com.hireai.domain.biz.agent.service;

import com.hireai.domain.biz.agent.model.AgentModel;

/**
 * Domain service for the agent ACTIVATE state transition (PENDING_VERIFICATION -> ACTIVE).
 * Framework-free; delegates to the aggregate's guarded transition, which enforces the legal
 * transition and the current-version-present rule. The bean is registered in DomainServiceConfig.
 */
public interface AgentActivateDomainService {

    AgentModel activate(AgentModel agent);
}
```

Impl:

```java
package com.hireai.domain.biz.agent.service.impl;

import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.service.AgentActivateDomainService;

/** Stateless implementation of the activate transition; delegates to the aggregate. */
public class AgentActivateDomainServiceImpl implements AgentActivateDomainService {

    @Override
    public AgentModel activate(AgentModel agent) {
        return agent.activate();
    }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentActivateDomainServiceImplTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/domain/biz/agent/service/AgentActivateDomainService.java backend/src/main/java/com/hireai/domain/biz/agent/service/impl/AgentActivateDomainServiceImpl.java backend/src/test/java/com/hireai/domain/biz/agent/service/impl/AgentActivateDomainServiceImplTest.java
git commit -m "feat: add AgentActivateDomainService"
```

---

## Task 9 — Flyway `V3` (agents + agent_versions) and JPA infrastructure

This task brings up the schema and the persistence layer together so the integration test in Task 12 can exercise a real round-trip. It is the largest task; it is broken into ordered sub-steps. The JPA round-trip is validated by Task 12's integration test (it needs Postgres + the GIN array operator, so it is not unit-testable in isolation). Here we (a) write the migration, (b) write the JPA entities/repos/mapper/impl, (c) confirm the module compiles.

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__agents.sql`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentVersionJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentJpaRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentVersionJpaRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/OutputSpecJsonMapper.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentRepositoryImpl.java`

- [ ] **Write the Flyway migration `V3__agents.sql`.**

```sql
-- V3: Agents and their versions. An Agent is a registered third-party executor owned by a
-- Builder; only ACTIVE agents are routable. agent_versions carries the routable contract
-- (output_spec JSONB, capability_categories for matching, the HTTPS webhook, an execution
-- ceiling, and price). current_version_id on agents is a plain UUID (no FK) to avoid a
-- circular-FK ordering problem with agent_versions. capability_categories gets a GIN index
-- so the routing match (array overlap) is index-backed.

CREATE TABLE agents (
    id                 UUID PRIMARY KEY,
    owner_id           UUID NOT NULL REFERENCES users (id),
    name               TEXT NOT NULL,
    status             TEXT NOT NULL CHECK (status IN
                           ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    current_version_id UUID,
    reputation_score   NUMERIC(5, 2) NOT NULL DEFAULT 50.00,
    gmt_create         TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agents_owner ON agents (owner_id);

CREATE TABLE agent_versions (
    id                    UUID PRIMARY KEY,
    agent_id              UUID NOT NULL REFERENCES agents (id),
    version_number        INT NOT NULL,
    output_spec           JSONB NOT NULL,
    capability_categories TEXT[] NOT NULL,
    webhook_url           TEXT NOT NULL,
    max_execution_seconds INT NOT NULL CHECK (max_execution_seconds > 0),
    price                 NUMERIC(14, 2) NOT NULL CHECK (price >= 0),
    gmt_create            TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, version_number)
);

CREATE INDEX idx_agent_versions_categories ON agent_versions USING GIN (capability_categories);
```

- [ ] **Write `AgentJpaEntity.java`** (mirrors `TaskJpaEntity`; `gmt_modified` is DB-managed so it is not mapped).

```java
package com.hireai.infrastructure.repository.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for an agent. Separate from the domain {@code AgentModel} so the
 * domain stays framework-free. {@code current_version_id} is a plain UUID (no FK).
 */
@Entity
@Table(name = "agents")
public class AgentJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "reputation_score", nullable = false)
    private BigDecimal reputationScore;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected AgentJpaEntity() {
    }

    public AgentJpaEntity(UUID id, UUID ownerId, String name, String status, UUID currentVersionId,
                          BigDecimal reputationScore, Instant gmtCreate) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.status = status;
        this.currentVersionId = currentVersionId;
        this.reputationScore = reputationScore;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public UUID getCurrentVersionId() { return currentVersionId; }
    public BigDecimal getReputationScore() { return reputationScore; }
    public Instant getGmtCreate() { return gmtCreate; }
}
```

- [ ] **Write `AgentVersionJpaEntity.java`** (`output_spec` JSONB + `capability_categories` TEXT[]).

```java
package com.hireai.infrastructure.repository.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA persistence entity for an agent version (aggregate child). {@code output_spec} is
 * stored as JSONB (the @JdbcTypeCode + columnDefinition is required for String->jsonb
 * binding). {@code capability_categories} is a Postgres TEXT[] mapped to a List<String>.
 */
@Entity
@Table(name = "agent_versions")
public class AgentVersionJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_spec", columnDefinition = "jsonb", nullable = false)
    private String outputSpec;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "capability_categories", columnDefinition = "text[]", nullable = false)
    private List<String> capabilityCategories;

    @Column(name = "webhook_url", nullable = false)
    private String webhookUrl;

    @Column(name = "max_execution_seconds", nullable = false)
    private int maxExecutionSeconds;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected AgentVersionJpaEntity() {
    }

    public AgentVersionJpaEntity(UUID id, UUID agentId, int versionNumber, String outputSpec,
                                 List<String> capabilityCategories, String webhookUrl,
                                 int maxExecutionSeconds, BigDecimal price, Instant gmtCreate) {
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.capabilityCategories = capabilityCategories;
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.price = price;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public int getVersionNumber() { return versionNumber; }
    public String getOutputSpec() { return outputSpec; }
    public List<String> getCapabilityCategories() { return capabilityCategories; }
    public String getWebhookUrl() { return webhookUrl; }
    public int getMaxExecutionSeconds() { return maxExecutionSeconds; }
    public BigDecimal getPrice() { return price; }
    public Instant getGmtCreate() { return gmtCreate; }
}
```

- [ ] **Write `AgentJpaRepository.java`.**

```java
package com.hireai.infrastructure.repository.agent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for agent rows. Internal to infrastructure. */
public interface AgentJpaRepository extends JpaRepository<AgentJpaEntity, UUID> {

    List<AgentJpaEntity> findByOwnerIdOrderByGmtCreateDesc(UUID ownerId, Pageable pageable);
}
```

- [ ] **Write `AgentVersionJpaRepository.java`** including the native array-overlap candidate query. `&&` is the Postgres array-overlap operator; `ARRAY[:category]` matches the GIN index. The projection columns map to `AgentCandidate` in the impl.

```java
package com.hireai.infrastructure.repository.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for agent-version rows. Internal to infrastructure. */
public interface AgentVersionJpaRepository extends JpaRepository<AgentVersionJpaEntity, UUID> {

    Optional<AgentVersionJpaEntity> findByAgentIdAndVersionNumber(UUID agentId, int versionNumber);

    /**
     * One row per ACTIVE agent whose current version covers the requested category and whose
     * price is within budget. Returns the columns needed to build an AgentCandidate. Uses the
     * Postgres array-overlap operator (&&) against the GIN-indexed capability_categories.
     */
    @Query(value = """
            SELECT v.agent_id            AS agent_id,
                   v.id                  AS agent_version_id,
                   v.capability_categories AS capability_categories,
                   v.price               AS price,
                   v.webhook_url         AS webhook_url,
                   v.max_execution_seconds AS max_execution_seconds,
                   a.reputation_score    AS reputation_score
            FROM agent_versions v
            JOIN agents a ON a.id = v.agent_id AND a.current_version_id = v.id
            WHERE a.status = 'ACTIVE'
              AND v.capability_categories && ARRAY[:category]::text[]
              AND v.price <= :maxPrice
            ORDER BY a.reputation_score DESC
            """, nativeQuery = true)
    List<AgentCandidateRow> findActiveCandidates(@Param("category") String category,
                                                 @Param("maxPrice") BigDecimal maxPrice);

    /** Projection for the candidate query; mapped to the domain AgentCandidate in the impl. */
    interface AgentCandidateRow {
        UUID getAgentId();
        UUID getAgentVersionId();
        String[] getCapabilityCategories();
        BigDecimal getPrice();
        String getWebhookUrl();
        int getMaxExecutionSeconds();
        BigDecimal getReputationScore();
    }
}
```

- [ ] **Write `OutputSpecJsonMapper.java`** (agent-local copy so Track A owns no files in `infrastructure/repository/task`; identical logic to the Task mapper, distinct Spring bean by package).

```java
package com.hireai.infrastructure.repository.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.stereotype.Component;

/**
 * Serialises the {@code OutputSpec} value object to/from the agent_versions JSONB column.
 * Jackson handles the record natively, so the domain stays annotation-free. Agent-local copy
 * (the Task track owns its own equivalent under infrastructure/repository/task).
 */
@Component
public class OutputSpecJsonMapper {

    private final ObjectMapper objectMapper;

    public OutputSpecJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(OutputSpec outputSpec) {
        try {
            return objectMapper.writeValueAsString(outputSpec);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to serialise output spec");
        }
    }

    public OutputSpec fromJson(String json) {
        try {
            return objectMapper.readValue(json, OutputSpec.class);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to read output spec");
        }
    }
}
```

- [ ] **Write `AgentRepositoryImpl.java`** (persists the child through the root; loads the root + its current version; maps the candidate projection). It implements `findById`/`findByOwnerId` by loading the agent's version v1 as the current version snapshot.

```java
package com.hireai.infrastructure.repository.agent;

import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link AgentRepository}. Maps
 * {@code AgentModel} &lt;-&gt; JPA entities, persisting the version child only through the
 * root, and serialises the output spec via {@link OutputSpecJsonMapper}. The candidate read
 * maps the native projection to the shared {@link AgentCandidate} contract record.
 */
@Repository
public class AgentRepositoryImpl implements AgentRepository {

    private final AgentJpaRepository agentJpa;
    private final AgentVersionJpaRepository versionJpa;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public AgentRepositoryImpl(AgentJpaRepository agentJpa, AgentVersionJpaRepository versionJpa,
                               OutputSpecJsonMapper outputSpecJsonMapper) {
        this.agentJpa = agentJpa;
        this.versionJpa = versionJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @Override
    public AgentModel save(AgentModel agent) {
        agentJpa.save(new AgentJpaEntity(
                agent.id(), agent.ownerId(), agent.name(), agent.status().name(),
                agent.currentVersionId(), agent.reputationScore(), agent.createdAt()));

        AgentVersionModel version = agent.currentVersion();
        if (version != null && versionJpa.findById(version.id()).isEmpty()) {
            versionJpa.save(new AgentVersionJpaEntity(
                    version.id(), version.agentId(), version.versionNumber(),
                    outputSpecJsonMapper.toJson(version.outputSpec()),
                    version.capabilityCategories(), version.webhookUrl(),
                    version.maxExecutionSeconds(), version.pricing().price(), version.createdAt()));
        }
        return agent;
    }

    @Override
    public Optional<AgentModel> findById(UUID agentId) {
        return agentJpa.findById(agentId).map(this::toModel);
    }

    @Override
    public List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query) {
        return agentJpa.findByOwnerIdOrderByGmtCreateDesc(
                        ownerId, PageRequest.of(query.page(), query.size()))
                .stream()
                .map(this::toModel)
                .toList();
    }

    @Override
    public List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice) {
        String normalisedCategory = category == null ? "" : category.trim().toLowerCase();
        return versionJpa.findActiveCandidates(normalisedCategory, maxPrice).stream()
                .map(row -> new AgentCandidate(
                        row.getAgentId(), row.getAgentVersionId(),
                        List.of(row.getCapabilityCategories()), row.getPrice(),
                        row.getWebhookUrl(), row.getMaxExecutionSeconds(), row.getReputationScore()))
                .toList();
    }

    private AgentModel toModel(AgentJpaEntity entity) {
        AgentVersionModel version = versionJpa.findByAgentIdAndVersionNumber(entity.getId(), 1)
                .map(this::toVersionModel)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent " + entity.getId() + " has no version 1"));
        return new AgentModel(
                entity.getId(), entity.getOwnerId(), entity.getName(),
                AgentStatus.valueOf(entity.getStatus()), entity.getCurrentVersionId(),
                entity.getReputationScore(), version, entity.getGmtCreate());
    }

    private AgentVersionModel toVersionModel(AgentVersionJpaEntity entity) {
        return new AgentVersionModel(
                entity.getId(), entity.getAgentId(), entity.getVersionNumber(),
                outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getCapabilityCategories(), entity.getWebhookUrl(),
                entity.getMaxExecutionSeconds(), Pricing.of(entity.getPrice()), entity.getGmtCreate());
    }
}
```

- [ ] **Confirm the module compiles (no isolated unit test — Task 12 exercises the round-trip against Postgres):**

```
mvn -f backend/pom.xml -B test-compile
```

Expected: BUILD SUCCESS.

- [ ] **Commit.**

```
git add backend/src/main/resources/db/migration/V3__agents.sql backend/src/main/java/com/hireai/infrastructure/repository/agent/
git commit -m "feat: add Flyway V3 agents schema and JPA persistence for the Agent aggregate"
```

---

## Task 10 — `AgentWriteAppService` + `AgentReadAppService` (interfaces + impls)

CQRS app services. Write: `register` (returns `agentId`, publishes `AgentRegisteredDomainEvent`), `activate` (owner-checked, publishes `AgentActivatedDomainEvent`). Read: `getForOwner` (owner-checked, NOT_FOUND-on-mismatch so existence is not leaked — mirrors `TaskReadAppService`), `listForOwner`. Mirrors the Task app-service pattern exactly: interface `@Validated` + `@NonNull`; impl `@Service @Slf4j @RequiredArgsConstructor @Transactional`.

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/agent/AgentWriteAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/agent/impl/AgentWriteAppServiceImpl.java`
- Create: `backend/src/main/java/com/hireai/application/biz/agent/AgentReadAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/agent/impl/AgentReadAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/agent/impl/AgentWriteAppServiceImplTest.java`

The unit test uses hand-written fakes (no Mockito dependency is in the project; the existing tests use plain Java), so we provide an in-memory `AgentRepository` fake and a recording `ApplicationEventPublisher`.

- [ ] **Write the failing test.**

```java
package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.agent.service.impl.AgentActivateDomainServiceImpl;
import com.hireai.domain.biz.agent.service.impl.AgentRegisterDomainServiceImpl;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWriteAppServiceImplTest {

    /** In-memory fake of the Agent repository. */
    static class FakeAgentRepository implements AgentRepository {
        final Map<UUID, AgentModel> store = new HashMap<>();

        @Override public AgentModel save(AgentModel agent) { store.put(agent.id(), agent); return agent; }
        @Override public Optional<AgentModel> findById(UUID agentId) { return Optional.ofNullable(store.get(agentId)); }
        @Override public List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query) {
            return store.values().stream().filter(a -> a.ownerId().equals(ownerId)).toList();
        }
        @Override public List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice) {
            return List.of();
        }
    }

    /** Recording event publisher. */
    static class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        @Override public void publishEvent(Object event) { events.add(event); }
        @Override public void publishEvent(ApplicationEvent event) { events.add(event); }
    }

    private final FakeAgentRepository repository = new FakeAgentRepository();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final AgentWriteAppService service = new AgentWriteAppServiceImpl(
            repository, new AgentRegisterDomainServiceImpl(),
            new AgentActivateDomainServiceImpl(), publisher);

    private AgentRegisterInfo info(UUID ownerId) {
        return new AgentRegisterInfo(ownerId, "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of("summarisation"), "https://agent.example.com/hook", 120, new BigDecimal("5.00"));
    }

    @Test
    void registerPersistsPendingAgentAndPublishesEvent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        AgentModel saved = repository.findById(agentId).orElseThrow();
        assertThat(saved.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(saved.ownerId()).isEqualTo(ownerId);
        assertThat(publisher.events).anyMatch(e -> e instanceof AgentRegisteredDomainEvent);
    }

    @Test
    void activateTransitionsToActiveAndPublishesEvent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        service.activate(agentId, ownerId);

        AgentModel saved = repository.findById(agentId).orElseThrow();
        assertThat(saved.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(saved.currentVersionId()).isNotNull();
        assertThat(publisher.events).anyMatch(e -> e instanceof AgentActivatedDomainEvent);
    }

    @Test
    void activateRejectsNonOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        assertThatThrownBy(() -> service.activate(agentId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void activateRejectsUnknownAgent() {
        assertThatThrownBy(() -> service.activate(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Run it (expected FAIL):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentWriteAppServiceImplTest
```

- [ ] **Minimal implementation.**

`AgentWriteAppService.java`:

```java
package com.hireai.application.biz.agent;

import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates agent WRITE use cases. {@code register} creates an Agent + version v1 in
 * PENDING_VERIFICATION and returns the new agent id; {@code activate} transitions an owned
 * agent to ACTIVE. Owner identity is supplied by the caller (derived server-side from the
 * JWT, Invariant #5); activate enforces an explicit owner check.
 */
@Validated
public interface AgentWriteAppService {

    UUID register(@NonNull AgentRegisterInfo registerInfo);

    void activate(@NonNull UUID agentId, @NonNull UUID ownerId);
}
```

`AgentWriteAppServiceImpl.java`:

```java
package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.agent.service.AgentActivateDomainService;
import com.hireai.domain.biz.agent.service.AgentRegisterDomainService;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentWriteAppServiceImpl implements AgentWriteAppService {

    private final AgentRepository agentRepository;
    private final AgentRegisterDomainService registerDomainService;
    private final AgentActivateDomainService activateDomainService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public UUID register(AgentRegisterInfo registerInfo) {
        AgentModel agent = registerDomainService.register(registerInfo);
        UUID agentId = agentRepository.save(agent).id();
        eventPublisher.publishEvent(new AgentRegisteredDomainEvent(
                agentId, agent.ownerId(), agent.currentVersion().id(), Instant.now()));
        log.info("Agent {} registered by owner {} (PENDING_VERIFICATION)", agentId, agent.ownerId());
        return agentId;
    }

    @Override
    public void activate(UUID agentId, UUID ownerId) {
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        AgentModel active = activateDomainService.activate(agent);
        agentRepository.save(active);
        eventPublisher.publishEvent(new AgentActivatedDomainEvent(
                active.id(), active.ownerId(), active.currentVersionId(), Instant.now()));
        log.info("Agent {} activated by owner {}", agentId, ownerId);
    }
}
```

`AgentReadAppService.java`:

```java
package com.hireai.application.biz.agent;

import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates agent READ use cases. Enforces Invariant #5 (server-side identity +
 * ownership): an agent is only returned to its owner; otherwise NOT_FOUND, so existence is
 * not leaked across builders.
 */
@Validated
public interface AgentReadAppService {

    AgentModel getForOwner(@NonNull UUID agentId, @NonNull UUID ownerId);

    List<AgentModel> listForOwner(@NonNull UUID ownerId, @NonNull AgentQuery query);
}
```

`AgentReadAppServiceImpl.java`:

```java
package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentReadAppServiceImpl implements AgentReadAppService {

    private final AgentRepository agentRepository;

    @Override
    public AgentModel getForOwner(UUID agentId, UUID ownerId) {
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        return agent;
    }

    @Override
    public List<AgentModel> listForOwner(UUID ownerId, AgentQuery query) {
        return agentRepository.findByOwnerId(ownerId, query);
    }
}
```

- [ ] **Run it (expected PASS):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentWriteAppServiceImplTest
```

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/application/biz/agent/ backend/src/test/java/com/hireai/application/biz/agent/impl/AgentWriteAppServiceImplTest.java
git commit -m "feat: add Agent read/write app services with owner checks and domain events"
```

---

## Task 11 — Register the two domain-service beans in `DomainServiceConfig`

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java`

This is the ONLY parallel track that edits `DomainServiceConfig`. Add two `@Bean` methods mirroring the existing ones. No new unit test (config wiring is exercised by Task 12's `@SpringBootTest`); verify by compiling.

- [ ] **Apply the edit.** Add the two imports and two `@Bean` methods to the existing class. The resulting file is:

```java
package com.hireai.application.config;

import com.hireai.domain.biz.agent.service.AgentActivateDomainService;
import com.hireai.domain.biz.agent.service.AgentRegisterDomainService;
import com.hireai.domain.biz.agent.service.impl.AgentActivateDomainServiceImpl;
import com.hireai.domain.biz.agent.service.impl.AgentRegisterDomainServiceImpl;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.biz.task.service.impl.TaskSubmitDomainServiceImpl;
import com.hireai.domain.biz.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.wallet.service.WalletTopUpDomainService;
import com.hireai.domain.biz.wallet.service.impl.WalletFreezeDomainServiceImpl;
import com.hireai.domain.biz.wallet.service.impl.WalletTopUpDomainServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers framework-free domain services as Spring beans. Domain services carry no
 * Spring annotations (the domain layer has zero framework imports), so the application
 * layer wires their implementations here, exposing them by their domain interfaces.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public WalletTopUpDomainService walletTopUpDomainService() {
        return new WalletTopUpDomainServiceImpl();
    }

    @Bean
    public WalletFreezeDomainService walletFreezeDomainService() {
        return new WalletFreezeDomainServiceImpl();
    }

    @Bean
    public TaskSubmitDomainService taskSubmitDomainService() {
        return new TaskSubmitDomainServiceImpl();
    }

    @Bean
    public AgentRegisterDomainService agentRegisterDomainService() {
        return new AgentRegisterDomainServiceImpl();
    }

    @Bean
    public AgentActivateDomainService agentActivateDomainService() {
        return new AgentActivateDomainServiceImpl();
    }
}
```

- [ ] **Confirm it compiles:**

```
mvn -f backend/pom.xml -B test-compile
```

Expected: BUILD SUCCESS.

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java
git commit -m "chore: register Agent domain services in DomainServiceConfig"
```

---

## Task 12 — Controller + DTOs + converter, and end-to-end integration test

This task adds the HTTP surface and proves the whole aggregate (register → activate → list/get → `findActiveCandidates`) works against a real Postgres via Testcontainers, mirroring `TaskSubmissionIntegrationTest`. The integration test drives the app services directly (the controller is thin and identity-injected, exactly like the Task slice's test), and additionally asserts the candidate read.

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/dto/RegisterAgentRequest.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/dto/AgentDTO.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/converter/AgentModel2DTOConverter.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/AgentController.java`
- Test: `backend/src/test/java/com/hireai/agent/AgentRegistrationIntegrationTest.java`

- [ ] **Write the failing integration test** (boots Spring against Postgres so Flyway applies V1+V2+V3).

```java
package com.hireai.agent;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1+V2+V3.
 * Verifies the Agent registration slice end-to-end: register (PENDING_VERIFICATION + v1),
 * activate (-> ACTIVE + current_version_id), owner-scoped get/list, the JSONB + TEXT[]
 * round-trip, and the routing candidate read (category overlap, budget filter, reputation
 * tie-break ordering). Each test creates its own owner so the shared container carries no
 * cross-test state.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
class AgentRegistrationIntegrationTest {

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

    @Autowired AgentWriteAppService agentWriteAppService;
    @Autowired AgentReadAppService agentReadAppService;
    @Autowired AgentRepository agentRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID newOwner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", id, id + "@test.local");
        return id;
    }

    private AgentRegisterInfo info(UUID ownerId, String category, String price) {
        return new AgentRegisterInfo(ownerId, "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of(category), "https://agent.example.com/hook", 120, new BigDecimal(price));
    }

    @Test
    void registerPersistsPendingAgentWithVersionOne() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "summarisation", "5.00"));

        AgentModel agent = agentReadAppService.getForOwner(agentId, owner);
        assertThat(agent.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(agent.currentVersionId()).isNull();
        assertThat(agent.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(agent.currentVersion().capabilityCategories()).containsExactly("summarisation");
        assertThat(agent.currentVersion().outputSpec().format()).isEqualTo(OutputFormat.JSON);
    }

    @Test
    void activateTransitionsToActiveAndSetsCurrentVersion() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "translation", "3.00"));

        agentWriteAppService.activate(agentId, owner);

        AgentModel agent = agentReadAppService.getForOwner(agentId, owner);
        assertThat(agent.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(agent.currentVersionId()).isEqualTo(agent.currentVersion().id());
    }

    @Test
    void getRejectsNonOwner() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "summarisation", "5.00"));

        assertThatThrownBy(() -> agentReadAppService.getForOwner(agentId, newOwner()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void listReturnsOnlyOwnedAgents() {
        UUID owner = newOwner();
        agentWriteAppService.register(info(owner, "summarisation", "5.00"));
        agentWriteAppService.register(info(owner, "translation", "3.00"));
        agentWriteAppService.register(info(newOwner(), "summarisation", "5.00"));

        List<AgentModel> mine = agentReadAppService.listForOwner(owner, AgentQuery.firstPage());
        assertThat(mine).hasSize(2);
        assertThat(mine).allMatch(a -> a.ownerId().equals(owner));
    }

    @Test
    void findActiveCandidatesReturnsOnlyActiveInCategoryWithinBudgetOrderedByReputation() {
        // A cheaper active agent and a pricier active agent, both in 'summarisation';
        // plus one still PENDING (must be excluded) and one out-of-category (excluded).
        UUID owner = newOwner();
        UUID cheap = agentWriteAppService.register(info(owner, "summarisation", "5.00"));
        UUID pricey = agentWriteAppService.register(info(owner, "summarisation", "9.00"));
        UUID pending = agentWriteAppService.register(info(owner, "summarisation", "1.00"));
        UUID other = agentWriteAppService.register(info(owner, "translation", "1.00"));
        agentWriteAppService.activate(cheap, owner);
        agentWriteAppService.activate(pricey, owner);
        agentWriteAppService.activate(other, owner);
        // give 'pricey' the higher reputation so it sorts first
        jdbc.update("UPDATE agents SET reputation_score = 80.00 WHERE id = ?", pricey);
        jdbc.update("UPDATE agents SET reputation_score = 60.00 WHERE id = ?", cheap);

        List<AgentCandidate> candidates =
                agentRepository.findActiveCandidates("summarisation", new BigDecimal("10.00"));

        assertThat(candidates).extracting(AgentCandidate::agentId).containsExactly(pricey, cheap);
        assertThat(candidates).noneMatch(c -> c.agentId().equals(pending));
        assertThat(candidates).noneMatch(c -> c.agentId().equals(other));

        // budget filter: max 6.00 excludes the 9.00 'pricey' agent
        List<AgentCandidate> withinBudget =
                agentRepository.findActiveCandidates("summarisation", new BigDecimal("6.00"));
        assertThat(withinBudget).extracting(AgentCandidate::agentId).containsExactly(cheap);
    }
}
```

All four `register`/`activate` calls above share the same `owner` variable, so the explicit owner check passes and no helper is needed.

- [ ] **Run it (with Docker present it PASSES; without Docker the whole class auto-skips via `@EnabledIf`. The test references only the app services + repository, so it compiles independently of the controller; run to confirm current state):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentRegistrationIntegrationTest
```

Expected: FAIL or skip. If Docker is present it FAILS only if behaviour is wrong; with the implementation below it PASSES. (It does not reference the controller, so it compiles already; the controller classes below are still required for the slice and are committed in this task.)

- [ ] **Write `RegisterAgentRequest.java`** (Bean Validation at the boundary; mirrors `SubmitTaskRequest`).

```java
package com.hireai.controller.biz.agent.dto;

import com.hireai.domain.biz.task.enums.OutputFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Inbound HTTP DTO for registering an agent + its first version. Bean Validation at the boundary. */
public record RegisterAgentRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Valid OutputSpecRequest outputSpec,
        @NotEmpty List<@NotBlank @Size(max = 100) String> capabilityCategories,
        @NotBlank @Size(max = 2000) String webhookUrl,
        @Min(value = 1, message = "maxExecutionSeconds must be positive") int maxExecutionSeconds,
        @NotNull
        @DecimalMin(value = "0.00", message = "price must be non-negative")
        @Digits(integer = 12, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price
) {

    public record OutputSpecRequest(
            @NotNull OutputFormat format,
            @Size(max = 5000) String schema,
            @Size(max = 5000) String acceptanceCriteria
    ) {
    }
}
```

- [ ] **Write `AgentDTO.java`** (outbound; no domain types leak; mirrors `TaskDTO`).

```java
package com.hireai.controller.biz.agent.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbound HTTP DTO for an agent (with its current version). No domain types leak. */
public record AgentDTO(
        UUID id,
        UUID ownerId,
        String name,
        String status,
        UUID currentVersionId,
        BigDecimal reputationScore,
        AgentVersionDTO currentVersion,
        Instant createdAt
) {

    public record AgentVersionDTO(
            UUID id,
            int versionNumber,
            OutputSpecDTO outputSpec,
            List<String> capabilityCategories,
            String webhookUrl,
            int maxExecutionSeconds,
            BigDecimal price
    ) {
    }

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
```

- [ ] **Write `AgentModel2DTOConverter.java`** (hand-written, one direction; mirrors `TaskModel2DTOConverter`).

```java
package com.hireai.controller.biz.agent.converter;

import com.hireai.controller.biz.agent.dto.AgentDTO;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.task.model.OutputSpec;

/**
 * Explicit, hand-written converter from the Agent domain model to its outbound DTO.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 */
public final class AgentModel2DTOConverter {

    private AgentModel2DTOConverter() {
    }

    public static AgentDTO toDTO(AgentModel agent) {
        AgentVersionModel version = agent.currentVersion();
        OutputSpec spec = version.outputSpec();
        AgentDTO.AgentVersionDTO versionDTO = new AgentDTO.AgentVersionDTO(
                version.id(),
                version.versionNumber(),
                new AgentDTO.OutputSpecDTO(spec.format().name(), spec.schema(), spec.acceptanceCriteria()),
                version.capabilityCategories(),
                version.webhookUrl(),
                version.maxExecutionSeconds(),
                version.pricing().price());
        return new AgentDTO(
                agent.id(),
                agent.ownerId(),
                agent.name(),
                agent.status().name(),
                agent.currentVersionId(),
                agent.reputationScore(),
                versionDTO,
                agent.createdAt());
    }
}
```

- [ ] **Write `AgentController.java`** (thin; identity from `CurrentUserProvider`; mirrors `TaskController`).

```java
package com.hireai.controller.biz.agent;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.agent.converter.AgentModel2DTOConverter;
import com.hireai.controller.biz.agent.dto.AgentDTO;
import com.hireai.controller.biz.agent.dto.RegisterAgentRequest;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.task.model.OutputSpec;
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

/**
 * Agent HTTP surface. Thin: validate the request, resolve owner identity server-side, build
 * the domain carrier, call one app service, wrap the result. Owner identity comes from
 * {@link CurrentUserProvider} (the JWT principal) — never from path or body (Invariant #5).
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController extends BaseController {

    private final AgentWriteAppService writeAppService;
    private final AgentReadAppService readAppService;
    private final CurrentUserProvider currentUser;

    public AgentController(AgentWriteAppService writeAppService,
                           AgentReadAppService readAppService,
                           CurrentUserProvider currentUser) {
        this.writeAppService = writeAppService;
        this.readAppService = readAppService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<AgentDTO> register(@Valid @RequestBody RegisterAgentRequest request) {
        UUID ownerId = currentUser.currentUserId();
        RegisterAgentRequest.OutputSpecRequest specRequest = request.outputSpec();
        AgentRegisterInfo info = new AgentRegisterInfo(
                ownerId,
                request.name(),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()),
                request.capabilityCategories(),
                request.webhookUrl(),
                request.maxExecutionSeconds(),
                request.price());
        UUID agentId = writeAppService.register(info);
        AgentDTO dto = AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId));
        return ok(dto);
    }

    @PostMapping("/{agentId}/activate")
    public WebResult<AgentDTO> activate(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        writeAppService.activate(agentId, ownerId);
        AgentDTO dto = AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId));
        return ok(dto);
    }

    @GetMapping("/{agentId}")
    public WebResult<AgentDTO> getById(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        AgentDTO dto = AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId));
        return ok(dto);
    }

    @GetMapping
    public WebResult<List<AgentDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID ownerId = currentUser.currentUserId();
        List<AgentDTO> agents = readAppService.listForOwner(ownerId, new AgentQuery(page, size))
                .stream()
                .map(AgentModel2DTOConverter::toDTO)
                .toList();
        return ok(agents);
    }
}
```

- [ ] **Run the full agent test set + integration test (expected PASS, or the integration class auto-skips if Docker is unavailable):**

```
mvn -f backend/pom.xml -B test -Dtest=AgentRegistrationIntegrationTest
```

- [ ] **Run the whole backend suite to confirm nothing regressed:**

```
mvn -f backend/pom.xml -B test
```

Expected: BUILD SUCCESS (all unit tests pass; integration tests pass or skip without Docker).

- [ ] **Commit.**

```
git add backend/src/main/java/com/hireai/controller/biz/agent/ backend/src/test/java/com/hireai/agent/AgentRegistrationIntegrationTest.java
git commit -m "feat: add Agent HTTP controller, DTOs, converter, and end-to-end integration test"
```

---

## Definition of done (Track A)

- [ ] `mvn -f backend/pom.xml -B package` succeeds (Hibernate `ddl-auto: validate` passes against Flyway V1+V2+V3 when Docker is available; the integration test exercises the JSONB + TEXT[] round-trip and the GIN candidate query).
- [ ] All unit tests green: `AgentStatusTest`, `PricingTest`, `AgentVersionModelTest`, `AgentModelTest`, `AgentContractTypesTest`, `AgentRegisterDomainServiceImplTest`, `AgentActivateDomainServiceImplTest`, `AgentWriteAppServiceImplTest`.
- [ ] `AgentRegistrationIntegrationTest` passes with Docker (auto-skips without).
- [ ] Invariants honoured: HTTPS-only webhook enforced in the domain (#6); owner identity from `CurrentUserProvider` + explicit owner checks on activate/get (#5); register creates v1 + `PENDING_VERIFICATION`; activate transitions to `ACTIVE` and sets `current_version_id`; `AgentRegisteredDomainEvent`/`AgentActivatedDomainEvent` published.
- [ ] `findActiveCandidates(category, maxPrice)` returns one `AgentCandidate` per ACTIVE agent's current version covering the category within budget, ordered by reputation desc — ready for the Routing module to consume.
- [ ] This track is the only one that edited `DomainServiceConfig` and owns Flyway `V3`.
