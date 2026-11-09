package com.hireai.infrastructure.repository.offering.storefront;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for agent_profiles rows. Internal to infrastructure. */
public interface StorefrontJpaRepository extends JpaRepository<StorefrontDO, UUID> {

    @Query("SELECT s.agentId FROM StorefrontDO s WHERE s.agentId IN :ids AND s.listed = true")
    List<UUID> findListedAgentIds(@Param("ids") Collection<UUID> ids);
}
