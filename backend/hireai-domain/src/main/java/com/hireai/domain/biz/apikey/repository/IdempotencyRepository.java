package com.hireai.domain.biz.apikey.repository;

import com.hireai.domain.biz.apikey.model.IdempotencyRecord;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for idempotency records. insert() surfaces the UNIQUE violation to the caller. */
public interface IdempotencyRepository {

    /** Throws (DataIntegrityViolationException) if (ownerId, idempotencyKey) already exists. */
    void insert(IdempotencyRecord record);

    Optional<IdempotencyRecord> find(UUID ownerId, String idempotencyKey);
}
