package com.hireai.domain.biz.ledger.settlement.model;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Settlement aggregate root: the auditable record of how one task settled. The money itself moves
 * through the append-only ledger (the source of truth); this records the decision + the split.
 * For REJECT, net and commission are zero (the client was fully refunded).
 */
public record SettlementModel(UUID id, UUID taskId, SettlementType type,
                              Money net, Money commission, Instant createdAt) {

    public static SettlementModel accepted(UUID taskId, Money net, Money commission) {
        return new SettlementModel(UUID.randomUUID(), taskId, SettlementType.ACCEPT, net, commission, Instant.now());
    }

    public static SettlementModel rejected(UUID taskId) {
        return new SettlementModel(UUID.randomUUID(), taskId, SettlementType.REJECT, Money.ZERO, Money.ZERO, Instant.now());
    }
}
