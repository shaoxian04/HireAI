package com.hireai.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V17,
 * creating the disputes table. Verifies that the DisputeModel aggregate round-trips
 * through the repository (save → findByTaskId), including a full lifecycle from OPEN
 * through RULED to RESOLVED.
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class DisputeRepositoryIntegrationTest {

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
    DisputeRepository disputeRepository;

    @Test
    void persistsAndRehydratesRuledDispute() {
        UUID taskId = UUID.randomUUID();
        DisputeModel open = DisputeModel.open(taskId, UUID.randomUUID(), RejectReason.B_FACTUAL, "corr-x");
        disputeRepository.save(open);

        Ruling ruling = new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half",
                RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T00:00:00Z"));
        DisputeModel ruled = open.recordRuling(ruling).resolve();
        disputeRepository.save(ruled);

        DisputeModel found = disputeRepository.findByTaskId(taskId).orElseThrow();
        assertThat(found.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(found.reasonCategory()).isEqualTo(RejectReason.B_FACTUAL);
        assertThat(found.effectiveRuling().get().category()).isEqualTo(RulingCategory.PARTIALLY_FULFILLED);
        assertThat(found.effectiveRuling().get().rationale()).isEqualTo("half");
        assertThat(found.resolvedAt()).isNotNull();
    }

    @Test
    void rulingHistoryRoundTrip() {
        UUID taskId = UUID.randomUUID();
        DisputeModel open = DisputeModel.open(taskId, UUID.randomUUID(), RejectReason.A_MISMATCH, "corr-hist");
        disputeRepository.save(open);

        Ruling tier1 = new Ruling(1, RulingCategory.NOT_FULFILLED, "spec not met",
                RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T00:00:00Z"));
        DisputeModel resolved = open.recordRuling(tier1).resolve();
        disputeRepository.save(resolved);

        DisputeModel found = disputeRepository.findByTaskId(taskId).orElseThrow();
        assertThat(found.rulings()).hasSize(1);
        assertThat(found.effectiveRuling()).isPresent();
        assertThat(found.effectiveRuling().get().category()).isEqualTo(RulingCategory.NOT_FULFILLED);
        assertThat(found.effectiveRuling().get().tier()).isEqualTo(1);
        assertThat(found.effectiveRuling().get().decidedBy()).isEqualTo(RulingDecidedBy.ARBITRATOR);
        assertThat(found.effectiveRuling().get().rationale()).isEqualTo("spec not met");
        assertThat(found.status()).isEqualTo(DisputeStatus.RESOLVED);
    }
}
