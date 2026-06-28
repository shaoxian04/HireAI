package com.hireai.application.biz.ledger.wallet.impl;

import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.biz.ledger.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.ledger.wallet.service.WalletTopUpDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class WalletWriteAppServiceImpl implements WalletWriteAppService {

    private final WalletRepository walletRepository;
    private final WalletTopUpDomainService topUpDomainService;
    private final WalletFreezeDomainService freezeDomainService;

    @Override
    public UUID topUp(UUID userId, Money amount, String correlationId) {
        WalletModel wallet = loadOrOpen(userId);
        topUpDomainService.topUp(wallet, amount, correlationId);
        return walletRepository.save(wallet).id();
    }

    @Override
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
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }
}
