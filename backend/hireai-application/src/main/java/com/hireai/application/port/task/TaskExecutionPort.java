package com.hireai.application.port.task;

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

    /** QUEUED → EXECUTING under a row lock; a no-op once the task has moved past QUEUED. */
    void markExecuting(UUID taskId);

    void markTimedOut(UUID taskId);

    void markFailed(UUID taskId);
}
