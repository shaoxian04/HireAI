# Shortlist Selection (Frontend Match → Pick → Book) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn frontend open-task submission into "match → show a shortlist → the client picks", where picking reuses the existing direct booking — with no new task state, no sweeper, no migration, and the money path untouched.

**Architecture:** A read-only match-preview endpoint returns bookable (ACTIVE + listed) agents for a category, split into an in-budget shortlist (ordered by the Phase-1 domain `rank()`) and a near-miss list (above budget, cheapest 3). The frontend renders them; selecting an agent calls the existing `POST /api/tasks/direct` with `budget := chosen agent's price` (pay-the-price). The form draft lives in `localStorage`.

**Tech Stack:** Spring Boot (Java 21) COLA multi-module reactor; Postgres; Next.js 16 + TypeScript + Tailwind; JUnit 5 + Mockito + Testcontainers; Vitest + Testing Library + MSW; Playwright (E2E).

Spec: `docs/superpowers/specs/2026-07-10-shortlist-selection-design.md`.

## Global Constraints

- **Layering (compiler-enforced):** `controller → application → domain ← infrastructure/repository`. The `domain` and `utility` modules carry **no Spring**. Domain services are framework-free and wired in `DomainServiceConfig`.
- **App services** are always an **interface + `impl/`** class (`@Service`).
- **Money:** all credit amounts use `com.hireai.domain.shared.model.Money` (never float); `Money.of(BigDecimal)`, `.value()` returns `BigDecimal`.
- **Category normalization:** the domain matcher's category check is case-sensitive (`capabilityCategories().contains(category)`) and DB categories are stored lowercase — so **lowercase the category before ranking** and before the SQL filter.
- **No new migration, no new `TaskStatus`, no new sweeper, no settlement/escrow change.** Picking reuses `POST /api/tasks/direct` verbatim.
- **All existing backend + frontend tests stay green.** In particular the Phase-1 `RoutingMatchDomainServiceTest` must pass **unchanged** after the matcher signature widens.
- **Backend targeted test flag:** `-Dsurefire.failIfNoSpecifiedTests=false` (NOT `-DfailIfNoTests`). Testcontainers ITs auto-skip without Docker.
- **Commits:** conventional (`feat:`/`test:`/`refactor:`/`docs:`), no attribution. Commit after each task.
- **Branch:** `feat/shortlist-selection` (already created off `main`).

---

### Task 1: `MatchCriteria` domain seam + widen the matcher

The matcher currently takes a `TaskRoutingView` (a 5-field *Task* projection) but only reads `category()`/`budget()`. The preview has no task, so extract a minimal input that `TaskRoutingView` implements — a source-compatible widening.

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/info/MatchCriteria.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/info/PreviewCriteria.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java` (add `implements MatchCriteria`)
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/RoutingMatchDomainService.java` (param `TaskRoutingView` → `MatchCriteria`)
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/impl/RoutingMatchDomainServiceImpl.java` (same signature change on `rank`, `selectOne`, and the private `score`/`withinBudget` helpers)
- Test: `backend/hireai-main/src/test/java/com/hireai/domain/biz/task/routing/service/RoutingMatchPreviewCriteriaTest.java`

**Interfaces:**
- Consumes: existing `RoutingMatchDomainServiceImpl`, `MatchingPolicy`, `AgentCandidate`, `ScoredCandidate`.
- Produces: `interface MatchCriteria { String category(); java.math.BigDecimal budget(); }`; `record PreviewCriteria(String category, BigDecimal budget) implements MatchCriteria`; `RoutingMatchDomainService.rank(MatchCriteria, List<AgentCandidate>) : List<ScoredCandidate>` and `selectOne(MatchCriteria, List<AgentCandidate>) : Optional<UUID>`.

- [ ] **Step 1: Write the failing test**

`RoutingMatchPreviewCriteriaTest.java`:
```java
package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The matcher accepts any MatchCriteria (here a task-less PreviewCriteria) and still budget-filters. */
class RoutingMatchPreviewCriteriaTest {

    private RoutingMatchDomainService greedy() {
        return new RoutingMatchDomainServiceImpl(new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0), new Random(1));
    }

    private AgentCandidate candidate(String price) {
        return new AgentCandidate(UUID.randomUUID(), UUID.randomUUID(), List.of("summarisation"),
                new BigDecimal(price), "https://a.example/hook", 60, new BigDecimal("50.00"),
                "{\"format\":\"JSON\"}", 5, 0, 10);
    }

    @Test
    void rankAcceptsPreviewCriteriaAndFiltersByBudget() {
        List<ScoredCandidate> ranked = greedy().rank(
                new PreviewCriteria("summarisation", new BigDecimal("20.00")),
                List.of(candidate("10.00"), candidate("30.00"))); // 30.00 is over budget -> filtered out
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).candidate().price()).isEqualByComparingTo("10.00");
    }
}
```

- [ ] **Step 2: Run test to verify it fails (does not compile — `PreviewCriteria` / widened `rank` absent)**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=RoutingMatchPreviewCriteriaTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`PreviewCriteria` cannot be resolved / `rank(PreviewCriteria,...)` not applicable).

- [ ] **Step 3: Create `MatchCriteria.java`**

```java
package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;

/**
 * Minimal input the routing matcher needs: a task category and a budget ceiling. Extracted so the
 * matcher no longer requires a full {@link TaskRoutingView} — the frontend match-preview has no task.
 * {@link TaskRoutingView} implements this; {@link PreviewCriteria} is the task-less carrier.
 */
public interface MatchCriteria {
    String category();

    BigDecimal budget();
}
```

- [ ] **Step 4: Create `PreviewCriteria.java`**

```java
package com.hireai.domain.biz.task.info;

import java.math.BigDecimal;

