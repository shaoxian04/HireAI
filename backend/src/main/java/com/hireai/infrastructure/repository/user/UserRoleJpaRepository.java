package com.hireai.infrastructure.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for role grants. Internal to infrastructure. */
public interface UserRoleJpaRepository extends JpaRepository<UserRoleJpaEntity, UserRoleJpaEntity.Key> {

    List<UserRoleJpaEntity> findByUserId(UUID userId);

    boolean existsByUserIdAndRole(UUID userId, String role);
}
