package com.hireai.offering;

import com.hireai.application.port.query.BuilderStatsQueryPort;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for JdbcBuilderStatsQueryDao. Boots Spring against a real Postgres
 * (Testcontainers) so Flyway applies all migrations. Each test seeds its own isolated data
 * via JdbcTemplate; agent/version/task rows are fully self-contained.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class BuilderStatsQueryDaoIntegrationTest {

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

    @Autowired BuilderStatsQueryPort builderStatsQueryPort;
    @Autowired JdbcTemplate jdbc;

    // -----------------------------------------------------------------------
    // Seed helpers
    // -----------------------------------------------------------------------

    /** Seeds builder user → agent → version (max_execution_seconds 60). Returns agentId. */
    private SeedResult seedAgentWithVersion() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)",
                ownerId, uniqueEmail("builder"));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);

        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, 50.00)",
                agentId, ownerId, "StatsAgent-" + agentId.toString().substring(0, 8), versionId);

        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, " +
                        "webhook_url, max_execution_seconds, price) " +
                        "VALUES (?, ?, 1, ?::jsonb, ARRAY['general'], ?, 60, 10.00)",
                versionId, agentId, "{\"format\":\"JSON\"}",
                "https://agent.example.com/callback");

        return new SeedResult(agentId, versionId);
    }

    record SeedResult(UUID agentId, UUID versionId) {}

    /** Seeds a CLIENT user and returns their id. */
    private UUID seedClient() {
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)",
                clientId, uniqueEmail("client"));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", clientId);
        return clientId;
    }

    /** Inserts a task row. createdAt null → uses now(). */
    private UUID seedTask(UUID clientId, UUID versionId, String status, BigDecimal budget,
                          Instant createdAt) {
        UUID taskId = UUID.randomUUID();
        if (createdAt == null) {
            jdbc.update("""
                    INSERT INTO tasks (id, client_id, title, description, budget, output_spec,
                                       status, agent_version_id, category)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'general')
                    """,
                    taskId, clientId, "Task " + taskId.toString().substring(0, 8),
                    "Description", budget, "{\"format\":\"JSON\"}", status, versionId);
        } else {
            jdbc.update("""
                    INSERT INTO tasks (id, client_id, title, description, budget, output_spec,
                                       status, agent_version_id, category, gmt_create, gmt_modified)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'general', ?, ?)
                    """,
                    taskId, clientId, "Task " + taskId.toString().substring(0, 8),
                    "Description", budget, "{\"format\":\"JSON\"}", status, versionId,
                    Timestamp.from(createdAt), Timestamp.from(createdAt));
        }
        return taskId;
    }

    /** Inserts a task_result row for the given task; received_at = task_created + turnaroundSeconds. */
    private void seedTaskResult(UUID taskId, Instant taskCreatedAt, long turnaroundSeconds) {
        Instant receivedAt = taskCreatedAt.plusSeconds(turnaroundSeconds);
        jdbc.update("""
                INSERT INTO task_results (id, task_id, result_payload, agent_status, received_at,
                                          gmt_create, gmt_modified)
                VALUES (?, ?, ?::jsonb, 'SUCCESS', ?, ?, ?)
                """,
                UUID.randomUUID(), taskId, "{\"result\":\"ok\"}",
                Timestamp.from(receivedAt), Timestamp.from(receivedAt), Timestamp.from(receivedAt));
    }

    // -----------------------------------------------------------------------
    // Test 1: Happy path — seeded tasks produce correct aggregates
    // -----------------------------------------------------------------------

    @Test
    void stats_happyPath_correctAggregates() {
        SeedResult seed = seedAgentWithVersion();
        UUID clientId = seedClient();
        Instant base = Instant.now().minusSeconds(3600);

        // Task A: RESULT_RECEIVED, budget 10, NO task_results row
        UUID taskA = seedTask(clientId, seed.versionId(), "RESULT_RECEIVED",
                new BigDecimal("10.00"), base.minusSeconds(60));

        // Task B: RESULT_RECEIVED, budget 30, HAS task_results row (30 s turnaround ≤ 60 s limit → on-time)
        Instant taskBCreated = base.minusSeconds(30);
        UUID taskB = seedTask(clientId, seed.versionId(), "RESULT_RECEIVED",
                new BigDecimal("30.00"), taskBCreated);
        seedTaskResult(taskB, taskBCreated, 30);

        // Task C: FAILED, budget 20
        seedTask(clientId, seed.versionId(), "FAILED", new BigDecimal("20.00"), null);

        // Task D: EXECUTING, budget 40
        seedTask(clientId, seed.versionId(), "EXECUTING", new BigDecimal("40.00"), null);

        BuilderStatsQueryPort.StatsRow row = builderStatsQueryPort.stats(seed.agentId());

        assertThat(row.total()).isEqualTo(4);
        assertThat(row.completed()).isEqualTo(2);    // A + B
        assertThat(row.failed()).isEqualTo(1);       // C
        assertThat(row.open()).isEqualTo(1);         // D
        // Escrow = A(10) + B(30) + D(40) = 80; C(FAILED) excluded
        assertThat(row.creditsInEscrow()).isEqualByComparingTo(new BigDecimal("80.00"));
        // Potential = A(10) + B(30) = 40
        assertThat(row.potentialEarnings()).isEqualByComparingTo(new BigDecimal("40.00"));
        // withResult = 1 (only B has a task_results row)
        assertThat(row.withResultCount()).isEqualTo(1);
        // onTime = 1 (B: 30 s ≤ 60 s limit)
        assertThat(row.onTimeCount()).isEqualTo(1);
        // avgTurnaround ≈ 30 s (only B contributes)
        assertThat(row.avgTurnaroundSeconds()).isNotNull();
        assertThat(row.avgTurnaroundSeconds()).isCloseTo(30.0, within(2.0));
    }

    @Test
    void trend_happyPath_onePointForTodayWithAllFourTasks() {
        SeedResult seed = seedAgentWithVersion();
        UUID clientId = seedClient();

        // Seed 4 tasks all created "now" (within last 14 days)
        for (int i = 0; i < 4; i++) {
            seedTask(clientId, seed.versionId(), "SUBMITTED", new BigDecimal("5.00"), null);
        }

        List<BuilderStatsQueryPort.TrendPointRow> trend =
                builderStatsQueryPort.trend(seed.agentId(), 14);

        assertThat(trend).isNotEmpty();
        // All tasks created today → exactly one trend point, count = 4
        int total = trend.stream().mapToInt(BuilderStatsQueryPort.TrendPointRow::count).sum();
        assertThat(total).isGreaterThanOrEqualTo(4);
    }

    @Test
    void recentTasks_happyPath_returnedNewestFirst() {
        SeedResult seed = seedAgentWithVersion();
        UUID clientId = seedClient();

        Instant base = Instant.now().minusSeconds(7200);
        // Seed 4 tasks with staggered creation times, oldest first
        for (int i = 0; i < 4; i++) {
            seedTask(clientId, seed.versionId(), "SUBMITTED", new BigDecimal("5.00"),
                    base.plusSeconds(i * 60L));
        }

        List<BuilderStatsQueryPort.RecentTaskRow> recent =
                builderStatsQueryPort.recentTasks(seed.agentId(), 10);

        assertThat(recent).hasSize(4);
        // Verify descending order (newest first)
        for (int i = 0; i < recent.size() - 1; i++) {
            assertThat(recent.get(i).createdAt())
                    .isAfterOrEqualTo(recent.get(i + 1).createdAt());
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: Agent with no tasks → zeros, null turnaround, empty lists
    // -----------------------------------------------------------------------

    @Test
    void stats_noTasks_returnsAllZerosAndNullTurnaround() {
        SeedResult seed = seedAgentWithVersion();

        BuilderStatsQueryPort.StatsRow row = builderStatsQueryPort.stats(seed.agentId());

        assertThat(row.total()).isZero();
        assertThat(row.completed()).isZero();
        assertThat(row.failed()).isZero();
        assertThat(row.open()).isZero();
        assertThat(row.creditsInEscrow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.potentialEarnings()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.avgTurnaroundSeconds()).isNull();
        assertThat(row.onTimeCount()).isZero();
        assertThat(row.withResultCount()).isZero();
    }

    @Test
    void trend_noTasks_returnsEmptyList() {
        SeedResult seed = seedAgentWithVersion();
        List<BuilderStatsQueryPort.TrendPointRow> trend =
                builderStatsQueryPort.trend(seed.agentId(), 14);
        assertThat(trend).isEmpty();
    }

    @Test
    void recentTasks_noTasks_returnsEmptyList() {
        SeedResult seed = seedAgentWithVersion();
        List<BuilderStatsQueryPort.RecentTaskRow> recent =
                builderStatsQueryPort.recentTasks(seed.agentId(), 10);
        assertThat(recent).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String uniqueEmail(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@stats.local";
    }
}
