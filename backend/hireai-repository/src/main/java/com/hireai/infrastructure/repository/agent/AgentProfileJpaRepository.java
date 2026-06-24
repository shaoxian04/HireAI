package com.hireai.infrastructure.repository.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data JPA repository for agent_profiles rows. Internal to infrastructure. */
public interface AgentProfileJpaRepository extends JpaRepository<AgentProfileDO, UUID> {
}
