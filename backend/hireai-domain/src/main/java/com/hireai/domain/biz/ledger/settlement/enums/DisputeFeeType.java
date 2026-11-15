package com.hireai.domain.biz.ledger.settlement.enums;

import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;

/**
 * The two costs a dispute can carry (spec D16/D18/D42/D43): the cost of AI arbitration, and the
 * Appeal Fee of a web appeal to an admin. Both are borne by the side the effective ruling goes against.
 */
public enum DisputeFeeType {
    ARBITRATION_COST(LedgerEntryType.ARBITRATION_FEE),
    APPEAL_FEE(LedgerEntryType.APPEAL_FEE);

    private final LedgerEntryType ledgerType;

    DisputeFeeType(LedgerEntryType ledgerType) {
        this.ledgerType = ledgerType;
    }

    public LedgerEntryType ledgerType() {
        return ledgerType;
    }
}
