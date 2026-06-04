package com.hireai.application.biz.wallet;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.biz.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.wallet.service.WalletTopUpDomainService;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates wallet WRITE use cases. Transactional; invokes a per-transition
 * domain service and persists through the repository INTERFACE. Returns only the
 * aggregate ID — callers re-read full state via {@link WalletReadAppService}.
 */
@Service
public class WalletWriteAppService {

    private final WalletRepository walletRepository;
    private final WalletTopUpDomainService topUpDomainService;
    private final WalletFreezeDomainService freezeDomainService;

    public WalletWriteAppService(WalletRepository walletRepository,
                                 WalletTopUpDomainService topUpDomainService,
                                 WalletFreezeDomainService freezeDomainService) {
        this.walletRepository = walletRepository;
        this.topUpDomainService = topUpDomainService;
        this.freezeDomainService = freezeDomainService;
    }

    @Transactional
    public UUID topUp(UUID userId, Money amount, String correlationId) {
        WalletModel wallet = loadOrOpen(userId);
        topUpDomainService.topUp(wallet, amount, correlationId);
        return walletRepository.save(wallet).id();
    }

    @Transactional
    public UUID freeze(UUID userId, Money amount, UUID taskId, String correlationId) {
        WalletModel wallet = requireWallet(userId);
        freezeDomainService.freeze(wallet, amount, taskId, correlationId);
        return walletRepository.save(wallet).id();
    }

    private WalletModel loadOrOpen(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(WalletModel.openFor(userId)));
    }

    private WalletModel requireWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No wallet for user " + userId));
    }
}
