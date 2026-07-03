package com.hireai.infrastructure.repository.adjudication;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
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

/**
 * Integration test for the client-scoped dispute read DAO. Boots Spring against a real Postgres
 * (Testcontainers) so Flyway applies V1–V23. Seeds one client with two disputes — one RULED
 * (with a tier-1 arbitrator ruling in dispute_rulings) and one RESOLVED — and verifies
 * {@link JdbcDisputeQueryDao#findDisputesForClient(UUID)} returns both, RULED first, with
 * proposedCategory populated for the RULED row.
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class JdbcDisputeQueryDaoIntegrationTest {

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

    @Autowired DisputeQueryPort dao;
    @Autowired JdbcTemplate jdbc;

    private static String uniqueEmail(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@test.local";
    }

    private UUID insertClient() {
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", clientId, uniqueEmail("mydisputesc"));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", clientId);
        return clientId;
    }

    private UUID insertTask(UUID clientId, String title, String status) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, category)
                VALUES (?, ?, ?, 'A task under dispute.', ?, '{"format":"TEXT"}'::jsonb, ?, 'summarisation')
                """, taskId, clientId, title, new BigDecimal("20.00"), status);
        return taskId;
    }

    private UUID insertDispute(UUID taskId, UUID clientId, String status) {
        UUID disputeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO disputes (id, task_id, raised_by, reason_category, status, correlation_id)
                VALUES (?, ?, ?, 'A_MISMATCH', ?, ?)
                """, disputeId, taskId, clientId, status, "dispute-" + taskId);
        return disputeId;
    }

    private void insertRuling(UUID disputeId, String category) {
        jdbc.update("""
                INSERT INTO dispute_rulings (id, dispute_id, tier, decided_by, category, rationale, decided_at)
                VALUES (?, ?, 1, 'ARBITRATOR', ?, 'rationale text', now())
                """, UUID.randomUUID(), disputeId, category);
    }

    @Test
    void findDisputesForClientReturnsRuledFirstWithProposedCategory() {
        UUID clientId = insertClient();

        UUID resolvedTaskId = insertTask(clientId, "Resolved task", "RESOLVED");
        UUID resolvedDisputeId = insertDispute(resolvedTaskId, clientId, "RESOLVED");

        UUID ruledTaskId = insertTask(clientId, "Ruled task", "DISPUTED");
        UUID ruledDisputeId = insertDispute(ruledTaskId, clientId, "RULED");
        insertRuling(ruledDisputeId, "NOT_FULFILLED");

        List<DisputeMineRow> rows = dao.findDisputesForClient(clientId);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).disputeId()).isEqualTo(ruledDisputeId);
        assertThat(rows.get(0).status()).isEqualTo("RULED");
        assertThat(rows.get(0).proposedCategory()).isEqualTo("NOT_FULFILLED");
        assertThat(rows.get(0).taskTitle()).isEqualTo("Ruled task");
        assertThat(rows.get(0).updatedAt()).isNotNull();

        assertThat(rows.get(1).disputeId()).isEqualTo(resolvedDisputeId);
        assertThat(rows.get(1).status()).isEqualTo("RESOLVED");
    }
}
