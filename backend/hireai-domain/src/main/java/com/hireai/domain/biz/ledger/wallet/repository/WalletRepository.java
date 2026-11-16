package com.hireai.domain.biz.ledger.wallet.repository;

import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Wallet aggregate. One repository per aggregate
 * root; ledger entries are persisted through the wallet, never independently.
 * The interface lives in the domain layer and carries no framework imports;
 * the JPA implementation lives in infrastructure.
 */
public interface WalletRepository {

    WalletModel save(WalletModel wallet);

    Optional<WalletModel> findById(UUID walletId);

    Optional<WalletModel> findByUserId(UUID userId);

    /**
     * {@link #findByUserId} under a row lock held to the end of the transaction: every settlement and every
     * escrow movement reads its wallets this way, so two of them touching one wallet at the same moment
     * serialize instead of failing the version check.
     */
    Optional<WalletModel> findByUserIdForUpdate(UUID userId);

    List<LedgerEntryModel> findLedger(UUID walletId, WalletLedgerQuery query);
}
