package com.hireai.infrastructure.repository.ledger.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for wallet rows. Internal to infrastructure. */
public interface WalletJpaRepository extends JpaRepository<WalletDO, UUID> {

    Optional<WalletDO> findByUserId(UUID userId);

    /** The wallet row, locked (SELECT ... FOR UPDATE) until the transaction ends. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT w FROM WalletDO w WHERE w.userId = :userId")
    Optional<WalletDO> findByUserIdForUpdate(@org.springframework.data.repository.query.Param("userId") UUID userId);
}
