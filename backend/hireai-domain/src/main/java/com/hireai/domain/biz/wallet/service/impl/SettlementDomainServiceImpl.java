package com.hireai.domain.biz.wallet.service.impl;

import com.hireai.domain.biz.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.service.SettlementDomainService;
import com.hireai.domain.biz.wallet.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

public class SettlementDomainServiceImpl implements SettlementDomainService {

    @Override
    public SettlementBreakdown settleAcceptance(WalletModel clientWallet, WalletModel builderWallet,
                                                Money budget, UUID taskId, String correlationId) {
        Money commission = SettlementPolicy.commissionOn(budget);
        Money net = budget.subtract(commission);

        clientWallet.release(net, taskId, LedgerEntryType.PAYOUT, correlationId);
        // Tiny budgets can round the commission to zero; release() requires a positive amount.
        if (commission.isPositive()) {
            clientWallet.release(commission, taskId, LedgerEntryType.COMMISSION, correlationId);
        }
        builderWallet.credit(net, taskId, LedgerEntryType.PAYOUT, correlationId);
        return new SettlementBreakdown(net, commission);
    }

    @Override
    public void settleRejection(WalletModel clientWallet, Money budget, UUID taskId, String correlationId) {
        clientWallet.refund(budget, taskId, correlationId);
    }
}
