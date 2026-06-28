package com.hireai.infrastructure.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for identity links. Internal to infrastructure. */
public interface UserIdentityJpaRepository extends JpaRepository<UserIdentityDO, UUID> {

    Optional<UserIdentityDO> findByProviderAndProviderSubject(String provider, String subject);
}
