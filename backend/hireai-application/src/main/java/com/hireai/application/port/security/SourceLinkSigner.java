package com.hireai.application.port.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and verifies the token of an Item's source download link (spec S4, D31). A token names exactly
 * one Item and its expiry (the execution deadline) and carries a MAC under a server-side key, so it is
 * unguessable, cannot be moved to another Item and is never stored. It is a different key from the
 * dispatch token's, so neither token is accepted as the other.
 */
public interface SourceLinkSigner {

    /** A URL-safe token for {@code itemId}, valid until {@code expiresAt}. */
    String sign(UUID itemId, Instant expiresAt);

    /**
     * The claims of a token whose MAC is valid, expired or not (the caller judges expiry, so an expired
     * attempt can still be logged against its Item); empty for a malformed or forged token.
     */
    Optional<Claims> verify(String token);

    record Claims(UUID itemId, Instant expiresAt) {
    }
}
