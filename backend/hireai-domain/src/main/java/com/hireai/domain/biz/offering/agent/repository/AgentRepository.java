package com.hireai.domain.biz.offering.agent.repository;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.model.AgentModel;

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

    /**
     * Persists a publish-new-version supersession atomically: demote the agent's prior ACTIVE
     * version to DEPRECATED, insert {@code agent.currentVersion()} as the new ACTIVE version, and
     * update the agent row (current_version_id). The prior version is retained as history.
     */
    void publishNewVersion(AgentModel agent);

    Optional<AgentModel> findById(UUID agentId);

    List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query);

    List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice);

    /**
     * Looks up a single ACTIVE agent version by its id (no category/price filters).
     * Returns empty if the version does not exist or its agent is not ACTIVE.
     */
    Optional<AgentCandidate> findCandidateByVersionId(UUID agentVersionId);

    /**
     * Owner (builder user id) of the agent that owns this version. Deliberately NO status
     * filter — settlement must resolve the payee even if the agent was deactivated after
     * executing the task.
     */
    Optional<UUID> findOwnerByVersionId(UUID agentVersionId);
}
