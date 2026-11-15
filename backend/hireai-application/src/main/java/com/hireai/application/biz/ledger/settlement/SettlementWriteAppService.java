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

    /**
     * One Item of a multi-Item Task accepted: pays the builder 85/15 on the Item's price out of the
     * Task's escrow, and records the Item's ACCEPT settlement (spec S3).
     */
    SettlementBreakdown settleItemAccepted(UUID taskId, UUID itemId, int itemNo, UUID clientId, UUID builderId,
                                           Money price);

    /** One Item of a multi-Item Task refunded in full to the client; records the Item's REJECT settlement. */
    void settleItemRefunded(UUID taskId, UUID itemId, int itemNo, UUID clientId, Money price);

    /** Partial fulfilment: pay the builder 85/15 on half the budget; refund the other half. Records a SPLIT settlement. */
    SettlementBreakdown settleSplit(UUID taskId, UUID clientId, UUID builderId, Money budget);
}
