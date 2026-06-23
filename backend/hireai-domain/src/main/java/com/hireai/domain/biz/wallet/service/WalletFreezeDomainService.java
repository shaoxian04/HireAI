package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Domain service for the escrow "freeze" state transition at task submission.
 * Enforces (via the aggregate) the hard invariant: no escrow without sufficient
 * available balance. Framework-free; the bean is registered in DomainServiceConfig.
 */
public interface WalletFreezeDomainService {

    void freeze(WalletModel wallet, Money amount, UUID taskId, String correlationId);
}
