# Matching Engine + Reliability Sweepers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-winner `max(reputation)` matcher with a multi-factor scored, epsilon-greedy ranking engine, and close the three reliability holes (no re-match of held tasks, no execution timeout, stranded escrow on DLQ failure).

**Architecture:** The scorer is a framework-free domain service (`rank` + `selectOne`) fed by an enriched candidate SQL query (in-flight / sample counts computed per agent). Two `@Scheduled` sweepers (modeled on `ArbitrationSweeper`) drive re-match and timeout through a thin `TaskReliabilityAppService`. All new escrow exits are full refunds through the existing `SettlementWriteAppService.settleRejected` path.

**Tech Stack:** Java 21, Spring Boot (COLA multi-module reactor in `backend/`), Flyway (next migration **V24**), Postgres native queries via Spring Data, RabbitMQ, JUnit 5 + AssertJ + Mockito, Testcontainers (integration tests auto-skip without Docker), Next.js 16 + vitest + msw (one small frontend change).

**Spec:** `docs/superpowers/specs/2026-07-04-matching-engine-design.md` (approved). Explainer: `docs/matching-selection-mechanics.md`.

## Global Constraints

- Domain layer (`hireai-domain`) is framework-free: no Spring imports; beans wired in `DomainServiceConfig` (`hireai-application/src/main/java/com/hireai/application/config/DomainServiceConfig.java`).
- App services: interface + `impl/` subpackage. Controllers derive identity from `CurrentUserProvider` only.
- `TaskModel` is immutable — every transition returns a new copy; illegal transitions throw `DomainException(ResultCode.DOMAIN_RULE_VIOLATION, ...)`.
- **Do NOT map `match_attempts`, `execution_deadline`, `pinned_agent_version_id` on `TaskDO`.** `TaskRepositoryImpl.save` writes the full row from `TaskModel`; a mapped-but-unthreaded column would be overwritten with NULL on every transition save. These columns are managed exclusively by targeted native queries (Task 8) and are invisible to the entity.
- Flyway owns schema (`ddl-auto: validate`); unmapped DB columns are fine (precedent: `gmt_modified`).
- Commit format: `<type>: <description>` (feat/fix/refactor/docs/test/chore). No attribution footers.
- Backend targeted test run (from repo root): `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest=<TestClass> -DfailIfNoTests=false`. Full suite: `mvn -f backend/pom.xml -B test`. Frontend: `npx vitest run` in `frontend/`.
- Integration tests (`*IntegrationTest`) need Docker; they auto-skip without it. If Docker is available, run them; if not, note it in the commit body.
- Config defaults (spec §7): weights 0.40/0.20/0.20/0.20, ε 0.10, rematch-interval PT10S, rematch-max-attempts 3, execution sweep-interval PT30S, grace 60s, default max-concurrent 5.

## File Structure (created/modified)

```
backend/hireai-main/src/main/resources/db/migration/V24__matching_and_reliability.sql   [create]
backend/hireai-domain/.../biz/task/routing/service/MatchingPolicy.java                  [create]
backend/hireai-domain/.../biz/task/routing/info/ScoredCandidate.java                    [create]
backend/hireai-domain/.../biz/offering/agent/info/AgentCandidate.java                   [modify: +3 fields]
backend/hireai-domain/.../biz/task/routing/service/RoutingMatchDomainService.java       [rewrite: rank/selectOne]
backend/hireai-domain/.../biz/task/routing/service/impl/RoutingMatchDomainServiceImpl.java [rewrite: scorer]
backend/hireai-domain/.../biz/task/model/TaskModel.java                                 [modify: markCancelled + widen 2 transitions]
backend/hireai-domain/.../biz/task/repository/TaskRepository.java                       [modify: +7 reliability methods]
backend/hireai-domain/.../biz/offering/agent/model/AgentVersionModel.java               [modify: +maxConcurrent]
backend/hireai-domain/.../biz/offering/agent/model/AgentModel.java                      [modify: register signature]
backend/hireai-domain/.../biz/offering/agent/info/AgentRegisterInfo.java                [modify: +maxConcurrent]
backend/hireai-domain/.../biz/offering/agent/service/impl/AgentRegisterDomainServiceImpl.java [modify]
backend/hireai-application/.../config/DomainServiceConfig.java                          [modify: policy + rng wiring]
backend/hireai-application/.../biz/task/TaskWriteAppService.java                        [modify: 3-arg assignAndQueue, +2 methods]
backend/hireai-application/.../biz/task/impl/TaskWriteAppServiceImpl.java               [modify]
backend/hireai-application/.../biz/task/routing/impl/RoutingAppServiceImpl.java         [modify: selectOne + deadline]
backend/hireai-application/.../biz/task/impl/TaskExecutionPortImpl.java                 [modify: markFailed guard+refund]
backend/hireai-application/.../biz/task/reliability/TaskReliabilityAppService.java      [create]
backend/hireai-application/.../biz/task/reliability/impl/TaskReliabilityAppServiceImpl.java [create]
backend/hireai-repository/.../offering/agent/AgentVersionJpaRepository.java             [modify: enriched queries + projection]
backend/hireai-repository/.../offering/agent/AgentRepositoryImpl.java                   [modify: row mapping]
backend/hireai-repository/.../offering/agent/AgentVersionDO.java                        [modify: +max_concurrent (Task 13 only)]
backend/hireai-repository/.../task/TaskJpaRepository.java                               [modify: +7 native queries]
backend/hireai-repository/.../task/TaskRepositoryImpl.java                              [modify: delegate port methods]
backend/hireai-infrastructure/.../messaging/CapacityRematchSweeper.java                 [create]
backend/hireai-infrastructure/.../messaging/ExecutionTimeoutSweeper.java                [create]
backend/hireai-controller/.../biz/agent/dto/RegisterAgentRequest.java                   [modify: +maxConcurrent]
backend/hireai-controller/.../biz/agent/AgentController.java                            [modify: default + thread-through]
backend/hireai-main/src/main/resources/application.yml                                  [modify: hireai.matching + hireai.execution]
frontend/lib/types.ts                                                                   [modify: CreateAgentRequest]
frontend/app/builder/agents/new/page.tsx                                                [modify: +field]
frontend/app/builder/agents/new/page.test.tsx                                           [create]
```

Test files are named per task below. Full paths for `...` follow the existing package roots visible in each task's code.

---

### Task 1: V24 migration

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V24__matching_and_reliability.sql`

**Interfaces:**
- Produces: DB columns `agent_versions.max_concurrent INT NOT NULL DEFAULT 5`, `tasks.match_attempts INT NOT NULL DEFAULT 0`, `tasks.execution_deadline TIMESTAMPTZ NULL`, `tasks.pinned_agent_version_id UUID NULL`; index `idx_tasks_agent_version_status`. Later tasks read/write these via native SQL only.

- [ ] **Step 1: Write the migration**

```sql
-- V24: matching engine + reliability sweepers (spec: docs/superpowers/specs/2026-07-04-matching-engine-design.md).
-- 1) Builder-declared per-version capacity for the loadHeadroom score factor. DEFAULT 5 backfills
--    every existing version; new registrations pass an explicit value.
ALTER TABLE agent_versions ADD COLUMN max_concurrent INT NOT NULL DEFAULT 5
    CHECK (max_concurrent BETWEEN 1 AND 100);

-- 2) Attempt-bounded re-match counter (AWAITING_CAPACITY sweeper).
ALTER TABLE tasks ADD COLUMN match_attempts INT NOT NULL DEFAULT 0;

-- 3) Execution deadline, stamped at ASSIGNMENT (queue time + max_execution_seconds + grace) so one
--    sweep catches both silent executors (EXECUTING) and lost dispatches (stuck QUEUED).
ALTER TABLE tasks ADD COLUMN execution_deadline TIMESTAMPTZ;

-- 4) Direct bookings pin an exact agent version at submit; the re-match sweeper must never
--    substitute another agent for a pinned task. NULL = open task.
ALTER TABLE tasks ADD COLUMN pinned_agent_version_id UUID;

-- 5) Makes the candidate query's per-agent in-flight/sample counts index range scans.
CREATE INDEX idx_tasks_agent_version_status ON tasks (agent_version_id, status);

-- 6) Defensive category normalisation. AgentVersionModel.create already lowercases categories at
--    registration, but rows created by seed data or before that rule may not comply; the candidate
--    query lowercases its :category parameter and relies on stored values being lowercase.
UPDATE agent_versions SET capability_categories =
    (SELECT array_agg(lower(trim(c))) FROM unnest(capability_categories) AS c)
WHERE capability_categories IS NOT NULL;
```

**Do NOT touch `TaskDO` or `AgentVersionDO` in this task** (see Global Constraints; `AgentVersionDO` gets its mapping in Task 13 when the value is threaded from the domain).

- [ ] **Step 2: Verify the build and suite still pass**

Run: `mvn -f backend/pom.xml -B test`
Expected: BUILD SUCCESS (~398 tests; integration tests validate the migration against Testcontainers Postgres when Docker is present — Flyway will fail loudly there if the SQL is bad).

- [ ] **Step 3: Commit**

```bash
git add backend/hireai-main/src/main/resources/db/migration/V24__matching_and_reliability.sql
git commit -m "feat: V24 migration - max_concurrent, match_attempts, execution_deadline, pinned version, counts index"
```

---

### Task 2: MatchingPolicy + ScoredCandidate (domain value objects)

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/MatchingPolicy.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/info/ScoredCandidate.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/domain/biz/task/routing/service/MatchingPolicyTest.java`

**Interfaces:**
- Produces: `MatchingPolicy(double weightReputation, double weightValue, double weightLoad, double weightExploration, double epsilon)` record with validating compact constructor and `MatchingPolicy.defaults()`; `ScoredCandidate(AgentCandidate candidate, double score)` record. Task 4 consumes both.

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.domain.biz.task.routing.service;

