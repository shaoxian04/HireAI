package com.hireai.infrastructure.repository.offering.agent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for agent rows. Internal to infrastructure. */
public interface AgentJpaRepository extends JpaRepository<AgentDO, UUID> {

    List<AgentDO> findByOwnerIdOrderByGmtCreateDesc(UUID ownerId, Pageable pageable);

    /**
     * Reads the running aggregates under a row lock so concurrent settlements on the same agent
     * serialize rather than losing one another's increment.
     */
    @Query(value = """
            SELECT a.reliability_sum    AS reliability_sum,
                   a.reliability_count  AS reliability_count,
                   a.satisfaction_sum   AS satisfaction_sum,
                   a.satisfaction_count AS satisfaction_count
            FROM agents a
            WHERE a.id = :agentId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<ReputationAggregateRow> lockReputationAggregates(@Param("agentId") UUID agentId);

    @Query(value = """
            SELECT a.reliability_sum    AS reliability_sum,
                   a.reliability_count  AS reliability_count,
                   a.satisfaction_sum   AS satisfaction_sum,
                   a.satisfaction_count AS satisfaction_count
            FROM agents a
            WHERE a.id = :agentId
            """, nativeQuery = true)
    Optional<ReputationAggregateRow> findReputationAggregates(@Param("agentId") UUID agentId);

    /**
     * Targeted update of the reputation columns only. Deliberately NOT a full-row save: a builder
     * publishing a new version mid-settlement writes current_version_id, and a full-row save from
     * either side would clobber the other's work.
     */
    @Modifying
    @Query(value = """
            UPDATE agents
            SET reliability_sum    = :reliabilitySum,
                reliability_count  = :reliabilityCount,
                satisfaction_sum   = :satisfactionSum,
                satisfaction_count = :satisfactionCount,
                reputation_score   = :score
            WHERE id = :agentId
            """, nativeQuery = true)
    int updateReputation(@Param("agentId") UUID agentId,
                         @Param("reliabilitySum") BigDecimal reliabilitySum,
                         @Param("reliabilityCount") long reliabilityCount,
                         @Param("satisfactionSum") BigDecimal satisfactionSum,
                         @Param("satisfactionCount") long satisfactionCount,
                         @Param("score") BigDecimal score);

    interface ReputationAggregateRow {
        BigDecimal getReliabilitySum();
        long getReliabilityCount();
        BigDecimal getSatisfactionSum();
        long getSatisfactionCount();
    }
}
