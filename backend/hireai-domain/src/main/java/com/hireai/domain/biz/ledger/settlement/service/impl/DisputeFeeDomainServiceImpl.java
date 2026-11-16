package com.hireai.domain.biz.ledger.settlement.service.impl;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.settlement.service.DisputeFeeDomainService;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

import java.util.Set;
import java.util.UUID;

public class DisputeFeeDomainServiceImpl implements DisputeFeeDomainService {

    /** The share of the Item price a loser bears at most (D43; configurable, D41). */
    private final java.math.BigDecimal capRate;

    public DisputeFeeDomainServiceImpl() {
        this(SettlementPolicy.DISPUTE_FEE_CAP_RATE);
    }

    public DisputeFeeDomainServiceImpl(java.math.BigDecimal capRate) {
        SettlementPolicy.disputeFeeCap(Money.ZERO, Money.ZERO, capRate); // validates the share
        this.capRate = capRate;
    }

    @Override
    public DisputeFeeCharge charge(DisputeFeeType type, Money fullCost, Money itemPrice, Set<DisputeParty> payers,
                                   WalletModel clientWallet, WalletModel builderWallet,
                                   UUID taskId, String correlationId) {
        Money capped = SettlementPolicy.disputeFeeCap(fullCost, itemPrice, capRate);
        Money clientShare = Money.ZERO;
        Money builderShare = Money.ZERO;
        if (payers.contains(DisputeParty.CLIENT) && payers.contains(DisputeParty.BUILDER)) {
            clientShare = capped.percentage(SettlementPolicy.PARTIAL_BUILDER_FRACTION);
            builderShare = capped.subtract(clientShare); // by subtraction → exact reconciliation
        } else if (payers.contains(DisputeParty.CLIENT)) {
            clientShare = capped;
        } else if (payers.contains(DisputeParty.BUILDER)) {
            builderShare = capped;
        }
        Money clientCharged = debitWhatItCan(clientWallet, clientShare, type, taskId, correlationId);
        Money builderCharged = debitWhatItCan(builderWallet, builderShare, type, taskId, correlationId);
        Money platformCovered = fullCost.subtract(clientCharged).subtract(builderCharged);
        return new DisputeFeeCharge(type, fullCost, capped, clientCharged, builderCharged, platformCovered);
    }

    /** Charges {@code share}, or the whole available balance when that is smaller; the platform covers the rest. */
    private static Money debitWhatItCan(WalletModel wallet, Money share, DisputeFeeType type,
                                        UUID taskId, String correlationId) {
        Money charged = share.isGreaterThan(wallet.available()) ? wallet.available() : share;
        if (charged.isPositive()) {
            wallet.chargeFee(charged, taskId, type.ledgerType(), correlationId);
        }
        return charged.isPositive() ? charged : Money.ZERO;
    }
}