/** Task-less {@link MatchCriteria} for the match-preview flow (no task row exists yet). */
public record PreviewCriteria(String category, BigDecimal budget) implements MatchCriteria {
}
```

- [ ] **Step 5: Make `TaskRoutingView` implement `MatchCriteria`**

In `TaskRoutingView.java`, change the record declaration line so it implements the interface (its `category()`/`budget()` accessors already satisfy it — no body change):
```java
public record TaskRoutingView(UUID taskId, String category, BigDecimal budget, String status,
                              String outputSpecJson) implements MatchCriteria {
}
```

- [ ] **Step 6: Widen the interface `RoutingMatchDomainService`**

In `RoutingMatchDomainService.java`, replace the `TaskRoutingView` import with `MatchCriteria` and change both method parameter types:
```java
import com.hireai.domain.biz.task.info.MatchCriteria;
// ...
    List<ScoredCandidate> rank(MatchCriteria criteria, List<AgentCandidate> candidates);

    Optional<UUID> selectOne(MatchCriteria criteria, List<AgentCandidate> candidates);
```

- [ ] **Step 7: Widen the impl `RoutingMatchDomainServiceImpl`**

In `RoutingMatchDomainServiceImpl.java`, replace the `TaskRoutingView` import with `import com.hireai.domain.biz.task.info.MatchCriteria;` and change `TaskRoutingView` → `MatchCriteria` in all four signatures (bodies are unchanged — they only call `criteria.category()` / `criteria.budget()`):
```java
    public List<ScoredCandidate> rank(MatchCriteria criteria, List<AgentCandidate> candidates) { ... }
    public Optional<UUID> selectOne(MatchCriteria criteria, List<AgentCandidate> candidates) { ... }
    private double score(AgentCandidate c, MatchCriteria criteria) { ... }
    private boolean withinBudget(AgentCandidate candidate, MatchCriteria criteria) { ... }
```

- [ ] **Step 8: Run the new test + the Phase-1 matcher test together**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=RoutingMatchPreviewCriteriaTest,RoutingMatchDomainServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — both classes green (proves the widening is source-compatible; `RoutingMatchDomainServiceTest` passes `TaskRoutingView` unchanged).

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/info/MatchCriteria.java \
        backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/info/PreviewCriteria.java \
        backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/info/TaskRoutingView.java \
        backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/RoutingMatchDomainService.java \
        backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/routing/service/impl/RoutingMatchDomainServiceImpl.java \
        backend/hireai-main/src/test/java/com/hireai/domain/biz/task/routing/service/RoutingMatchPreviewCriteriaTest.java
git commit -m "refactor: extract MatchCriteria so the matcher takes category+budget, not a task view"
```

---

### Task 2: Match-preview query port + JDBC DAO

Return all bookable (ACTIVE + listed) candidates for a category, enriched with the scorer's per-agent metrics. **No price filter** — one query feeds both the shortlist and the near-miss list.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/port/query/MatchPreviewQueryPort.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/catalogue/JdbcMatchPreviewQueryDao.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/offering/MatchPreviewQueryDaoIntegrationTest.java`

**Interfaces:**
- Consumes: Postgres schema (`agents`, `agent_profiles.is_listed`, `agent_versions`, `tasks`); `NamedParameterJdbcTemplate`.
- Produces: `MatchPreviewQueryPort.findBookableCandidates(String category) : List<ShortlistCandidateRow>` where
  `record ShortlistCandidateRow(UUID agentId, UUID agentVersionId, String agentName, String tagline, String logoUrl, BigDecimal price, BigDecimal reputationScore, List<String> capabilityCategories, String outputSpecJson, String outputFormat, String webhookUrl, int maxExecutionSeconds, int maxConcurrent, long inFlight, long sampleCount)`.

- [ ] **Step 1: Write the failing integration test**

`MatchPreviewQueryDaoIntegrationTest.java`:
```java
package com.hireai.offering;

import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Testcontainers IT for the bookable-candidate query (spec §6.1; test plan items 8-11). */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class MatchPreviewQueryDaoIntegrationTest {

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MatchPreviewQueryPort queryPort;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM agent_versions");
        jdbc.update("DELETE FROM agent_profiles");
        jdbc.update("DELETE FROM agents");
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", id);
        return id;
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    /** Seeds one agent (status) + current version ({category}@{price}) + a profile ({isListed}). */
    private UUID[] seedAgent(String status, boolean isListed, String category, String price) {
        UUID ownerId = newBuilder();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, ?, ?, 80.00)",
                agentId, ownerId, "Agent " + price, status, versionId);
        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, " +
                        "max_execution_seconds, price, status) " +
                        "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?, 'ACTIVE')",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category},
                "https://agent.example/hook", new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed, is_featured) " +
                        "VALUES (?, 'T', ?, false)",
                agentId, isListed);
        return new UUID[]{agentId, versionId};
    }

    private void insertTask(UUID clientId, String status, UUID agentVersionId) {
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, " +
                        "agent_version_id) VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, ?, ?)",
                UUID.randomUUID(), clientId, "{\"format\":\"JSON\"}", status, agentVersionId);
    }

    @Test
    void returnsOnlyActiveListedAgentsForTheCategory() {
        UUID[] wanted = seedAgent("ACTIVE", true, "summarisation", "10.00");
        seedAgent("ACTIVE", false, "summarisation", "11.00");   // unlisted -> excluded
        seedAgent("SUSPENDED", true, "summarisation", "12.00");  // inactive -> excluded
        seedAgent("ACTIVE", true, "translation", "13.00");       // other category -> excluded

        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates("summarisation");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).agentId()).isEqualTo(wanted[0]);
        assertThat(rows.get(0).outputFormat()).isEqualTo("JSON");
    }

    @Test
    void hasNoPriceFilterSoBothInAndOverBudgetComeBack() {
        seedAgent("ACTIVE", true, "summarisation", "10.00");
        seedAgent("ACTIVE", true, "summarisation", "50.00");

        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates("summarisation");

        assertThat(rows).extracting(ShortlistCandidateRow::price)
                .anySatisfy(p -> assertThat(p).isEqualByComparingTo("10.00"))
                .anySatisfy(p -> assertThat(p).isEqualByComparingTo("50.00"));
    }

    @Test
    void computesPerAgentInFlightAndSampleCounts() {
        UUID[] agent = seedAgent("ACTIVE", true, "summarisation", "10.00");
        UUID client = newClient();
        insertTask(client, "QUEUED", agent[1]);
        insertTask(client, "EXECUTING", agent[1]);
        insertTask(client, "RESOLVED", agent[1]);

        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates("summarisation");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).inFlight()).isEqualTo(2L);
        assertThat(rows.get(0).sampleCount()).isEqualTo(1L);
    }

    @Test
    void categoryIsCaseInsensitive() {
        seedAgent("ACTIVE", true, "summarisation", "10.00");
        assertThat(queryPort.findBookableCandidates("Summarisation")).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=MatchPreviewQueryDaoIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`MatchPreviewQueryPort` absent). (If Docker is not running the class would *skip*, not prove anything — run this task's steps on a machine with Docker.)

- [ ] **Step 3: Create the port `MatchPreviewQueryPort.java`**

```java
package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-side port for the frontend match-preview. Returns every BOOKABLE (ACTIVE + listed)
 * agent whose current version covers the category — with NO price filter, so the app service
 * can split the result into the in-budget shortlist and the above-budget near-miss list. Rows
 * carry both display fields and the scorer's per-agent metrics.
 */
