package com.hireai.ledger;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settlements touching the same wallet at the same moment (a multi-Item Task's Items, several Tasks of one
 * client, a sweeper beside a click) serialize on the wallet row instead of failing on its optimistic version
 * check: none is lost, none is a 500.
 */
class ConcurrentSettlementIntegrationTest extends WholeAppIntegrationTest {

    private static final int SETTLEMENTS = 12;

    @Autowired SettlementWriteAppService settlementWriteAppService;

    @Test
    void concurrentSettlementsOfOneClientWalletAllApply() throws Exception {
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", clientId, clientId + "@test.local");
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, ?)",
                UUID.randomUUID(), clientId, new BigDecimal(SETTLEMENTS));

        ExecutorService pool = Executors.newFixedThreadPool(SETTLEMENTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < SETTLEMENTS; i++) {
            UUID taskId = UUID.randomUUID();
            done.add(pool.submit(() -> {
                start.await();
                settlementWriteAppService.refundAiReviewFee(taskId, clientId, Money.of("1.00"));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(30, TimeUnit.SECONDS); // rethrows an optimistic-lock failure
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?", BigDecimal.class,
                clientId)).isEqualByComparingTo(new BigDecimal(SETTLEMENTS));
        assertThat(jdbc.queryForObject("SELECT escrow_balance FROM wallets WHERE user_id = ?", BigDecimal.class,
                clientId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries l JOIN wallets w ON w.id = l.wallet_id "
                + "WHERE w.user_id = ?", Integer.class, clientId)).isEqualTo(SETTLEMENTS);
    }
}
