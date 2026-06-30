package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisputeRulingJpaRepository extends JpaRepository<DisputeRulingDO, UUID> {

    /** How many ruling rows already persisted for this dispute (drives idempotent append). */
    long countByDisputeId(UUID disputeId);

    /** Ruling history in append order (oldest first). */
    List<DisputeRulingDO> findByDisputeIdOrderByGmtCreateAsc(UUID disputeId);
}
