package com.hireai.infrastructure.repository.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryJpaRepository extends JpaRepository<WebhookDeliveryDO, UUID> {

    @Query(value = "SELECT id FROM webhook_deliveries WHERE status='PENDING' AND next_attempt_at <= :now "
                 + "ORDER BY next_attempt_at LIMIT :limit", nativeQuery = true)
    List<UUID> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    // Row-lock a single still-due PENDING row; SKIP LOCKED so parallel sweepers never block each other.
    @Query(value = "SELECT * FROM webhook_deliveries WHERE id = :id AND status='PENDING' "
                 + "AND next_attempt_at <= :now FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WebhookDeliveryDO> claimForUpdate(@Param("id") UUID id, @Param("now") Instant now);
}
