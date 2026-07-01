package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeJpaRepository extends JpaRepository<DisputeDO, UUID> {
    Optional<DisputeDO> findByTaskId(UUID taskId);

    @Query("SELECT d.id FROM DisputeDO d WHERE d.status = 'ARBITRATING' AND d.gmtCreate < :cutoff")
    List<UUID> findStaleArbitratingIds(Instant cutoff);
}
