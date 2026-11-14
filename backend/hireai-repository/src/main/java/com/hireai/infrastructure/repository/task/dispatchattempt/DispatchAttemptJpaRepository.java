package com.hireai.infrastructure.repository.task.dispatchattempt;

import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DispatchAttemptJpaRepository extends JpaRepository<DispatchAttemptDO, UUID> {

    int countByTaskId(UUID taskId);

    /** The task's attempts in order, with the owning Agent (LEFT JOIN: soft reference, no FK). */
    @Query("""
            SELECT new com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView(
                       d.attemptNumber, v.agentId, d.agentVersionId, d.outcome, d.occurredAt)
            FROM DispatchAttemptDO d
            LEFT JOIN com.hireai.infrastructure.repository.offering.agent.AgentVersionDO v
                   ON v.id = d.agentVersionId
            WHERE d.taskId = :taskId
            ORDER BY d.attemptNumber
            """)
    List<DispatchAttemptView> findViewsByTaskId(@Param("taskId") UUID taskId);
}
