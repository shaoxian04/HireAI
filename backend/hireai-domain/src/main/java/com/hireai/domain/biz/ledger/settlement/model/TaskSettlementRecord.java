package com.hireai.domain.biz.ledger.settlement.model;

import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What one Task's recorded settlements say (Invariant #2: settlement is reconstructable from the record):
 * the whole-Task {@link SettlementModel}, if any, and the {@link ItemSettlementModel}s of a multi-Item Task's
 * Items. Every amount shown to anyone, and every "still held in escrow" figure, comes from here, never from
 * re-deriving the policy, so what is shown is what was settled.
 *
 * <p>{@code budget} is the Task's frozen escrow of record.
 */
public record TaskSettlementRecord(Money budget, Optional<SettlementModel> whole, List<ItemSettlementModel> items) {

    public TaskSettlementRecord {
        whole = whole == null ? Optional.empty() : whole;
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** What the Items that settled on their own released from escrow. */
    public Money itemsGross() {
        return items.stream().map(ItemSettlementModel::gross).reduce(Money.ZERO, Money::add);
    }

    /** What is still in escrow for the whole-Task settlement: the budget less the Items already settled. */
    public Money stillHeld() {
        return budget.subtract(itemsGross());
    }

    /** Paid to the builder (net of commission), over every recorded settlement. */
    public Money net() {
        return items.stream().map(ItemSettlementModel::net).reduce(Money.ZERO, Money::add)
                .add(whole.map(SettlementModel::net).orElse(Money.ZERO));
    }

    /** Commission taken, over every recorded settlement. */
    public Money commission() {
        return items.stream().map(ItemSettlementModel::commission).reduce(Money.ZERO, Money::add)
                .add(whole.map(SettlementModel::commission).orElse(Money.ZERO));
    }

    /** Refunded to the client, over every recorded settlement (the whole-Task one refunds what it did not pay). */
    public Money refund() {
        Money items = this.items.stream().map(ItemSettlementModel::refund).reduce(Money.ZERO, Money::add);
        return items.add(whole.map(w -> stillHeld().subtract(w.net()).subtract(w.commission())).orElse(Money.ZERO));
    }

    /** One Item's recorded settlement, if it has settled. */
    public Optional<ItemSettlementModel> item(UUID itemId) {
        return items.stream().filter(s -> s.itemId().equals(itemId)).findFirst();
    }

    /**
     * The commission on the next Item settlement paying the builder's side {@code gross}: allocated on the
     * Task's running total, so the Items' commissions together are the 15% of everything paid, rounded once
     * ({@code commissionOn(total) - commission so far}), never 15% rounded up on every Item (a 0.10 Item
     * would otherwise pay 0.02, i.e. 20%). Each Item's share is its exact 15% rounded down or up by at most
     * one cent, and never more than its gross.
     */
    public Money commissionOnNextItem(Money gross) {
        Money paidSoFar = items.stream().map(s -> s.net().add(s.commission())).reduce(Money.ZERO, Money::add);
        Money takenSoFar = items.stream().map(ItemSettlementModel::commission).reduce(Money.ZERO, Money::add);
        Money allocated = SettlementPolicy.commissionOn(paidSoFar.add(gross)).subtract(takenSoFar);
        if (allocated.isNegative()) {
            return Money.ZERO;
        }
        return allocated.isGreaterThan(gross) ? gross : allocated;
    }
}
