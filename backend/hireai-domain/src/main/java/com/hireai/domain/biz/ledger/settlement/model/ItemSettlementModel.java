package com.hireai.domain.biz.ledger.settlement.model;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * The auditable record of how one Item of a multi-Item Task settled (spec S3): paid 85/15
 * (ACCEPT), refunded in full (REJECT), or paid pro rata with the rest refunded (SPLIT, spec D19). The money moves through the ledger; this records the
 * decision and the split. One per Item, ever.
 */
public record ItemSettlementModel(UUID id, UUID taskId, UUID itemId, int itemNo, SettlementType type,
                                  Money net, Money commission, Money refund, Instant createdAt) {

    public static ItemSettlementModel accepted(UUID taskId, UUID itemId, int itemNo, Money net, Money commission) {
        return new ItemSettlementModel(UUID.randomUUID(), taskId, itemId, itemNo, SettlementType.ACCEPT,
                net, commission, Money.ZERO, Instant.now());
    }

    public static ItemSettlementModel proRata(UUID taskId, UUID itemId, int itemNo, Money net, Money commission,
                                              Money refund) {
        return new ItemSettlementModel(UUID.randomUUID(), taskId, itemId, itemNo, SettlementType.SPLIT,
                net, commission, refund, Instant.now());
    }

    /** Everything this Item's settlement released from escrow. */
    public Money gross() {
        return net.add(commission).add(refund);
    }

    public static ItemSettlementModel refunded(UUID taskId, UUID itemId, int itemNo, Money refund) {
        return new ItemSettlementModel(UUID.randomUUID(), taskId, itemId, itemNo, SettlementType.REJECT,
                Money.ZERO, Money.ZERO, refund, Instant.now());
    }
}
