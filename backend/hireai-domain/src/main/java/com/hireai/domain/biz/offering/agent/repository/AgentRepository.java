package com.hireai.domain.biz.offering.agent.repository;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.info.AgentReputationTarget;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.reputation.info.ReputationAggregates;

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

    /**
     * Agent id + owner id for a version, in one query. Every reputation emission site needs both:
     * the agent to attach the event to, and the owner to compare against the task's client for the
     * L1 self-dealing exclusion. Same no-status-filter rule as {@link #findOwnerByVersionId}.
     */
    Optional<AgentReputationTarget> findReputationTargetByVersionId(UUID agentVersionId);

    /**
     * Reads an agent's running reputation aggregates under a row lock, so concurrent settlements
     * on the same agent serialize instead of losing one another's increment.
     */
    Optional<ReputationAggregates> lockReputationAggregates(UUID agentId);

    /**
     * Targeted update of the five reputation columns only — never a full-row save. A builder
     * publishing a new version mid-settlement writes different columns, so neither write can lose
     * the other.
     */
    void updateReputation(UUID agentId, ReputationAggregates aggregates, BigDecimal score);

    /** The aggregates as currently cached on the agent row (no lock) — for read-only breakdowns. */
    Optional<ReputationAggregates> findReputationAggregates(UUID agentId);
}
