package com.hireai.infrastructure.repository.ledger.wallet;

import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletLedgerQuery;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link WalletRepository}. Maps
 * {@code WalletModel} &lt;-&gt; JPA entities and persists the aggregate's pending
 * ledger entries. Children (ledger entries) are written only through the root.
 */
@Repository
public class WalletRepositoryImpl implements WalletRepository {

    private final WalletJpaRepository walletJpa;
    private final LedgerEntryJpaRepository ledgerJpa;

    public WalletRepositoryImpl(WalletJpaRepository walletJpa, LedgerEntryJpaRepository ledgerJpa) {
        this.walletJpa = walletJpa;
        this.ledgerJpa = ledgerJpa;
    }

    @Override
    public WalletModel save(WalletModel wallet) {
        walletJpa.save(new WalletDO(
                wallet.id(), wallet.userId(),
                wallet.available().value(), wallet.escrow().value()));

        for (LedgerEntryModel entry : wallet.pendingEntries()) {
            ledgerJpa.save(new LedgerEntryDO(
                    entry.id(), wallet.id(), entry.type(),
                    entry.amount().value(), entry.balanceAfter().value(),
                    entry.relatedTaskId(), entry.correlationId(), entry.createdAt()));
        }
        wallet.clearPendingEntries();
        return wallet;
    }

    @Override
    public Optional<WalletModel> findById(UUID walletId) {
        return walletJpa.findById(walletId).map(this::toModel);
    }

    @Override
    public Optional<WalletModel> findByUserId(UUID userId) {
        return walletJpa.findByUserId(userId).map(this::toModel);
    }

    @Override
    public List<LedgerEntryModel> findLedger(UUID walletId, WalletLedgerQuery query) {
        return ledgerJpa.findByWalletIdOrderByCreatedAtDesc(
                        walletId, PageRequest.of(query.page(), query.size()))
                .stream()
                .map(this::toModel)
                .toList();
    }

    private WalletModel toModel(WalletDO e) {
        return new WalletModel(e.getId(), e.getUserId(),
                Money.of(e.getAvailableBalance()), Money.of(e.getEscrowBalance()));
    }

    private LedgerEntryModel toModel(LedgerEntryDO e) {
        return new LedgerEntryModel(e.getId(), e.getEntryType(),
                Money.of(e.getAmount()), Money.of(e.getBalanceAfter()),
                e.getRelatedTaskId(), e.getCorrelationId(), e.getCreatedAt());
    }
}
