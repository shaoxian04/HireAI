package com.hireai.admin;

import com.hireai.application.biz.admin.AdminQueryPort;
import com.hireai.application.biz.admin.view.AdminViews;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the admin read DAO. Boots Spring against a real Postgres (Testcontainers)
 * so Flyway applies V1–V23. Each test seeds its own rows via JdbcTemplate. Skips (not fails) without
 * a Docker daemon.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class JdbcAdminQueryDaoIntegrationTest {

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

    @Autowired AdminQueryPort dao;
    @Autowired JdbcTemplate jdbc;

    private static String uniqueEmail(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@test.local";
    }

    /** Seeds a CLIENT + a DISPUTED task (with a result row) + an ESCALATED dispute. Returns the task id. */
    private UUID seedEscalatedDisputeWithResult(BigDecimal budget, BigDecimal escrow) {
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", clientId, uniqueEmail("adminc"));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", clientId);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, ?)",
                UUID.randomUUID(), clientId, escrow);

        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status)
                VALUES (?, ?, 'Admin evidence task', 'Summarise the attached report.', ?, '{"format":"TEXT"}'::jsonb, 'DISPUTED')
                """, taskId, clientId, budget);
        jdbc.update("""
                INSERT INTO task_results (id, task_id, result_payload, agent_status)
                VALUES (?, ?, '{"summary":"done"}'::jsonb, 'COMPLETED')
                """, UUID.randomUUID(), taskId);

        UUID disputeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO disputes (id, task_id, raised_by, reason_category, status, correlation_id)
                VALUES (?, ?, ?, 'A_MISMATCH', 'ESCALATED', ?)
                """, disputeId, taskId, clientId, "dispute-" + taskId);
        return taskId;
    }

    @Test
    void overviewCountsEscalatedDisputesEscrowAndCommission() {
        seedEscalatedDisputeWithResult(new BigDecimal("20.00"), new BigDecimal("20.00"));
        jdbc.update("INSERT INTO settlements (id, task_id, type, net, commission) VALUES (?, ?, 'SPLIT', 8.50, 1.50)",
                UUID.randomUUID(), UUID.randomUUID());

        AdminViews.Overview o = dao.overview();

        assertThat(o.disputesEscalated()).isGreaterThanOrEqualTo(1);
        assertThat(o.escrowHeld()).isGreaterThanOrEqualTo(new BigDecimal("20.00"));
        assertThat(o.commissionEarned()).isGreaterThanOrEqualTo(new BigDecimal("1.50"));
        assertThat(o.usersTotal()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void needsAttentionQueueReturnsEscalatedOnly() {
        seedEscalatedDisputeWithResult(new BigDecimal("20.00"), new BigDecimal("20.00"));

        List<AdminViews.DisputeRow> rows = dao.disputeQueue(true);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allMatch(r -> r.status().equals("OPEN") || r.status().equals("ESCALATED"));
        assertThat(rows).anyMatch(AdminViews.DisputeRow::needsAttention);
    }

    @Test
    void evidenceReturnsTaskDescriptionAndResult() {
        UUID taskId = seedEscalatedDisputeWithResult(new BigDecimal("20.00"), new BigDecimal("20.00"));

        Optional<AdminViews.Evidence> ev = dao.disputeEvidence(taskId);

        assertThat(ev).isPresent();
        assertThat(ev.get().taskDescription()).isEqualTo("Summarise the attached report.");
        assertThat(ev.get().resultPayloadJson()).contains("summary");
        assertThat(ev.get().agentStatus()).isEqualTo("COMPLETED");
    }
}
