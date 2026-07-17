package com.hireai.domain.biz.apikey.repository;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the API-key aggregate. */
public interface ApiKeyRepository {

    ApiKeyModel save(ApiKeyModel key);

    /** Only an ACTIVE key by its hash — the auth lookup. Revoked keys are invisible here. */
    Optional<ApiKeyModel> findActiveByHash(String keyHash);

    Optional<ApiKeyModel> findById(UUID id);

    /** All of a user's keys, newest first (for the management list). */
    List<ApiKeyModel> findByUserId(UUID userId);

    /** Best-effort last-used bump. api_keys is NOT append-only, so a direct UPDATE is fine. */
    void touchLastUsed(UUID id, Instant now);
}
