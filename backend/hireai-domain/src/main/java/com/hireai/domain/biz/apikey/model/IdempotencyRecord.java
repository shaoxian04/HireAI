package com.hireai.domain.biz.apikey.model;

import java.time.Instant;
import java.util.UUID;

/** One idempotency record: a submit outcome keyed by (owner, idempotency key). */
public record IdempotencyRecord(UUID id, UUID ownerId, String idempotencyKey,
                                String requestFingerprint, UUID taskId, Instant createdAt) {

    public static IdempotencyRecord create(UUID ownerId, String idempotencyKey,
                                           String requestFingerprint, UUID taskId, Instant now) {
        return new IdempotencyRecord(UUID.randomUUID(), ownerId, idempotencyKey,
                requestFingerprint, taskId, now);
    }
}
