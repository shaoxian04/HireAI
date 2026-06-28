package com.hireai.ledger;

import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.shared.model.Money;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code wallets.version} column increments after a topUp+save,
 * confirming that Hibernate's {@code @Version} optimistic-lock is wired end-to-end
 * through {@code WalletRepositoryImpl}'s load-then-mutate {@code save} path.
 *
 * Skips automatically (does NOT fail) when no Docker daemon is reachable;
 * correctness of the {@code @Version}/V13/save change is validated in CI.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class WalletVersionIntegrationTest {

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

    @Autowired WalletRepository walletRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email) VALUES (?, ?)",
                id, id + "@test.local");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    @Test
    void walletVersionIncrementsOnUpdate() {
        UUID userId = newUser();
        walletRepository.save(WalletModel.openFor(userId));

        WalletModel w1 = walletRepository.findByUserId(userId).orElseThrow();
        long v0 = jdbcTemplate.queryForObject(
                "SELECT version FROM wallets WHERE id = ?", Long.class, w1.id());

        w1.topUp(Money.of(new java.math.BigDecimal("10.00")), "corr-1");
        walletRepository.save(w1);

        long v1 = jdbcTemplate.queryForObject(
                "SELECT version FROM wallets WHERE id = ?", Long.class, w1.id());
        assertThat(v1).isGreaterThan(v0);
    }
}
