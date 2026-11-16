package com.hireai.domain.biz.task.item;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.TaskSettlementRecord;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.biz.ledger.settlement.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-Item settlement never leaks or creates credits (Invariant #3), and its commissions add up to 15% of what
 * was paid, rounded once (never rounded up on every Item):
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
                Money paid = Money.ZERO;
                List<ItemSettlementModel> recorded = new ArrayList<>();
                for (int item = 1; item <= count; item++) {
                    String corr = "settle-" + taskId + "-item-" + item;
                    if (random.nextBoolean()) {
                        Money cut = new TaskSettlementRecord(escrow, Optional.empty(), recorded)
                                .commissionOnNextItem(price);
                        SettlementBreakdown b = settlement.settleAcceptance(client, builder, price, cut, taskId, corr);
                        recorded.add(ItemSettlementModel.accepted(taskId, UUID.randomUUID(), item, b.net(),
                                b.commission()));
                        net = net.add(b.net());
                        commission = commission.add(b.commission());
                        paid = paid.add(price);
                    } else {
                        settlement.settleRejection(client, price, taskId, corr);
                        recorded.add(ItemSettlementModel.refunded(taskId, UUID.randomUUID(), item, price));
                        refund = refund.add(price);
                    }
                }

                assertThat(net.add(commission).add(refund)).as("%s x %d", p, count).isEqualTo(escrow);
                // The Items' commissions together are 15% of everything paid, rounded once: never over-charged.
                assertThat(commission).as("commission, %s x %d", p, count)
                        .isEqualTo(SettlementPolicy.commissionOn(paid));
                assertThat(new TaskSettlementRecord(escrow, Optional.empty(), recorded).stillHeld())
                        .isEqualTo(Money.ZERO);
                assertThat(client.escrow()).as("escrow left, %s x %d", p, count).isEqualTo(Money.ZERO);
                assertThat(builder.available()).isEqualTo(net);
                assertThat(client.available()).isEqualTo(refund);
            }
        }
    }

    @Test
    void aTenCentItemIsNotChargedTwentyPercent() {
        // 15% of 0.10 is 0.015: rounding up on every Item would take 0.02 each (20%). Allocated on the running
        // total, ten Items take 0.15 in all, each 0.01 or 0.02.
        List<ItemSettlementModel> recorded = new ArrayList<>();
        UUID taskId = UUID.randomUUID();
        for (int item = 1; item <= 10; item++) {
            Money cut = new TaskSettlementRecord(Money.of("1.00"), Optional.empty(), recorded)
                    .commissionOnNextItem(Money.of("0.10"));
            assertThat(cut).isIn(Money.of("0.01"), Money.of("0.02"));
            recorded.add(ItemSettlementModel.accepted(taskId, UUID.randomUUID(), item,
                    Money.of("0.10").subtract(cut), cut));
        }
        assertThat(recorded.stream().map(ItemSettlementModel::commission).reduce(Money.ZERO, Money::add))
                .isEqualTo(Money.of("0.15"));
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
