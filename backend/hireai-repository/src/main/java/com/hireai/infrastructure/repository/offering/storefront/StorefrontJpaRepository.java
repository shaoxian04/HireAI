package com.hireai.infrastructure.repository.offering.storefront;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data JPA repository for agent_profiles rows. Internal to infrastructure. */
public interface StorefrontJpaRepository extends JpaRepository<StorefrontDO, UUID> {
}
