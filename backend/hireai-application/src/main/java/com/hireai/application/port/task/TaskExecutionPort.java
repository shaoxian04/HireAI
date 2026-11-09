package com.hireai.application.port.task;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;

import java.util.UUID;

/**
 * Application port exposing the execution-side task status transitions the dispatch
 * pipeline (Plan 2) drives, without coupling it to the Task aggregate's concrete app
 * services. Plan 3 implements this over {@code TaskWriteAppService}:
 * {@code markExecuting} on successful dispatch (QUEUED→EXECUTING), {@code markTimedOut}
 * and {@code markFailed} on DLQ/exhausted retries.
 */
public interface TaskExecutionPort {

    /**
     * Row-locked pre-send check for the dispatch consumer: {@code true} only when the task is
     * still QUEUED and assigned to {@code agentVersionId}. A message for a task that already
     * ended (refunded, timed out, failed) or that already has a result is dropped, so the Agent
     * never receives a WorkOrder for work it will not be paid for. A missing task is
     * {@code false}.
     */
    boolean isAwaitingDispatch(UUID taskId, UUID agentVersionId);

    /**
     * Appends one row to the task's dispatch history (#75) under the task row lock, numbered after
     * the attempts already recorded. The consumer records {@code ACCEPTED} after a successful send;
     * {@link #markFailed} records {@code UNREACHABLE} itself.
     */
    void recordDispatchAttempt(UUID taskId, UUID agentVersionId, DispatchOutcome outcome);

    /** QUEUED → EXECUTING under a row lock; a no-op once the task has moved past QUEUED. */
    void markExecuting(UUID taskId);

    void markTimedOut(UUID taskId);

    /**
     * The dispatch ran out of attempts (the DLQ listener): QUEUED/EXECUTING → FAILED under a row
     * lock, with a refund, an {@code EXECUTION_FAILED} reputation event, a client
     * {@code task.failed} webhook and an {@code UNREACHABLE} dispatch attempt. A no-op once the
     * task has already ended.
     */
    void markFailed(UUID taskId);

    /**
     * The Agent declined the WorkOrder as busy (429/503): under a row lock, a task still QUEUED for
     * {@code agentVersionId} records a {@code BUSY} dispatch attempt and ends FAILED with
     * {@code AGENT_BUSY}, a refund and a client {@code task.failed} webhook. No reputation event:
     * declining at capacity is never a fault. A no-op when the task has moved on (another outcome
     * committed first, or it is assigned to another version).
     *
     * <p>Routed tasks end the same way for now; routed failover (#78) re-routes them instead.
     */
    void declineAsBusy(UUID taskId, UUID agentVersionId);
}
