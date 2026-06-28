package com.hireai.infrastructure.repository.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for role grants. Internal to infrastructure. */
public interface UserRoleJpaRepository extends JpaRepository<UserRoleDO, UserRoleDO.Key> {

    List<UserRoleDO> findByUserId(UUID userId);

    /**
     * Idempotent, race-safe grant of a (user_id, role). {@code ON CONFLICT DO NOTHING} makes a
     * concurrent duplicate a no-op without throwing, so it never marks the surrounding transaction
     * rollback-only (which a caught {@code DataIntegrityViolationException} would). Must run inside a
     * transaction (the caller provides one).
     */
    @Modifying
    @Query(value = "INSERT INTO user_roles (user_id, role) VALUES (:userId, :role) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    void insertIgnore(@Param("userId") UUID userId, @Param("role") String role);
}
