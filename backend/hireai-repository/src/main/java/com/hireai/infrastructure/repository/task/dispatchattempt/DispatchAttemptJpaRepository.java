package com.hireai.infrastructure.repository.task.dispatchattempt;

import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DispatchAttemptJpaRepository extends JpaRepository<DispatchAttemptDO, UUID> {

    int countByTaskId(UUID taskId);

    /** The task's attempts in order, with the owning Agent (LEFT JOIN: soft reference, no FK). */
    @Query("""
            SELECT new com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView(
                       d.attemptNumber, v.agentId, d.agentVersionId, d.outcome, d.occurredAt, d.declineReason)
            FROM DispatchAttemptDO d
            LEFT JOIN com.hireai.infrastructure.repository.offering.agent.AgentVersionDO v
                   ON v.id = d.agentVersionId
            WHERE d.taskId = :taskId
            ORDER BY d.attemptNumber
            """)
    List<DispatchAttemptView> findViewsByTaskId(@Param("taskId") UUID taskId);

    /** Per Agent and outcome, how many attempts its versions recorded: rows of {agentId, outcome, count}. */
    @Query("""
            SELECT v.agentId, d.outcome, count(d)
            FROM DispatchAttemptDO d
            JOIN com.hireai.infrastructure.repository.offering.agent.AgentVersionDO v
              ON v.id = d.agentVersionId
            WHERE v.agentId IN :agentIds
            GROUP BY v.agentId, d.outcome
            """)
    List<Object[]> countOutcomesByAgentId(@Param("agentIds") Collection<UUID> agentIds);
}