public interface MatchPreviewQueryPort {

    List<ShortlistCandidateRow> findBookableCandidates(String category);

    record ShortlistCandidateRow(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                                 String logoUrl, BigDecimal price, BigDecimal reputationScore,
                                 List<String> capabilityCategories, String outputSpecJson,
                                 String outputFormat, String webhookUrl, int maxExecutionSeconds,
                                 int maxConcurrent, long inFlight, long sampleCount) {
    }
}
```

- [ ] **Step 4: Create the DAO `JdbcMatchPreviewQueryDao.java`**

```java
package com.hireai.infrastructure.repository.offering.catalogue;

import com.hireai.application.port.query.MatchPreviewQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link MatchPreviewQueryPort}. Mirrors the catalogue card projection
 * (agents + agent_profiles + agent_versions) and adds the per-agent in_flight/sample_count
 * subqueries the matcher needs. Bookable = a.status = 'ACTIVE' AND p.is_listed (exactly the direct
 * booking requirement). No price filter — the app service partitions by budget. Category is
 * lowercased before binding (categories are stored lowercase).
 */
@Repository
public class JdbcMatchPreviewQueryDao implements MatchPreviewQueryPort {

    private static final String SQL = """
            SELECT a.id                    AS agent_id,
                   v.id                    AS agent_version_id,
                   a.name                  AS agent_name,
                   p.tagline               AS tagline,
                   p.logo_url              AS logo_url,
                   v.price                 AS price,
                   a.reputation_score      AS reputation_score,
                   v.capability_categories AS capability_categories,
                   v.output_spec::text     AS output_spec_json,
                   v.output_spec->>'format' AS output_format,
                   v.webhook_url           AS webhook_url,
                   v.max_execution_seconds AS max_execution_seconds,
                   v.max_concurrent        AS max_concurrent,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id AND t.status IN ('QUEUED','EXECUTING'))          AS in_flight,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id
                       AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION'))     AS sample_count
            FROM agents a
            JOIN agent_profiles p ON p.agent_id = a.id
            JOIN agent_versions v ON v.id = a.current_version_id
            WHERE a.status = 'ACTIVE'
              AND p.is_listed
              AND v.capability_categories && ARRAY[:category]::text[]
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMatchPreviewQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ShortlistCandidateRow> findBookableCandidates(String category) {
        var params = new MapSqlParameterSource()
                .addValue("category", category == null ? "" : category.trim().toLowerCase());
        return jdbc.query(SQL, params, (rs, i) -> new ShortlistCandidateRow(
                rs.getObject("agent_id", UUID.class),
                rs.getObject("agent_version_id", UUID.class),
                rs.getString("agent_name"),
                rs.getString("tagline"),
                rs.getString("logo_url"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("reputation_score"),
                stringList(rs.getArray("capability_categories")),
                rs.getString("output_spec_json"),
                rs.getString("output_format"),
                rs.getString("webhook_url"),
                rs.getInt("max_execution_seconds"),
                rs.getInt("max_concurrent"),
                rs.getLong("in_flight"),
                rs.getLong("sample_count")));
    }

    private static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=MatchPreviewQueryDaoIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (4 tests) — requires Docker.

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/port/query/MatchPreviewQueryPort.java \
        backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/offering/catalogue/JdbcMatchPreviewQueryDao.java \
        backend/hireai-main/src/test/java/com/hireai/offering/MatchPreviewQueryDaoIntegrationTest.java
git commit -m "feat: bookable-candidate query for the match preview (ACTIVE+listed, no price filter)"
```

---

### Task 3: Match-preview application read service

Partition the bookable candidates into the ranked in-budget shortlist (top 5) and the price-ascending near-miss list (top 3), reusing the domain `rank()`.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/MatchPreviewAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/MatchPreviewAppServiceImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/application/biz/task/MatchPreviewAppServiceImplTest.java`

**Interfaces:**
- Consumes: `MatchPreviewQueryPort` (Task 2), `RoutingMatchDomainService`/`RoutingMatchDomainServiceImpl` (Task 1), `AgentCandidate`, `PreviewCriteria`, `Money`.
- Produces: `MatchPreviewAppService.preview(String category, Money budget) : MatchPreview`, with nested records
  `MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses)` and
  `AgentOption(UUID agentId, UUID agentVersionId, String agentName, String tagline, String logoUrl, BigDecimal price, BigDecimal reputationScore, boolean available, String outputFormat, List<String> capabilityCategories)`.

- [ ] **Step 1: Write the failing unit test**

`MatchPreviewAppServiceImplTest.java`:
```java
package com.hireai.application.biz.task;

import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import com.hireai.application.biz.task.impl.MatchPreviewAppServiceImpl;
import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import com.hireai.domain.biz.task.routing.service.MatchingPolicy;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchPreviewAppServiceImplTest {

    private final MatchPreviewQueryPort queryPort = mock(MatchPreviewQueryPort.class);

    private MatchPreviewAppService service() {
        var matcher = new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0), new Random(1)); // epsilon 0 -> deterministic
        return new MatchPreviewAppServiceImpl(queryPort, matcher);
    }

    private ShortlistCandidateRow row(String name, String price, String rep, int maxConc, long inFlight, long samples) {
        return new ShortlistCandidateRow(UUID.randomUUID(), UUID.randomUUID(), name, "tag", "logo",
                new BigDecimal(price), new BigDecimal(rep), List.of("summarisation"),
                "{\"format\":\"JSON\"}", "JSON", "https://a/hook", 60, maxConc, inFlight, samples);
    }

    @Test
    void shortlistHoldsOnlyInBudgetRankedBest_nearMissHoldsOverBudget() {
        when(queryPort.findBookableCandidates(anyString())).thenReturn(List.of(
                row("Cheap+Good", "10.00", "90.00", 5, 0, 10),
                row("Midrange", "15.00", "50.00", 5, 0, 10),
                row("Pricey", "30.00", "70.00", 5, 0, 10)));      // over budget 20 -> near-miss

        MatchPreview preview = service().preview("summarisation", Money.of("20.00"));

        assertThat(preview.shortlist()).extracting(AgentOption::agentName)
                .containsExactly("Cheap+Good", "Midrange");        // both in budget, best-first
        assertThat(preview.nearMisses()).extracting(AgentOption::agentName).containsExactly("Pricey");
    }

    @Test
    void nearMissIsPriceAscendingAndCappedAtThree() {
        when(queryPort.findBookableCandidates(anyString())).thenReturn(List.of(
                row("In", "10.00", "50.00", 5, 0, 0),
                row("P40", "40.00", "50.00", 5, 0, 0),
                row("P25", "25.00", "50.00", 5, 0, 0),
                row("P50", "50.00", "50.00", 5, 0, 0),
                row("P30", "30.00", "50.00", 5, 0, 0)));

        MatchPreview preview = service().preview("summarisation", Money.of("20.00"));

        assertThat(preview.nearMisses()).extracting(AgentOption::agentName)
                .containsExactly("P25", "P30", "P40");              // cheapest 3, ascending
    }

    @Test
    void shortlistCappedAtFive() {
        List<ShortlistCandidateRow> six = List.of(
                row("a", "10.00", "50.00", 5, 0, 0), row("b", "11.00", "50.00", 5, 0, 0),
                row("c", "12.00", "50.00", 5, 0, 0), row("d", "13.00", "50.00", 5, 0, 0),
                row("e", "14.00", "50.00", 5, 0, 0), row("f", "15.00", "50.00", 5, 0, 0));
        when(queryPort.findBookableCandidates(anyString())).thenReturn(six);

        assertThat(service().preview("summarisation", Money.of("30.00")).shortlist()).hasSize(5);
    }

    @Test
    void availabilityIsFalseWhenAtOrOverCapacity() {
        when(queryPort.findBookableCandidates(anyString()))
                .thenReturn(List.of(row("Busy", "10.00", "50.00", 3, 3, 0)));  // inFlight == maxConcurrent

        assertThat(service().preview("summarisation", Money.of("20.00")).shortlist().get(0).available())
                .isFalse();
    }

    @Test
    void emptyWhenNoBookableCandidates() {
        when(queryPort.findBookableCandidates(anyString())).thenReturn(List.of());

        MatchPreview preview = service().preview("summarisation", Money.of("20.00"));

        assertThat(preview.shortlist()).isEmpty();
        assertThat(preview.nearMisses()).isEmpty();
    }

    @Test
    void categoryIsLowercasedBeforeRanking() {
        when(queryPort.findBookableCandidates(anyString()))
                .thenReturn(List.of(row("Agent", "10.00", "50.00", 5, 0, 0)));

        // Rows advertise "summarisation" (lowercase); a mixed-case input must still match after normalization.
        assertThat(service().preview("Summarisation", Money.of("20.00")).shortlist()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=MatchPreviewAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`MatchPreviewAppService` absent).

- [ ] **Step 3: Create the interface `MatchPreviewAppService.java`**

```java
package com.hireai.application.biz.task;

import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read service for the frontend match preview: given a category + budget, returns an in-budget
 * shortlist (ranked by the domain matcher) and an above-budget near-miss list. No task is created
 * and no escrow is frozen — picking happens later via direct booking.
 */
public interface MatchPreviewAppService {

    MatchPreview preview(String category, Money budget);

    record MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses) {
    }

    record AgentOption(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                       String logoUrl, BigDecimal price, BigDecimal reputationScore, boolean available,
                       String outputFormat, List<String> capabilityCategories) {
    }
}
```

- [ ] **Step 4: Create the impl `MatchPreviewAppServiceImpl.java`**

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MatchPreviewAppServiceImpl implements MatchPreviewAppService {

    private static final int SHORTLIST_LIMIT = 5;
    private static final int NEAR_MISS_LIMIT = 3;

    private final MatchPreviewQueryPort queryPort;
    private final RoutingMatchDomainService matcher;

    @Override
    @Transactional(readOnly = true)
    public MatchPreview preview(String category, Money budget) {
        String normalized = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates(normalized);

        Map<UUID, ShortlistCandidateRow> byVersion = rows.stream()
                .collect(Collectors.toMap(ShortlistCandidateRow::agentVersionId, Function.identity(),
                        (a, b) -> a));

        List<AgentCandidate> candidates = rows.stream().map(this::toCandidate).toList();
        List<AgentOption> shortlist = matcher
                .rank(new PreviewCriteria(normalized, budget.value()), candidates).stream()
                .limit(SHORTLIST_LIMIT)
                .map(sc -> toOption(byVersion.get(sc.candidate().agentVersionId())))
                .toList();

        BigDecimal budgetValue = budget.value();
        List<AgentOption> nearMisses = rows.stream()
                .filter(r -> r.price().compareTo(budgetValue) > 0)
                .sorted(Comparator.comparing(ShortlistCandidateRow::price))
                .limit(NEAR_MISS_LIMIT)
                .map(this::toOption)
                .toList();

        return new MatchPreview(shortlist, nearMisses);
    }

    private AgentCandidate toCandidate(ShortlistCandidateRow r) {
        return new AgentCandidate(r.agentId(), r.agentVersionId(), r.capabilityCategories(),
                r.price(), r.webhookUrl(), r.maxExecutionSeconds(), r.reputationScore(),
                r.outputSpecJson(), r.maxConcurrent(), r.inFlight(), r.sampleCount());
    }

    private AgentOption toOption(ShortlistCandidateRow r) {
        boolean available = r.inFlight() < r.maxConcurrent();
        return new AgentOption(r.agentId(), r.agentVersionId(), r.agentName(), r.tagline(),
                r.logoUrl(), r.price(), r.reputationScore(), available, r.outputFormat(),
                r.capabilityCategories());
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=MatchPreviewAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/task/MatchPreviewAppService.java \
        backend/hireai-application/src/main/java/com/hireai/application/biz/task/impl/MatchPreviewAppServiceImpl.java \
        backend/hireai-main/src/test/java/com/hireai/application/biz/task/MatchPreviewAppServiceImplTest.java
git commit -m "feat: match-preview read service (ranked in-budget shortlist + cheapest-3 near-miss)"
```

---

### Task 4: Match-preview endpoint + DTOs + converter

Expose `GET /api/tasks/match-preview`.

**Files:**
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/dto/MatchPreviewDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/dto/AgentOptionDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/converter/MatchPreview2DTOConverter.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/TaskController.java`
- Modify (test): `backend/hireai-main/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java`

**Interfaces:**
- Consumes: `MatchPreviewAppService` (Task 3), `WebResult`/`BaseController.ok`, `CurrentUserProvider`, `Money`, `DomainException`/`ResultCode`.
- Produces: HTTP `GET /api/tasks/match-preview?category={c}&budget={b}` → `WebResult<MatchPreviewDTO>`;
  `record MatchPreviewDTO(List<AgentOptionDTO> shortlist, List<AgentOptionDTO> nearMisses)`;
  `record AgentOptionDTO(UUID agentId, UUID agentVersionId, String agentName, String tagline, String logoUrl, BigDecimal price, BigDecimal reputationScore, String availability, String outputFormat, List<String> capabilityCategories)`.

- [ ] **Step 1: Write the failing controller tests (edit `TaskControllerTest.java`)**

Add the mock bean (the `@WebMvcTest` context now requires it or it fails to load) and imports + three tests:
```java
// add to the imports block:
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import java.math.BigDecimal;
import java.util.List;

// add alongside the other @MockBean fields:
    @MockBean MatchPreviewAppService matchPreviewAppService;

// add these test methods inside the class:
    @Test
    void matchPreviewReturns200WithBothLists() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(matchPreviewAppService.preview(eq("summarisation"), any()))
                .thenReturn(new MatchPreview(
                        List.of(new AgentOption(agentId, versionId, "Alpha", "tag", "logo",
                                new BigDecimal("12.00"), new BigDecimal("80.00"), true, "JSON",
                                List.of("summarisation"))),
                        List.of(new AgentOption(UUID.randomUUID(), UUID.randomUUID(), "Pricey", null, null,
                                new BigDecimal("40.00"), new BigDecimal("90.00"), false, "JSON",
                                List.of("summarisation")))));

        mockMvc.perform(get("/api/tasks/match-preview")
                        .param("category", "summarisation").param("budget", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortlist[0].agentName").value("Alpha"))
                .andExpect(jsonPath("$.data.shortlist[0].price").value(12.00))
                .andExpect(jsonPath("$.data.shortlist[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.nearMisses[0].agentName").value("Pricey"))
                .andExpect(jsonPath("$.data.nearMisses[0].availability").value("BUSY"));
    }

    @Test
    void matchPreviewBlankCategoryReturns400() throws Exception {
        mockMvc.perform(get("/api/tasks/match-preview").param("category", "").param("budget", "30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void matchPreviewNonPositiveBudgetReturns400() throws Exception {
        mockMvc.perform(get("/api/tasks/match-preview").param("category", "summarisation").param("budget", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=TaskControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error (`MatchPreviewAppService`/`AgentOption` used but the controller endpoint + DTOs don't exist).

- [ ] **Step 3: Create `AgentOptionDTO.java`**

```java
package com.hireai.controller.biz.task.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One selectable agent option in a match preview. availability is "AVAILABLE" or "BUSY". */
public record AgentOptionDTO(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                             String logoUrl, BigDecimal price, BigDecimal reputationScore,
                             String availability, String outputFormat, List<String> capabilityCategories) {
}
```

- [ ] **Step 4: Create `MatchPreviewDTO.java`**

```java
package com.hireai.controller.biz.task.dto;

import java.util.List;

/** Match-preview payload: an in-budget shortlist and an above-budget near-miss list. */
public record MatchPreviewDTO(List<AgentOptionDTO> shortlist, List<AgentOptionDTO> nearMisses) {
}
```

- [ ] **Step 5: Create `MatchPreview2DTOConverter.java`**

```java
package com.hireai.controller.biz.task.converter;

import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import com.hireai.controller.biz.task.dto.AgentOptionDTO;
import com.hireai.controller.biz.task.dto.MatchPreviewDTO;

/** Maps the app-layer MatchPreview read model to the HTTP DTO (availability boolean -> label). */
public final class MatchPreview2DTOConverter {

    private MatchPreview2DTOConverter() {
    }

    public static MatchPreviewDTO toDTO(MatchPreview preview) {
        return new MatchPreviewDTO(
                preview.shortlist().stream().map(MatchPreview2DTOConverter::option).toList(),
                preview.nearMisses().stream().map(MatchPreview2DTOConverter::option).toList());
    }

    private static AgentOptionDTO option(AgentOption o) {
        return new AgentOptionDTO(o.agentId(), o.agentVersionId(), o.agentName(), o.tagline(),
                o.logoUrl(), o.price(), o.reputationScore(),
                o.available() ? "AVAILABLE" : "BUSY", o.outputFormat(), o.capabilityCategories());
    }
}
```

- [ ] **Step 6: Wire the endpoint into `TaskController.java`**

Add imports:
```java
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.controller.biz.task.converter.MatchPreview2DTOConverter;
import com.hireai.controller.biz.task.dto.MatchPreviewDTO;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import java.math.BigDecimal;
```
Add the field + constructor parameter (keep the existing five, append this sixth):
```java
    private final MatchPreviewAppService matchPreviewAppService;
```
In the constructor, add the parameter `MatchPreviewAppService matchPreviewAppService` and `this.matchPreviewAppService = matchPreviewAppService;`.
Add the endpoint method:
```java
    @GetMapping("/match-preview")
    public WebResult<MatchPreviewDTO> matchPreview(@RequestParam("category") String category,
                                                   @RequestParam("budget") BigDecimal budget) {
        if (category == null || category.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "category is required");
        }
        if (budget == null || budget.signum() <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "budget must be positive");
        }
        return ok(MatchPreview2DTOConverter.toDTO(matchPreviewAppService.preview(category, Money.of(budget))));
    }
```
(`@RequestParam`, `@GetMapping`, `Money`, `WebResult`, `ok` are already imported in this file.)

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=TaskControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all existing TaskControllerTest tests + the 3 new ones).

- [ ] **Step 8: Run the full backend suite to confirm no regressions**

Run: `mvn -f backend/pom.xml -B test`
Expected: BUILD SUCCESS (Testcontainers ITs skip without Docker; run with Docker if available).

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/dto/MatchPreviewDTO.java \
        backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/dto/AgentOptionDTO.java \
        backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/converter/MatchPreview2DTOConverter.java \
        backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/TaskController.java \
        backend/hireai-main/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java
git commit -m "feat: GET /api/tasks/match-preview endpoint (shortlist + near-miss)"
```

---

### Task 5: Frontend types + `ShortlistPanel` component

A pure presentational component rendering the three zones, with an `onSelect` callback.

**Files:**
- Modify: `frontend/lib/types.ts` (add `AgentOptionDTO`, `MatchPreviewDTO`)
- Create: `frontend/components/ShortlistPanel.tsx`
- Test: `frontend/components/ShortlistPanel.test.tsx`

**Interfaces:**
- Consumes: `Button`, `Card` from `@/components/ui`.
- Produces: `AgentOptionDTO`, `MatchPreviewDTO` types; `ShortlistPanel({ shortlist, nearMisses, budget, onSelect })` — in-budget "Select" buttons and near-miss "Select (above budget)" buttons both call `onSelect(option)`; empty state when both lists are empty.

- [ ] **Step 1: Write the failing test**

`frontend/components/ShortlistPanel.test.tsx`:
```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO } from "@/lib/types";

const opt = (over: Partial<AgentOptionDTO>): AgentOptionDTO => ({
  agentId: "a", agentVersionId: "v", agentName: "Agent", tagline: null, logoUrl: null,
  price: 10, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
  capabilityCategories: ["summarisation"], ...over,
});

describe("ShortlistPanel", () => {
  it("renders in-budget cards and fires onSelect on Select", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel budget={30} nearMisses={[]} onSelect={onSelect}
      shortlist={[opt({ agentVersionId: "v1", agentName: "Alpha" })]} />);
    expect(screen.getByText("Alpha")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v1" }));
  });

  it("renders the near-miss zone with an above-budget button", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel budget={20} shortlist={[]} onSelect={onSelect}
      nearMisses={[opt({ agentVersionId: "v2", agentName: "Pricey", price: 25 })]} />);
    expect(screen.getByText("Pricey")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /above budget/i }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v2" }));
  });

  it("shows the empty state when both lists are empty", () => {
    render(<ShortlistPanel budget={30} shortlist={[]} nearMisses={[]} onSelect={vi.fn()} />);
    expect(screen.getByText(/no agents match/i)).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run components/ShortlistPanel.test.tsx`
Expected: FAIL — cannot resolve `@/components/ShortlistPanel` / `AgentOptionDTO`.

- [ ] **Step 3: Add the types to `frontend/lib/types.ts`**

Append:
```ts
export interface AgentOptionDTO {
  agentId: string;
  agentVersionId: string;
  agentName: string;
  tagline: string | null;
  logoUrl: string | null;
  price: number;
  reputationScore: number;
  availability: "AVAILABLE" | "BUSY";
  outputFormat: string | null;
  capabilityCategories: string[];
}

export interface MatchPreviewDTO {
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
}
```

- [ ] **Step 4: Create `frontend/components/ShortlistPanel.tsx`**

```tsx
"use client";

import type { AgentOptionDTO } from "@/lib/types";
import { Button, Card } from "@/components/ui";

interface Props {
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
  budget: number;
  onSelect: (option: AgentOptionDTO) => void;
}

export function ShortlistPanel({ shortlist, nearMisses, budget, onSelect }: Props) {
  if (shortlist.length === 0 && nearMisses.length === 0) {
    return (
      <Card>
        <p className="font-mono text-sm text-dim">
          No agents match this category yet. Adjust the category or budget and search again.
        </p>
      </Card>
    );
  }
  return (
    <div className="space-y-6">
      {shortlist.length > 0 && (
        <section className="space-y-3">
          <p className="eyebrow">In budget</p>
          {shortlist.map((o) => (
            <AgentCard key={o.agentVersionId} option={o} onSelect={onSelect} />
          ))}
        </section>
      )}
      {nearMisses.length > 0 && (
        <section className="space-y-3">
          <p className="eyebrow">Above your budget</p>
          <p className="font-mono text-xs text-dim">
            These cost more than your {budget} cr budget — selecting one pays its price.
          </p>
          {nearMisses.map((o) => (
            <AgentCard key={o.agentVersionId} option={o} aboveBudget onSelect={onSelect} />
          ))}
        </section>
      )}
    </div>
  );
}

function AgentCard({
  option,
  aboveBudget,
  onSelect,
}: {
  option: AgentOptionDTO;
  aboveBudget?: boolean;
  onSelect: (o: AgentOptionDTO) => void;
}) {
  return (
    <Card>
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="font-semibold">{option.agentName}</p>
          {option.tagline && <p className="text-sm text-muted">{option.tagline}</p>}
          <p className="mt-1 font-mono text-xs text-dim">
            <span className="tabular text-accent">{option.price} cr</span>
            {" · ★ "}
            <span className="tabular">{option.reputationScore}</span>
            {" · "}
            {option.availability === "AVAILABLE" ? "available" : "busy"}
            {option.outputFormat && <> · {option.outputFormat}</>}
          </p>
        </div>
        <Button onClick={() => onSelect(option)}>
          {aboveBudget ? "Select (above budget)" : "Select"}
        </Button>
      </div>
    </Card>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run components/ShortlistPanel.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/types.ts frontend/components/ShortlistPanel.tsx frontend/components/ShortlistPanel.test.tsx
git commit -m "feat(frontend): ShortlistPanel + match-preview types"
```

---

### Task 6: Rework the task-submit page into form → find → pick → book

**Files:**
- Modify: `frontend/app/client/tasks/new/page.tsx`
- Test: `frontend/app/client/tasks/new/page.test.tsx`

**Interfaces:**
- Consumes: `api`, `ApiError` (`@/lib/api`); `ShortlistPanel` (Task 5); `AgentOptionDTO`, `MatchPreviewDTO`, `DirectBookRequest`, `TaskDTO` (`@/lib/types`); `Button`, `Card`, `Field`, `Input` (`@/components/ui`); `RoleGuard`, `AppShell`.
- Produces: the reworked page — `GET /tasks/match-preview` on "Find agents", `POST /tasks/direct` with `budget := selected.price` on confirm, and a `localStorage` draft under key `hireai.taskDraft`.

- [ ] **Step 1: Write the failing test**

`frontend/app/client/tasks/new/page.test.tsx`:
```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http } from "msw";
import { server, ok } from "../../../../test/msw/handlers";
import { AuthProvider } from "@/lib/auth";
import SubmitTaskPage from "@/app/client/tasks/new/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

const previewBody = {
  shortlist: [{
    agentId: "a-1", agentVersionId: "v-1", agentName: "Alpha", tagline: null, logoUrl: null,
    price: 12, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
    capabilityCategories: ["summarisation"],
  }],
  nearMisses: [{
    agentId: "a-2", agentVersionId: "v-2", agentName: "Pricey", tagline: null, logoUrl: null,
    price: 40, reputationScore: 90, availability: "BUSY", outputFormat: "JSON",
    capabilityCategories: ["summarisation"],
  }],
};

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(<AuthProvider><SubmitTaskPage /></AuthProvider>);
}

function fillForm(budget: string) {
  fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise" } });
  fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
  fireEvent.change(screen.getByLabelText(/category/i), { target: { value: "summarisation" } });
  fireEvent.change(screen.getByLabelText(/budget/i), { target: { value: budget } });
}

describe("submit task — shortlist flow", () => {
  it("finds agents then books an in-budget pick at the agent's price", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-9", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillForm("30");
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByText("Alpha");
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.agentId).toBe("a-1");
    expect(captured!.budget).toBe(12); // pays the agent's price, not the typed budget
  });

  it("books a near-miss at its higher price", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-10", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillForm("20");
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByText("Pricey");
    fireEvent.click(screen.getByRole("button", { name: /above budget/i }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.agentId).toBe("a-2");
    expect(captured!.budget).toBe(40);
  });

  it("persists the form draft to localStorage", async () => {
    renderPage();
    fillForm("25");
    await waitFor(() =>
      expect(localStorage.getItem("hireai.taskDraft")).toContain("Summarise"),
    );
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run app/client/tasks/new/page.test.tsx`
Expected: FAIL — the page still posts to `/tasks` and has no shortlist/confirm UI (no "Find agents" button, no "Confirm booking").

- [ ] **Step 3: Replace `frontend/app/client/tasks/new/page.tsx`**

```tsx
"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO, DirectBookRequest, MatchPreviewDTO, TaskDTO } from "@/lib/types";
import { Button, Card, Field, Input } from "@/components/ui";

const DRAFT_KEY = "hireai.taskDraft";

interface Draft {
  title: string;
  description: string;
  category: string;
  budget: number;
}

function SubmitTask() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");
  const [budget, setBudget] = useState(30);
  const [preview, setPreview] = useState<MatchPreviewDTO | null>(null);
  const [selected, setSelected] = useState<AgentOptionDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Restore the draft once on mount so a reload / re-search never loses the client's work.
  useEffect(() => {
    const raw = typeof localStorage !== "undefined" ? localStorage.getItem(DRAFT_KEY) : null;
    if (!raw) return;
    try {
      const d = JSON.parse(raw) as Draft;
      setTitle(d.title ?? "");
      setDescription(d.description ?? "");
      setCategory(d.category ?? "");
      setBudget(typeof d.budget === "number" ? d.budget : 30);
    } catch {
      /* ignore a malformed draft */
    }
  }, []);

  // Persist the draft whenever a field changes.
  useEffect(() => {
    if (typeof localStorage === "undefined") return;
    const draft: Draft = { title, description, category, budget };
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  }, [title, description, category, budget]);

  async function onFind(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSelected(null);
    setLoading(true);
    try {
      const result = await api<MatchPreviewDTO>(
        `/tasks/match-preview?category=${encodeURIComponent(category)}&budget=${budget}`,
      );
      setPreview(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Search failed");
    } finally {
      setLoading(false);
    }
  }

  async function onBook() {
    if (!selected) return;
    setError(null);
    setLoading(true);
    const body: DirectBookRequest = {
      title,
      description,
      budget: selected.price, // pay the chosen agent's price
      agentId: selected.agentId,
    };
    try {
      const created = await api<TaskDTO>("/tasks/direct", {
        method: "POST",
        body: JSON.stringify(body),
      });
      if (typeof localStorage !== "undefined") localStorage.removeItem(DRAFT_KEY);
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Booking failed");
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <Link href="/client" className="font-mono text-xs text-dim transition hover:text-accent">
          ← console
        </Link>
        <p className="eyebrow mt-4 flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          New task
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Submit task</h1>
        <p className="mt-2 text-sm text-muted">
          Describe the task and find matching agents. Nothing is charged until you pick one — then
          that agent&apos;s price is frozen in escrow.
        </p>
      </div>

      <Card>
        <form onSubmit={onFind} className="space-y-4">
          <Field label="Title" htmlFor="title">
            <Input id="title" value={title} onChange={(e) => setTitle(e.target.value)} required />
          </Field>
          <Field label="Description" htmlFor="description">
            <textarea
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
              className="block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25"
              required
            />
          </Field>
          <Field label="Category" htmlFor="category">
            <Input
              id="category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder="must match an active agent's category"
              required
            />
          </Field>
          <Field label="Budget (credits)" htmlFor="budget">
            <Input
              id="budget"
              type="number"
              min={0}
              value={budget}
              onChange={(e) => setBudget(Number(e.target.value))}
              required
            />
          </Field>
          <Button type="submit" disabled={loading} className="w-full">
            {loading ? "Searching…" : "Find agents ▸"}
          </Button>
        </form>
      </Card>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
        >
          {error}
        </p>
      )}

      {preview && !selected && (
        <ShortlistPanel
          shortlist={preview.shortlist}
          nearMisses={preview.nearMisses}
          budget={budget}
          onSelect={setSelected}
        />
      )}

      {selected && (
        <Card>
          <p className="eyebrow mb-2">Confirm booking</p>
          <p className="text-sm text-muted">
            You&apos;ll pay{" "}
            <span className="text-accent">{selected.price} cr</span> to {selected.agentName},
            frozen in escrow.
            {selected.price > budget && (
              <> This is above your {budget} cr budget.</>
            )}
          </p>
          <div className="mt-4 flex items-center gap-4">
            <Button onClick={onBook} disabled={loading}>
              {loading ? "Booking…" : "Confirm & book ▸"}
            </Button>
            <button
              type="button"
              onClick={() => setSelected(null)}
              className="font-mono text-xs text-dim transition hover:text-accent"
            >
              ← back
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <SubmitTask />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run app/client/tasks/new/page.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the whole frontend suite + build to confirm no regressions**

Run: `cd frontend && npx vitest run && npm run build`
Expected: all tests pass; build succeeds. (The old `EMPTY_OUTPUT_SPEC`/`OutputSpecFields` import is gone from this page; if any lint rule flags the now-unused `CreateTaskRequest`/`OutputSpecDTO` types elsewhere, they remain used by other files — do not delete them.)

- [ ] **Step 6: Commit**

```bash
git add frontend/app/client/tasks/new/page.tsx frontend/app/client/tasks/new/page.test.tsx
git commit -m "feat(frontend): submit task via match preview -> pick -> book at the agent's price"
```

---

### Task 7: Live end-to-end verification with Playwright

Verify the real flow against a running stack (per `docs/details/demo-runbook.md`: Postgres/Supabase + RabbitMQ + a stub agent + the backend + `npm --prefix frontend run dev`). This is an **interactive Playwright-MCP verification** (as Phase 1 was) — no committed spec file.

**Files:** none committed (screenshots saved to the repo root as evidence).

- [ ] **Step 1: Bring up the stack**

Start the backend (with `.env` → Supabase, cwd `backend/`), a RabbitMQ broker, the stub agent + HTTPS tunnel, and the frontend dev server. Confirm the seeded client login works. (See `docs/details/demo-runbook.md`.)

- [ ] **Step 2: Shortlist happy path (in-budget)**

Log in as the seeded client → go to `/client/tasks/new` → fill title/description, category matching a seeded agent, a budget above that agent's price → click **Find agents**. Assert the shortlist renders the expected agent(s). Screenshot → `e2e-shortlist-in-budget.png`.

- [ ] **Step 3: Book the in-budget pick**

Click **Select** on an agent → assert the confirm card shows that agent's price → **Confirm & book** → assert redirect to `/client/tasks/{id}` and that the escrowed amount equals the **agent's price** (not the typed budget). Screenshot → `e2e-shortlist-booked.png`.

- [ ] **Step 4: Near-miss path**

Return to `/client/tasks/new` (the draft should be cleared after the previous booking; re-fill) → set a budget **below** every matching agent's price → **Find agents** → assert the "Above your budget" zone shows up to 3 price-ascending options and the in-budget zone is empty → **Select (above budget)** → confirm the above-budget notice → book → assert success at the near-miss price. Screenshot → `e2e-shortlist-near-miss.png`.

- [ ] **Step 5: Drive to completion**

Let the stub agent return a result → poll the task-detail page to `PENDING_REVIEW` → **Accept** → assert the settled summary. Screenshot → `e2e-shortlist-accepted.png`.

- [ ] **Step 6: Record the outcome**

Note pass/fail for each step in the task handoff. No commit (screenshots are untracked evidence unless you choose to add them).

---

## Self-Review

**1. Spec coverage:**
- §4 flow (form → preview → pick → book) → Tasks 4, 6. ✅
- §5 pay-the-price (`budget := agent.price`) → Task 6 Step 3 (`budget: selected.price`) + Task 6 tests. ✅
- §6.1 query (ACTIVE+listed, no price filter, counts, output format) → Task 2. ✅
- §6.2 read service (rank top-5, near-miss price-asc top-3, availability, normalization) → Task 3. ✅
- §6.3 endpoint (GET, param validation) → Task 4. ✅
- §6.4 DTOs (availability label, reuse DirectBookRequest) → Task 4 + Task 6. ✅
- §6.5 `MatchCriteria` seam → Task 1. ✅
- §7 frontend (form minus output-spec, 3 zones, confirm, localStorage draft) → Tasks 5, 6. ✅
- §8 error handling (empty lists, invalid params, insufficient balance surfaces as ApiError) → Task 4 (400s) + Task 6 (ApiError banner) + Task 5 (empty state). ✅
- §9 invariants (no state/sweeper/migration/money change) → honored across all tasks. ✅
- §10 tests incl. Playwright E2E → Tasks 1–6 unit/integration + Task 7. ✅

**2. Placeholder scan:** No "TBD"/"handle edge cases"/"similar to"; every code step shows full code. ✅

**3. Type consistency:** `AgentOption`/`ShortlistCandidateRow`/`AgentOptionDTO` field orders match their constructors across Tasks 2/3/4; `rank(MatchCriteria, …)` used consistently (Tasks 1, 3); `DirectBookRequest { title, description, budget, agentId }` matches the existing type (Task 6); `MatchPreviewDTO`/`AgentOptionDTO` TS shapes mirror the Java DTOs (Tasks 4, 5). ✅
