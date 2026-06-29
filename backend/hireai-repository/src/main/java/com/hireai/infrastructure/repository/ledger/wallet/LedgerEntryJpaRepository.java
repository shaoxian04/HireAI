package com.hireai.infrastructure.repository.ledger.wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for ledger rows. Insert + read only. */
public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryDO, UUID> {

    List<LedgerEntryDO> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);
}
