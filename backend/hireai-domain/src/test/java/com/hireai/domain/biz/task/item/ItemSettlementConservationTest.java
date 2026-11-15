package com.hireai.domain.biz.task.item;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.biz.ledger.settlement.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-Item settlement never leaks or creates credits (Invariant #3), and never pays zero commission:
 * for any mix of accepted and refunded Items, everything that leaves escrow (builder net + platform
 * commission + client refund) adds up to exactly what was frozen, price × count.
 */
class ItemSettlementConservationTest {

    private final SettlementDomainServiceImpl settlement = new SettlementDomainServiceImpl();

    @Test
    void everyMixOfAcceptedAndRefundedItemsReleasesExactlyTheFrozenEscrow() {
        Random random = new Random(103);
        String[] prices = {"0.04", "0.05", "0.07", "0.10", "0.33", "1.00", "2.50", "3.33", "9.99", "17.01", "123.45"};
        for (String p : prices) {
            for (int count : new int[]{2, 3, 7, 10, 99, 1000}) {
                Money price = Money.of(p);
                Money escrow = TaskItems.escrowFor(price, count);
                UUID taskId = UUID.randomUUID();
                WalletModel client = WalletModel.openFor(UUID.randomUUID());
                WalletModel builder = WalletModel.openFor(UUID.randomUUID());
                client.topUp(escrow, "seed");
                client.freeze(escrow, taskId, "freeze");

                Money net = Money.ZERO;
                Money commission = Money.ZERO;
                Money refund = Money.ZERO;
                for (int item = 1; item <= count; item++) {
                    String corr = "settle-" + taskId + "-item-" + item;
                    if (random.nextBoolean()) {
                        SettlementBreakdown b = settlement.settleAcceptance(client, builder, price, taskId, corr);
                        assertThat(b.commission().isPositive()).as("commission on %s", p).isTrue();
                        net = net.add(b.net());
                        commission = commission.add(b.commission());
                    } else {
                        settlement.settleRejection(client, price, taskId, corr);
                        refund = refund.add(price);
                    }
                }

                assertThat(net.add(commission).add(refund)).as("%s x %d", p, count).isEqualTo(escrow);
                assertThat(client.escrow()).as("escrow left, %s x %d", p, count).isEqualTo(Money.ZERO);
                assertThat(builder.available()).isEqualTo(net);
                assertThat(client.available()).isEqualTo(refund);
            }
        }
    }

    @Test
    void theMinimumItemPriceIsTheLowestPriceThatStillEarnsACommission() {
        assertThat(SettlementPolicy.commissionOn(TaskItems.MIN_ITEM_PRICE).isPositive()).isTrue();
        Money justBelow = TaskItems.MIN_ITEM_PRICE.subtract(Money.of("0.01"));
        assertThat(SettlementPolicy.commissionOn(justBelow)).isEqualTo(Money.ZERO);
    }

    @Test
    void anItemPricedBelowTheMinimumIsRefused() {
        List<String> many = Collections.nCopies(500, "invoice");
        assertThatThrownBy(() -> TaskItems.submit(UUID.randomUUID(), many, Money.of("0.03")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("at least " + TaskItems.MIN_ITEM_PRICE.value());
        assertThat(TaskItems.submit(UUID.randomUUID(), many, TaskItems.MIN_ITEM_PRICE).count()).isEqualTo(500);
        assertThat(new BigDecimal("0.04")).isEqualByComparingTo(TaskItems.MIN_ITEM_PRICE.value());
    }
}
