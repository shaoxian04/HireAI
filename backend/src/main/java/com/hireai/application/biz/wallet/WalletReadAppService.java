package com.hireai.application.biz.wallet;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletLedgerQuery;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates wallet READ use cases. Returns domain models to the controller,
 * which converts them to DTOs. Read-only transactions; cache-safe.
 */
@Service
public class WalletReadAppService {

    private final WalletRepository walletRepository;

    public WalletReadAppService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public WalletModel getByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No wallet for user " + userId));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryModel> getLedger(UUID userId, WalletLedgerQuery query) {
        WalletModel wallet = getByUserId(userId);
        return walletRepository.findLedger(wallet.id(), query);
    }
}
