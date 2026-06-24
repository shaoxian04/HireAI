package com.hireai.infrastructure.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for user rows. Internal to infrastructure. */
public interface UserJpaRepository extends JpaRepository<UserDO, UUID> {

    Optional<UserDO> findByEmail(String email);
}
