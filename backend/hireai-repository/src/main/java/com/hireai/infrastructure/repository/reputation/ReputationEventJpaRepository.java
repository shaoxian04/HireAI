package com.hireai.infrastructure.repository.reputation;

import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReputationEventJpaRepository extends JpaRepository<ReputationEventDO, UUID> {

    List<ReputationEventDO> findByAgentIdOrderByOccurredAtDesc(UUID agentId, Pageable pageable);

    boolean existsByTaskIdAndEventType(UUID taskId, ReputationEventType eventType);

    /**
     * Replays an agent's whole stream and re-derives its aggregates, split by component. Used by
     * the reconciliation path to prove the cached aggregates on the agents row agree with the
     * source of truth.
     *
     * <p>Grouped in SQL rather than by loading every row, so reconciling a high-volume agent does
     * not depend on its history fitting in memory.
     */
    @Query(value = """
            SELECT COALESCE(SUM(e.quality * e.weight) FILTER (WHERE e.event_type <> 'RATING'), 0) AS reliability_sum,
                   COUNT(*)                           FILTER (WHERE e.event_type <> 'RATING')     AS reliability_count,
                   COALESCE(SUM(e.quality * e.weight) FILTER (WHERE e.event_type =  'RATING'), 0) AS satisfaction_sum,
                   COUNT(*)                           FILTER (WHERE e.event_type =  'RATING')     AS satisfaction_count
            FROM reputation_events e
            WHERE e.agent_id = :agentId
            """, nativeQuery = true)
    AggregateRow replayAggregates(@Param("agentId") UUID agentId);

    interface AggregateRow {
        java.math.BigDecimal getReliabilitySum();
        long getReliabilityCount();
        java.math.BigDecimal getSatisfactionSum();
        long getSatisfactionCount();
    }
}
