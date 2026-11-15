package com.hireai.application.biz.ledger.settlement.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.ItemSettlementRepository;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SettlementWriteAppServiceImpl implements SettlementWriteAppService {

    private final WalletRepository walletRepository;
    private final SettlementDomainService settlementDomainService;
    private final SettlementRepository settlementRepository;
    private final ItemSettlementRepository itemSettlementRepository;

    @Override
    public SettlementBreakdown settleAccepted(UUID taskId, UUID clientId, UUID builderId, Money budget) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        String correlationId = "settle-" + taskId;
        SettlementBreakdown breakdown = settlementDomainService.settleAcceptance(
                clientWallet, builderWallet, budget, taskId, correlationId);

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        settlementRepository.save(SettlementModel.accepted(taskId, breakdown.net(), breakdown.commission()));
        return breakdown;
    }

    @Override
    public void settleRejected(UUID taskId, UUID clientId, Money budget) {
        WalletModel clientWallet = requireWallet(clientId);
        settlementDomainService.settleRejection(clientWallet, budget, taskId, "settle-" + taskId);
        walletRepository.save(clientWallet);
        settlementRepository.save(SettlementModel.rejected(taskId));
    }

    @Override
    public SettlementBreakdown settleSplit(UUID taskId, UUID clientId, UUID builderId, Money budget) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        String correlationId = "settle-" + taskId;
        SettlementBreakdown breakdown = settlementDomainService.settleSplit(
                clientWallet, builderWallet, budget, taskId, correlationId);

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        settlementRepository.save(SettlementModel.split(taskId, breakdown.net(), breakdown.commission()));
        return breakdown;
    }

    @Override
    public SettlementBreakdown settleItemAccepted(UUID taskId, UUID itemId, int itemNo, UUID clientId,
                                                  UUID builderId, Money price) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        SettlementBreakdown breakdown = settlementDomainService.settleAcceptance(
                clientWallet, builderWallet, price, taskId, itemCorrelation(taskId, itemNo));

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        itemSettlementRepository.append(ItemSettlementModel.accepted(taskId, itemId, itemNo,
                breakdown.net(), breakdown.commission()));
        return breakdown;
    }

    @Override
    public void settleItemRefunded(UUID taskId, UUID itemId, int itemNo, UUID clientId, Money price) {
        WalletModel clientWallet = requireWallet(clientId);
        settlementDomainService.settleRejection(clientWallet, price, taskId, itemCorrelation(taskId, itemNo));
        walletRepository.save(clientWallet);
        itemSettlementRepository.append(ItemSettlementModel.refunded(taskId, itemId, itemNo, price));
    }

    /** Ties an Item's ledger entries to it, so each Item's settlement is reconstructable on its own. */
    private static String itemCorrelation(UUID taskId, int itemNo) {
        return "settle-" + taskId + "-item-" + itemNo;
    }

    private WalletModel requireWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }

    private WalletModel loadOrOpen(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(WalletModel.openFor(userId)));
    }
}
