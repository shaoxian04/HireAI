package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyJpaRepository extends JpaRepository<IdempotencyRecordDO, UUID> {
    Optional<IdempotencyRecordDO> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);
}
