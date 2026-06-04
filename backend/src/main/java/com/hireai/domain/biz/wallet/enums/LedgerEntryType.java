package com.hireai.domain.biz.wallet.enums;

/**
 * Classifies every movement recorded in the append-only ledger.
 * The ledger is the immutable audit trail; entry types let the settlement
 * history be reconstructed and reconciled.
 */
public enum LedgerEntryType {

    /** Client adds virtual credits to the available balance. */
    TOPUP,
    /** Available -> escrow at task submission. */
    ESCROW_FREEZE,
    /** Escrow released to an Agent on acceptance (net of commission). */
    PAYOUT,
    /** Escrow returned to the client's available balance. */
    REFUND,
    /** Platform commission deducted from a release. */
    COMMISSION,
    /** Partial settlement split between client refund and Agent payout. */
    SPLIT
}
