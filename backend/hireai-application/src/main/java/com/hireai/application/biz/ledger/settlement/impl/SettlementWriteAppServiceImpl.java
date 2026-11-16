package com.hireai.application.biz.ledger.settlement.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.DisputeFeeRepository;
import com.hireai.domain.biz.ledger.settlement.service.DisputeFeeDomainService;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.ItemSettlementRepository;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SettlementWriteAppServiceImpl implements SettlementWriteAppService {

    private final WalletRepository walletRepository;
    private final SettlementDomainService settlementDomainService;
    private final SettlementRepository settlementRepository;
    private final ItemSettlementRepository itemSettlementRepository;
    private final DisputeFeeDomainService disputeFeeDomainService;
    private final DisputeFeeRepository disputeFeeRepository;

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
        // A multi-Item Task's Items may already have settled one by one (a sampled Task reworked after
        // a failed sample): only what is still in escrow goes back, never their share twice.
        Money settledItems = itemSettlementRepository.findByTaskId(taskId).stream()
                .map(ItemSettlementModel::gross).reduce(Money.ZERO, Money::add);
        Money held = budget.subtract(settledItems);
        if (held.isPositive()) {
            settlementDomainService.settleRejection(clientWallet, held, taskId, "settle-" + taskId);
        }
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
    public DisputeFeeCharge chargeDisputeFee(UUID disputeId, UUID taskId, UUID clientId, UUID builderId,
                                             DisputeFeeType type, Money fullCost, Money itemPrice,
                                             Set<DisputeParty> payers) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        DisputeFeeCharge charge = disputeFeeDomainService.charge(type, fullCost, itemPrice, payers,
                clientWallet, builderWallet, taskId, "dispute-fee-" + disputeId);

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        disputeFeeRepository.append(disputeId, taskId, charge);
        return charge;
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
    public SettlementBreakdown settleItemProRata(UUID taskId, UUID itemId, int itemNo, UUID clientId,
                                                 UUID builderId, Money price, Money paid) {
        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        SettlementBreakdown breakdown = settlementDomainService.settlePartial(
                clientWallet, builderWallet, price, paid, taskId, itemCorrelation(taskId, itemNo));

        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        itemSettlementRepository.append(ItemSettlementModel.proRata(taskId, itemId, itemNo,
                breakdown.net(), breakdown.commission(), price.subtract(paid)));
        return breakdown;
    }

    @Override
    public void settleItemRefunded(UUID taskId, UUID itemId, int itemNo, UUID clientId, Money price) {
        WalletModel clientWallet = requireWallet(clientId);
        settlementDomainService.settleRejection(clientWallet, price, taskId, itemCorrelation(taskId, itemNo));
        walletRepository.save(clientWallet);
        itemSettlementRepository.append(ItemSettlementModel.refunded(taskId, itemId, itemNo, price));
    }

    @Override
    public void chargeAiReviewFee(UUID taskId, UUID clientId, Money fee) {
        WalletModel clientWallet = requireWallet(clientId);
        clientWallet.release(fee, taskId, LedgerEntryType.AI_REVIEW_FEE, aiReviewCorrelation(taskId));
        walletRepository.save(clientWallet);
    }

    @Override
    public void refundAiReviewFee(UUID taskId, UUID clientId, Money fee) {
        WalletModel clientWallet = requireWallet(clientId);
        clientWallet.refund(fee, taskId, aiReviewCorrelation(taskId));
        walletRepository.save(clientWallet);
    }

    /** Ties the AI Review fee's freeze, charge or refund to the Task, apart from its settlement. */
    public static String aiReviewCorrelation(UUID taskId) {
        return "ai-review-" + taskId;
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
