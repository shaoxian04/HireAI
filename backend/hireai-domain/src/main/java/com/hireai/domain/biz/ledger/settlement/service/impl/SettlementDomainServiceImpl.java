package com.hireai.domain.biz.ledger.settlement.service.impl;

import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
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

    @Override
    public SettlementBreakdown settleSplit(WalletModel clientWallet, WalletModel builderWallet,
                                           Money budget, UUID taskId, String correlationId) {
        Money builderShare = SettlementPolicy.builderShareOnSplit(budget);
        Money commission = SettlementPolicy.commissionOn(builderShare);
        Money net = builderShare.subtract(commission);
        Money clientRefund = budget.subtract(builderShare); // by subtraction → exact reconciliation

        clientWallet.release(net, taskId, LedgerEntryType.SPLIT, correlationId);
        if (commission.isPositive()) {
            clientWallet.release(commission, taskId, LedgerEntryType.COMMISSION, correlationId);
        }
        if (clientRefund.isPositive()) {
            clientWallet.refund(clientRefund, taskId, correlationId);
        }
        builderWallet.credit(net, taskId, LedgerEntryType.SPLIT, correlationId);
        return new SettlementBreakdown(net, commission);
    }
}
