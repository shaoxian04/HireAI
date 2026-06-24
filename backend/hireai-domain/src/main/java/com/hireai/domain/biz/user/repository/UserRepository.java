package com.hireai.domain.biz.user.repository;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the User aggregate. Roles live in the user_roles join table and are
 * read/written through this root. The interface is framework-free; the JPA impl is in infrastructure.
 */
public interface UserRepository {

    Optional<UserModel> findByEmail(String email);

    Optional<UserModel> findById(UUID id);

    /** Inserts the user row and one user_roles row per role. */
    UserModel create(UserModel user);

    /** Idempotently grants a role (used by the become-builder upgrade). */
    void addRole(UUID userId, Role role);
}
