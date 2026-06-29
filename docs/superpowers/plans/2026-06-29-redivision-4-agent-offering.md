# Capability Re-division — Slice 4: Agent Offering — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Relocate the `agent` + `catalogue` code into an `offering` subdomain across every module, then deepen the Agent Offering shapes the SAD §3.3 draws: give `AgentVersion` a real lifecycle (`DRAFT`/`ACTIVE`/`DEPRECATED`) with a **publish-new-version → supersession** use case (replacing the in-place pricing mutation), promote the storefront to its own `Storefront` aggregate with a `Media` value object, and wire manual Agent `SUSPEND`/`DEACTIVATE`/`REACTIVATE` transitions — all keeping the suite green, plus a matching builder-console frontend change.

**Architecture:** Slice 4 of the incremental-strangler refactor (spec: `docs/superpowers/specs/2026-06-29-backend-capability-redivision-design.md`, §3 package map, §4 "Agent Offering", §5 `agent_versions.status` migration, §6 step 4). Pure relocation first (behavior-identical), then four deepenings via TDD. Controllers stay route-grouped (`controller.biz.agent` at `/api/agents`, `controller.biz.catalogue` at `/api/catalogue` — only their imports change). Prior slices already created `identity`, `ledger.wallet`, `ledger.settlement`; migrations `V1`–`V14` are immutable, so the new migration is **`V15`** (spec §5 calls it `V13`, but slices 2/3 already consumed `V13`=`wallet_version` and `V14`=`settlements` — the real next number is `V15`).

**Tech Stack:** Java 21, Spring Boot 3.x, COLA reactor, JPA/Hibernate, Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (auto-skip without Docker); Next.js 16 + TypeScript + vitest/MSW.

## Global Constraints

- **Suite green at every commit:** `mvn -f backend/pom.xml -q -B test` → BUILD SUCCESS, 0 failures. Docker is unavailable locally, so the Testcontainers `*IntegrationTest`s auto-skip; the **version-lifecycle/supersession** invariant (one ACTIVE per agent + DEPRECATED history) and the **storefront persistence** round-trip are validated by CI-gated integration tests. Capture the post-slice-3 baseline count first (`git tag` `redivision-3-ledger-settlement`); relocation (Task 1) must leave it identical; deepening tasks add the deltas noted per task.
- **Frontend (Task 5 only):** `npx vitest run` in `frontend/` → all green.
- **COLA layering compiler-enforced;** `hireai-domain`/`hireai-utility` carry no Spring. The new `AgentVersionStatus` enum, `Media` VO, `StorefrontModel`, and the three lifecycle domain services are plain Java.
- **Additive migration only:** new **`V15`**; `V1`–`V14` immutable, never renumbered. One-ACTIVE-per-agent enforced by a **partial unique index** (`WHERE status = 'ACTIVE'`).
- **Routes:** `/api/agents/*` and `/api/catalogue/*` unchanged except the **one consequential change** — `PUT /api/agents/{id}/pricing` is **replaced** by `POST /api/agents/{id}/versions`; three new optional endpoints `POST /api/agents/{id}/suspend|deactivate|reactivate` are added.
- **Stage ONLY `backend/`** (Tasks 1–4, 6) and **`frontend/`** (Task 5) — never `git add -A` (the tree has unrelated untracked docs/junk + an embedded `.claude/` repo). **No `Co-Authored-By`** trailer. Windows / Git Bash for shell steps.
- **OUT OF SCOPE (spec §7 — do NOT implement):** webhook reachability probe at activation; `is_featured` write path; multi-factor matcher / epsilon-greedy; the reputation-driven AUTO-suspend (that's Module 5). `DRAFT` is declared but not produced (reserved for a future stage-before-publish flow); publish lands a new version directly `ACTIVE`.

## File Structure

After this slice the `offering` subdomain owns (controllers stay route-grouped, unchanged location):

```
hireai-domain      com.hireai.domain.biz.offering
                     agent/      model/{AgentModel, AgentVersionModel, Pricing}
                                 enums/{AgentStatus, AgentVersionStatus}
                                 info/{AgentRegisterInfo, AgentCandidate, PublishVersionInfo}
                                 event/{AgentRegistered, AgentActivated}DomainEvent
                                 repository/{AgentRepository, AgentQuery}
                                 service/{AgentRegister,AgentActivate,AgentSuspend,AgentReactivate,AgentDeactivate}DomainService(+impl/)
                     storefront/ model/{StorefrontModel(root), Media(VO)}
                                 info/ProfileUpdateInfo
                                 repository/StorefrontRepository
hireai-application com.hireai.application.biz.offering
                     agent/      Agent{Read,Write,Storefront}AppService(+impl/)
                     catalogue/  CatalogueReadAppService(+impl/)
hireai-repository  com.hireai.infrastructure.repository.offering
                     agent/      AgentDO, AgentVersionDO, *JpaRepository, AgentRepositoryImpl
                     storefront/ StorefrontDO, StorefrontJpaRepository, StorefrontRepositoryImpl
                     catalogue/  JdbcCatalogueQueryDao, JdbcBuilderStatsQueryDao, JdbcBuilderEarningsQueryDao
```

New migration: `backend/hireai-main/src/main/resources/db/migration/V15__agent_version_status.sql`.
Unchanged location (imports update only): `controller.biz.agent.*`, `controller.biz.catalogue.*`, `application.config.DomainServiceConfig`, `application.biz.task.impl.DirectBookingAppServiceImpl` (reads storefront `listed`).

---

### Task 1: Relocate `agent` + `catalogue` → `offering` (mechanical, behavior-identical)

Move five package trees (domain `agent`; application `agent` + `catalogue`; repository `agent` + `catalogue`) plus their test packages under `offering`, and rewrite their fully-qualified prefixes everywhere. One atomic compile-green unit; the existing suite is the test. **Controllers stay put** (`controller.biz.agent`, `controller.biz.catalogue`) — only their imports change.

**Why a `([.;])` capture in the sed:** three of the prefixes have classes declared *directly* in the package (e.g. `AgentReadAppService` → `package com.hireai.application.biz.agent;`, `AgentDO` → `package com.hireai.infrastructure.repository.agent;`), so a trailing-dot-only match would miss those `;`-terminated package lines; and `application.biz.agent` is a **prefix of** `application.biz.agentcallback` (which must NOT move this slice). Matching `agent` followed by exactly `.` or `;` rewrites both subpackage refs and bare package decls while never touching `agentcallback`.

- [ ] **Step 1: Create the `offering` parents and move the directories**

Run (Git Bash, from repo root):

```bash
cd backend
mkdir -p hireai-domain/src/main/java/com/hireai/domain/biz/offering
mkdir -p hireai-application/src/main/java/com/hireai/application/biz/offering
mkdir -p hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering
mkdir -p hireai-main/src/test/java/com/hireai/domain/biz/offering
mkdir -p hireai-main/src/test/java/com/hireai/application/biz/offering

# main source
git mv hireai-domain/src/main/java/com/hireai/domain/biz/agent                hireai-domain/src/main/java/com/hireai/domain/biz/offering/agent
git mv hireai-application/src/main/java/com/hireai/application/biz/agent       hireai-application/src/main/java/com/hireai/application/biz/offering/agent
git mv hireai-application/src/main/java/com/hireai/application/biz/catalogue   hireai-application/src/main/java/com/hireai/application/biz/offering/catalogue
git mv hireai-repository/src/main/java/com/hireai/infrastructure/repository/agent      hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/agent
git mv hireai-repository/src/main/java/com/hireai/infrastructure/repository/catalogue  hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/catalogue

# tests (component packages)
git mv hireai-main/src/test/java/com/hireai/domain/biz/agent          hireai-main/src/test/java/com/hireai/domain/biz/offering/agent
git mv hireai-main/src/test/java/com/hireai/application/biz/agent      hireai-main/src/test/java/com/hireai/application/biz/offering/agent

# tests (top-level slice packages com.hireai.agent + com.hireai.catalogue → com.hireai.offering)
git mv hireai-main/src/test/java/com/hireai/agent hireai-main/src/test/java/com/hireai/offering
git mv hireai-main/src/test/java/com/hireai/catalogue/CatalogueReadAppServiceImplTest.java  hireai-main/src/test/java/com/hireai/offering/CatalogueReadAppServiceImplTest.java
git mv hireai-main/src/test/java/com/hireai/catalogue/CatalogueQueryDaoIntegrationTest.java hireai-main/src/test/java/com/hireai/offering/CatalogueQueryDaoIntegrationTest.java
rmdir hireai-main/src/test/java/com/hireai/catalogue
```

(`com.hireai.agent` test dir holds 5 files — AgentProfileModelTest, AgentProfileRepositoryIntegrationTest, AgentStorefrontAppServiceTest, AgentRegistrationIntegrationTest, BuilderStatsQueryDaoIntegrationTest; `com.hireai.catalogue` holds 2. Both merge into `com.hireai.offering` — no class-name collisions.)

- [ ] **Step 2: Rewrite the package prefixes across all backend Java files**

Run (Git Bash, from repo ROOT). One `sed -E` invocation; the `([.;])` capture is preserved via `\1`. Controllers (`controller.biz.agent`, `controller.biz.catalogue`) and `application.biz.agentcallback` are deliberately untouched:

```bash
grep -rlE 'com\.hireai\.(domain\.biz\.agent|application\.biz\.agent|application\.biz\.catalogue|infrastructure\.repository\.agent|infrastructure\.repository\.catalogue|agent|catalogue)[.;]' --include='*.java' backend \
  | xargs sed -E -i \
      -e 's/com\.hireai\.domain\.biz\.agent([.;])/com.hireai.domain.biz.offering.agent\1/g' \
      -e 's/com\.hireai\.application\.biz\.agent([.;])/com.hireai.application.biz.offering.agent\1/g' \
      -e 's/com\.hireai\.application\.biz\.catalogue([.;])/com.hireai.application.biz.offering.catalogue\1/g' \
      -e 's/com\.hireai\.infrastructure\.repository\.agent([.;])/com.hireai.infrastructure.repository.offering.agent\1/g' \
      -e 's/com\.hireai\.infrastructure\.repository\.catalogue([.;])/com.hireai.infrastructure.repository.offering.catalogue\1/g' \
      -e 's/com\.hireai\.agent([.;])/com.hireai.offering\1/g' \
      -e 's/com\.hireai\.catalogue([.;])/com.hireai.offering\1/g'
```

The longer-prefix rules run before the bare `com.hireai.agent`/`com.hireai.catalogue` rules on each line, so an import like `com.hireai.domain.biz.agent.model.X` is rewritten to `...offering.agent.model.X` first and never re-matched by the short rule (it no longer contains `com.hireai.agent`).

- [ ] **Step 3: Verify no stale references remain, controllers + agentcallback untouched**

```bash
grep -rnE 'com\.hireai\.(domain\.biz\.agent|application\.biz\.agent|application\.biz\.catalogue|infrastructure\.repository\.agent|infrastructure\.repository\.catalogue)[.;]' --include='*.java' backend && echo "STALE REFS!" || echo "(clean prefixes)"
grep -rnE 'com\.hireai\.(agent|catalogue)[.;]' --include='*.java' backend && echo "STALE TEST PKGS!" || echo "(clean test pkgs)"
# controllers + agentcallback must STILL match (unchanged):
grep -rlE 'com\.hireai\.controller\.biz\.(agent|catalogue)' --include='*.java' backend  # expect matches
grep -rlE 'com\.hireai\.application\.biz\.agentcallback' --include='*.java' backend       # expect matches
```

Expected: `(clean prefixes)` and `(clean test pkgs)`; the controller + agentcallback greps still list files.

- [ ] **Step 4: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures; test count **identical** to the slice-3 baseline (relocation adds/removes nothing).

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "refactor(offering): relocate agent + catalogue packages into the offering subdomain"
```

---

### Task 2: AgentVersion lifecycle + publish-new-version supersession

Add `AgentVersionStatus` (`DRAFT`/`ACTIVE`/`DEPRECATED`); stop hard-loading version 1; support many versions per agent with **exactly one ACTIVE**; replace the in-place `updateCommercials` mutation with a **publish-new-version** use case that creates the next `ACTIVE` version (carrying over the prior version's `outputSpec` + `webhookUrl`, incrementing `versionNumber`) and demotes the prior `ACTIVE` → `DEPRECATED` in the same transaction. Migration `V15` adds `agent_versions.status` (backfill → `ACTIVE`) + a partial-unique index. Endpoint `POST /api/agents/{id}/versions` replaces `PUT /api/agents/{id}/pricing`.

**Files (relative to `backend/`, post-Task-1 paths):**
- Create: `hireai-domain/.../offering/agent/enums/AgentVersionStatus.java`
- Create: `hireai-domain/.../offering/agent/info/PublishVersionInfo.java`
- Create: `hireai-controller/.../controller/biz/agent/dto/PublishVersionRequest.java`
- Create: `hireai-main/src/main/resources/db/migration/V15__agent_version_status.sql`
- Create test: `hireai-main/src/test/java/com/hireai/domain/biz/offering/agent/enums/AgentVersionStatusTest.java`
- Create test: `hireai-main/src/test/java/com/hireai/offering/AgentVersionSupersessionIntegrationTest.java` (CI-gated)
- Modify: `AgentVersionModel`, `AgentModel`, `AgentRepository`, `AgentVersionDO`, `AgentVersionJpaRepository`, `AgentRepositoryImpl`, `AgentWriteAppService(+impl)`, `AgentController`, `AgentDTO`, `AgentModel2DTOConverter`
- Modify tests: `AgentVersionModelTest`, `AgentWriteAppServiceImplTest`, `AgentRegistrationIntegrationTest`, `AgentControllerStorefrontTest`
- Delete: `hireai-domain/.../offering/agent/info/PricingUpdateInfo.java`, `hireai-controller/.../controller/biz/agent/dto/UpdatePricingRequest.java`

- [ ] **Step 1: Migration `V15`**

Create `backend/hireai-main/src/main/resources/db/migration/V15__agent_version_status.sql`:

```sql
-- V15: agent_versions lifecycle status + one-ACTIVE-per-agent.
-- Gives AgentVersion a real lifecycle (DRAFT/ACTIVE/DEPRECATED) so a builder can publish a new
-- version that supersedes the prior one: the old version is retained as history (DEPRECATED) and
-- the new one becomes the routable contract (ACTIVE). Existing single-version agents backfill to
-- ACTIVE via the column DEFAULT. (Spec §5 labels this V13; V13/V14 were taken by slices 2/3, so the
-- real next additive number is V15. V1-V14 are immutable.)
ALTER TABLE agent_versions
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'DEPRECATED'));

