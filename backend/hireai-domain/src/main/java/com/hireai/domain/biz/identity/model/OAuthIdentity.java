package com.hireai.domain.biz.identity.model;

import java.util.UUID;

/**
 * An external identity link (OAuth) owned by the User aggregate: a provider plus its stable subject.
 * Immutable. {@code emailAtLink} records the provider email captured when the link was made (audit only).
 */
public record OAuthIdentity(UUID id, UUID userId, String provider, String subject, String emailAtLink) {

    /** Mint a new link for an existing local user. */
    public static OAuthIdentity link(UUID userId, String provider, String subject, String emailAtLink) {
        return new OAuthIdentity(UUID.randomUUID(), userId, provider, subject, emailAtLink);
    }
}
