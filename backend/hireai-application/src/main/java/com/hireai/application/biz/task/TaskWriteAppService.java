package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.enums.FailureReason;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates task WRITE use cases. The submit use case enforces Hard Invariant #1
 * (escrow before execution): the task is persisted and its budget frozen in the SAME
 * transaction, so a failed freeze rolls the task back — there is no task without a
 * successful escrow freeze. Returns only the task id; callers re-read via the read service.
 * The routing transitions ({@link #assignAndQueue}, {@link #markAwaitingAgent}) are
 * driven by the routing module after a match decision.
 */
@Validated
public interface TaskWriteAppService {

    UUID submit(@NonNull TaskSubmitInfo taskSubmitInfo);

    /** Direct booking: identical atomic submit+freeze, but routing is pinned to agentVersionId. */
    UUID submitDirectlyBooked(@NonNull TaskSubmitInfo taskSubmitInfo, @NonNull UUID agentVersionId);

    /**
     * SUBMITTED/AWAITING_AGENT -> QUEUED, and stamps the execution deadline
     * (assignment time + agent maxExecutionSeconds + grace) used by the timeout sweeper.
     */
    void assignAndQueue(@NonNull UUID taskId, @NonNull UUID agentVersionId, @NonNull Instant executionDeadline);

    void markAwaitingAgent(@NonNull UUID taskId);

    /** Re-match bookkeeping: increments and returns the task's match_attempts counter. */
    int registerMatchAttempt(@NonNull UUID taskId);

    /**
     * Re-match exhaustion: AWAITING_AGENT -> CANCELLED + FULL escrow refund (recorded settlement,
     * Hard Invariants #1/#2). Status-guarded no-op if the task already left AWAITING_AGENT.
     */
    void cancelAwaitingAgentWithRefund(@NonNull UUID taskId);

    /**
     * SUBMITTED-sweeper exhaustion: SUBMITTED -> CANCELLED + FULL escrow refund + client webhook.
     * Row-locked; status-guarded no-op if routing moved the task on in the meantime.
     */
    void cancelSubmittedWithRefund(@NonNull UUID taskId);

    /**
     * Pinned fail-fast (#77): the direct booking's Agent is no longer ACTIVE or its pinned version
     * is no longer current, so SUBMITTED/AWAITING_AGENT -> CANCELLED with AGENT_UNAVAILABLE, a FULL
     * escrow refund, a client webhook and an UNAVAILABLE dispatch attempt. No reputation event: a
     * Builder deactivating or publishing is not at fault. Runs in its own transaction (it is called
     * from routing, which runs after the submit commit); row-locked and status-guarded, so a task
     * that has already moved on is left alone.
     */
    void cancelPinnedAsUnavailable(@NonNull UUID taskId, @NonNull UUID agentVersionId);

    /**
     * Routed failover (#78): QUEUED -> QUEUED assigned to {@code newAgentVersionId}, with a new
     * execution deadline. Joins the caller's row-locked transaction (MANDATORY).
     */
    void reassign(@NonNull UUID taskId, @NonNull UUID newAgentVersionId, @NonNull Instant executionDeadline);


    /**
     * A dispatch failure before the Agent started ended the task (#58): QUEUED/EXECUTING -> FAILED
     * with {@code reason}, a FULL escrow refund and a client webhook. Joins the caller's row-locked
     * transaction (MANDATORY); the caller has already checked the task is still in flight on the
     * failing version.
     */
    void failWithRefund(@NonNull UUID taskId, @NonNull FailureReason reason);

    /**
     * A pinned task found unavailable just before a send (#58): the same fail-fast end as
     * {@link #cancelPinnedAsUnavailable}, from QUEUED, inside the dispatch path's row-locked
     * transaction (MANDATORY).
     */
    void cancelUnavailableAtDispatch(@NonNull UUID taskId, @NonNull UUID agentVersionId);

    /**
     * Appends one row to the task's dispatch history (#75), numbered after the attempts already
     * recorded. Joins the caller's transaction (MANDATORY), which must hold the task row lock: that
     * lock serialises every writer of the history, so the numbering cannot race.
     */
    void appendDispatchAttempt(@NonNull UUID taskId, @NonNull UUID agentVersionId, @NonNull DispatchOutcome outcome);
}
