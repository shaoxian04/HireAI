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

    // DisputeDO maps only gmt_create (gmt_modified exists but Hibernate never updates it), so "how
    // long in RULED" comes from the latest ruling's decided_at (the arbitrator proposal timestamp).
    @Query(value = """
        SELECT d.id FROM disputes d
        WHERE d.status = 'RULED'
          AND (SELECT max(r.decided_at) FROM dispute_rulings r WHERE r.dispute_id = d.id) < :cutoff
        """, nativeQuery = true)
    List<UUID> findStaleRuledIds(Instant cutoff);
}