-- Exactly one ACTIVE version per agent (the current routable contract). Partial unique index;
-- publish-new-version demotes the prior ACTIVE to DEPRECATED BEFORE inserting the new ACTIVE row,
-- so the index never sees two ACTIVE rows for one agent.
CREATE UNIQUE INDEX uq_agent_versions_one_active
    ON agent_versions (agent_id) WHERE status = 'ACTIVE';
```

- [ ] **Step 2: `AgentVersionStatus` enum + its test**

`hireai-domain/.../offering/agent/enums/AgentVersionStatus.java`:

```java
package com.hireai.domain.biz.offering.agent.enums;

/**
 * Lifecycle of one agent version. ACTIVE is the agent's single current routable contract;
 * DEPRECATED versions are retained history (tasks bind to a specific version id, so a superseded
 * version's contract stays valid for in-flight work, Invariant #4). DRAFT is declared for a future
 * stage-before-publish flow and is not produced in this slice (publish lands a version ACTIVE).
 */
public enum AgentVersionStatus {
    DRAFT,
    ACTIVE,
    DEPRECATED
}
```

`hireai-main/src/test/java/com/hireai/domain/biz/offering/agent/enums/AgentVersionStatusTest.java`:

```java
package com.hireai.domain.biz.offering.agent.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionStatusTest {

    @Test
    void declaresLifecycleStates() {
        assertThat(AgentVersionStatus.values()).containsExactly(
                AgentVersionStatus.DRAFT,
                AgentVersionStatus.ACTIVE,
                AgentVersionStatus.DEPRECATED);
    }
}
```

- [ ] **Step 3: `AgentVersionModel` — add `status`, add `supersededBy`, drop `updateCommercials`**

Replace the whole class body of `hireai-domain/.../offering/agent/model/AgentVersionModel.java` with:

```java
package com.hireai.domain.biz.offering.agent.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Child entity of the {@link AgentModel} aggregate: one immutable, versioned snapshot of the
 * routable contract an Agent exposes — its output spec, the capability categories it serves, the
 * HTTPS webhook, an execution-time ceiling, pricing, and a lifecycle {@link AgentVersionStatus}.
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
    private final AgentVersionStatus status;
    private final Instant createdAt;

    public AgentVersionModel(UUID id, UUID agentId, int versionNumber, OutputSpec outputSpec,
                             List<String> capabilityCategories, String webhookUrl,
                             int maxExecutionSeconds, Pricing pricing,
                             AgentVersionStatus status, Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.capabilityCategories = List.copyOf(capabilityCategories);
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.pricing = pricing;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Factory: validates the contract and builds a fresh ACTIVE version snapshot. */
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
                normalisedCategories, webhookUrl.trim(), maxExecutionSeconds, pricing,
                AgentVersionStatus.ACTIVE, Instant.now());
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

    /**
     * Supersession: produce the NEXT version (versionNumber + 1, fresh id + createdAt, status
     * ACTIVE) carrying over this version's immutable contract (outputSpec, webhookUrl) with new
     * commercials. The CALLER demotes this version to DEPRECATED in the same transaction. Replaces
     * the old in-place updateCommercials mutation: in-flight tasks keep referencing the prior (now
     * DEPRECATED) version id, so no live contract is invalidated (Invariant #4).
     */
    public AgentVersionModel supersededBy(Pricing pricing, int maxExecutionSeconds,
                                          List<String> capabilityCategories) {
        requirePresent(pricing, "pricing");
        List<String> normalised = normaliseCategories(capabilityCategories);
        if (maxExecutionSeconds <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxExecutionSeconds must be positive");
        }
        return new AgentVersionModel(UUID.randomUUID(), agentId, versionNumber + 1, outputSpec,
                normalised, webhookUrl, maxExecutionSeconds, pricing,
                AgentVersionStatus.ACTIVE, Instant.now());
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    /** Pricing rule: a task budget must cover this version's price. */
    public void assertAffordable(Money budget) {
        if (budget.value().compareTo(pricing.price()) < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Budget " + budget + " is below the agent's price " + pricing.price());
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
    public AgentVersionStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] **Step 4: `AgentModel` — add `publishNewVersion`**

In `hireai-domain/.../offering/agent/model/AgentModel.java`, add (after `activate()`):

```java
    /**
     * Publish-new-version (supersession): produce a copy whose currentVersion is the NEXT version
     * (status ACTIVE, versionNumber + 1), carrying over the current version's outputSpec + webhookUrl
     * with new commercials. The repository demotes the prior ACTIVE version to DEPRECATED in the same
     * transaction. current_version_id advances to the new version only when the agent is ACTIVE
     * (a PENDING_VERIFICATION agent keeps null until activation, mirroring register()).
     */
    public AgentModel publishNewVersion(Pricing pricing, int maxExecutionSeconds,
                                        List<String> capabilityCategories) {
        if (currentVersion == null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Agent has no current version to supersede");
        }
        AgentVersionModel next = currentVersion.supersededBy(pricing, maxExecutionSeconds, capabilityCategories);
        UUID newCurrentVersionId = status == AgentStatus.ACTIVE ? next.id() : currentVersionId;
        return new AgentModel(id, ownerId, name, status, newCurrentVersionId,
                reputationScore, next, createdAt);
    }
```

(The class-level Javadoc's "v1 in this slice" line is now stale — update it to "carrying the current routable version (ACTIVE); prior versions are retained as DEPRECATED history".)

- [ ] **Step 5: `AgentRepository` — drop `updateCurrentVersion`, add `publishNewVersion`**

In `hireai-domain/.../offering/agent/repository/AgentRepository.java`, **delete** the `updateCurrentVersion(AgentVersionModel)` declaration (+ its Javadoc) and the now-unused `AgentVersionModel` import, and add:

```java
    /**
     * Persists a publish-new-version supersession atomically: demote the agent's prior ACTIVE
     * version to DEPRECATED, insert {@code agent.currentVersion()} as the new ACTIVE version, and
     * update the agent row (current_version_id). The prior version is retained as history.
     */
    void publishNewVersion(AgentModel agent);
```

- [ ] **Step 6: `AgentVersionDO` — add `status` column**

In `hireai-repository/.../offering/agent/AgentVersionDO.java`: add the field (after `price`), extend the constructor, add the getter.

Field:

```java
    @Column(name = "status", nullable = false)
    private String status;
```

Constructor — change the signature to insert `String status` before `Instant gmtCreate`, and assign it:

```java
    public AgentVersionDO(UUID id, UUID agentId, int versionNumber, String outputSpec,
                                 List<String> capabilityCategories, String webhookUrl,
                                 int maxExecutionSeconds, BigDecimal price, String status, Instant gmtCreate) {
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.capabilityCategories = capabilityCategories;
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.price = price;
        this.status = status;
        this.gmtCreate = gmtCreate;
    }
```

Getter (with the others):

```java
    public String getStatus() { return status; }
```

- [ ] **Step 7: `AgentVersionJpaRepository` — ACTIVE finder + bulk demote**

In `hireai-repository/.../offering/agent/AgentVersionJpaRepository.java`: **remove** `findByAgentIdAndVersionNumber` (no longer used), add `import org.springframework.data.jpa.repository.Modifying;`, and add:

```java
    /** Loads the current ACTIVE version of an agent (the routable contract). */
    Optional<AgentVersionDO> findByAgentIdAndStatus(UUID agentId, String status);

    /**
     * Bulk status transition (e.g. demote ACTIVE -> DEPRECATED) for one agent. Direct SQL UPDATE:
     * executes synchronously at call time, so during publish the prior ACTIVE row is DEPRECATED in
     * the DB BEFORE the new ACTIVE row is inserted — the partial-unique index never sees two ACTIVE
     * rows. flushAutomatically/clearAutomatically keep the persistence context consistent.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentVersionDO v SET v.status = :to WHERE v.agentId = :agentId AND v.status = :from")
    int updateStatus(@Param("agentId") UUID agentId, @Param("from") String from, @Param("to") String to);
```

- [ ] **Step 8: `AgentRepositoryImpl` — load by ACTIVE, set status on insert, `publishNewVersion`**

In `hireai-repository/.../offering/agent/AgentRepositoryImpl.java`: add `import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;`. Then:

(a) In `save(...)`, the version-insert branch must pass `status`:

```java
        AgentVersionModel version = agent.currentVersion();
        if (version != null && versionJpa.findById(version.id()).isEmpty()) {
            versionJpa.save(new AgentVersionDO(
                    version.id(), version.agentId(), version.versionNumber(),
                    outputSpecJsonMapper.toJson(version.outputSpec()),
                    version.capabilityCategories(), version.webhookUrl(),
                    version.maxExecutionSeconds(), version.pricing().price(),
                    version.status().name(), version.createdAt()));
        }
```

(b) Replace the whole `updateCurrentVersion(...)` method with `publishNewVersion(...)`:

```java
    @Override
    public void publishNewVersion(AgentModel agent) {
        AgentVersionModel v = agent.currentVersion();
        // 1. demote the prior ACTIVE version (direct SQL — runs before the insert).
        versionJpa.updateStatus(agent.id(),
                AgentVersionStatus.ACTIVE.name(), AgentVersionStatus.DEPRECATED.name());
        // 2. insert the new ACTIVE version (the prior one is retained as DEPRECATED history).
        versionJpa.save(new AgentVersionDO(
                v.id(), v.agentId(), v.versionNumber(),
                outputSpecJsonMapper.toJson(v.outputSpec()),
                v.capabilityCategories(), v.webhookUrl(),
                v.maxExecutionSeconds(), v.pricing().price(),
                v.status().name(), v.createdAt()));
        // 3. update the agent row (current_version_id now points at the new version when ACTIVE).
        agentJpa.save(new AgentDO(
                agent.id(), agent.ownerId(), agent.name(), agent.status().name(),
                agent.currentVersionId(), agent.reputationScore(), agent.createdAt()));
    }
```

(c) In `toModel(...)`, load the current ACTIVE version instead of version 1:

```java
    private AgentModel toModel(AgentDO entity) {
        AgentVersionModel version = versionJpa
                .findByAgentIdAndStatus(entity.getId(), AgentVersionStatus.ACTIVE.name())
                .map(this::toVersionModel)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent " + entity.getId() + " has no ACTIVE version"));
        return new AgentModel(
                entity.getId(), entity.getOwnerId(), entity.getName(),
                AgentStatus.valueOf(entity.getStatus()), entity.getCurrentVersionId(),
                entity.getReputationScore(), version, entity.getGmtCreate());
    }
```

(d) In `toVersionModel(...)`, map `status`:

```java
    private AgentVersionModel toVersionModel(AgentVersionDO entity) {
        return new AgentVersionModel(
                entity.getId(), entity.getAgentId(), entity.getVersionNumber(),
                outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getCapabilityCategories(), entity.getWebhookUrl(),
                entity.getMaxExecutionSeconds(), Pricing.of(entity.getPrice()),
                AgentVersionStatus.valueOf(entity.getStatus()), entity.getGmtCreate());
    }
```

(The `findActiveCandidates`/`findCandidateByVersionId` native queries are unchanged — they already isolate the current version via `a.current_version_id = v.id`, which supersession keeps pointing at the new ACTIVE row.)

- [ ] **Step 9: `PublishVersionInfo` (replaces `PricingUpdateInfo`)**

Create `hireai-domain/.../offering/agent/info/PublishVersionInfo.java`, then `git rm hireai-domain/.../offering/agent/info/PricingUpdateInfo.java`:

```java
package com.hireai.domain.biz.offering.agent.info;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carrier for publish-new-version: the new commercials (price / maxExecutionSeconds / categories).
 * outputSpec and webhookUrl are deliberately excluded — they carry over from the current version
 * (re-declaring the webhook/output contract in a new version is deferred, spec §7).
 */
public record PublishVersionInfo(BigDecimal price, int maxExecutionSeconds,
                                 List<String> capabilityCategories) {
}
```

- [ ] **Step 10: `AgentWriteAppService(+impl)` — replace `updatePricing` with `publishNewVersion`**

In `hireai-application/.../offering/agent/AgentWriteAppService.java`: swap the `PricingUpdateInfo` import for `PublishVersionInfo`, and replace the `updatePricing(...)` declaration (+ Javadoc) with:

```java
    /**
     * Publishes a NEW version of the agent's contract (supersedes the prior ACTIVE version, which is
     * retained as DEPRECATED history). The new version carries over the current version's outputSpec +
     * webhookUrl with the supplied commercials. The caller must be the agent owner; throws
     * {@code DomainException(NOT_FOUND)} otherwise. Returns the refreshed agent model.
     */
    AgentModel publishNewVersion(@NonNull UUID agentId, @NonNull UUID ownerId,
                                 @NonNull PublishVersionInfo info);
```

In `hireai-application/.../offering/agent/impl/AgentWriteAppServiceImpl.java`: swap the `PricingUpdateInfo`/`AgentVersionModel` imports for `PublishVersionInfo`; refactor `activate` to use a shared `loadOwned` helper; replace `updatePricing(...)` with `publishNewVersion(...)`:

```java
    @Override
    public void activate(UUID agentId, UUID ownerId) {
        AgentModel agent = loadOwned(agentId, ownerId);
        AgentModel active = activateDomainService.activate(agent);
        agentRepository.save(active);
        eventPublisher.publishEvent(new AgentActivatedDomainEvent(
                active.id(), active.ownerId(), active.currentVersionId(), Instant.now()));
        log.info("Agent {} activated by owner {}", agentId, ownerId);
    }

    /**
     * Publish-new-version. Pricing edits are permitted on PENDING_VERIFICATION agents too — the
     * version model exists from registration, so currentVersion() is always non-null after register().
     */
    @Override
    public AgentModel publishNewVersion(UUID agentId, UUID ownerId, PublishVersionInfo info) {
        AgentModel agent = loadOwned(agentId, ownerId);
        AgentModel updated = agent.publishNewVersion(Pricing.of(info.price()),
                info.maxExecutionSeconds(), info.capabilityCategories());
        agentRepository.publishNewVersion(updated);
        log.info("Agent {} published version {} by owner {} (price={}, maxExec={})",
                agentId, updated.currentVersion().versionNumber(), ownerId,
                info.price(), info.maxExecutionSeconds());
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent disappeared after publishing a version: " + agentId));
    }

    /** Load + owner check (Invariant #5): a foreign agent is indistinguishable from a missing one. */
    private AgentModel loadOwned(UUID agentId, UUID ownerId) {
        AgentModel agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));
        if (!agent.ownerId().equals(ownerId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId);
        }
        return agent;
    }
```

- [ ] **Step 11: Controller — `POST /api/agents/{id}/versions` replaces `PUT .../pricing`**

Create `hireai-controller/.../controller/biz/agent/dto/PublishVersionRequest.java`, then `git rm` `UpdatePricingRequest.java`:

```java
package com.hireai.controller.biz.agent.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /api/agents/{agentId}/versions — publishes a NEW agent version that
 * supersedes the current one. outputSpec + webhookUrl carry over from the current version; only
 * the commercials (price / maxExecutionSeconds / capabilityCategories) are re-declared.
 */
public record PublishVersionRequest(
        @NotNull @DecimalMin("0.00")
        @Digits(integer = 12, fraction = 2) BigDecimal price,
        @Min(1) int maxExecutionSeconds,
        @NotEmpty List<String> capabilityCategories) {
}
```

In `AgentController.java`: swap the `PricingUpdateInfo`/`UpdatePricingRequest` imports for `PublishVersionInfo`/`PublishVersionRequest`, and replace the `@PutMapping("/{agentId}/pricing")` method with:

```java
    @PostMapping("/{agentId}/versions")
    public WebResult<AgentDTO> publishVersion(@PathVariable("agentId") UUID agentId,
                                              @Valid @RequestBody PublishVersionRequest request) {
        UUID ownerId = currentUser.currentUserId();
        AgentModel updated = writeAppService.publishNewVersion(agentId, ownerId,
                new PublishVersionInfo(request.price(), request.maxExecutionSeconds(),
                        request.capabilityCategories()));
        return ok(AgentModel2DTOConverter.toDTO(updated));
    }
```

(`@PutMapping` stays imported — still used by the profile + review-response endpoints.)

- [ ] **Step 12: Surface version status on the DTO (additive, tolerant)**

In `hireai-controller/.../controller/biz/agent/dto/AgentDTO.java`, add a `status` field to the nested `AgentVersionDTO` record (after `price`):

```java
    public record AgentVersionDTO(
            UUID id,
            int versionNumber,
            OutputSpecDTO outputSpec,
            List<String> capabilityCategories,
            String webhookUrl,
            int maxExecutionSeconds,
            BigDecimal price,
            String status
    ) {
    }
```

In `AgentModel2DTOConverter.java`, pass `version.status().name()` as the final `AgentVersionDTO` argument.

- [ ] **Step 13: Rework `AgentVersionModelTest`**

In `hireai-main/src/test/java/com/hireai/domain/biz/offering/agent/model/AgentVersionModelTest.java`, add `import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;`. The 7-arg `AgentVersionModel.create(...)` calls are unchanged. Add a status assertion to `createBuildsVersionOneAndNormalisesCategories` (append `assertThat(v.status()).isEqualTo(AgentVersionStatus.ACTIVE);`). Replace the `// ---- updateCommercials tests ----` section (the three `updateCommercials*` methods) with:

```java
    // ---- supersededBy (publish-new-version) tests ----

    @Test
    void supersededByCreatesNextActiveVersionCarryingTheContract() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel original = AgentVersionModel.create(agentId, 1, spec(),
                List.of("summarisation"), "https://agent.example.com/hook",
                120, Pricing.of(new BigDecimal("5.00")));

        AgentVersionModel next = original.supersededBy(
                Pricing.of(new BigDecimal("99.50")), 300, List.of(" Translation "));

        // new commercials + incremented version number
        assertThat(next.pricing().price()).isEqualByComparingTo("99.50");
        assertThat(next.maxExecutionSeconds()).isEqualTo(300);
        assertThat(next.capabilityCategories()).containsExactly("translation");
        assertThat(next.versionNumber()).isEqualTo(original.versionNumber() + 1);
        assertThat(next.status()).isEqualTo(AgentVersionStatus.ACTIVE);
        // contract carried over
        assertThat(next.webhookUrl()).isEqualTo(original.webhookUrl());
        assertThat(next.outputSpec()).isEqualTo(original.outputSpec());
        // it is a NEW version (distinct identity), not an in-place edit
        assertThat(next.id()).isNotEqualTo(original.id());
    }

    @Test
    void supersededByRejectsZeroMaxExecutionSeconds() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
        assertThatThrownBy(() -> v.supersededBy(Pricing.of(BigDecimal.ONE), 0, List.of("summarisation")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void supersededByRejectsEmptyCategories() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
        assertThatThrownBy(() -> v.supersededBy(Pricing.of(BigDecimal.ONE), 60, List.of()))
                .isInstanceOf(DomainException.class);
    }
```

- [ ] **Step 14: Rework `AgentWriteAppServiceImplTest` (publish path)**

In `hireai-main/src/test/java/com/hireai/application/biz/offering/agent/impl/AgentWriteAppServiceImplTest.java`:
- In `FakeAgentRepository`, **delete** the `updateCurrentVersion(...)` override and add:

```java
        @Override public void publishNewVersion(AgentModel agent) {
            store.put(agent.id(), agent);
            if (agent.currentVersion() != null) {
                versionStore.put(agent.currentVersion().id(), agent.currentVersion());
            }
        }
```

- Swap the `PricingUpdateInfo` import for `PublishVersionInfo`. Replace the three `updatePricing*` tests with:

```java
    // ---- publishNewVersion tests ----

    @Test
    void publishNewVersionCreatesIncrementedActiveVersionAndReturnsRefreshedModel() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        UUID v1Id = repository.findById(agentId).orElseThrow().currentVersion().id();

        AgentModel result = service.publishNewVersion(agentId, ownerId,
                new PublishVersionInfo(new BigDecimal("99.50"), 120, List.of("Translation ")));

        assertThat(result.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(result.currentVersion().pricing().price()).isEqualByComparingTo("99.50");
        assertThat(result.currentVersion().capabilityCategories()).containsExactly("translation");
        assertThat(result.currentVersion().status())
                .isEqualTo(com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus.ACTIVE);
        assertThat(result.currentVersion().id()).isNotEqualTo(v1Id);
        assertThat(result.currentVersion().outputSpec()).isNotNull();
    }

    @Test
    void publishNewVersionRejectsForeignOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        assertThatThrownBy(() -> service.publishNewVersion(agentId, UUID.randomUUID(),
                new PublishVersionInfo(new BigDecimal("10.00"), 60, List.of("summarisation"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void publishNewVersionRejectsUnknownAgent() {
        assertThatThrownBy(() -> service.publishNewVersion(UUID.randomUUID(), UUID.randomUUID(),
                new PublishVersionInfo(new BigDecimal("10.00"), 60, List.of("summarisation"))))
                .isInstanceOf(DomainException.class);
    }
```

> Note: this test's `FakeAgentProfileRepository` / service construction is also retyped in Task 3 (StorefrontRepository) and Task 4 (three lifecycle domain services). Each task edits its own slice of this file.

- [ ] **Step 15: Rework `AgentRegistrationIntegrationTest`**

In `hireai-main/src/test/java/com/hireai/offering/AgentRegistrationIntegrationTest.java`: remove the `import ...PricingUpdateInfo;` and **delete** the `updatePricingPersistsNewCommercialsWithSameVersionIdentity` test (the "same version identity" assertion is now false — publish creates a new id; supersession is covered by the dedicated integration test below). All other tests stand (`registerPersistsPendingAgentWithVersionOne` still asserts `versionNumber()==1`).

- [ ] **Step 16: Supersession integration test (CI-gated)**

Create `hireai-main/src/test/java/com/hireai/offering/AgentVersionSupersessionIntegrationTest.java`. The Testcontainers harness (container, `@DynamicPropertySource`, `dockerAvailable`, autowired services + `JdbcTemplate jdbc`, `newOwner()` + `info(...)` helpers) is **identical to `AgentRegistrationIntegrationTest`** in the same package — copy it verbatim. The behavior under test (exercises **three** versions — v1 via register, then publish v2 and v3 — to prove the partial-unique index holds across repeated supersession and history accumulates):

```java
    @Test
    void publishNewVersionSupersedesPriorActiveAndKeepsHistory() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "summarisation", "5.00"));
        agentWriteAppService.activate(agentId, owner);

        AgentModel v1 = agentReadAppService.getForOwner(agentId, owner);
        UUID v1Id = v1.currentVersion().id();
        assertThat(v1.currentVersion().versionNumber()).isEqualTo(1);

        agentWriteAppService.publishNewVersion(agentId, owner,
                new PublishVersionInfo(new BigDecimal("12.00"), 200, List.of("summarisation")));
        agentWriteAppService.publishNewVersion(agentId, owner,
                new PublishVersionInfo(new BigDecimal("15.00"), 250, List.of("translation")));

        AgentModel current = agentReadAppService.getForOwner(agentId, owner);
        assertThat(current.currentVersion().versionNumber()).isEqualTo(3);
        assertThat(current.currentVersion().pricing().price()).isEqualByComparingTo("15.00");
        assertThat(current.currentVersion().capabilityCategories()).containsExactly("translation");
        assertThat(current.currentVersion().id()).isNotEqualTo(v1Id);
        // agents.current_version_id tracks the ACTIVE version
        assertThat(current.currentVersionId()).isEqualTo(current.currentVersion().id());

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_versions WHERE agent_id = ? AND status = 'ACTIVE'",
                Integer.class, agentId);
        assertThat(activeCount).isEqualTo(1);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_versions WHERE agent_id = ?", Integer.class, agentId);
        assertThat(total).isEqualTo(3);
        String v1Status = jdbc.queryForObject(
                "SELECT status FROM agent_versions WHERE id = ?", String.class, v1Id);
        assertThat(v1Status).isEqualTo("DEPRECATED");
    }
```

Imports needed in addition to the copied harness: `com.hireai.domain.biz.offering.agent.info.PublishVersionInfo`, `com.hireai.domain.biz.offering.agent.model.AgentModel`, `java.math.BigDecimal`, `java.util.List`, `java.util.UUID`, AssertJ `assertThat`.

- [ ] **Step 17: Rework `AgentControllerStorefrontTest` (pricing → versions)**

In `hireai-main/src/test/java/com/hireai/controller/biz/agent/AgentControllerStorefrontTest.java`: add `import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;` and `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;`. Add the `status` arg to the `AgentVersionModel` constructor in the helper and rename it; rename the three `putPricing_*` tests:

```java
    private AgentModel versionedAgentModel(BigDecimal price, int maxExec, List<String> categories) {
        AgentVersionModel version = new AgentVersionModel(
                UUID.randomUUID(), AGENT_ID, 2,
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                categories, "https://agent.example.com/hook", maxExec,
                Pricing.of(price), AgentVersionStatus.ACTIVE, Instant.now());
        return new AgentModel(AGENT_ID, OWNER_ID, "Test Agent",
                AgentStatus.ACTIVE, version.id(), new BigDecimal("50.00"), version, Instant.now());
    }

    @Test
    void postVersion_happyPath_returns200WithUpdatedPrice() throws Exception {
        AgentModel updated = versionedAgentModel(new BigDecimal("99.50"), 120, List.of("translation"));
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(writeAppService.publishNewVersion(eq(AGENT_ID), eq(OWNER_ID), any()))
                .thenReturn(updated);

        mockMvc.perform(post("/api/agents/{id}/versions", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "price": 99.50,
                                  "maxExecutionSeconds": 120,
                                  "capabilityCategories": ["translation"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currentVersion.price").value(99.50));
    }

    @Test
    void postVersion_foreignOwner_returns404() throws Exception {
        UUID foreignId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(foreignId);
        when(writeAppService.publishNewVersion(eq(AGENT_ID), eq(foreignId), any()))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + AGENT_ID));

        mockMvc.perform(post("/api/agents/{id}/versions", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price": 10.00, "maxExecutionSeconds": 60, "capabilityCategories": ["summarisation"]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void postVersion_emptyCategories_returns400() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        mockMvc.perform(post("/api/agents/{id}/versions", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price": 10.00, "maxExecutionSeconds": 60, "capabilityCategories": []}
                                """))
                .andExpect(status().isBadRequest());
    }
```

(The `AgentProfileModel listedProfile()` helper + import in this file is retyped to `StorefrontModel` in Task 3.)

- [ ] **Step 18: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures. New unit tests (`AgentVersionStatusTest` + reworked supersededBy/publishNewVersion/postVersion) run locally; `AgentVersionSupersessionIntegrationTest` is SKIPPED locally (no Docker) and runs in CI.

- [ ] **Step 19: Commit**

```bash
git add backend/
git commit -m "feat(offering): AgentVersion lifecycle + publish-new-version supersession (V15)"
```

> NOTE FOR CONTROLLER: the one-ACTIVE-per-agent invariant, the demote-before-insert ordering, and the `V15` partial-unique index are exercised only by `AgentVersionSupersessionIntegrationTest` (Testcontainers) — validated in CI, not locally. Flag for the CI run.

---

### Task 3: Storefront → own `Storefront` aggregate + `Media` VO

Promote `AgentProfileModel` to a `StorefrontModel` aggregate root under `offering.storefront`, with a `Media` value object wrapping logo/cover/gallery (and owning the gallery-cap rule). The `agent_profiles` table is unchanged — `Media` is a pure domain reshape over the existing `logo_url`/`cover_url`/`gallery_urls` columns, so **no migration**. `StorefrontModel` keeps the full public API (`createDefault`/`updateContent`/`withLogo`/`withCover`/`addGalleryUrl`/`removeMedia`/`assertCanAddGallery`/`logoUrl`/`coverUrl`/`galleryUrls`/...) via delegators, so consumers + tests are mostly type-renames. The catalogue read (`JdbcCatalogueQueryDao`) is raw SQL over the same columns — untouched.

**Files:**
- Create: `hireai-domain/.../offering/storefront/model/Media.java`, `model/StorefrontModel.java`, `repository/StorefrontRepository.java`
- Move: `hireai-domain/.../offering/agent/info/ProfileUpdateInfo.java` → `offering/storefront/info/ProfileUpdateInfo.java`
- Create: `hireai-repository/.../offering/storefront/StorefrontDO.java`, `StorefrontJpaRepository.java`, `StorefrontRepositoryImpl.java`
- Delete: `hireai-domain/.../offering/agent/model/AgentProfileModel.java`, `agent/repository/AgentProfileRepository.java`; `hireai-repository/.../offering/agent/AgentProfileDO.java`, `AgentProfileJpaRepository.java`, `AgentProfileRepositoryImpl.java`
- Modify: `AgentStorefrontAppService(+impl)`, `AgentWriteAppServiceImpl`, `AgentController` (ProfileUpdateInfo import), `AgentProfileViewDTO`, `application.biz.task.impl.DirectBookingAppServiceImpl`
- Modify tests: `AgentProfileModelTest`→`StorefrontModelTest` (+ new `MediaTest`), `AgentStorefrontAppServiceTest`, `AgentProfileRepositoryIntegrationTest`→`StorefrontRepositoryIntegrationTest`, `AgentWriteAppServiceImplTest`, `AgentControllerStorefrontTest`, `DirectBookingAppServiceTest`, `DirectBookingIntegrationTest`

- [ ] **Step 1: `Media` value object**

`hireai-domain/.../offering/storefront/model/Media.java`:

```java
package com.hireai.domain.biz.offering.storefront.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.util.ArrayList;
import java.util.List;

/**
 * Media value object for a {@link StorefrontModel}: the agent's logo, cover, and gallery image
 * URLs. Immutable — every change returns a new Media. Owns the gallery-capacity rule (the single
 * home for MAX_GALLERY), so callers (e.g. the media-upload flow) can fail fast before an upload.
 */
public record Media(String logoUrl, String coverUrl, List<String> galleryUrls) {

    public static final int MAX_GALLERY = 6;

    public Media {
        galleryUrls = galleryUrls == null ? List.of() : List.copyOf(galleryUrls);
    }

    public static Media empty() {
        return new Media(null, null, List.of());
    }

    public Media withLogo(String url) {
        return new Media(url, coverUrl, galleryUrls);
    }

    public Media withCover(String url) {
        return new Media(logoUrl, url, galleryUrls);
    }

    public void assertCanAddGallery() {
        if (galleryUrls.size() >= MAX_GALLERY) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Gallery is full (max " + MAX_GALLERY + " images)");
        }
    }

    public Media addGalleryUrl(String url) {
        assertCanAddGallery();
        List<String> next = new ArrayList<>(galleryUrls);
        next.add(url);
        return new Media(logoUrl, coverUrl, next);
    }

    /** Removes a media entry; kind is logo|cover|gallery. Unknown gallery URL is a no-op. */
    public Media remove(String kind, String url) {
        return switch (kind) {
            case "logo" -> withLogo(null);
            case "cover" -> withCover(null);
            case "gallery" -> {
                List<String> next = new ArrayList<>(galleryUrls);
                next.remove(url);
                yield new Media(logoUrl, coverUrl, next);
            }
            default -> throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unknown media kind: " + kind);
        };
    }
}
```

- [ ] **Step 2: `StorefrontModel` aggregate root**

`hireai-domain/.../offering/storefront/model/StorefrontModel.java`:

```java
package com.hireai.domain.biz.offering.storefront.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.util.List;
import java.util.UUID;

/**
 * Storefront aggregate root: an Agent's public marketing presence (1:1 with the Agent aggregate,
 * table agent_profiles). Holds the text content + listing flags and a {@link Media} value object
 * (logo/cover/gallery). Marketing concern only — never touches the routable contract. Immutable:
 * every change returns a new copy. Catalogue visibility = agents.status ACTIVE AND listed here.
 */
public final class StorefrontModel {

    public static final int MAX_TAGLINE = 160;
    public static final int MAX_TEXT = 8000;
    /** Re-exported so callers that reach the cap via the root need not import Media. */
    public static final int MAX_GALLERY = Media.MAX_GALLERY;

    private final UUID agentId;
    private final String tagline;
    private final String description;
    private final String sampleOutput;
    private final Media media;
    private final boolean listed;
    private final boolean featured;

    public StorefrontModel(UUID agentId, String tagline, String description, String sampleOutput,
                           Media media, boolean listed, boolean featured) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.media = media == null ? Media.empty() : media;
        this.listed = listed;
        this.featured = featured;
    }

    /** Factory for registration: empty, unlisted, unfeatured. */
    public static StorefrontModel createDefault(UUID agentId) {
        if (agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "agent id is required");
        }
        return new StorefrontModel(agentId, null, null, null, Media.empty(), false, false);
    }

    /** Builder edits the storefront text + listing toggle. */
    public StorefrontModel updateContent(String tagline, String description,
                                         String sampleOutput, boolean listed) {
        return new StorefrontModel(agentId,
                limited(tagline, MAX_TAGLINE, "tagline"),
                limited(description, MAX_TEXT, "description"),
                limited(sampleOutput, MAX_TEXT, "sample output"),
                media, listed, featured);
    }

    public StorefrontModel withLogo(String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.withLogo(url), listed, featured);
    }

    public StorefrontModel withCover(String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.withCover(url), listed, featured);
    }

    /** Gallery capacity rule — delegates to the Media VO (the single home for the max). */
    public void assertCanAddGallery() {
        media.assertCanAddGallery();
    }

    public StorefrontModel addGalleryUrl(String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.addGalleryUrl(url), listed, featured);
    }

    /** Removes a media entry; kind is logo|cover|gallery. */
    public StorefrontModel removeMedia(String kind, String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.remove(kind, url), listed, featured);
    }

    private static String limited(String value, int max, String field) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    field + " must be at most " + max + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public UUID agentId() { return agentId; }
    public String tagline() { return tagline; }
    public String description() { return description; }
    public String sampleOutput() { return sampleOutput; }
    public Media media() { return media; }
    public boolean listed() { return listed; }
    public boolean featured() { return featured; }

    // Convenience read accessors (delegate to Media) so adapters/DTOs need not reach through media().
    public String logoUrl() { return media.logoUrl(); }
    public String coverUrl() { return media.coverUrl(); }
    public List<String> galleryUrls() { return media.galleryUrls(); }
}
```

- [ ] **Step 3: `StorefrontRepository` + move `ProfileUpdateInfo`**

`hireai-domain/.../offering/storefront/repository/StorefrontRepository.java`:

```java
package com.hireai.domain.biz.offering.storefront.repository;

import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the Storefront aggregate (1:1 with the Agent root, table agent_profiles). */
public interface StorefrontRepository {

    StorefrontModel save(StorefrontModel storefront);

    Optional<StorefrontModel> findByAgentId(UUID agentId);
}
```

Move `ProfileUpdateInfo` for cohesion (Git Bash, from `backend/`):

```bash
mkdir -p hireai-domain/src/main/java/com/hireai/domain/biz/offering/storefront/info
git mv hireai-domain/src/main/java/com/hireai/domain/biz/offering/agent/info/ProfileUpdateInfo.java \
       hireai-domain/src/main/java/com/hireai/domain/biz/offering/storefront/info/ProfileUpdateInfo.java
sed -i 's/^package com\.hireai\.domain\.biz\.offering\.agent\.info;/package com.hireai.domain.biz.offering.storefront.info;/' \
       hireai-domain/src/main/java/com/hireai/domain/biz/offering/storefront/info/ProfileUpdateInfo.java
grep -rlE 'com\.hireai\.domain\.biz\.offering\.agent\.info\.ProfileUpdateInfo' --include='*.java' backend \
  | xargs sed -i 's/com\.hireai\.domain\.biz\.offering\.agent\.info\.ProfileUpdateInfo/com.hireai.domain.biz.offering.storefront.info.ProfileUpdateInfo/g'
```

- [ ] **Step 4: Infrastructure — `StorefrontDO` / JpaRepository / RepositoryImpl**

Create the three files, then delete the five old agent-storefront files.

`hireai-repository/.../offering/storefront/StorefrontDO.java`:

```java
package com.hireai.infrastructure.repository.offering.storefront;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA persistence entity for agent_profiles (1:1 with agents). {@code gallery_urls} is a Postgres
 * TEXT[] mapped to a {@code List<String>}. Separate from the domain {@link com.hireai.domain.biz.offering.storefront.model.StorefrontModel}
 * so the domain stays framework-free.
 */
@Entity
@Table(name = "agent_profiles")
public class StorefrontDO {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "description")
    private String description;

    @Column(name = "sample_output")
    private String sampleOutput;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "cover_url")
    private String coverUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "gallery_urls", columnDefinition = "text[]", nullable = false)
    private List<String> galleryUrls;

    @Column(name = "is_listed", nullable = false)
    private boolean listed;

    @Column(name = "is_featured", nullable = false)
    private boolean featured;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    @Column(name = "gmt_modified", nullable = false)
    private Instant gmtModified;

    protected StorefrontDO() {
    }

    public StorefrontDO(UUID agentId, String tagline, String description,
                        String sampleOutput, String logoUrl, String coverUrl,
                        List<String> galleryUrls, boolean listed, boolean featured,
                        Instant gmtCreate, Instant gmtModified) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.logoUrl = logoUrl;
        this.coverUrl = coverUrl;
        this.galleryUrls = galleryUrls;
        this.listed = listed;
        this.featured = featured;
        this.gmtCreate = gmtCreate;
        this.gmtModified = gmtModified;
    }

    public UUID getAgentId() { return agentId; }
    public String getTagline() { return tagline; }
    public String getDescription() { return description; }
    public String getSampleOutput() { return sampleOutput; }
    public String getLogoUrl() { return logoUrl; }
    public String getCoverUrl() { return coverUrl; }
    public List<String> getGalleryUrls() { return galleryUrls; }
    public boolean isListed() { return listed; }
    public boolean isFeatured() { return featured; }
    public Instant getGmtCreate() { return gmtCreate; }
    public Instant getGmtModified() { return gmtModified; }
}
```

`hireai-repository/.../offering/storefront/StorefrontJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.offering.storefront;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data JPA repository for agent_profiles rows. Internal to infrastructure. */
public interface StorefrontJpaRepository extends JpaRepository<StorefrontDO, UUID> {
}
```

`hireai-repository/.../offering/storefront/StorefrontRepositoryImpl.java`:

```java
package com.hireai.infrastructure.repository.offering.storefront;

import com.hireai.domain.biz.offering.storefront.model.Media;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of {@link StorefrontRepository}. Maps {@code StorefrontModel} &lt;-&gt;
 * {@link StorefrontDO}, splitting/joining the {@link Media} VO over the logo/cover/gallery columns.
 * save() upserts by PK and preserves {@code gmt_create} on update (single writer = the owner).
 */
@Repository
public class StorefrontRepositoryImpl implements StorefrontRepository {

    private final StorefrontJpaRepository jpa;

    public StorefrontRepositoryImpl(StorefrontJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public StorefrontModel save(StorefrontModel storefront) {
        Instant now = Instant.now();
        Instant created = jpa.findById(storefront.agentId())
                .map(StorefrontDO::getGmtCreate)
                .orElse(now);
        Media media = storefront.media();
        jpa.save(new StorefrontDO(
                storefront.agentId(), storefront.tagline(), storefront.description(),
                storefront.sampleOutput(), media.logoUrl(), media.coverUrl(), media.galleryUrls(),
                storefront.listed(), storefront.featured(), created, now));
        return storefront;
    }

    @Override
    public Optional<StorefrontModel> findByAgentId(UUID agentId) {
        return jpa.findById(agentId).map(this::toModel);
    }

    private StorefrontModel toModel(StorefrontDO entity) {
        List<String> gallery = entity.getGalleryUrls() != null
                ? List.copyOf(entity.getGalleryUrls())
                : List.of();
        return new StorefrontModel(
                entity.getAgentId(), entity.getTagline(), entity.getDescription(),
                entity.getSampleOutput(),
                new Media(entity.getLogoUrl(), entity.getCoverUrl(), gallery),
                entity.isListed(), entity.isFeatured());
    }
}
```

Delete the superseded files:

```bash
cd backend
git rm hireai-domain/src/main/java/com/hireai/domain/biz/offering/agent/model/AgentProfileModel.java
git rm hireai-domain/src/main/java/com/hireai/domain/biz/offering/agent/repository/AgentProfileRepository.java
git rm hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/agent/AgentProfileDO.java
git rm hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/agent/AgentProfileJpaRepository.java
git rm hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/agent/AgentProfileRepositoryImpl.java
```

- [ ] **Step 5: Retype the consumers (app services, DTO, DirectBooking)**

Mechanical rename across the consuming files — `AgentProfileModel`→`StorefrontModel`, `AgentProfileRepository`→`StorefrontRepository`, and the import package `offering.agent.{model,repository}`→`offering.storefront.{model,repository}`:

```bash
cd backend
grep -rlE 'AgentProfileModel|AgentProfileRepository' --include='*.java' \
    hireai-application hireai-controller \
  | xargs sed -i \
      -e 's/com\.hireai\.domain\.biz\.offering\.agent\.model\.AgentProfileModel/com.hireai.domain.biz.offering.storefront.model.StorefrontModel/g' \
      -e 's/com\.hireai\.domain\.biz\.offering\.agent\.repository\.AgentProfileRepository/com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository/g' \
      -e 's/AgentProfileModel/StorefrontModel/g' \
      -e 's/AgentProfileRepository/StorefrontRepository/g'
```

This updates: `AgentStorefrontAppService`, `AgentStorefrontAppServiceImpl` (field `profileRepository` keeps its name; type becomes `StorefrontRepository`; `loadProfile` returns `StorefrontModel.createDefault`), `AgentWriteAppServiceImpl` (field `agentProfileRepository` keeps its name; `StorefrontModel.createDefault`), `AgentProfileViewDTO` (`from(StorefrontModel)` — body uses delegators, unchanged), and `application.biz.task.impl.DirectBookingAppServiceImpl` (`StorefrontRepository` field + `StorefrontModel::listed` method ref). The `StorefrontModel` API is identical to the old `AgentProfileModel`, so only the type names change. Verify the JdbcCatalogueQueryDao + CatalogueReadAppService were NOT touched (raw SQL, no model dependency).

- [ ] **Step 6: Retype the tests**

Apply the same rename across the affected test files, then handle the two renamed test files:

```bash
cd backend
grep -rlE 'AgentProfileModel|AgentProfileRepository' --include='*.java' hireai-main/src/test \
  | xargs sed -i \
      -e 's/com\.hireai\.domain\.biz\.offering\.agent\.model\.AgentProfileModel/com.hireai.domain.biz.offering.storefront.model.StorefrontModel/g' \
      -e 's/com\.hireai\.domain\.biz\.offering\.agent\.repository\.AgentProfileRepository/com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository/g' \
      -e 's/AgentProfileModel/StorefrontModel/g' \
      -e 's/AgentProfileRepository/StorefrontRepository/g'
# rename the two storefront-specific test files to match the new aggregate name
git mv hireai-main/src/test/java/com/hireai/offering/AgentProfileModelTest.java \
       hireai-main/src/test/java/com/hireai/offering/StorefrontModelTest.java
git mv hireai-main/src/test/java/com/hireai/offering/AgentProfileRepositoryIntegrationTest.java \
       hireai-main/src/test/java/com/hireai/offering/StorefrontRepositoryIntegrationTest.java
sed -i 's/class AgentProfileModelTest/class StorefrontModelTest/' \
       hireai-main/src/test/java/com/hireai/offering/StorefrontModelTest.java
sed -i 's/class AgentProfileRepositoryIntegrationTest/class StorefrontRepositoryIntegrationTest/' \
       hireai-main/src/test/java/com/hireai/offering/StorefrontRepositoryIntegrationTest.java
```

This retypes `StorefrontModelTest` (former AgentProfileModelTest — `StorefrontModel.MAX_GALLERY`/`MAX_TAGLINE`/`createDefault`/... all preserved), `AgentStorefrontAppServiceTest` (`mock(StorefrontRepository.class)`), `StorefrontRepositoryIntegrationTest`, `AgentWriteAppServiceImplTest` (`FakeAgentProfileRepository` class is retyped to implement `StorefrontRepository` with `StorefrontModel` — rename the class to `FakeStorefrontRepository` is optional; the `implements StorefrontRepository` line and `Map<UUID, StorefrontModel>` come from the sed), `AgentControllerStorefrontTest` (`listedProfile()` returns `StorefrontModel`), `DirectBookingAppServiceTest` (`StorefrontModel.createDefault`), `DirectBookingIntegrationTest`.

- [ ] **Step 7: Add a `MediaTest` for the VO**

`hireai-main/src/test/java/com/hireai/offering/MediaTest.java`:

```java
package com.hireai.offering;

import com.hireai.domain.biz.offering.storefront.model.Media;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaTest {

    @Test
    void galleryCapEnforcedByTheVo() {
        Media media = Media.empty();
        for (int i = 0; i < Media.MAX_GALLERY; i++) {
            media = media.addGalleryUrl("https://img.example.com/" + i + ".png");
        }
        final Media full = media;
        assertThatThrownBy(() -> full.addGalleryUrl("https://img.example.com/over.png"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
    }

    @Test
    void removeGalleryAndLogo() {
        Media media = Media.empty()
                .withLogo("https://x/l.png")
                .addGalleryUrl("a.png")
                .addGalleryUrl("b.png");
        media = media.remove("gallery", "a.png").remove("logo", null);
        assertThat(media.galleryUrls()).containsExactly("b.png");
        assertThat(media.logoUrl()).isNull();
    }
}
```

- [ ] **Step 8: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures. `StorefrontRepositoryIntegrationTest` SKIPPED locally (no Docker).

- [ ] **Step 9: Commit**

```bash
git add backend/
git commit -m "feat(offering): promote storefront to its own aggregate with a Media VO"
```

---

### Task 4: Agent SUSPEND / DEACTIVATE / REACTIVATE

Wire the existing `AgentStatus.SUSPENDED`/`DEACTIVATED` values with guarded aggregate transitions, per-transition domain services (matching the existing `AgentActivateDomainService` pattern), owner-checked app-service methods, and `POST /api/agents/{id}/suspend|deactivate|reactivate` endpoints. Routing/catalogue already filter `status = 'ACTIVE'`, so a suspended/deactivated agent drops out automatically (no query change). **No** reputation-driven auto-suspend (Module 5, deferred).

**Files:**
- Modify: `AgentModel` (suspend/reactivate/deactivate)
- Create: `offering/agent/service/{AgentSuspend,AgentReactivate,AgentDeactivate}DomainService.java` + `service/impl/*Impl.java`
- Modify: `application.config.DomainServiceConfig` (3 beans)
- Modify: `AgentWriteAppService(+impl)` (suspend/reactivate/deactivate)
- Modify: `AgentController` (3 endpoints)
- Modify tests: `AgentModelTest`, `AgentWriteAppServiceImplTest`, `AgentControllerStorefrontTest`

- [ ] **Step 1: Aggregate transitions on `AgentModel`**

Add to `hireai-domain/.../offering/agent/model/AgentModel.java` (after `activate()`):

```java
    /** ACTIVE -> SUSPENDED: pause routing/booking; reversible via reactivate(). */
    public AgentModel suspend() {
        if (status != AgentStatus.ACTIVE) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only an ACTIVE agent can be suspended; was " + status);
        }
        return new AgentModel(id, ownerId, name, AgentStatus.SUSPENDED, currentVersionId,
                reputationScore, currentVersion, createdAt);
    }

    /** SUSPENDED -> ACTIVE: resume routing/booking. */
    public AgentModel reactivate() {
        if (status != AgentStatus.SUSPENDED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only a SUSPENDED agent can be reactivated; was " + status);
        }
        return new AgentModel(id, ownerId, name, AgentStatus.ACTIVE, currentVersionId,
                reputationScore, currentVersion, createdAt);
    }

    /** ACTIVE or SUSPENDED -> DEACTIVATED: terminal retirement (no return). */
    public AgentModel deactivate() {
        if (status != AgentStatus.ACTIVE && status != AgentStatus.SUSPENDED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only an ACTIVE or SUSPENDED agent can be deactivated; was " + status);
        }
        return new AgentModel(id, ownerId, name, AgentStatus.DEACTIVATED, currentVersionId,
                reputationScore, currentVersion, createdAt);
    }
```

- [ ] **Step 2: Three per-transition domain services**

`offering/agent/service/AgentSuspendDomainService.java`:

```java
package com.hireai.domain.biz.offering.agent.service;

import com.hireai.domain.biz.offering.agent.model.AgentModel;

/** Domain service for the agent SUSPEND transition (ACTIVE -> SUSPENDED). Framework-free;
 *  delegates to the aggregate's guarded transition. Registered in DomainServiceConfig. */
public interface AgentSuspendDomainService {
    AgentModel suspend(AgentModel agent);
}
```

`offering/agent/service/impl/AgentSuspendDomainServiceImpl.java`:

```java
package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentSuspendDomainService;

/** Stateless implementation of the suspend transition; delegates to the aggregate. */
public class AgentSuspendDomainServiceImpl implements AgentSuspendDomainService {
    @Override
    public AgentModel suspend(AgentModel agent) {
        return agent.suspend();
    }
}
```

`offering/agent/service/AgentReactivateDomainService.java`:

```java
package com.hireai.domain.biz.offering.agent.service;

import com.hireai.domain.biz.offering.agent.model.AgentModel;

/** Domain service for the agent REACTIVATE transition (SUSPENDED -> ACTIVE). Registered in DomainServiceConfig. */
public interface AgentReactivateDomainService {
    AgentModel reactivate(AgentModel agent);
}
```

`offering/agent/service/impl/AgentReactivateDomainServiceImpl.java`:

```java
package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentReactivateDomainService;

/** Stateless implementation of the reactivate transition; delegates to the aggregate. */
public class AgentReactivateDomainServiceImpl implements AgentReactivateDomainService {
    @Override
    public AgentModel reactivate(AgentModel agent) {
        return agent.reactivate();
    }
}
```

`offering/agent/service/AgentDeactivateDomainService.java`:

```java
package com.hireai.domain.biz.offering.agent.service;

import com.hireai.domain.biz.offering.agent.model.AgentModel;

/** Domain service for the agent DEACTIVATE transition (ACTIVE|SUSPENDED -> DEACTIVATED, terminal).
 *  Registered in DomainServiceConfig. */
public interface AgentDeactivateDomainService {
    AgentModel deactivate(AgentModel agent);
}
```

`offering/agent/service/impl/AgentDeactivateDomainServiceImpl.java`:

```java
package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.service.AgentDeactivateDomainService;

/** Stateless implementation of the deactivate transition; delegates to the aggregate. */
public class AgentDeactivateDomainServiceImpl implements AgentDeactivateDomainService {
    @Override
    public AgentModel deactivate(AgentModel agent) {
        return agent.deactivate();
    }
}
```

- [ ] **Step 3: Register the three beans in `DomainServiceConfig`**

In `hireai-application/.../application/config/DomainServiceConfig.java`, add imports for the three interfaces + impls (package `com.hireai.domain.biz.offering.agent.service[.impl]`) and the beans:

```java
    @Bean
    public AgentSuspendDomainService agentSuspendDomainService() {
        return new AgentSuspendDomainServiceImpl();
    }

    @Bean
    public AgentReactivateDomainService agentReactivateDomainService() {
        return new AgentReactivateDomainServiceImpl();
    }

    @Bean
    public AgentDeactivateDomainService agentDeactivateDomainService() {
        return new AgentDeactivateDomainServiceImpl();
    }
```

- [ ] **Step 4: App-service methods**

In `AgentWriteAppService.java`, add:

```java
    void suspend(@NonNull UUID agentId, @NonNull UUID ownerId);

    void reactivate(@NonNull UUID agentId, @NonNull UUID ownerId);

    void deactivate(@NonNull UUID agentId, @NonNull UUID ownerId);
```

In `AgentWriteAppServiceImpl.java`, inject the three domain services (add fields after `activateDomainService` so the `@RequiredArgsConstructor` order is `..., registerDomainService, activateDomainService, suspendDomainService, reactivateDomainService, deactivateDomainService, eventPublisher`) and add:

```java
    private final AgentSuspendDomainService suspendDomainService;
    private final AgentReactivateDomainService reactivateDomainService;
    private final AgentDeactivateDomainService deactivateDomainService;
```

```java
    @Override
    public void suspend(UUID agentId, UUID ownerId) {
        AgentModel agent = loadOwned(agentId, ownerId);
        agentRepository.save(suspendDomainService.suspend(agent));
        log.info("Agent {} suspended by owner {}", agentId, ownerId);
    }

    @Override
    public void reactivate(UUID agentId, UUID ownerId) {
        AgentModel agent = loadOwned(agentId, ownerId);
        agentRepository.save(reactivateDomainService.reactivate(agent));
        log.info("Agent {} reactivated by owner {}", agentId, ownerId);
    }

    @Override
    public void deactivate(UUID agentId, UUID ownerId) {
        AgentModel agent = loadOwned(agentId, ownerId);
        agentRepository.save(deactivateDomainService.deactivate(agent));
        log.info("Agent {} deactivated by owner {}", agentId, ownerId);
    }
```

(Place the three new `import com.hireai.domain.biz.offering.agent.service.Agent{Suspend,Reactivate,Deactivate}DomainService;` lines with the existing service imports. `loadOwned` already exists from Task 2.)

- [ ] **Step 5: Controller endpoints**

In `AgentController.java`, add (after `activate`):

```java
    @PostMapping("/{agentId}/suspend")
    public WebResult<AgentDTO> suspend(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        writeAppService.suspend(agentId, ownerId);
        return ok(AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId)));
    }

    @PostMapping("/{agentId}/reactivate")
    public WebResult<AgentDTO> reactivate(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        writeAppService.reactivate(agentId, ownerId);
        return ok(AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId)));
    }

    @PostMapping("/{agentId}/deactivate")
    public WebResult<AgentDTO> deactivate(@PathVariable("agentId") UUID agentId) {
        UUID ownerId = currentUser.currentUserId();
        writeAppService.deactivate(agentId, ownerId);
        return ok(AgentModel2DTOConverter.toDTO(readAppService.getForOwner(agentId, ownerId)));
    }
```

- [ ] **Step 6: `AgentModelTest` — transition tests**

Add to `hireai-main/src/test/java/com/hireai/domain/biz/offering/agent/model/AgentModelTest.java`:

```java
    @Test
    void suspendThenReactivateRoundTrips() {
        AgentModel active = registered(UUID.randomUUID()).activate();
        AgentModel suspended = active.suspend();
        assertThat(suspended.status()).isEqualTo(AgentStatus.SUSPENDED);
        assertThat(suspended.reactivate().status()).isEqualTo(AgentStatus.ACTIVE);
    }

    @Test
    void suspendRejectsNonActiveAgent() {
        AgentModel pending = registered(UUID.randomUUID());
        assertThatThrownBy(pending::suspend).isInstanceOf(DomainException.class);
    }

    @Test
    void reactivateRejectsNonSuspendedAgent() {
        AgentModel active = registered(UUID.randomUUID()).activate();
        assertThatThrownBy(active::reactivate).isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateIsTerminalFromActiveOrSuspended() {
        AgentModel active = registered(UUID.randomUUID()).activate();
        assertThat(active.deactivate().status()).isEqualTo(AgentStatus.DEACTIVATED);
        assertThat(active.suspend().deactivate().status()).isEqualTo(AgentStatus.DEACTIVATED);
        // terminal: cannot deactivate a pending agent, nor reactivate a deactivated one
        assertThatThrownBy(() -> registered(UUID.randomUUID()).deactivate())
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> active.deactivate().reactivate())
                .isInstanceOf(DomainException.class);
    }
```

- [ ] **Step 7: `AgentWriteAppServiceImplTest` — wire the new constructor + lifecycle tests**

The service construction must pass the three new domain services (Task 2 already added the publish path; Task 3 retyped the profile repo). Update the field/construction to:

```java
    private final AgentWriteAppService service = new AgentWriteAppServiceImpl(
            repository, profileRepository, new AgentRegisterDomainServiceImpl(),
            new AgentActivateDomainServiceImpl(), new AgentSuspendDomainServiceImpl(),
            new AgentReactivateDomainServiceImpl(), new AgentDeactivateDomainServiceImpl(), publisher);
```

(Add the three `import com.hireai.domain.biz.offering.agent.service.impl.Agent{Suspend,Reactivate,Deactivate}DomainServiceImpl;` lines.) Add tests:

```java
    @Test
    void suspendReactivateDeactivateTransitionOwnedAgent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        service.activate(agentId, ownerId);

        service.suspend(agentId, ownerId);
        assertThat(repository.findById(agentId).orElseThrow().status())
                .isEqualTo(AgentStatus.SUSPENDED);

        service.reactivate(agentId, ownerId);
        assertThat(repository.findById(agentId).orElseThrow().status())
                .isEqualTo(AgentStatus.ACTIVE);

        service.deactivate(agentId, ownerId);
        assertThat(repository.findById(agentId).orElseThrow().status())
                .isEqualTo(AgentStatus.DEACTIVATED);
    }

    @Test
    void suspendRejectsForeignOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        service.activate(agentId, ownerId);
        assertThatThrownBy(() -> service.suspend(agentId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
```

- [ ] **Step 8: `AgentControllerStorefrontTest` — endpoint smoke tests**

Add (uses the existing `post` import added in Task 2):

```java
    @Test
    void postSuspend_happyPath_returns200WithSuspendedStatus() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(readAppService.getForOwner(AGENT_ID, OWNER_ID))
                .thenReturn(versionedAgentModel(new BigDecimal("10.00"), 60, List.of("summarisation")).suspend());

        mockMvc.perform(post("/api/agents/{id}/suspend", AGENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    void postDeactivate_foreignOwner_returns404() throws Exception {
        UUID foreignId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(foreignId);
        org.mockito.Mockito.doThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + AGENT_ID))
                .when(writeAppService).deactivate(AGENT_ID, foreignId);

        mockMvc.perform(post("/api/agents/{id}/deactivate", AGENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
```

(`versionedAgentModel(...).suspend()` requires the model be ACTIVE — it is, per the helper — then transitions to SUSPENDED for the DTO.)

- [ ] **Step 9: Build + test**

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add backend/
git commit -m "feat(offering): manual agent suspend/deactivate/reactivate transitions"
```

---

### Task 5: Frontend — publish-new-version + lifecycle controls

Update the builder console to (a) publish a new version instead of editing pricing in place, and (b) suspend/deactivate/reactivate an agent from the manage-page header. Minimal, consistent with the existing UI kit; update the MSW handlers + the one affected vitest.

**Files:**
- Modify: `frontend/components/manage/TabPricing.tsx` (PUT /pricing → POST /versions)
- Modify: `frontend/app/builder/agents/[id]/page.tsx` (lifecycle controls in the header)
- Modify: `frontend/test/msw/handlers.ts` (versions + suspend/deactivate/reactivate handlers)
- Modify: `frontend/test/manage.test.tsx` (pricing→publish copy; add a suspend test)
- Modify: `frontend/lib/types.ts` (rename `UpdatePricingRequest` → `PublishVersionRequest`)

- [ ] **Step 1: `TabPricing.tsx` → publish a new version**

In `frontend/components/manage/TabPricing.tsx`, change the warning copy, the API call, and the button label. Replace `handleSave` + the copy line + the Button block:

```tsx
  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      const updated = await api<AgentDTO>(`/agents/${agentId}/versions`, {
        method: "POST",
        body: JSON.stringify({
          price,
          maxExecutionSeconds: maxExec,
          capabilityCategories: categoriesCsv
            .split(",")
            .map((s) => s.trim().toLowerCase())
            .filter(Boolean),
        }),
      });
      onAgentChange(updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Publish failed");
    } finally {
      setSaving(false);
    }
  }
```

Copy line (replace the amber note):

```tsx
      <p className="font-mono text-[0.65rem] text-amber">
        Publishing creates a new version and retires the current one — in-flight tasks keep the
        version they were dispatched with.
      </p>
```

Button + success message:

```tsx
        <Button onClick={handleSave} disabled={saving}>
          {saving ? "Publishing…" : "Publish version ▸"}
        </Button>
        {saved && (
          <p role="status" className="font-mono text-xs text-accent">
            Published
          </p>
        )}
```

- [ ] **Step 2: Lifecycle controls in the manage header**

In `frontend/app/builder/agents/[id]/page.tsx`, add `Button` to the UI import (`import { Badge } from "@/components/ui/Badge";` → also `import { Button } from "@/components/ui/Button";`), and a transition handler + buttons in the header. Add inside `ManageAgentPage` (after the `agent`/`profile` state):

```tsx
  const [transitioning, setTransitioning] = useState(false);

  async function transition(verb: "suspend" | "reactivate" | "deactivate") {
    setTransitioning(true);
    setLoadError(null);
    try {
      const updated = await api<AgentDTO>(`/agents/${id}/${verb}`, { method: "POST" });
      setAgent(updated);
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setTransitioning(false);
    }
  }
```

In the header, after the `<Badge>` line, add a controls row:

```tsx
          <div className="ml-auto flex gap-2">
            {agent.status === "ACTIVE" && (
              <Button variant="secondary" disabled={transitioning} onClick={() => transition("suspend")}>
                Suspend
              </Button>
            )}
            {agent.status === "SUSPENDED" && (
              <Button variant="secondary" disabled={transitioning} onClick={() => transition("reactivate")}>
                Reactivate
              </Button>
            )}
            {(agent.status === "ACTIVE" || agent.status === "SUSPENDED") && (
              <Button variant="secondary" disabled={transitioning} onClick={() => transition("deactivate")}>
                Deactivate
              </Button>
            )}
          </div>
```

(The enclosing `<div className="mt-4 flex flex-wrap items-center gap-3">` already wraps the title + badge; the `ml-auto` pushes the controls to the right.)

- [ ] **Step 3: MSW handlers**

In `frontend/test/msw/handlers.ts`, replace the `http.put("*/api/agents/:id/pricing", ...)` handler with a versions handler, and add three lifecycle handlers (in `manageHandlers`):

```ts
  http.post("*/api/agents/:id/versions", async ({ params, request }) => {
    const body = (await request.json()) as { price: number; maxExecutionSeconds: number; capabilityCategories: string[] };
    return ok({
      ...AGENT_DTO_A1,
      id: params.id as string,
      currentVersion: { ...AGENT_DTO_A1.currentVersion, price: body.price },
    });
  }),

  http.post("*/api/agents/:id/suspend", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string, status: "SUSPENDED" }),
  ),
  http.post("*/api/agents/:id/reactivate", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string, status: "ACTIVE" }),
  ),
  http.post("*/api/agents/:id/deactivate", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string, status: "DEACTIVATED" }),
  ),
