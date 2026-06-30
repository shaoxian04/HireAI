package com.hireai.application.biz.ledger.settlement.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
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

    private WalletModel requireWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }

    private WalletModel loadOrOpen(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(WalletModel.openFor(userId)));
    }
}
