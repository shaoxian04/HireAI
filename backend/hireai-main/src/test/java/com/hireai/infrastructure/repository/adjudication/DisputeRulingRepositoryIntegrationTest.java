package com.hireai.infrastructure.repository.adjudication;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V21,
 * creating the dispute_rulings table and its append-only triggers. Verifies that:
 * (a) a ruling row inserts and reads back correctly via {@link DisputeRulingJpaRepository};
 * (b) an UPDATE on that row is rejected by the append-only trigger;
 * (c) a DELETE on that row is rejected by the append-only trigger.
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class DisputeRulingRepositoryIntegrationTest {

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
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    DisputeRulingJpaRepository rulingRepository;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Insert a minimal dispute row. The disputes table has no FK on task_id or raised_by
     * (soft references), so no upstream fixture is needed.
     */
    private UUID insertDispute() {
        UUID disputeId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO disputes (id, task_id, raised_by, reason_category, status, correlation_id) " +
                "VALUES (?, ?, ?, 'A_MISMATCH', 'OPEN', 'corr-test')",
                disputeId, UUID.randomUUID(), UUID.randomUUID());
        return disputeId;
    }

    @Test
    void insertsAndReadsBack() {
        UUID disputeId = insertDispute();
        UUID rulingId = UUID.randomUUID();
        Instant now = Instant.now();

        rulingRepository.save(new DisputeRulingDO(
                rulingId, disputeId, 1, "ARBITRATOR", "FULFILLED", "all good", now, now));

        List<DisputeRulingDO> found = rulingRepository.findByDisputeIdOrderByGmtCreateAsc(disputeId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(rulingId);
        assertThat(found.get(0).getCategory()).isEqualTo("FULFILLED");
        assertThat(found.get(0).getDecidedBy()).isEqualTo("ARBITRATOR");
        assertThat(found.get(0).getRationale()).isEqualTo("all good");
        assertThat(found.get(0).getTier()).isEqualTo(1);
        assertThat(rulingRepository.countByDisputeId(disputeId)).isEqualTo(1L);
    }

    @Test
    void updateIsBlockedByTrigger() {
        UUID disputeId = insertDispute();
        UUID rulingId = UUID.randomUUID();
        Instant now = Instant.now();
        rulingRepository.save(new DisputeRulingDO(
                rulingId, disputeId, 1, "FALLBACK", "NOT_FULFILLED", "none", now, now));

        assertThatThrownBy(() ->
                jdbc.update("UPDATE dispute_rulings SET rationale = 'x' WHERE id = ?", rulingId))
                .hasMessageContaining("append-only");
    }

    @Test
    void deleteIsBlockedByTrigger() {
        UUID disputeId = insertDispute();
        UUID rulingId = UUID.randomUUID();
        Instant now = Instant.now();
        rulingRepository.save(new DisputeRulingDO(
                rulingId, disputeId, 1, "ARBITRATOR", "PARTIALLY_FULFILLED", "partial", now, now));

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM dispute_rulings WHERE id = ?", rulingId))
                .hasMessageContaining("append-only");
    }
}
