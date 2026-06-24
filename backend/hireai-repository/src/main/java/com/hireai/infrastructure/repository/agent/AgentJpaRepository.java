package com.hireai.infrastructure.repository.agent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for agent rows. Internal to infrastructure. */
public interface AgentJpaRepository extends JpaRepository<AgentDO, UUID> {

    List<AgentDO> findByOwnerIdOrderByGmtCreateDesc(UUID ownerId, Pageable pageable);
}
