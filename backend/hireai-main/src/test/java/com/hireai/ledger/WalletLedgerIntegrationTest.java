package com.hireai.ledger;

import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletLedgerQuery;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1,
 * including the append-only triggers. Verifies the Wallet slice end-to-end:
 * persistence, the escrow invariant, ledger reconstruction, and that the ledger
 * is genuinely append-only at the DB layer.
 *
 * Each test creates its own user so the shared container carries no cross-test
 * state (no reliance on test ordering).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class WalletLedgerIntegrationTest {

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

    @Autowired WalletWriteAppService writeAppService;
    @Autowired WalletReadAppService readAppService;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)",
                id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    @Test
    void topUpThenFreezePersistsBalancesAndLedger() {
        UUID user = newUser();
        writeAppService.topUp(user, Money.of("100.00"), "corr-topup");
        writeAppService.freeze(user, Money.of("30.00"), UUID.randomUUID(), "corr-freeze");

        WalletModel wallet = readAppService.getByUserId(user);
        assertThat(wallet.available()).isEqualTo(Money.of("70.00"));
        assertThat(wallet.escrow()).isEqualTo(Money.of("30.00"));

        List<LedgerEntryModel> ledger = readAppService.getLedger(user, WalletLedgerQuery.firstPage());
        assertThat(ledger).hasSize(2);
    }

    @Test
    void availableBalanceReconstructsFromLedger() {
        UUID user = newUser();
        writeAppService.topUp(user, Money.of("250.00"), "c1");
        writeAppService.freeze(user, Money.of("60.00"), UUID.randomUUID(), "c2");

        WalletModel wallet = readAppService.getByUserId(user);
        // The latest entry's balance_after must equal the stored available balance.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE wallet_id = ? " +
                        "AND balance_after = (SELECT available_balance FROM wallets WHERE id = ?) " +
                        "AND created_at = (SELECT max(created_at) FROM ledger_entries WHERE wallet_id = ?)",
                Integer.class, wallet.id(), wallet.id(), wallet.id());
        assertThat(rows).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ledgerIsAppendOnly() {
        UUID user = newUser();
        writeAppService.topUp(user, Money.of("10.00"), "c1");
        WalletModel wallet = readAppService.getByUserId(user);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount = amount + 1 WHERE wallet_id = ?", wallet.id()))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE wallet_id = ?", wallet.id()))
                .hasMessageContaining("append-only");
    }
}
