package com.hireai.application.biz.wallet.impl;

import com.hireai.application.biz.wallet.WalletReadAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletLedgerQuery;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletReadAppServiceImpl implements WalletReadAppService {

    private final WalletRepository walletRepository;

    @Override
    public WalletModel getByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }

    @Override
    public List<LedgerEntryModel> getLedger(UUID userId, WalletLedgerQuery query) {
        WalletModel wallet = getByUserId(userId);
        return walletRepository.findLedger(wallet.id(), query);
    }
}
