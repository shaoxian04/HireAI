package com.hireai.domain.biz.identity.model;

/**
 * A user's local password credential — the BCrypt hash, or {@link #NONE} for an OAuth-only account
 * with no local password. Value object: immutable, equality by value. Verifying a raw password needs
 * the PasswordEncoder and is done in the application layer, not here.
 */
public record Credential(String secretHash) {

    /** No local password (an OAuth-only account). */
    public static final Credential NONE = new Credential(null);

    public static Credential ofHash(String secretHash) {
        return secretHash == null ? NONE : new Credential(secretHash);
    }

    public boolean isAbsent() {
        return secretHash == null;
    }
}