```

- [ ] **Step 4: `manage.test.tsx`**

Update the "pricing tab saves" test's button + status matchers, and add a suspend test:

```tsx
  it("pricing tab publishes a new version", async () => {
    renderBuilder();
    await screen.findByRole("textbox", { name: /tagline/i });
    await userEvent.click(screen.getByRole("button", { name: /pricing/i }));

    const priceInput = await screen.findByRole("spinbutton", { name: /price/i });
    expect(priceInput).toHaveValue(10);
    await userEvent.clear(priceInput);
    await userEvent.type(priceInput, "12");

    await userEvent.click(screen.getByRole("button", { name: /publish/i }));

    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent(/published/i);
  });

  it("suspends an active agent from the header", async () => {
    renderBuilder();
    await screen.findByRole("textbox", { name: /tagline/i }); // page loaded (agent is ACTIVE)
    await userEvent.click(screen.getByRole("button", { name: /^suspend$/i }));
    expect(await screen.findByText("SUSPENDED")).toBeInTheDocument();
  });
```

- [ ] **Step 5: `types.ts`**

In `frontend/lib/types.ts`, rename the (unused) `UpdatePricingRequest` interface to `PublishVersionRequest` (same shape) so the type name reflects the endpoint:

```ts
export interface PublishVersionRequest {
  price: number;
  maxExecutionSeconds: number;
  capabilityCategories: string[];
}
```

- [ ] **Step 6: Run the frontend suite**

```bash
cd frontend && npx vitest run
```

Expected: all green (the reworked pricing test + the new suspend test pass).

- [ ] **Step 7: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): publish-new-version + agent lifecycle controls in the builder console"
```

