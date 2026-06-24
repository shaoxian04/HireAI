package com.hireai.infrastructure.repository.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for wallet rows. Internal to infrastructure. */
public interface WalletJpaRepository extends JpaRepository<WalletDO, UUID> {

    Optional<WalletDO> findByUserId(UUID userId);
}