import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingPolicyTest {

    @Test
    void defaultsAreValidAndMatchTheSpec() {
        MatchingPolicy p = MatchingPolicy.defaults();
        assertThat(p.weightReputation()).isEqualTo(0.40);
        assertThat(p.weightValue()).isEqualTo(0.20);
        assertThat(p.weightLoad()).isEqualTo(0.20);
        assertThat(p.weightExploration()).isEqualTo(0.20);
        assertThat(p.epsilon()).isEqualTo(0.10);
    }

    @Test
    void rejectsWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new MatchingPolicy(0.50, 0.20, 0.20, 0.20, 0.10))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("sum to 1.0");
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> new MatchingPolicy(-0.10, 0.50, 0.30, 0.30, 0.10))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEpsilonOutOfRange() {
        assertThatThrownBy(() -> new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 1.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("epsilon");
    }

    @Test
    void epsilonZeroIsValid() {
        assertThat(new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0).epsilon()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest=MatchingPolicyTest -DfailIfNoTests=false`
Expected: COMPILATION ERROR ("cannot find symbol: class MatchingPolicy").

- [ ] **Step 3: Write the implementation**

`MatchingPolicy.java`:

```java
package com.hireai.domain.biz.task.routing.service;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Immutable weights + exploration rate for the multi-factor matcher (spec §4.2). Bound from
 * hireai.matching.* config in DomainServiceConfig; a bad configuration fails bean creation,
 * so a typo in YAML is a startup crash, not a subtly wrong marketplace.
 */
public record MatchingPolicy(double weightReputation, double weightValue, double weightLoad,
                             double weightExploration, double epsilon) {

    private static final double TOLERANCE = 1e-9;

    public MatchingPolicy {
        requireUnitRange(weightReputation, "weightReputation");
        requireUnitRange(weightValue, "weightValue");
        requireUnitRange(weightLoad, "weightLoad");
        requireUnitRange(weightExploration, "weightExploration");
        if (epsilon < 0.0 || epsilon > 1.0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching epsilon must be in [0,1]; got " + epsilon);
        }
        double sum = weightReputation + weightValue + weightLoad + weightExploration;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching weights must sum to 1.0; got " + sum);
        }
    }

    public static MatchingPolicy defaults() {
        return new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.10);
    }

    private static void requireUnitRange(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Matching weight " + name + " must be in [0,1]; got " + value);
        }
    }
}
```

`ScoredCandidate.java`:

```java
package com.hireai.domain.biz.task.routing.info;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;

/**
 * One ranked matching candidate with its computed multi-factor score. Returned by
 * RoutingMatchDomainService.rank (best-first); the Phase 2 shortlist reads the top N,
 * selectOne applies epsilon-greedy on top.
 */
public record ScoredCandidate(AgentCandidate candidate, double score) {
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest=MatchingPolicyTest -DfailIfNoTests=false`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain backend/hireai-main/src/test/java/com/hireai/domain/biz/task/routing/service/MatchingPolicyTest.java
git commit -m "feat: MatchingPolicy + ScoredCandidate domain value objects"
```

---

### Task 3: Enrich AgentCandidate + candidate queries (in_flight / sample_count / max_concurrent)

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/offering/agent/info/AgentCandidate.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/agent/AgentVersionJpaRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/agent/AgentRepositoryImpl.java` (rowToCandidate, lines 112–118)
- Modify (mechanical, add `5, 0L, 0L` args): every `new AgentCandidate(` call site in tests — `RoutingMatchDomainServiceTest`, `RoutingAppServiceImplTest`, `RoutingAppServiceDirectDispatchTest`, `AgentContractTypesTest`, `SpineContractsTest` (all under `backend/hireai-main/src/test/java/`)
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/offering/agent/CandidateCountsIntegrationTest.java` (create)

**Interfaces:**
- Produces: `AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories, BigDecimal price, String webhookUrl, int maxExecutionSeconds, BigDecimal reputationScore, String outputSpecJson, int maxConcurrent, long inFlight, long sampleCount)` — the three new components are appended in this order. Tasks 4+ consume them.

- [ ] **Step 1: Extend the record**

```java
public record AgentCandidate(UUID agentId, UUID agentVersionId, List<String> capabilityCategories,
                             BigDecimal price, String webhookUrl, int maxExecutionSeconds,
                             BigDecimal reputationScore, String outputSpecJson,
                             int maxConcurrent, long inFlight, long sampleCount) {
}
```

Update the record's javadoc last paragraph to add: `maxConcurrent/inFlight/sampleCount feed the multi-factor scorer (loadHeadroom + exploration terms); counts are per-AGENT across all its versions, derived fresh by the candidate query.`

- [ ] **Step 2: Extend both native queries + the projection**

In `AgentVersionJpaRepository`, replace the `findActiveCandidates` query with:

```java
    @Query(value = """
            SELECT v.agent_id            AS agent_id,
                   v.id                  AS agent_version_id,
                   v.capability_categories AS capability_categories,
                   v.price               AS price,
                   v.webhook_url         AS webhook_url,
                   v.max_execution_seconds AS max_execution_seconds,
                   a.reputation_score    AS reputation_score,
                   v.output_spec         AS output_spec,
                   v.max_concurrent      AS max_concurrent,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id
                       AND t.status IN ('QUEUED','EXECUTING'))            AS in_flight,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id
                       AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION')) AS sample_count
            FROM agent_versions v
            JOIN agents a ON a.id = v.agent_id AND a.current_version_id = v.id
            WHERE a.status = 'ACTIVE'
              AND v.capability_categories && ARRAY[:category]::text[]
              AND v.price <= :maxPrice
            ORDER BY a.reputation_score DESC
            """, nativeQuery = true)
```

Apply the **same three added columns** (`max_concurrent`, `in_flight`, `sample_count` — identical subqueries) to `findCandidateByVersionId`. Extend the projection:

```java
    interface AgentCandidateRow {
        UUID getAgentId();
        UUID getAgentVersionId();
        String[] getCapabilityCategories();
        BigDecimal getPrice();
        String getWebhookUrl();
        int getMaxExecutionSeconds();
        BigDecimal getReputationScore();
        String getOutputSpec();
        Integer getMaxConcurrent();
        Long getInFlight();
        Long getSampleCount();
    }
```

In `AgentRepositoryImpl.rowToCandidate`:

```java
    private AgentCandidate rowToCandidate(AgentVersionJpaRepository.AgentCandidateRow row) {
        return new AgentCandidate(
                row.getAgentId(), row.getAgentVersionId(),
                List.of(row.getCapabilityCategories()), row.getPrice(),
                row.getWebhookUrl(), row.getMaxExecutionSeconds(), row.getReputationScore(),
                row.getOutputSpec(),
                row.getMaxConcurrent() == null ? 5 : row.getMaxConcurrent(),
                row.getInFlight() == null ? 0L : row.getInFlight(),
                row.getSampleCount() == null ? 0L : row.getSampleCount());
    }
```

- [ ] **Step 3: Fix every test call site**

Run `grep -rn "new AgentCandidate(" backend/hireai-main/src/test backend/hireai-repository` and append `, 5, 0L, 0L` (maxConcurrent=5, inFlight=0, sampleCount=0) to each constructor call in the five test classes. Where a test has a local `candidate(...)` helper (e.g. `RoutingMatchDomainServiceTest`), change only the helper.

- [ ] **Step 4: Write the integration test for the counts**

First **read** `backend/hireai-main/src/test/java/com/hireai/task/routing/RoutingIntegrationTest.java` and reuse its exact scaffolding (annotations, Testcontainers setup, `@DynamicPropertySource`, Docker-gating, and however it seeds users/agents/tasks). Then create `CandidateCountsIntegrationTest` with these cases (spec tests 13–18), seeding directly through the repositories/JDBC the same way that class does:

```java
// Case 13: seed one ACTIVE agent (category "summarisation", price 10, maxConcurrent left to default);
// insert 2 tasks in QUEUED + 1 in EXECUTING assigned to its version, 1 RESOLVED, 1 FAILED, 1 CANCELLED
// (CANCELLED task has agent_version_id NULL — it was never assigned).
// findActiveCandidates("summarisation", 30) → single row: inFlight()==3, sampleCount()==2, maxConcurrent()==5.

// Case 14: give the same agent a superseded (DEPRECATED) old version with 2 RESOLVED tasks pointing at
// the OLD version id → sampleCount()==4 (counts aggregate per agent across versions).

// Case 15: a second ACTIVE agent with zero tasks → inFlight()==0, sampleCount()==0 (not null).

// Case 17: register an agent whose stored category is "summarisation" (domain lowercases), query with
// "Summarisation" → still matched (repository lowercases the parameter).

// Case 18: findCandidateByVersionId(versionId) returns the same three new fields.
```

Write each case as a real `@Test` with AssertJ assertions on the returned `AgentCandidate` fields.

- [ ] **Step 5: Run the tests**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest='CandidateCountsIntegrationTest,RoutingMatchDomainServiceTest,RoutingAppServiceImplTest,RoutingAppServiceDirectDispatchTest,AgentContractTypesTest,SpineContractsTest' -DfailIfNoTests=false`
Expected: PASS (integration class skips without Docker — then verify at least the five updated unit classes pass).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-domain backend/hireai-repository backend/hireai-main/src/test
git commit -m "feat: enrich candidate query with per-agent in_flight/sample_count and max_concurrent"
```

---

### Task 4: The scorer — rank + selectOne, config wiring, caller swap

**Files:**
- Rewrite: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/RoutingMatchDomainService.java`
- Rewrite: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/impl/RoutingMatchDomainServiceImpl.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/config/DomainServiceConfig.java` (routingMatchDomainService bean, lines 54–57)
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/routing/impl/RoutingAppServiceImpl.java` (line 54: `selectAgentVersion` → `selectOne`)
- Modify: `backend/hireai-main/src/main/resources/application.yml` (add `hireai.matching` block)
- Rewrite test: `backend/hireai-main/src/test/java/com/hireai/domain/biz/task/routing/service/RoutingMatchDomainServiceTest.java`
- Modify test: `backend/hireai-main/src/test/java/com/hireai/application/biz/task/routing/RoutingAppServiceImplTest.java` (mock `selectOne` instead of `selectAgentVersion`)

**Interfaces:**
- Consumes: `MatchingPolicy`, `ScoredCandidate` (Task 2); enriched `AgentCandidate` (Task 3).
- Produces: `List<ScoredCandidate> rank(TaskRoutingView criteria, List<AgentCandidate> candidates)` and `Optional<UUID> selectOne(TaskRoutingView criteria, List<AgentCandidate> candidates)`. Constructor: `RoutingMatchDomainServiceImpl(MatchingPolicy policy, RandomGenerator rng)`. The old `selectAgentVersion` is **deleted**.

- [ ] **Step 1: Write the failing tests (full replacement of RoutingMatchDomainServiceTest)**

```java
package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class RoutingMatchDomainServiceTest {

    private static final MatchingPolicy POLICY = MatchingPolicy.defaults();

    /** epsilon=0 => pure argmax; seed irrelevant. The standard fixture for scoring tests. */
    private RoutingMatchDomainService greedy() {
        return new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0), new Random(1));
    }

