package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

/**
 * Domain service for the wallet "top up" state transition. One domain service per
 * transition (not a Wallet god-class). Framework-free: this interface and its impl
 * carry no Spring/JPA imports; the bean is registered in DomainServiceConfig.
 */
public interface WalletTopUpDomainService {

    void topUp(WalletModel wallet, Money amount, String correlationId);
}
