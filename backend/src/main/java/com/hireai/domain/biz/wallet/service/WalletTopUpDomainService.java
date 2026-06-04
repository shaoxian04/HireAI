package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

/**
 * Domain service for the "top up" state transition. One domain service per
 * transition (not a Wallet god-class). Stateless; enforces the transition's
 * invariants via the aggregate.
 */
public class WalletTopUpDomainService {

    public void topUp(WalletModel wallet, Money amount, String correlationId) {
        wallet.topUp(amount, correlationId);
    }
}
