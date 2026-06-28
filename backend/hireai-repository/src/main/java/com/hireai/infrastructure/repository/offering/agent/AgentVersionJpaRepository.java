package com.hireai.infrastructure.repository.offering.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for agent-version rows. Internal to infrastructure. */
public interface AgentVersionJpaRepository extends JpaRepository<AgentVersionDO, UUID> {

    /** Loads the current ACTIVE version of an agent (the routable contract). */
    Optional<AgentVersionDO> findByAgentIdAndStatus(UUID agentId, String status);

    /**
     * Bulk status transition (e.g. demote ACTIVE -> DEPRECATED) for one agent. Direct SQL UPDATE:
     * executes synchronously at call time, so during publish the prior ACTIVE row is DEPRECATED in
     * the DB BEFORE the new ACTIVE row is inserted — the partial-unique index never sees two ACTIVE
     * rows. flushAutomatically/clearAutomatically keep the persistence context consistent.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentVersionDO v SET v.status = :to WHERE v.agentId = :agentId AND v.status = :from")
    int updateStatus(@Param("agentId") UUID agentId, @Param("from") String from, @Param("to") String to);

    /**
     * One row per ACTIVE agent whose current version covers the requested category and whose
     * price is within budget. Returns the columns needed to build an AgentCandidate. Uses the
     * Postgres array-overlap operator (&&) against the GIN-indexed capability_categories.
     */
    @Query(value = """
            SELECT v.agent_id            AS agent_id,
                   v.id                  AS agent_version_id,
                   v.capability_categories AS capability_categories,
                   v.price               AS price,
                   v.webhook_url         AS webhook_url,
                   v.max_execution_seconds AS max_execution_seconds,
                   a.reputation_score    AS reputation_score,
                   v.output_spec         AS output_spec
            FROM agent_versions v
            JOIN agents a ON a.id = v.agent_id AND a.current_version_id = v.id
            WHERE a.status = 'ACTIVE'
              AND v.capability_categories && ARRAY[:category]::text[]
              AND v.price <= :maxPrice
            ORDER BY a.reputation_score DESC
            """, nativeQuery = true)
    List<AgentCandidateRow> findActiveCandidates(@Param("category") String category,
                                                 @Param("maxPrice") BigDecimal maxPrice);

    /**
     * Looks up a single ACTIVE agent version by its id. Returns the same columns as
     * {@link #findActiveCandidates} so the same row-to-AgentCandidate mapping applies.
     * Filters: agent must be ACTIVE and the version must be the current version for the agent.
     */
    @Query(value = """
            SELECT v.agent_id            AS agent_id,
                   v.id                  AS agent_version_id,
                   v.capability_categories AS capability_categories,
                   v.price               AS price,
                   v.webhook_url         AS webhook_url,
                   v.max_execution_seconds AS max_execution_seconds,
                   a.reputation_score    AS reputation_score,
                   v.output_spec         AS output_spec
            FROM agent_versions v
            JOIN agents a ON a.id = v.agent_id AND a.current_version_id = v.id
            WHERE v.id = :versionId
              AND a.status = 'ACTIVE'
            """, nativeQuery = true)
    Optional<AgentCandidateRow> findCandidateByVersionId(@Param("versionId") UUID versionId);

    /**
     * Owner id of the agent that owns this version. No status filter — settlement must
     * resolve the payee even if the agent was deactivated after executing the task.
     */
    @Query(value = """
            SELECT a.owner_id
            FROM agent_versions v
            JOIN agents a ON a.id = v.agent_id
            WHERE v.id = :versionId
            """, nativeQuery = true)
    Optional<UUID> findOwnerByVersionId(@Param("versionId") UUID versionId);

    /** Projection for the candidate query; mapped to the domain AgentCandidate in the impl. */
    interface AgentCandidateRow {
        UUID getAgentId();
        UUID getAgentVersionId();
        String[] getCapabilityCategories();
        BigDecimal getPrice();
        String getWebhookUrl();
        int getMaxExecutionSeconds();
        BigDecimal getReputationScore();
        String getOutputSpec();
    }
}
