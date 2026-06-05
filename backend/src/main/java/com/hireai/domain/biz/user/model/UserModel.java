package com.hireai.domain.biz.user.model;

import com.hireai.domain.biz.user.enums.Role;

import java.util.UUID;

/**
 * Minimal User READ aggregate. Maps the existing {@code users} table (no schema change). Carries
 * just what authentication needs: identity, the BCrypt hash to verify against, the role, and the
 * active flag. Immutable; no behaviour beyond accessors in this slice (login is orchestrated by
 * the app service, not the model).
 */
public record UserModel(UUID id, String email, String passwordHash, Role role, boolean active) {
}
