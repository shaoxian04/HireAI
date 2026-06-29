package com.hireai.domain.biz.identity.repository;

import com.hireai.domain.biz.identity.model.OAuthIdentity;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for external identity links (OAuth) owned by the User aggregate.
 * One repository per table per the DDD conventions.
 */
public interface OAuthIdentityRepository {

    /** The local user id linked to a provider's stable subject, if any. */
    Optional<UUID> findUserIdByProviderSubject(String provider, String subject);

    /** Persists a new identity link. */
    void save(OAuthIdentity identity);
}
