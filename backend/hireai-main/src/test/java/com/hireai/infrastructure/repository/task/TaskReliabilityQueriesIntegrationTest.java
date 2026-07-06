package com.hireai.infrastructure.repository.task;

import com.hireai.domain.biz.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the five Task reliability queries added in Task 8
 * (spec: docs/superpowers/specs/2026-07-04-matching-engine-design.md). match_attempts,
 * execution_deadline and pinned_agent_version_id are deliberately UNMAPPED on TaskDO (see
 * V24), so every port method here runs as native SQL. These are pure repository reads/writes
 * with no dispatch/messaging involved, so this follows the lighter Testcontainers-Postgres-only
 * scaffold already used for other repository-focused ITs (mirrors
 * {@code CandidateCountsIntegrationTest}'s annotations/dockerAvailable/DynamicPropertySource
 * shape and JDBC direct-seeding style) rather than {@code RoutingIntegrationTest}'s full
 * RabbitMQ setup.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class TaskReliabilityQueriesIntegrationTest {

    /** Skip (do not fail) the whole class when no Docker daemon is reachable. */
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

    @Autowired TaskRepository taskRepository;
    @Autowired JdbcTemplate jdbc;

    /**
     * The Testcontainers Postgres is shared across this class's test methods (static
     * @Container), so seeded tasks persist between tests unless cleared. Reset before each
     * test so statuses/deadlines/counters never leak across cases (same rationale as
     * CandidateCountsIntegrationTest's clearRoutableData). Users/user_roles are deliberately
     * left alone — a seeded demo user carries a wallet FK, and per-test client rows use fresh
     * random UUIDs/emails so they never collide across tests.
     */
    @BeforeEach
    void clearTasks() {
        jdbc.update("DELETE FROM tasks");
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    private UUID insertTask(UUID clientId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status) " +
                        "VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, ?)",
                id, clientId, "{\"format\":\"JSON\"}", status);
        return id;
    }

    private void stampDeadline(UUID taskId, Instant deadline) {
        jdbc.update("UPDATE tasks SET execution_deadline = ? WHERE id = ?",
                java.sql.Timestamp.from(deadline), taskId);
    }

    @Test
    @Transactional
    void findIdsAwaitingCapacityReturnsExactlyTheAwaitingCapacityTaskIds() {
        UUID client = newClient();
        UUID awaiting1 = insertTask(client, "AWAITING_CAPACITY");
        UUID awaiting2 = insertTask(client, "AWAITING_CAPACITY");
        insertTask(client, "QUEUED");
        insertTask(client, "RESOLVED");

        List<UUID> ids = taskRepository.findIdsAwaitingCapacity();

        assertThat(ids).containsExactlyInAnyOrder(awaiting1, awaiting2);
    }

    @Test
    @Transactional
    void findIdsExecutionExpiredAppliesAllFourExclusionRules() {
        UUID client = newClient();
        Instant now = Instant.now();

        UUID expiredQueued = insertTask(client, "QUEUED");
        stampDeadline(expiredQueued, now.minus(1, ChronoUnit.HOURS));

        UUID expiredExecuting = insertTask(client, "EXECUTING");
        stampDeadline(expiredExecuting, now.minus(1, ChronoUnit.HOURS));

        UUID futureExecuting = insertTask(client, "EXECUTING");
        stampDeadline(futureExecuting, now.plus(1, ChronoUnit.HOURS));

        UUID expiredPendingReview = insertTask(client, "PENDING_REVIEW");
        stampDeadline(expiredPendingReview, now.minus(1, ChronoUnit.HOURS));

        UUID nullDeadlineQueued = insertTask(client, "QUEUED");
        // execution_deadline left NULL deliberately.

        List<UUID> ids = taskRepository.findIdsExecutionExpired(now);

        assertThat(ids).containsExactlyInAnyOrder(expiredQueued, expiredExecuting);
        assertThat(ids).doesNotContain(futureExecuting, expiredPendingReview, nullDeadlineQueued);
    }

    @Test
    @Transactional
    void incrementMatchAttemptsTwiceYieldsTwoStartingFromZero() {
        UUID client = newClient();
        UUID taskId = insertTask(client, "AWAITING_CAPACITY");

        assertThat(taskRepository.matchAttempts(taskId)).isZero();

        taskRepository.incrementMatchAttempts(taskId);
        taskRepository.incrementMatchAttempts(taskId);

        assertThat(taskRepository.matchAttempts(taskId)).isEqualTo(2);
    }

    @Test
    @Transactional
    void findPinnedAgentVersionIdEmptyForOpenTaskThenPresentAfterPin() {
        UUID client = newClient();
        UUID taskId = insertTask(client, "SUBMITTED");
        UUID versionId = UUID.randomUUID();

        assertThat(taskRepository.findPinnedAgentVersionId(taskId)).isEmpty();

        taskRepository.pinAgentVersion(taskId, versionId);

        assertThat(taskRepository.findPinnedAgentVersionId(taskId)).contains(versionId);
    }
}
