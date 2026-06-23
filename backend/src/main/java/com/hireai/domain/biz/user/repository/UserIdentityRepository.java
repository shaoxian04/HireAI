package com.hireai.domain.biz.user.repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for external identity links (OAuth). Keeps the User aggregate focused;
 * one repository per table per the DDD conventions.
 */
public interface UserIdentityRepository {

    /** The local user id linked to a provider's stable subject, if any. */
    Optional<UUID> findUserIdByProviderSubject(String provider, String subject);

    /** Links a provider identity to an existing local user. */
    void link(UUID userId, String provider, String subject, String emailAtLink);
}