---

### Task 6: Slice gate + tag

- [ ] **Step 1: Full backend suite green**

```bash
mvn -f backend/pom.xml -B test 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors|BUILD SUCCESS|BUILD FAILURE" | tail -3
```

Expected: BUILD SUCCESS; `Failures: 0, Errors: 0` (Testcontainers integration tests skip locally; CI runs `AgentVersionSupersessionIntegrationTest` + `StorefrontRepositoryIntegrationTest`).

- [ ] **Step 2: Frontend suite green**

```bash
cd frontend && npx vitest run
```

- [ ] **Step 3: Tag**

```bash
git tag redivision-4-agent-offering
```

---

## Self-Review

**Spec coverage (spec §3 `offering/`, §4 "Agent Offering", §5 migration + API/frontend, §6 step 4):**
- "relocate `agent` + `catalogue` → `offering`" → Task 1 (domain/application/repository moved; controllers stay route-grouped; test packages `com.hireai.agent`/`com.hireai.catalogue` → `com.hireai.offering`). ✓
- "AgentVersion real lifecycle (`DRAFT`/`ACTIVE`/`DEPRECATED`), drop hardcoded `versionNumber=1`, many versions per agent, publish-new-version → supersession (demote prior ACTIVE same tx; repo loads current ACTIVE, keeps DEPRECATED history); migration adds `agent_versions.status` + partial-unique index" → Task 2 (`V15`, `findByAgentIdAndStatus`, `publishNewVersion` demote-before-insert, `uq_agent_versions_one_active`). ✓
- "Replaces the in-place `updateCommercials` mutation" → Task 2 removes `updateCommercials`/`updateCurrentVersion`/`PricingUpdateInfo`/`UpdatePricingRequest`. ✓
- "`POST /api/agents/{id}/versions` replaces `PUT /api/agents/{id}/pricing`" → Task 2 Step 11 (+ frontend Task 5). ✓
- "Storefront → own aggregate + `Media` VO; keep `agent_profiles` (no migration unless justified)" → Task 3 (`StorefrontModel`+`Media` over the same columns, **no migration** — justified inline). ✓
- "manual Agent SUSPEND/DEACTIVATE transitions (enum values already exist)" → Task 4 (+ REACTIVATE for a usable pair; routing/catalogue already filter ACTIVE). ✓
- "Builder-console frontend change + Playwright pass" → Task 5 (publish-new-version + lifecycle controls; vitest updated). (Playwright is the reviewer's manual pass per spec §6.) ✓
- Migration is additive `V15` (spec's `V13` was renumbered — noted in the header + migration comment). ✓
- OUT OF SCOPE honored: no webhook probe, no `is_featured` write path, no matcher epsilon, no reputation auto-suspend; `DRAFT` declared-not-produced — all noted in Global Constraints. ✓

**Placeholder scan:** the two Testcontainers integration tests defer only the harness boilerplate to "copy verbatim from `AgentRegistrationIntegrationTest`" (the sibling sets up the Postgres container / `@DynamicPropertySource` / `dockerAvailable`); every assertion (one-ACTIVE count, three-version total, DEPRECATED status, current_version_id tracking; storefront round-trip via the retyped sibling) is fully specified. The sed/git commands in Tasks 1 + 3 are exact. No TBDs.

**Type consistency:**
- `AgentVersionModel` constructor gains `AgentVersionStatus status` before `Instant createdAt`; every caller updated — `create()`/`supersededBy()` (internal), `AgentRepositoryImpl.toVersionModel`, `AgentControllerStorefrontTest.versionedAgentModel`. The 7-arg `create(...)` factory keeps its signature (defaults ACTIVE), so `AgentVersionModelTest`/`AgentModelTest`/`AgentRegistrationIntegrationTest` create-calls are unchanged.
- `AgentVersionDO` constructor gains `String status` before `Instant gmtCreate`; both call sites in `AgentRepositoryImpl` (`save` insert branch, `publishNewVersion` insert) pass `version.status().name()`.
- `AgentRepository`: `updateCurrentVersion` removed → `publishNewVersion(AgentModel)` added; the `FakeAgentRepository` (unit test) and `AgentRepositoryImpl` (infra) both implement the new contract; no other implementor exists.
- `PublishVersionInfo(price, maxExec, categories)` ↔ `PublishVersionRequest` ↔ `AgentWriteAppService.publishNewVersion` ↔ `AgentModel.publishNewVersion(Pricing, int, List<String>)` are aligned; `Pricing.of(info.price())` matches the existing `Pricing.of(BigDecimal)`.
- `StorefrontModel` preserves the exact public surface of `AgentProfileModel` (`createDefault`/`updateContent`/`withLogo`/`withCover`/`addGalleryUrl`/`removeMedia`/`assertCanAddGallery`/`MAX_GALLERY`/`MAX_TAGLINE`/`logoUrl`/`coverUrl`/`galleryUrls`/`tagline`/`description`/`sampleOutput`/`listed`/`featured`/`agentId`), so the consuming app services, `AgentProfileViewDTO`, `DirectBookingAppServiceImpl` (`StorefrontModel::listed`), and all retyped tests compile after a pure type-name swap. The `Media` VO owns the gallery-cap rule (delegated from the root).
- `AgentDTO.AgentVersionDTO` gains `String status` (additive, last component); only `AgentModel2DTOConverter` constructs it — updated. Existing JSON assertions (`currentVersion.price`) are unaffected; the new `currentVersion.status` is asserted in the suspend controller test.
- Frontend: `POST /agents/{id}/versions` + `/{verb}` calls go through the existing `api()` client (`AgentDTO` envelope); MSW handlers + the two reworked tests align with the new endpoints and button/status copy.

**Risk note:** the one-ACTIVE-per-agent index, the demote-before-insert ordering (avoiding a transient two-ACTIVE state under the partial-unique index), and the storefront persistence round-trip are validated only by Testcontainers integration tests — i.e. in CI, not locally. The publish path also re-wires the agent-version write path (Invariant #4-adjacent: superseded versions stay DEPRECATED so in-flight tasks keep a valid contract). Flagged for the CI run / final whole-branch review + the spec-mandated Playwright pass on the builder console.
