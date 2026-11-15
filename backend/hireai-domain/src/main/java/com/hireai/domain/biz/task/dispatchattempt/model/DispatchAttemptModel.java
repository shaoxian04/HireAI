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
    /** The Agent's own words on a {@code CONTRACT_DECLINED} attempt; null otherwise (and optional then). */
    private final String declineReason;

    /** Longest decline reason kept (V42 CHECK); the rest of a longer one is dropped. */
    public static final int MAX_DECLINE_REASON_LENGTH = 500;

    private DispatchAttemptModel(UUID id, UUID taskId, UUID agentVersionId, int attemptNumber,
                                 DispatchOutcome outcome, Instant occurredAt, String declineReason) {
        this.id = id;
        this.taskId = taskId;
        this.agentVersionId = agentVersionId;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
        this.declineReason = declineReason;
    }

    /**
     * Records the outcome of the task's {@code attemptNumber}-th attempt (1-based, in the order the
     * attempts ended).
     */
    public static DispatchAttemptModel record(UUID taskId, UUID agentVersionId, int attemptNumber,
                                              DispatchOutcome outcome, Instant occurredAt) {
        if (outcome == DispatchOutcome.CONTRACT_DECLINED) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "A contract decline is recorded with contractDeclined(), which keeps the Agent's reason");
        }
        return create(taskId, agentVersionId, attemptNumber, outcome, occurredAt, null);
    }

    /**
     * The Agent refused the task's Contract (#93): the attempt after the {@code attemptsSoFar} already
     * recorded. {@code reason} is the Agent's untrusted free text: trimmed, control characters turned
     * into spaces, cut to {@value #MAX_DECLINE_REASON_LENGTH} characters; blank means no reason.
     */
    public static DispatchAttemptModel contractDeclined(UUID taskId, UUID agentVersionId, int attemptsSoFar,
                                                        String reason, Instant occurredAt) {
        return create(taskId, agentVersionId, attemptsSoFar + 1, DispatchOutcome.CONTRACT_DECLINED, occurredAt,
                normaliseReason(reason));
    }

    private static String normaliseReason(String reason) {
        if (reason == null) {
            return null;
        }
        String clean = reason.replaceAll("\\p{Cntrl}+", " ").replaceAll(" {2,}", " ").strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() <= MAX_DECLINE_REASON_LENGTH) {
            return clean;
        }
        // Never cut a surrogate pair in half.
        int end = Character.isHighSurrogate(clean.charAt(MAX_DECLINE_REASON_LENGTH - 1))
                ? MAX_DECLINE_REASON_LENGTH - 1 : MAX_DECLINE_REASON_LENGTH;
        return clean.substring(0, end).strip();
    }

    private static DispatchAttemptModel create(UUID taskId, UUID agentVersionId, int attemptNumber,
                                               DispatchOutcome outcome, Instant occurredAt, String declineReason) {
        if (taskId == null || agentVersionId == null || outcome == null || occurredAt == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "A dispatch attempt needs a task, an agent version, an outcome and a time");
        }
        if (attemptNumber < 1) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Dispatch attempt numbers start at 1; got " + attemptNumber);
        }
        return new DispatchAttemptModel(UUID.randomUUID(), taskId, agentVersionId, attemptNumber,
                outcome, occurredAt, declineReason);
    }

    /**
     * The attempt that follows the {@code attemptsSoFar} already recorded for the task: attempts are
     * numbered from 1 in the order they ended.
     */
    public static DispatchAttemptModel next(UUID taskId, UUID agentVersionId, int attemptsSoFar,
                                            DispatchOutcome outcome, Instant occurredAt) {
        return record(taskId, agentVersionId, attemptsSoFar + 1, outcome, occurredAt);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID agentVersionId() { return agentVersionId; }
    public int attemptNumber() { return attemptNumber; }
    public DispatchOutcome outcome() { return outcome; }
    public Instant occurredAt() { return occurredAt; }
    public String declineReason() { return declineReason; }
}
