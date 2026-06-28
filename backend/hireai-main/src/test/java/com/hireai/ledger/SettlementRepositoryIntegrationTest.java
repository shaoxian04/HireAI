package com.hireai.ledger;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.shared.model.Money;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V14,
 * creating the settlements table. Verifies that the Settlement aggregate round-trips
 * through the repository (save → findByTaskId).
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class SettlementRepositoryIntegrationTest {

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
    SettlementRepository settlementRepository;

    @Test
    void savesAndFindsSettlementByTaskId() {
        UUID taskId = UUID.randomUUID();
        settlementRepository.save(SettlementModel.accepted(
                taskId, Money.of("17.00"), Money.of("3.00")));

        SettlementModel found = settlementRepository.findByTaskId(taskId).orElseThrow();
        assertThat(found.type()).isEqualTo(SettlementType.ACCEPT);
        assertThat(found.net()).isEqualTo(Money.of("17.00"));
        assertThat(found.commission()).isEqualTo(Money.of("3.00"));
    }
}
