package com.hireai.application.biz.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/** Owns the money side of a task review: moves escrowed credits and records the settlement. */
public interface SettlementWriteAppService {

    /** Accept: payout 85% to the builder + 15% commission; records an ACCEPT settlement. */
    SettlementBreakdown settleAccepted(UUID taskId, UUID clientId, UUID builderId, Money budget);

    /** Reject: full refund to the client; records a REJECT settlement. */
    void settleRejected(UUID taskId, UUID clientId, Money budget);
}
