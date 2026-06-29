package com.hireai.infrastructure.repository.ledger.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SettlementJpaRepository extends JpaRepository<SettlementDO, UUID> {
    Optional<SettlementDO> findByTaskId(UUID taskId);
}
