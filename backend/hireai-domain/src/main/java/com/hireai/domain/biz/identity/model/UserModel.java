package com.hireai.domain.biz.identity.model;

import com.hireai.domain.biz.identity.enums.Role;

import java.util.Set;
import java.util.UUID;

/**
 * User aggregate (read + create). Dual-capability: every user holds {@code CLIENT} and may add
 * {@code BUILDER}. Roles are sourced from the {@code user_roles} join table. Immutable.
 */
public record UserModel(UUID id, String email, Credential credential, String displayName,
                        Set<Role> roles, boolean active) {

    /** A brand-new self-serve account: random id, CLIENT role, active. */
    public static UserModel newClient(String email, Credential credential, String displayName) {
        return new UserModel(UUID.randomUUID(), email, credential, displayName, Set.of(Role.CLIENT), true);
    }
}
