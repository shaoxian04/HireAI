package com.hireai.domain.biz.wallet.service.impl;

import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.service.WalletTopUpDomainService;
import com.hireai.domain.shared.model.Money;

/** Stateless implementation of the top-up transition; delegates to the aggregate. */
public class WalletTopUpDomainServiceImpl implements WalletTopUpDomainService {

    @Override
    public void topUp(WalletModel wallet, Money amount, String correlationId) {
        wallet.topUp(amount, correlationId);
    }
}
