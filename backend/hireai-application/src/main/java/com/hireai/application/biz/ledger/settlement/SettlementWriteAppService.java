package com.hireai.application.biz.ledger.settlement;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.shared.model.Money;

import java.util.Set;
import java.util.UUID;

/** Owns the money side of a task review: moves escrowed credits and records the settlement. */
public interface SettlementWriteAppService {

    /** Accept: payout 85% to the builder + 15% commission; records an ACCEPT settlement. */
    SettlementBreakdown settleAccepted(UUID taskId, UUID clientId, UUID builderId, Money budget);

    /**
     * Reject: full refund to the client; records a REJECT settlement. For a multi-Item Task some of whose
     * Items already settled on their own (spec S3/S8), only the escrow still held is refunded.
     */
    void settleRejected(UUID taskId, UUID clientId, Money budget);

    /**
     * One Item of a multi-Item Task accepted: pays the builder 85/15 on the Item's price out of the
     * Task's escrow, and records the Item's ACCEPT settlement (spec S3).
     */
    SettlementBreakdown settleItemAccepted(UUID taskId, UUID itemId, int itemNo, UUID clientId, UUID builderId,
                                           Money price);

    /**
     * One Item of a multi-Item Task settled pro rata (spec D19): the builder is paid 85/15 on {@code paid}
     * (0 &lt; paid &lt; price) and the rest of the price is refunded; records the Item's SPLIT settlement.
     */
    SettlementBreakdown settleItemProRata(UUID taskId, UUID itemId, int itemNo, UUID clientId, UUID builderId,
                                          Money price, Money paid);

    /** One Item of a multi-Item Task refunded in full to the client; records the Item's REJECT settlement. */
    void settleItemRefunded(UUID taskId, UUID itemId, int itemNo, UUID clientId, Money price);

    /** Partial fulfilment: pay the builder 85/15 on half the budget; refund the other half. Records a SPLIT settlement. */
    SettlementBreakdown settleSplit(UUID taskId, UUID clientId, UUID builderId, Money budget);

    /**
     * Loser pays (D43): charges one dispute fee to {@code payers}, capped at 20% of {@code itemPrice},
     * from the Client's balance (after its refund) and the Builder's earnings wallet; the platform
     * covers the rest. Writes the ledger debits and the append-only fee record. Run after the settlement.
     */
    DisputeFeeCharge chargeDisputeFee(UUID disputeId, UUID taskId, UUID clientId, UUID builderId,
                                      DisputeFeeType type, Money fullCost, Money itemPrice, Set<DisputeParty> payers);
}
