package com.hireai.domain.biz.task.dispatchattempt.model;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row of a task's append-only dispatch history (#75): which Agent version the
 * platform tried, the attempt's position in the task's history, and how it ended. There is no
 * mutating behaviour: the database refuses UPDATE and DELETE (V33), as for the ledger and the
 * reputation stream.
 *
 * <p>{@code taskId} and {@code agentVersionId} are soft references (no FK), so the history outlives
 * any cleanup of the rows it points at.
 */
public final class DispatchAttemptModel {

    private final UUID id;
    private final UUID taskId;
    private final UUID agentVersionId;
    private final int attemptNumber;
    private final DispatchOutcome outcome;
    private final Instant occurredAt;

    private DispatchAttemptModel(UUID id, UUID taskId, UUID agentVersionId, int attemptNumber,
                                 DispatchOutcome outcome, Instant occurredAt) {
        this.id = id;
        this.taskId = taskId;
        this.agentVersionId = agentVersionId;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
    }

    /**
     * Records the outcome of the task's {@code attemptNumber}-th attempt (1-based, in the order the
     * attempts ended).
     */
    public static DispatchAttemptModel record(UUID taskId, UUID agentVersionId, int attemptNumber,
                                              DispatchOutcome outcome, Instant occurredAt) {
        if (taskId == null || agentVersionId == null || outcome == null || occurredAt == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "A dispatch attempt needs a task, an agent version, an outcome and a time");
        }
        if (attemptNumber < 1) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Dispatch attempt numbers start at 1; got " + attemptNumber);
        }
        return new DispatchAttemptModel(UUID.randomUUID(), taskId, agentVersionId, attemptNumber,
                outcome, occurredAt);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID agentVersionId() { return agentVersionId; }
    public int attemptNumber() { return attemptNumber; }
    public DispatchOutcome outcome() { return outcome; }
    public Instant occurredAt() { return occurredAt; }
}
