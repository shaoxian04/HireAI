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

    void markExecuting(UUID taskId);

    void markTimedOut(UUID taskId);

    void markFailed(UUID taskId);
}
