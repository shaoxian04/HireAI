package com.hireai.domain.biz.ledger.wallet.service.impl;

import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/** Stateless implementation of the escrow-freeze transition; delegates to the aggregate. */
public class WalletFreezeDomainServiceImpl implements WalletFreezeDomainService {

    @Override
    public void freeze(WalletModel wallet, Money amount, UUID taskId, String correlationId) {
        wallet.freeze(amount, taskId, correlationId);
    }
}
