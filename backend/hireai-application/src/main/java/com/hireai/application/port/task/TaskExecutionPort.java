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
     * The dispatch to {@code agentVersionId} ran out of attempts (the DLQ listener). Under a row
     * lock it records an {@code UNREACHABLE} dispatch attempt and an {@code EXECUTION_FAILED}
     * reputation event against that Agent. Then a routed task still QUEUED fails over to another
     * Agent ({@code RoutingAppService#failOver}); a pinned task (or one already EXECUTING) ends
     * FAILED with {@code AGENT_UNREACHABLE}, a refund and a client {@code task.failed} webhook.
     * A no-op once the task has ended or is assigned to another version (a stale message for an
     * Agent the task has already moved away from).
     */
    void markFailed(UUID taskId, UUID agentVersionId);

    /**
     * The Agent declined the WorkOrder as busy (429/503): under a row lock, a task still QUEUED for
     * {@code agentVersionId} records a {@code BUSY} dispatch attempt. A routed task then fails over
     * to another Agent; a pinned task ends FAILED with {@code AGENT_BUSY}, a refund and a client
     * {@code task.failed} webhook. No reputation event either way: declining at capacity is never a
     * fault. A no-op when the task has moved on (another outcome committed first, or it is assigned
     * to another version).
     */
    void declineAsBusy(UUID taskId, UUID agentVersionId);
}
