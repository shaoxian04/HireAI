package com.hireai.infrastructure.repository.task.dispatchattempt;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the append-only dispatch_attempts table (V33). No setters and no update path: the
 * database triggers refuse UPDATE and DELETE. {@code gmt_create} is left to its column default.
 */
@Entity
@Table(name = "dispatch_attempts")
public class DispatchAttemptDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "agent_version_id", nullable = false)
    private UUID agentVersionId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private DispatchOutcome outcome;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected DispatchAttemptDO() {
    }

    public DispatchAttemptDO(UUID id, UUID taskId, UUID agentVersionId, int attemptNumber,
                             DispatchOutcome outcome, Instant occurredAt) {
        this.id = id;
        this.taskId = taskId;
        this.agentVersionId = agentVersionId;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getAgentVersionId() { return agentVersionId; }
    public int getAttemptNumber() { return attemptNumber; }
    public DispatchOutcome getOutcome() { return outcome; }
    public Instant getOccurredAt() { return occurredAt; }
}
