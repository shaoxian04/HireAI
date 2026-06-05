package com.hireai.domain.biz.agent.repository;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.model.AgentModel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Agent aggregate. One repository per aggregate root.
 * The interface lives in the domain layer and carries no framework imports; the JPA
 * implementation lives in infrastructure. The agent version (child) is persisted and
 * loaded through the root. {@link #findActiveCandidates} is the routing read used by the
 * Routing module (returns one candidate per ACTIVE agent's current version).
 */
public interface AgentRepository {

    AgentModel save(AgentModel agent);

    Optional<AgentModel> findById(UUID agentId);

    List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query);

    List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice);
}
