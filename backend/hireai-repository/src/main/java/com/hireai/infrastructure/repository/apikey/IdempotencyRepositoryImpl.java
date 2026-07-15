package com.hireai.infrastructure.repository.apikey;

import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * insert() uses saveAndFlush so the UNIQUE(owner_id, idempotency_key) violation surfaces
 * synchronously (as DataIntegrityViolationException) inside the caller's transaction — the
 * orchestration service relies on that to detect a concurrent-retry race and roll back the freeze.
 */
@Repository
public class IdempotencyRepositoryImpl implements IdempotencyRepository {

    private final IdempotencyJpaRepository jpa;

    public IdempotencyRepositoryImpl(IdempotencyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void insert(IdempotencyRecord r) {
        jpa.saveAndFlush(new IdempotencyRecordDO(r.id(), r.ownerId(), r.idempotencyKey(),
                r.requestFingerprint(), r.taskId(), r.createdAt()));
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID ownerId, String idempotencyKey) {
        return jpa.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey).map(this::toRecord);
    }

    private IdempotencyRecord toRecord(IdempotencyRecordDO d) {
        return new IdempotencyRecord(d.getId(), d.getOwnerId(), d.getIdempotencyKey(),
                d.getRequestFingerprint(), d.getTaskId(), d.getCreatedAt());
    }
}
