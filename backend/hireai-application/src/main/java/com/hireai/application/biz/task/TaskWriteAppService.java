package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates task WRITE use cases. The submit use case enforces Hard Invariant #1
 * (escrow before execution): the task is persisted and its budget frozen in the SAME
 * transaction, so a failed freeze rolls the task back — there is no task without a
 * successful escrow freeze. Returns only the task id; callers re-read via the read service.
 * The routing transitions ({@link #assignAndQueue}, {@link #markAwaitingCapacity}) are
 * driven by the routing module after a match decision.
 */
@Validated
public interface TaskWriteAppService {

    UUID submit(@NonNull TaskSubmitInfo taskSubmitInfo);

    /** Direct booking: identical atomic submit+freeze, but routing is pinned to agentVersionId. */
    UUID submitDirectlyBooked(@NonNull TaskSubmitInfo taskSubmitInfo, @NonNull UUID agentVersionId);

    /**
     * SUBMITTED/AWAITING_CAPACITY -> QUEUED, and stamps the execution deadline
     * (assignment time + agent maxExecutionSeconds + grace) used by the timeout sweeper.
     */
    void assignAndQueue(@NonNull UUID taskId, @NonNull UUID agentVersionId, @NonNull Instant executionDeadline);

    void markAwaitingCapacity(@NonNull UUID taskId);
}