    private TaskRoutingView task(String category, String budget) {
        return new TaskRoutingView(UUID.randomUUID(), category, new BigDecimal(budget), "SUBMITTED",
                "{\"format\":\"JSON\"}");
    }

    private AgentCandidate candidate(UUID versionId, List<String> categories, String price,
                                     String reputation, int maxConcurrent, long inFlight, long sampleCount) {
        return new AgentCandidate(UUID.randomUUID(), versionId, categories,
                new BigDecimal(price), "https://agent.example/hook", 60, new BigDecimal(reputation),
                "{\"format\":\"JSON\"}", maxConcurrent, inFlight, sampleCount);
    }

    /** Baseline candidate: everything equal so single factors can be varied per test. */
    private AgentCandidate base(UUID versionId, String price, String rep, long inFlight, long samples) {
        return candidate(versionId, List.of("summarisation"), price, rep, 5, inFlight, samples);
    }

    // ---- spec test 1: each factor in isolation decides the winner ----

    @Test
    void higherReputationWinsWhenOtherFactorsEqual() {
        UUID better = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "10.00", "50.00", 0, 10),
                base(better, "10.00", "90.00", 0, 10)));
        assertThat(chosen).contains(better);
    }

    @Test
    void cheaperPriceWinsWhenOtherFactorsEqual() {
        UUID cheaper = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "25.00", "50.00", 0, 10),
                base(cheaper, "10.00", "50.00", 0, 10)));
        assertThat(chosen).contains(cheaper);
    }

    @Test
    void idleAgentBeatsBusyAgentWhenOtherFactorsEqual() {
        UUID idle = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "10.00", "50.00", 4, 10),
                base(idle, "10.00", "50.00", 0, 10)));
        assertThat(chosen).contains(idle);
    }

    @Test
    void underSampledAgentBeatsVeteranWhenOtherFactorsEqual() {
        UUID newcomer = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "10.00", "50.00", 0, 100),
                base(newcomer, "10.00", "50.00", 0, 0)));
        assertThat(chosen).contains(newcomer);
    }

    // ---- spec test 2: composite score equals a hand-computed value ----

    @Test
    void scoreMatchesHandComputedValue() {
        // rep 90 -> 0.40*0.90 = 0.36 ; price 20/budget 30 -> valueFit (30-20)/30 = 1/3 -> 0.20*(1/3)
        // inFlight 1 / maxConcurrent 5 -> headroom 0.8 -> 0.20*0.8 = 0.16 ; samples 4 -> 1/5 -> 0.20*0.2 = 0.04
        AgentCandidate c = candidate(UUID.randomUUID(), List.of("summarisation"),
                "20.00", "90.00", 5, 1, 4);
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(c));
        double expected = 0.36 + 0.20 * (1.0 / 3.0) + 0.16 + 0.04;
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isCloseTo(expected, offset(1e-9));
    }

    // ---- spec test 3: rank returns all eligible, sorted, scored ----

    @Test
    void rankReturnsAllEligibleSortedBestFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(
                base(b, "25.00", "50.00", 0, 10),   // worse valueFit
                base(a, "10.00", "50.00", 0, 10),
                candidate(UUID.randomUUID(), List.of("translation"), "10.00", "99.00", 5, 0, 0))); // filtered
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).candidate().agentVersionId()).isEqualTo(a);
        assertThat(ranked.get(1).candidate().agentVersionId()).isEqualTo(b);
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    // ---- spec tests 5-6: filtering + empties (regression of existing behaviour) ----

    @Test
    void returnsEmptyWhenNoCandidateMatchesCategory() {
        assertThat(greedy().selectOne(task("summarisation", "30.00"),
                List.of(candidate(UUID.randomUUID(), List.of("translation"), "10.00", "90.00", 5, 0, 0))))
                .isEmpty();
    }

    @Test
    void filtersOutCandidatesPricedAboveBudget() {
        UUID affordable = UUID.randomUUID();
        assertThat(greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "40.00", "90.00", 0, 0),
                base(affordable, "25.00", "50.00", 0, 100))))
                .contains(affordable);
    }

    @Test
    void returnsEmptyOnEmptyOrNullInput() {
        assertThat(greedy().selectOne(task("summarisation", "30.00"), List.of())).isEmpty();
        assertThat(greedy().rank(task("summarisation", "30.00"), List.of())).isEmpty();
        assertThat(greedy().rank(null, List.of())).isEmpty();
    }

    // ---- spec test 7: deterministic total tie-break ----

    @Test
    void tieBreaksByPriceThenVersionId() {
        UUID low = new UUID(0L, 1L);
        UUID high = new UUID(0L, 2L);
        // identical scores: same price/rep/load/samples -> tie on price too -> versionId ascending
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(
                candidate(high, List.of("summarisation"), "10.00", "50.00", 5, 0, 0),
                candidate(low, List.of("summarisation"), "10.00", "50.00", 5, 0, 0)));
        assertThat(ranked.get(0).candidate().agentVersionId()).isEqualTo(low);
        assertThat(ranked.get(1).candidate().agentVersionId()).isEqualTo(high);

        // and cheaper wins before id is consulted
        UUID cheap = UUID.randomUUID();
        List<ScoredCandidate> ranked2 = greedy().rank(task("summarisation", "40.00"), List.of(
                candidate(UUID.randomUUID(), List.of("summarisation"), "20.00", "50.00", 5, 0, 100),
                candidate(cheap, List.of("summarisation"), "16.00", "56.00", 5, 4, 100)));
        // scores engineered equal: valueFit 0.5 vs 0.6, rep 0.5 vs 0.56, load 1.0 vs 0.2:
        // c1 = .4*.5 + .2*.5 + .2*1.0 + .2*(1/101) ; c2 = .4*.56 + .2*.6 + .2*.2 + .2*(1/101) — equal sums
        assertThat(ranked2.get(0).candidate().agentVersionId()).isEqualTo(cheap);
    }

    // ---- spec test 8: capacity edge cases ----

    @Test
    void atAndOverCapacityClampToZeroButStayEligible() {
        UUID at = UUID.randomUUID();
        UUID over = UUID.randomUUID();
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(
                base(at, "10.00", "50.00", 5, 10),
                base(over, "10.00", "50.00", 7, 10)));
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).score()).isCloseTo(ranked.get(1).score(), offset(1e-9));
    }

    // ---- spec test 9: price == budget ----

    @Test
    void priceEqualToBudgetIsEligibleWithZeroValueFit() {
        UUID only = UUID.randomUUID();
        assertThat(greedy().selectOne(task("summarisation", "30.00"),
                List.of(base(only, "30.00", "50.00", 0, 0)))).contains(only);
    }

    // ---- spec test 10: fixed-seed exploration sampling ----

    @Test
    void epsilonOneSamplesWeightedTowardUnderSampledAgents() {
        RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 1.0), new Random(7));
        UUID newcomer = UUID.randomUUID();
        UUID veteran = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                base(veteran, "10.00", "90.00", 0, 99),   // exploration weight 1/100
                base(newcomer, "10.00", "50.00", 0, 0));  // exploration weight 1
        int newcomerWins = 0;
        for (int i = 0; i < 200; i++) {
            if (service.selectOne(task("summarisation", "30.00"), candidates).orElseThrow().equals(newcomer)) {
                newcomerWins++;
            }
        }
        // weights 1 vs 0.0099 -> newcomer expected ~99% of draws; assert a wide, stable margin
        assertThat(newcomerWins).isGreaterThan(150);
    }

    // ---- spec test 11: single candidate always chosen ----

    @Test
    void singleCandidateAlwaysChosenRegardlessOfEpsilon() {
        RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 1.0), new Random(3));
        UUID only = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            assertThat(service.selectOne(task("summarisation", "30.00"),
                    List.of(base(only, "10.00", "50.00", 0, 0)))).contains(only);
        }
    }

    // ---- epsilon behaviour: greedy branch dominates at epsilon 0.1 with fixed seed ----

    @Test
    void greedyBranchTakesTopRankMostOfTheTime() {
        RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl(POLICY, new Random(11));
        UUID top = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                base(top, "10.00", "90.00", 0, 0),
                base(UUID.randomUUID(), "25.00", "50.00", 4, 100));
        int topWins = 0;
        for (int i = 0; i < 200; i++) {
            if (service.selectOne(task("summarisation", "30.00"), candidates).orElseThrow().equals(top)) {
                topWins++;
            }
        }
        assertThat(topWins).isGreaterThan(160); // ~90% greedy + top is also the likely exploration pick
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest=RoutingMatchDomainServiceTest -DfailIfNoTests=false`
Expected: COMPILATION ERROR (no such constructor / methods).

- [ ] **Step 3: Implement**

`RoutingMatchDomainService.java` (full replacement):

```java
package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE ranking engine for task->agent matching (spec §4). Framework-free; wired in
 * DomainServiceConfig with a MatchingPolicy and an injected RandomGenerator.
 *
 * rank      — deterministic: hard-filter (category + price<=budget), multi-factor score,
 *             sort best-first, total tie-break (price asc, then agentVersionId asc).
 *             Also the Phase 2 shortlist's top-N source.
 * selectOne — epsilon-greedy over rank: probability 1-epsilon take the top; probability epsilon
 *             sample the eligible set weighted by each candidate's exploration term, so
 *             under-sampled agents occasionally win a real auto-routed job.
 *
 * Exploration randomises SELECTION only — settlement stays deterministic (Hard Invariant #3).
 * Rationale + scenarios: docs/matching-selection-mechanics.md.
 */
public interface RoutingMatchDomainService {

    List<ScoredCandidate> rank(TaskRoutingView criteria, List<AgentCandidate> candidates);

    Optional<UUID> selectOne(TaskRoutingView criteria, List<AgentCandidate> candidates);
}
```

`RoutingMatchDomainServiceImpl.java` (full replacement):

```java
package com.hireai.domain.biz.task.routing.service.impl;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.MatchingPolicy;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Multi-factor scorer + epsilon-greedy selector (spec §4.2/§4.3). Stateless apart from the
 * injected RandomGenerator (seedable in tests; with epsilon=0 selectOne is a pure argmax).
 * Every factor is normalised to [0,1] so the configured weights keep their meaning:
 *   reputation/100 ; (budget-price)/budget ; max(0, 1 - inFlight/maxConcurrent) ; 1/(1+sampleCount)
 * loadHeadroom is a SOFT factor: an at/over-capacity agent scores 0 on it but stays eligible.
 */
public class RoutingMatchDomainServiceImpl implements RoutingMatchDomainService {

    private final MatchingPolicy policy;
    private final RandomGenerator rng;

    public RoutingMatchDomainServiceImpl(MatchingPolicy policy, RandomGenerator rng) {
        this.policy = policy;
        this.rng = rng;
    }

    @Override
    public List<ScoredCandidate> rank(TaskRoutingView criteria, List<AgentCandidate> candidates) {
        if (criteria == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(c -> coversCategory(c, criteria.category()))
                .filter(c -> withinBudget(c, criteria))
                .map(c -> new ScoredCandidate(c, score(c, criteria)))
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(sc -> sc.candidate().price())
                        .thenComparing(sc -> sc.candidate().agentVersionId()))
                .toList();
    }

    @Override
    public Optional<UUID> selectOne(TaskRoutingView criteria, List<AgentCandidate> candidates) {
        List<ScoredCandidate> ranked = rank(criteria, candidates);
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        if (ranked.size() > 1 && rng.nextDouble() < policy.epsilon()) {
            return Optional.of(sampleByExploration(ranked).candidate().agentVersionId());
        }
        return Optional.of(ranked.get(0).candidate().agentVersionId());
    }

    private double score(AgentCandidate c, TaskRoutingView criteria) {
        double reputation = c.reputationScore() == null
                ? 0.0 : clamp(c.reputationScore().doubleValue() / 100.0);
        double budget = criteria.budget().doubleValue();
        double valueFit = budget <= 0.0
                ? 0.0 : clamp((budget - c.price().doubleValue()) / budget);
        double loadHeadroom = c.maxConcurrent() <= 0
                ? 0.0 : clamp(1.0 - (double) c.inFlight() / c.maxConcurrent());
        double exploration = explorationTerm(c);
        return policy.weightReputation() * reputation
                + policy.weightValue() * valueFit
                + policy.weightLoad() * loadHeadroom
                + policy.weightExploration() * exploration;
    }

    /** Weighted lottery over the eligible set; ticket size = the exploration term (spec §4.3). */
    private ScoredCandidate sampleByExploration(List<ScoredCandidate> ranked) {
        double total = 0.0;
        for (ScoredCandidate sc : ranked) {
            total += explorationTerm(sc.candidate());
        }
        double threshold = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (ScoredCandidate sc : ranked) {
            cumulative += explorationTerm(sc.candidate());
            if (threshold < cumulative) {
                return sc;
            }
        }
        return ranked.get(ranked.size() - 1);
    }

    private static double explorationTerm(AgentCandidate c) {
        return 1.0 / (1.0 + c.sampleCount());
    }

    private static double clamp(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private boolean coversCategory(AgentCandidate candidate, String category) {
        return category != null
                && candidate.capabilityCategories() != null
                && candidate.capabilityCategories().contains(category);
    }

    private boolean withinBudget(AgentCandidate candidate, TaskRoutingView criteria) {
        return criteria.budget() != null
                && candidate.price() != null
                && candidate.price().compareTo(criteria.budget()) <= 0;
    }
}
```

`DomainServiceConfig` — replace the `routingMatchDomainService` bean and add the policy bean (new imports: `com.hireai.domain.biz.task.routing.service.MatchingPolicy`, `org.springframework.beans.factory.annotation.Value`, `java.security.SecureRandom`):

```java
    @Bean
    public MatchingPolicy matchingPolicy(
            @Value("${hireai.matching.weight-reputation:0.40}") double weightReputation,
            @Value("${hireai.matching.weight-value:0.20}") double weightValue,
            @Value("${hireai.matching.weight-load:0.20}") double weightLoad,
            @Value("${hireai.matching.weight-exploration:0.20}") double weightExploration,
            @Value("${hireai.matching.epsilon:0.10}") double epsilon) {
        // MatchingPolicy's compact constructor validates; bad config = startup crash (spec §7).
        return new MatchingPolicy(weightReputation, weightValue, weightLoad, weightExploration, epsilon);
    }

    @Bean
    public RoutingMatchDomainService routingMatchDomainService(MatchingPolicy matchingPolicy) {
        return new RoutingMatchDomainServiceImpl(matchingPolicy, new SecureRandom());
    }
```

`application.yml` — insert under the existing `hireai:` key (after the `platform:` block):

```yaml
  matching:
    # Multi-factor matcher weights (must sum to 1.0) + epsilon-greedy exploration rate.
    # Spec: docs/superpowers/specs/2026-07-04-matching-engine-design.md §4.
    weight-reputation: ${MATCHING_WEIGHT_REPUTATION:0.40}
    weight-value: ${MATCHING_WEIGHT_VALUE:0.20}
    weight-load: ${MATCHING_WEIGHT_LOAD:0.20}
    weight-exploration: ${MATCHING_WEIGHT_EXPLORATION:0.20}
    epsilon: ${MATCHING_EPSILON:0.10}
    # AWAITING_CAPACITY re-match sweeper: attempt-bounded fail-fast (~30s worst case), then
    # CANCELLED + full refund.
    rematch-interval: ${MATCHING_REMATCH_INTERVAL:PT10S}
    rematch-max-attempts: ${MATCHING_REMATCH_MAX_ATTEMPTS:3}
    # Applied when a registration omits maxConcurrent.
    default-max-concurrent: ${MATCHING_DEFAULT_MAX_CONCURRENT:5}
  execution:
    # Execution-timeout sweeper: QUEUED/EXECUTING past execution_deadline -> TIMED_OUT + refund.
    # The deadline is stamped at assignment: now + max_execution_seconds + grace-seconds.
    sweep-interval: ${EXECUTION_SWEEP_INTERVAL:PT30S}
    grace-seconds: ${EXECUTION_GRACE_SECONDS:60}
```

`RoutingAppServiceImpl` line 54: `routingMatchDomainService.selectAgentVersion(view, candidates)` → `routingMatchDomainService.selectOne(view, candidates)`.

`RoutingAppServiceImplTest`: change every `when(...selectAgentVersion(...))` / `verify(...selectAgentVersion(...))` to `selectOne`. No other behaviour change in this task.

- [ ] **Step 4: Run the tests**

Run: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest='RoutingMatchDomainServiceTest,RoutingAppServiceImplTest,MatchingPolicyTest' -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Run the full backend suite (catches any other selectAgentVersion references)**

Run: `mvn -f backend/pom.xml -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend frontend 2>/dev/null; git add backend
git commit -m "feat: multi-factor scored matcher with epsilon-greedy selection (rank/selectOne)"
```

---

### Task 5: Stamp execution_deadline at assignment

**Files:**
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/TaskWriteAppService.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/routing/impl/RoutingAppServiceImpl.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/repository/TaskRepository.java` (+`stampExecutionDeadline`)
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/task/TaskJpaRepository.java` + `TaskRepositoryImpl.java`
- Tests: `RoutingAppServiceImplTest`, `RoutingAppServiceDirectDispatchTest` (both under `backend/hireai-main/src/test/java/`)

**Interfaces:**
- Produces: `TaskWriteAppService.assignAndQueue(UUID taskId, UUID agentVersionId, Instant executionDeadline)` (3-arg; old 2-arg deleted); `TaskRepository.stampExecutionDeadline(UUID taskId, Instant deadline)`.

- [ ] **Step 1: Write the failing test (spec test 19)** — add to `RoutingAppServiceImplTest`:

```java
    @Test
    void assignAndQueueReceivesDeadlineOfNowPlusMaxExecPlusGrace() {
        // fixture candidate has maxExecutionSeconds 60; impl default grace is 60s
        // (arrange the existing happy-path mocks so route() reaches assignAndQueue)
        Instant before = Instant.now();
        routingAppService.route(taskId);
        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(taskWriteAppService).assignAndQueue(eq(taskId), eq(chosenVersionId), deadline.capture());
        Instant expectedMin = before.plusSeconds(120);
        assertThat(deadline.getValue()).isAfterOrEqualTo(expectedMin.minusSeconds(1));
        assertThat(deadline.getValue()).isBeforeOrEqualTo(Instant.now().plusSeconds(121));
    }
```

(Reuse the test class's existing fixture names for `taskId`/`chosenVersionId`/mock arrangement — read the class first; the assertion pattern above is what matters.)

- [ ] **Step 2: Run to verify it fails** (2-arg method still exists → compile error). Same command as Task 4 Step 4.

- [ ] **Step 3: Implement**

`TaskWriteAppService` — replace the `assignAndQueue` declaration:

```java
    /**
     * SUBMITTED/AWAITING_CAPACITY -> QUEUED, and stamps the execution deadline
     * (assignment time + agent maxExecutionSeconds + grace) used by the timeout sweeper.
     */
    void assignAndQueue(@NonNull UUID taskId, @NonNull UUID agentVersionId, @NonNull Instant executionDeadline);
```

`TaskWriteAppServiceImpl.assignAndQueue`:

```java
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assignAndQueue(UUID taskId, UUID agentVersionId, Instant executionDeadline) {
        TaskModel task = load(taskId);
        taskRepository.save(task.assignAndQueue(agentVersionId));
        taskRepository.stampExecutionDeadline(taskId, executionDeadline);
        log.info("Task {} assigned to agent version {} and queued (deadline {})",
                taskId, agentVersionId, executionDeadline);
    }
```

`TaskRepository` (domain port) — add:

```java
    /** Operational column write (unmapped on the entity — see plan Global Constraints). */
    void stampExecutionDeadline(UUID taskId, java.time.Instant deadline);
```

`TaskJpaRepository` — add:

```java
    @Modifying
    @Query(value = "UPDATE tasks SET execution_deadline = :deadline WHERE id = :id", nativeQuery = true)
    void stampExecutionDeadline(@Param("id") UUID id, @Param("deadline") Instant deadline);
```

(add imports `org.springframework.data.jpa.repository.Modifying`, `java.time.Instant`). `TaskRepositoryImpl` — delegate:

```java
    @Override
    public void stampExecutionDeadline(UUID taskId, Instant deadline) {
        taskJpa.stampExecutionDeadline(taskId, deadline);
    }
```

`RoutingAppServiceImpl` — add below the `publicBaseUrl` field (same non-final `@Value` pattern with inline default for plain-`new` unit tests):

```java
    /** Grace added to the agent's maxExecutionSeconds when stamping the execution deadline. */
    @org.springframework.beans.factory.annotation.Value("${hireai.execution.grace-seconds:60}")
    private long executionGraceSeconds = 60;
```

and in **both** `route()` and `dispatchDirect()` replace the `assignAndQueue` call with:

```java
        Instant executionDeadline = Instant.now()
                .plusSeconds(winner.maxExecutionSeconds() + executionGraceSeconds);
        taskWriteAppService.assignAndQueue(taskId, agentVersionId, executionDeadline);
```

(in `dispatchDirect` the candidate variable is named `target` — use `target.maxExecutionSeconds()`; add `import java.time.Instant;`).

- [ ] **Step 4: Update `RoutingAppServiceDirectDispatchTest`** — change `verify(...).assignAndQueue(taskId, versionId)` forms to `assignAndQueue(eq(taskId), eq(versionId), any(Instant.class))`.

- [ ] **Step 5: Run**: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest='RoutingAppServiceImplTest,RoutingAppServiceDirectDispatchTest' -DfailIfNoTests=false` → PASS, then full suite → PASS.

- [ ] **Step 6: Commit**: `git add backend && git commit -m "feat: stamp execution_deadline at assignment (maxExec + grace)"`

---

### Task 6: Persist the pinned agent version at direct-booking submit

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/repository/TaskRepository.java` (+`pinAgentVersion`)
- Modify: `TaskJpaRepository.java`, `TaskRepositoryImpl.java` (same files as Task 5)
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java`
- Test: extend the existing test class for `TaskWriteAppServiceImpl` (find it via `grep -rl "TaskWriteAppServiceImpl" backend/hireai-main/src/test`); if none exists, create `backend/hireai-main/src/test/java/com/hireai/application/biz/task/TaskWriteAppServiceImplTest.java` with Mockito mocks for all four constructor deps.

**Interfaces:**
- Produces: `TaskRepository.pinAgentVersion(UUID taskId, UUID agentVersionId)`; `submitDirectlyBooked` now records the pin in the same transaction.

- [ ] **Step 1: Failing test (spec test 20)**

```java
    @Test
    void directBookingPersistsThePinnedAgentVersion() {
        UUID versionId = UUID.randomUUID();
        // arrange: taskSubmitDomainService.submit -> a TaskModel; taskRepository.save returns it
        UUID taskId = service.submitDirectlyBooked(submitInfo, versionId);
        verify(taskRepository).pinAgentVersion(taskId, versionId);
    }

    @Test
    void openSubmitDoesNotPin() {
        service.submit(submitInfo);
        verify(taskRepository, never()).pinAgentVersion(any(), any());
    }
```

- [ ] **Step 2: Run to verify failure** (no such port method → compile error).

- [ ] **Step 3: Implement**

Port: `void pinAgentVersion(UUID taskId, UUID agentVersionId);`
JPA: `@Modifying @Query(value = "UPDATE tasks SET pinned_agent_version_id = :versionId WHERE id = :id", nativeQuery = true) void pinAgentVersion(@Param("id") UUID id, @Param("versionId") UUID versionId);`
Impl delegates as in Task 5.

`TaskWriteAppServiceImpl.submitDirectlyBooked`:

```java
    @Override
    public UUID submitDirectlyBooked(TaskSubmitInfo taskSubmitInfo, UUID agentVersionId) {
        UUID taskId = doSubmit(taskSubmitInfo, agentVersionId);
        // Same transaction as the submit: the re-match sweeper must be able to tell pinned tasks
        // from open tasks and never substitute another agent for a direct booking (spec §6.1).
        taskRepository.pinAgentVersion(taskId, agentVersionId);
        return taskId;
    }
```

- [ ] **Step 4: Run**: targeted test → PASS; full suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: persist pinned_agent_version_id at direct-booking submit"`

---

### Task 7: TaskModel transitions — markCancelled + widen for re-match

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/domain/biz/task/model/TaskModelTransitionsTest.java` (locate with glob if the package differs; extend it)

**Interfaces:**
- Produces: `TaskModel.markCancelled()` (AWAITING_CAPACITY → CANCELLED); `assignAndQueue` and `markAwaitingCapacity` accept **SUBMITTED or AWAITING_CAPACITY** as the from-state (re-match support).

- [ ] **Step 1: Failing tests** — add to `TaskModelTransitionsTest` (mirror its existing fixture helpers for building tasks in a given status):

```java
    @Test
    void markCancelledFromAwaitingCapacity() { /* AWAITING_CAPACITY -> markCancelled() -> CANCELLED */ }

    @Test
    void markCancelledRejectsOtherStatuses() { /* SUBMITTED -> markCancelled() throws DomainException */ }

    @Test
    void assignAndQueueAcceptsAwaitingCapacity() { /* AWAITING_CAPACITY -> assignAndQueue(v) -> QUEUED */ }

    @Test
    void markAwaitingCapacityIsIdempotentFromAwaitingCapacity() { /* stays AWAITING_CAPACITY, no throw */ }
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement** in `TaskModel`:

```java
    /** SUBMITTED/AWAITING_CAPACITY → QUEUED: a matching agent version was selected (re-match included). */
    public TaskModel assignAndQueue(UUID agentVersionId) {
        requireStatusIn("assignAndQueue", TaskStatus.SUBMITTED, TaskStatus.AWAITING_CAPACITY);
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, agentVersionId, this.result);
    }

    /** SUBMITTED → AWAITING_CAPACITY (idempotent from AWAITING_CAPACITY for re-match no-match passes). */
    public TaskModel markAwaitingCapacity() {
        requireStatusIn("markAwaitingCapacity", TaskStatus.SUBMITTED, TaskStatus.AWAITING_CAPACITY);
        return copyWith(TaskStatus.AWAITING_CAPACITY, this.agentVersionId, this.result);
    }

    /** AWAITING_CAPACITY → CANCELLED: re-match attempts exhausted; the caller refunds escrow. */
    public TaskModel markCancelled() {
        requireStatus(TaskStatus.AWAITING_CAPACITY, "markCancelled");
        return copyWith(TaskStatus.CANCELLED, this.agentVersionId, this.result);
    }
```

- [ ] **Step 4: Run** `TaskModelTransitionsTest` → PASS; full suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: markCancelled transition + widen assign/hold transitions for re-match"`

---

### Task 8: Reliability queries on the task repository

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/repository/TaskRepository.java`
- Modify: `TaskJpaRepository.java`, `TaskRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/task/TaskReliabilityQueriesIntegrationTest.java` (create; scaffold from `RoutingIntegrationTest` like Task 3)

**Interfaces:**
- Produces (on `TaskRepository`):

```java
    List<UUID> findIdsAwaitingCapacity();
    List<UUID> findIdsExecutionExpired(java.time.Instant now);
    void incrementMatchAttempts(UUID taskId);
    int matchAttempts(UUID taskId);
    Optional<UUID> findPinnedAgentVersionId(UUID taskId);
```

- [ ] **Step 1: Failing integration test** — seed tasks in assorted statuses and assert:

```java
// findIdsAwaitingCapacity: returns exactly the AWAITING_CAPACITY task ids.
// findIdsExecutionExpired(now): returns a QUEUED task with execution_deadline < now and an EXECUTING
//   one likewise; excludes EXECUTING with future deadline, excludes PENDING_REVIEW regardless,
//   excludes rows with NULL execution_deadline.
// incrementMatchAttempts twice -> matchAttempts == 2 (fresh task starts at 0).
// findPinnedAgentVersionId: empty for open task; present after taskRepository.pinAgentVersion(id, v).
```

- [ ] **Step 2: Run to verify failure** (compile error on port methods). Without Docker the IT skips — in that case verify failure by compilation, and rely on Task 15's Docker run.

- [ ] **Step 3: Implement** — `TaskJpaRepository`:

```java
    @Query(value = "SELECT id FROM tasks WHERE status = 'AWAITING_CAPACITY'", nativeQuery = true)
    List<UUID> findIdsAwaitingCapacity();

    @Query(value = """
            SELECT id FROM tasks
            WHERE status IN ('QUEUED','EXECUTING')
              AND execution_deadline IS NOT NULL AND execution_deadline < :now
            """, nativeQuery = true)
    List<UUID> findIdsExecutionExpired(@Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE tasks SET match_attempts = match_attempts + 1 WHERE id = :id", nativeQuery = true)
    void incrementMatchAttempts(@Param("id") UUID id);

    @Query(value = "SELECT match_attempts FROM tasks WHERE id = :id", nativeQuery = true)
    int findMatchAttempts(@Param("id") UUID id);

    @Query(value = "SELECT pinned_agent_version_id FROM tasks WHERE id = :id", nativeQuery = true)
    Optional<UUID> findPinnedAgentVersionId(@Param("id") UUID id);
```

`TaskRepositoryImpl` delegates each 1:1 (`matchAttempts` → `taskJpa.findMatchAttempts`). Port gets the five methods above with a javadoc note: *"Operational reliability columns — unmapped on TaskDO by design; see V24."*

**Transaction note for the implementer:** `@Modifying` queries require an active transaction. Callers added in Task 9 are `@Transactional`; the integration test methods should be `@Transactional` too (or use the scaffold's existing pattern).

- [ ] **Step 4: Run** the IT (Docker) or full suite → PASS/skip.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: task reliability queries (held ids, expired ids, attempts, pin lookup)"`

---

### Task 9: TaskWriteAppService — registerMatchAttempt + cancelAwaitingCapacityWithRefund

**Files:**
- Modify: `TaskWriteAppService.java`, `TaskWriteAppServiceImpl.java` (paths as Task 5)
- Test: the class used/created in Task 6

**Interfaces:**
- Produces: `int registerMatchAttempt(UUID taskId)` (increments, returns the new count); `void cancelAwaitingCapacityWithRefund(UUID taskId)` (status-guarded no-op if not AWAITING_CAPACITY; otherwise CANCELLED + full refund via `SettlementWriteAppService.settleRejected`).
- Consumes: Task 7 `markCancelled`, Task 8 repository methods, existing `SettlementWriteAppService.settleRejected(UUID taskId, UUID clientId, Money budget)`.

- [ ] **Step 1: Failing tests**

```java
    @Test
    void registerMatchAttemptIncrementsAndReturnsNewCount() {
        when(taskRepository.matchAttempts(taskId)).thenReturn(2);
        assertThat(service.registerMatchAttempt(taskId)).isEqualTo(2);
        verify(taskRepository).incrementMatchAttempts(taskId);
    }

    @Test
    void cancelRefundsFullBudgetAndCancels() {
        // arrange: taskRepository.findById -> task in AWAITING_CAPACITY with clientId + budget
        service.cancelAwaitingCapacityWithRefund(taskId);
        verify(taskRepository).save(argThat(t -> t.status() == TaskStatus.CANCELLED));
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
    }

    @Test
    void cancelIsANoOpWhenTaskAlreadyLeftAwaitingCapacity() {
        // arrange: task in QUEUED (a re-match won the race)
        service.cancelAwaitingCapacityWithRefund(taskId);
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement** — add `private final SettlementWriteAppService settlementWriteAppService;` to `TaskWriteAppServiceImpl` (constructor grows via Lombok `@RequiredArgsConstructor`; update any direct `new TaskWriteAppServiceImpl(...)` in tests). Interface:

```java
    /** Re-match bookkeeping: increments and returns the task's match_attempts counter. */
    int registerMatchAttempt(@NonNull UUID taskId);

    /**
     * Re-match exhaustion: AWAITING_CAPACITY -> CANCELLED + FULL escrow refund (recorded settlement,
     * Hard Invariants #1/#2). Status-guarded no-op if the task already left AWAITING_CAPACITY.
     */
    void cancelAwaitingCapacityWithRefund(@NonNull UUID taskId);
```

Impl:

```java
    @Override
    public int registerMatchAttempt(UUID taskId) {
        taskRepository.incrementMatchAttempts(taskId);
        return taskRepository.matchAttempts(taskId);
    }

    @Override
    public void cancelAwaitingCapacityWithRefund(UUID taskId) {
        TaskModel task = load(taskId);
        if (task.status() != TaskStatus.AWAITING_CAPACITY) {
            log.info("Task {} is {} (not AWAITING_CAPACITY); skipping cancel", taskId, task.status());
            return;
        }
        TaskModel cancelled = task.markCancelled();
        taskRepository.save(cancelled);
        settlementWriteAppService.settleRejected(taskId, cancelled.clientId(), cancelled.budget());
        log.info("Task {} CANCELLED after re-match exhaustion; escrow fully refunded", taskId);
    }
```

(import `com.hireai.application.biz.ledger.settlement.SettlementWriteAppService` and `com.hireai.domain.biz.task.enums.TaskStatus`.)

- [ ] **Step 4: Run** targeted + full suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: match-attempt counter + cancel-with-refund on re-match exhaustion"`

---

### Task 10: TaskReliabilityAppService (rematchOne / timeoutOne)

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/reliability/TaskReliabilityAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/reliability/impl/TaskReliabilityAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/task/reliability/TaskReliabilityAppServiceImplTest.java` (create, Mockito)

**Interfaces:**
- Produces:

```java
public interface TaskReliabilityAppService {
    List<UUID> awaitingCapacityTaskIds();
    void rematchOne(UUID taskId);      // NOT transactional — publishes MQ via RoutingAppService
    List<UUID> executionExpiredTaskIds();
    void timeoutOne(UUID taskId);      // transactional: TIMED_OUT + full refund
}
```

- Consumes: `TaskRepository` (Task 8), `RoutingAppService.route/dispatchDirect`, `TaskWriteAppService.registerMatchAttempt/cancelAwaitingCapacityWithRefund` (Task 9), `SettlementWriteAppService.settleRejected`.

- [ ] **Step 1: Failing tests (spec tests 21–31 at unit level)**

```java
package com.hireai.application.biz.task.reliability;
// Mockito unit test; construct TaskReliabilityAppServiceImpl directly with mocks and maxAttempts=3.

    @Test
    void rematchSkipsTasksThatAlreadyLeftAwaitingCapacity() {
        givenTaskInStatus(TaskStatus.QUEUED);
        service.rematchOne(taskId);
        verifyNoInteractions(routingAppService);
        verify(taskWriteAppService, never()).registerMatchAttempt(any());
    }

    @Test
    void openTaskRematchRunsFullRouting() {
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(1);
        service.rematchOne(taskId);
        verify(routingAppService).route(taskId);
        verify(routingAppService, never()).dispatchDirect(any(), any());
    }

    @Test
    void pinnedTaskRematchRetriesOnlyThePinnedVersion() {
        UUID pinned = UUID.randomUUID();
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.of(pinned));
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(1);
        service.rematchOne(taskId);
        verify(routingAppService).dispatchDirect(taskId, pinned);
        verify(routingAppService, never()).route(any());
    }

    @Test
    void exhaustedAttemptsCancelWithRefund() {
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);                 // before AND after routing
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(3);
        service.rematchOne(taskId);
        verify(routingAppService).route(taskId);                          // last chance still taken
        verify(taskWriteAppService).cancelAwaitingCapacityWithRefund(taskId);
    }

    @Test
    void underAttemptBoundDoesNotCancel() {
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(2);
        service.rematchOne(taskId);
        verify(taskWriteAppService, never()).cancelAwaitingCapacityWithRefund(any());
    }

    @Test
    void successfulRematchDoesNotCancelEvenAtBound() {
        // status AWAITING_CAPACITY before routing, QUEUED on the re-read after routing
        givenTaskStatusSequence(TaskStatus.AWAITING_CAPACITY, TaskStatus.QUEUED);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(3);
        service.rematchOne(taskId);
        verify(taskWriteAppService, never()).cancelAwaitingCapacityWithRefund(any());
    }

    @Test
    void timeoutMarksTimedOutAndRefunds() {
        givenTaskInStatus(TaskStatus.EXECUTING);   // with clientId + budget on the fixture task
        service.timeoutOne(taskId);
        verify(taskRepository).save(argThat(t -> t.status() == TaskStatus.TIMED_OUT));
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
    }

    @Test
    void timeoutIsANoOpOncePastExecuting() {
        givenTaskInStatus(TaskStatus.PENDING_REVIEW);
        service.timeoutOne(taskId);
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }
```

`givenTaskInStatus` builds a real `TaskModel` via its canonical constructor in the given status; `givenTaskStatusSequence` makes `taskRepository.findById` return the two states on consecutive calls.

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```java
package com.hireai.application.biz.task.reliability.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reliability orchestration behind the two sweepers (spec §6). rematchOne is deliberately NOT
 * transactional: it drives RoutingAppService, which publishes to RabbitMQ and manages its own
 * commit-before-publish ordering. timeoutOne and the id reads are transactional. Every write it
 * triggers is status-guarded, so overlapping sweeps and races resolve as no-ops.
 */
@Service
@Slf4j
public class TaskReliabilityAppServiceImpl implements TaskReliabilityAppService {

    private final TaskRepository taskRepository;
    private final RoutingAppService routingAppService;
    private final TaskWriteAppService taskWriteAppService;
    private final SettlementWriteAppService settlementWriteAppService;
    private final int rematchMaxAttempts;

    public TaskReliabilityAppServiceImpl(TaskRepository taskRepository,
                                         RoutingAppService routingAppService,
                                         TaskWriteAppService taskWriteAppService,
                                         SettlementWriteAppService settlementWriteAppService,
                                         @Value("${hireai.matching.rematch-max-attempts:3}") int rematchMaxAttempts) {
        if (rematchMaxAttempts < 1) {
            throw new IllegalStateException("rematch-max-attempts must be >= 1; got " + rematchMaxAttempts);
        }
        this.taskRepository = taskRepository;
        this.routingAppService = routingAppService;
        this.taskWriteAppService = taskWriteAppService;
        this.settlementWriteAppService = settlementWriteAppService;
        this.rematchMaxAttempts = rematchMaxAttempts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> awaitingCapacityTaskIds() {
        return taskRepository.findIdsAwaitingCapacity();
    }

    @Override
    public void rematchOne(UUID taskId) {
        Optional<TaskModel> maybeTask = taskRepository.findById(taskId);
        if (maybeTask.isEmpty() || maybeTask.get().status() != TaskStatus.AWAITING_CAPACITY) {
            return; // matched or cancelled since the sweep listed it — nothing to do
        }
        int attempts = taskWriteAppService.registerMatchAttempt(taskId);
        Optional<UUID> pinned = taskRepository.findPinnedAgentVersionId(taskId);
        if (pinned.isPresent()) {
            // Direct booking: retry ONLY the client's chosen version — never substitute (spec §6.1).
            routingAppService.dispatchDirect(taskId, pinned.get());
        } else {
            routingAppService.route(taskId);
        }
        boolean stillHeld = taskRepository.findById(taskId)
                .map(t -> t.status() == TaskStatus.AWAITING_CAPACITY)
                .orElse(false);
        if (stillHeld && attempts >= rematchMaxAttempts) {
            taskWriteAppService.cancelAwaitingCapacityWithRefund(taskId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> executionExpiredTaskIds() {
        return taskRepository.findIdsExecutionExpired(Instant.now());
    }

    @Override
    @Transactional
    public void timeoutOne(UUID taskId) {
        TaskModel task = taskRepository.findById(taskId).orElse(null);
        if (task == null
                || (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING)) {
            return; // result arrived / already failed since the sweep listed it
        }
        TaskModel timedOut = task.markTimedOut();
        taskRepository.save(timedOut);
        settlementWriteAppService.settleRejected(taskId, timedOut.clientId(), timedOut.budget());
        log.info("Task {} TIMED_OUT past execution deadline; escrow fully refunded", taskId);
    }
}
```

Interface file with the four method signatures + javadoc as in the Interfaces block.

- [ ] **Step 4: Run** targeted + full suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: TaskReliabilityAppService - attempt-bounded rematch + execution timeout with refund"`

---

### Task 11: The two sweepers

**Files:**
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/CapacityRematchSweeper.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ExecutionTimeoutSweeper.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/messaging/CapacityRematchSweeperTest.java`, `.../ExecutionTimeoutSweeperTest.java` (create, Mockito — mirror how `ArbitrationSweeper` is tested if a test exists; else plain Mockito on the package-visible `sweep()`)

**Interfaces:**
- Consumes: `TaskReliabilityAppService` (Task 10). No new interfaces produced.

- [ ] **Step 1: Failing tests** — for each sweeper: (a) `sweep()` calls the per-id method for every listed id; (b) an exception on the first id does not prevent the second (spec test 28); (c) empty list → no calls.

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement** (pattern-copy of `ArbitrationSweeper`):

```java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Re-match sweeper (spec §6.1): every tick, retries matching for every AWAITING_CAPACITY task
 * (attempt-bounded; exhaustion -> CANCELLED + full refund inside the app service). Per-id work is
 * a cross-bean call with its own transactions; one poisoned id can't block the rest.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class CapacityRematchSweeper {

    private final TaskReliabilityAppService taskReliabilityAppService;

    @Scheduled(fixedDelayString = "${hireai.matching.rematch-interval:PT10S}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over every held task. */
    void sweep() {
        List<UUID> held = taskReliabilityAppService.awaitingCapacityTaskIds();
        for (UUID id : held) {
            try {
                taskReliabilityAppService.rematchOne(id);
            } catch (Exception e) {
                log.warn("Re-match sweeper: failed for task {}", id, e);
            }
        }
        if (!held.isEmpty()) {
            log.info("Re-match sweeper: processed {} held task(s)", held.size());
        }
    }
}
```

`ExecutionTimeoutSweeper` is identical in shape: `@Scheduled(fixedDelayString = "${hireai.execution.sweep-interval:PT30S}")`, iterating `executionExpiredTaskIds()` → `timeoutOne(id)`, log prefix "Timeout sweeper".

(`SchedulingConfig` already enables `@EnableScheduling` app-wide — no change needed.)

- [ ] **Step 4: Run** targeted + full suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: capacity re-match + execution timeout sweepers"`

---

### Task 12: DLQ stranded-escrow fix

**Files:**
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/TaskExecutionPortImpl.java`
- Test: `backend/hireai-main/src/test/java/**/TaskExecutionPortImplTest.java` (extend — locate via glob)

**Interfaces:**
- Consumes: `SettlementWriteAppService.settleRejected`. `TaskExecutionPort` signature unchanged; `markFailed` becomes status-guarded and refunds.

- [ ] **Step 1: Failing tests (spec tests 33–34)**

```java
    @Test
    void markFailedRefundsTheClientEscrow() {
        // arrange: findById -> EXECUTING task with clientId + budget
        port.markFailed(taskId);
        verify(taskRepository).save(argThat(t -> t.status() == TaskStatus.FAILED));
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
    }

    @Test
    void duplicateMarkFailedIsANoOp() {
        // arrange: findById -> task already FAILED
        port.markFailed(taskId);
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }
```

- [ ] **Step 2: Run to verify failure** (existing constructor has no settlement dep → compile/verify error).

- [ ] **Step 3: Implement** — add `private final SettlementWriteAppService settlementWriteAppService;` and replace `markFailed`:

```java
    @Override
    public void markFailed(UUID taskId) {
        TaskModel task = load(taskId);
        if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING) {
            // Duplicate failure signal (e.g. DLQ redelivery, or the timeout sweeper got there first):
            // the terminal state + refund already happened. Idempotent no-op.
            log.info("Task {} already {}; ignoring duplicate failure signal", taskId, task.status());
            return;
        }
        TaskModel failed = task.markFailed();
        taskRepository.save(failed);
        // Stranded-escrow fix (spec §6.3): the DLQ path previously marked FAILED without refunding,
        // freezing the client's escrow forever. Every escrow exit is a recorded settlement.
        settlementWriteAppService.settleRejected(taskId, failed.clientId(), failed.budget());
        log.info("Task {} marked FAILED and escrow refunded", taskId);
    }
```

(imports: `SettlementWriteAppService`, `TaskStatus`.) `TaskDispatchConsumer` needs no change — it already calls `taskExecutionPort.markFailed` from the DLQ listener.

- [ ] **Step 4: Run** targeted + full suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "fix: refund escrow when dispatch dead-letters (stranded-escrow bug)"`

---

### Task 13: Builder-declared maxConcurrent through registration (backend)

**Files:**
- Modify: `AgentVersionModel.java`, `AgentModel.java`, `AgentRegisterInfo.java`, `AgentRegisterDomainServiceImpl.java` (paths in File Structure)
- Modify: `backend/hireai-repository/.../offering/agent/AgentVersionDO.java` (add mapped `max_concurrent` column + constructor arg + getter — **safe here** because the value is now threaded from the domain on every save)
- Modify: `AgentRepositoryImpl.java` (save / publishNewVersion / toVersionModel pass it through)
- Modify: `RegisterAgentRequest.java`, `AgentController.java`
- Tests: extend `AgentModelTest` / `AgentContractTypesTest` (locate via glob); update every `new AgentVersionModel(`, `AgentVersionModel.create(`, `AgentModel.register(`, `new AgentVersionDO(` call site (`grep -rn` each across `backend/`)

**Interfaces:**
- Produces: `AgentVersionModel.create(UUID agentId, int versionNumber, OutputSpec outputSpec, List<String> capabilityCategories, String webhookUrl, int maxExecutionSeconds, Pricing pricing, int maxConcurrent)`; canonical constructor gains `int maxConcurrent` **after `maxExecutionSeconds`**; `maxConcurrent()` accessor; `supersededBy` carries it over unchanged. `AgentModel.register(..., int maxExecutionSeconds, Pricing pricing, int maxConcurrent)`. `AgentRegisterInfo(..., BigDecimal price, int maxConcurrent)`. `RegisterAgentRequest.maxConcurrent` is `@Min(1) @Max(100) Integer` (nullable → default).

- [ ] **Step 1: Failing tests**

```java
    // AgentModelTest additions:
    @Test
    void registerCarriesMaxConcurrentOntoTheVersion() {
        AgentModel agent = /* register(...) with maxConcurrent 8 */;
        assertThat(agent.currentVersion().maxConcurrent()).isEqualTo(8);
    }

    @Test
    void createRejectsMaxConcurrentOutOfRange() {
        // maxConcurrent 0 and 101 -> DomainException(VALIDATION_ERROR)
    }

    @Test
    void supersededByCarriesMaxConcurrentOver() {
        // version with maxConcurrent 8 -> supersededBy(...) -> next.maxConcurrent() == 8
    }
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

`AgentVersionModel`: add field `private final int maxConcurrent;` + constructor param (after `maxExecutionSeconds`) + accessor `public int maxConcurrent() { return maxConcurrent; }`. In `create(...)` add param + validation:

```java
        if (maxConcurrent < 1 || maxConcurrent > 100) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxConcurrent must be between 1 and 100");
        }
```

`supersededBy` passes `this.maxConcurrent` into the new instance (carried over like `webhookUrl`).

`AgentModel.register(...)`: add trailing `int maxConcurrent` param, forwarded to `AgentVersionModel.create`.

`AgentRegisterInfo`: append `int maxConcurrent` component. `AgentRegisterDomainServiceImpl`: pass `info.maxConcurrent()`.

`RegisterAgentRequest`: add component after `price`:

```java
        @Min(value = 1, message = "maxConcurrent must be at least 1")
        @Max(value = 100, message = "maxConcurrent must be at most 100")
        Integer maxConcurrent
```

(import `jakarta.validation.constraints.Max`). `AgentController`: add a constructor param `@Value("${hireai.matching.default-max-concurrent:5}") int defaultMaxConcurrent` stored in a field (import `org.springframework.beans.factory.annotation.Value`), and in `register(...)` build the info with:

```java
                request.price(),
                request.maxConcurrent() == null ? defaultMaxConcurrent : request.maxConcurrent());
```

`AgentVersionDO`: add `@Column(name = "max_concurrent", nullable = false) private int maxConcurrent;` + constructor param (after `maxExecutionSeconds`) + getter. `AgentRepositoryImpl`: pass `version.maxConcurrent()` in both `save` and `publishNewVersion` DO constructions; pass `entity.getMaxConcurrent()` in `toVersionModel`.

Then `grep -rn "new AgentVersionModel(\|AgentVersionModel.create(\|AgentModel.register(\|new AgentVersionDO(" backend/` and add the argument (use `5` in tests that don't care) at every remaining call site.

- [ ] **Step 4: Run** full backend suite → PASS.
- [ ] **Step 5: Commit**: `git add backend && git commit -m "feat: builder-declared maxConcurrent through agent registration"`

---

### Task 14: Frontend — maxConcurrent field on the register form

**Files:**
- Modify: `frontend/lib/types.ts` (`CreateAgentRequest` + `AgentVersionDTO`? — **only** `CreateAgentRequest`; the read DTO is unchanged by the backend)
- Modify: `frontend/app/builder/agents/new/page.tsx`
- Test: `frontend/app/builder/agents/new/page.test.tsx` (create)

**Interfaces:**
- Consumes: backend `RegisterAgentRequest.maxConcurrent` (Task 13).

- [ ] **Step 1: Failing test** — model the scaffolding on `frontend/app/builder/earnings/page.test.tsx` (msw `server`/`ok` helpers from `frontend/test/msw/handlers`, `AuthProvider` + localStorage, `vi.mock("next/navigation", ...)`); note the auth localStorage shape there and reuse it with a BUILDER role. Test body:

```tsx
it("submits maxConcurrent (default 5) with the registration", async () => {
  let captured: Record<string, unknown> | null = null;
  server.use(
    http.post("*/api/agents", async ({ request }) => {
      captured = (await request.json()) as Record<string, unknown>;
      return ok({ id: "a-1" });
    }),
  );
  renderPage();
  fireEvent.change(screen.getByLabelText(/name/i), { target: { value: "Bot" } });
  fireEvent.change(screen.getByLabelText(/categories/i), { target: { value: "summarisation" } });
  fireEvent.change(screen.getByLabelText(/webhook/i), { target: { value: "https://a.example/run" } });
  fireEvent.click(screen.getByRole("button", { name: /register agent/i }));
  await waitFor(() => expect(captured).not.toBeNull());
  expect(captured!.maxConcurrent).toBe(5);
});
```

- [ ] **Step 2: Run to verify failure**: `npx vitest run app/builder/agents/new` (in `frontend/`) — fails (no test file target / field missing).

- [ ] **Step 3: Implement** — `types.ts`: add `maxConcurrent: number;` to `CreateAgentRequest`. `page.tsx`: add state `const [maxConcurrent, setMaxConcurrent] = useState(5);`, include `maxConcurrent` in `body`, and change the two-column grid to three columns:

```tsx
          <div className="grid grid-cols-3 gap-4">
            {/* existing Max execution seconds + Price fields unchanged */}
            <Field label="Max parallel tasks" htmlFor="maxConcurrent">
              <Input
                id="maxConcurrent"
                type="number"
                min={1}
                max={100}
                value={maxConcurrent}
                onChange={(e) => setMaxConcurrent(Number(e.target.value))}
                required
              />
            </Field>
          </div>
```

- [ ] **Step 4: Run**: `npx vitest run` (full frontend suite) → PASS (84 tests). Also `npm --prefix frontend run build` → succeeds.
- [ ] **Step 5: Commit**: `git add frontend && git commit -m "feat(frontend): max parallel tasks field on agent registration"`

---

### Task 15: End-to-end integration tests + regression + docs

**Files:**
- Create: `backend/hireai-main/src/test/java/com/hireai/task/routing/MatchingEngineIntegrationTest.java` (scaffold from `RoutingIntegrationTest` — same Testcontainers base, Docker-gated)
- Modify: `CLAUDE.md` (repo root — one build-status line)

**Interfaces:** consumes everything above; produces nothing new.

- [ ] **Step 1: Write the integration tests (spec tests 35–38).** In the scaffold's `@DynamicPropertySource`, force determinism: `registry.add("hireai.matching.epsilon", () -> "0");` (and reuse whatever it already registers for the containers). Cases:

```java
// 35: seed THREE ACTIVE agents in "summarisation" within budget with engineered scores
//     (e.g. price 10/idle/0-samples vs price 25/4-in-flight/100-samples vs price 28/idle/50-samples);
//     submit an open task; await dispatch; assert tasks.agent_version_id == the hand-computed winner
//     (compute the three scores in a comment; epsilon=0 makes it exact).
// 36: submit a task in a category with NO agent -> status AWAITING_CAPACITY; then activate a matching
//     agent; call taskReliabilityAppService.rematchOne(taskId) directly (sweepers are @Profile("!test"));
//     assert status becomes QUEUED/EXECUTING and agent_version_id set.
// 37: submit with no eligible agent; call rematchOne three times; assert status CANCELLED and the
//     client wallet's available balance equals its pre-submit value (read via the wallet read service),
//     and a settlements row exists for the task.
// 38: POST-register an agent with maxConcurrent 8 through AgentWriteAppService.register(...);
//     read back via agentRepository.findById -> currentVersion().maxConcurrent() == 8; and
//     findActiveCandidates returns maxConcurrent() == 8 for it.
```

- [ ] **Step 2: Run with Docker**: `mvn -f backend/pom.xml -pl hireai-main -am -B test -Dtest=MatchingEngineIntegrationTest -DfailIfNoTests=false` → PASS (or SKIPPED without Docker — then flag to the user that a Docker run is still owed).

- [ ] **Step 3: Full regression, both stacks**

Run: `mvn -f backend/pom.xml -B test` → BUILD SUCCESS.
Run (in `frontend/`): `npx vitest run` → all pass; `npm --prefix frontend run build` → success.

- [ ] **Step 4: Update CLAUDE.md** — in the backend bullet of *Repository status*, replace the sentence `**Still deferred (Module 4):** ...` context by appending before it: `**Matching engine + reliability built:** multi-factor scored matcher (reputation/valueFit/loadHeadroom/exploration, config-weighted) + epsilon-greedy selection (`rank`/`selectOne`, `V24`), builder-declared `max_concurrent`, attempt-bounded `AWAITING_CAPACITY` re-match sweeper (3×10s → `CANCELLED` + refund; pinned direct bookings retry only their version), execution-timeout sweeper (`execution_deadline` stamped at assignment → `TIMED_OUT` + refund), and the DLQ stranded-escrow refund fix.` Keep it one sentence in the existing style.

- [ ] **Step 5: Commit**

```bash
git add backend CLAUDE.md
git commit -m "test: matching engine integration suite (scored winner, rematch rescue, exhaustion refund)"
```

---

## Self-Review (done at plan-writing time)

- **Spec coverage:** §4 scorer → Tasks 2/4; §5.1 query → Task 3; §5.2 V24 → Task 1 (+DO mapping deferred to Task 13 deliberately); §5.3 registration + casing → Tasks 13/1 (casing fix reduced to backfill — `AgentVersionModel.create` already lowercases; spec's desired end-state already holds in code); §6.1 → Tasks 6/7/8/9/10/11; §6.2 → Tasks 5/10/11; §6.3 → Task 12; §6.4 → Tasks 9/10/12 (all reuse `settleRejected`); §7 config → Tasks 4/10/11/13; §8 → guards embedded per task; §9 tests 1–40 → mapped in Tasks 2–15 (test 32 late-callback: covered by existing first-result-wins guard + Task 12's no-op test; asserted end-to-end in Task 15 case 37's settlement-uniqueness check).
- **Known deviations from spec text (accepted):** grace key is `hireai.execution.grace-seconds` (spec table said `grace`); cancel/timeout refunds record a REJECT-type settlement row (spec §6.4 allows: "recorded through the existing settlement write path", no new settlement math).
- **Type consistency:** `AgentCandidate` component order (`..., outputSpecJson, maxConcurrent, inFlight, sampleCount`) used identically in Tasks 3/4/15; `assignAndQueue(UUID, UUID, Instant)` in Tasks 5/7/10; `TaskReliabilityAppService` names in Tasks 10/11/15.
- **Placeholders:** integration-test scaffolding intentionally says "read `RoutingIntegrationTest` and reuse its base" — the base class name/annotations are project-specific and must be copied from source, not invented; all test *bodies* and assertions are specified.
