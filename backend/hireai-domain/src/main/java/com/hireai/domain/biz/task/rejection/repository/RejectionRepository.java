package com.hireai.domain.biz.task.rejection.repository;

import com.hireai.domain.biz.task.rejection.model.RejectionModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rejection rounds (#95), one row per A/B/C rejection of a task. Callers hold the task's row lock
 * while they change a round, so the task row is the single serialization point (as for review).
 */
public interface RejectionRepository {

    /** Inserts a new round or updates an existing one. */
    void save(RejectionModel rejection);

    /** The task's most recent round, if it was ever rejected for A/B/C. */
    Optional<RejectionModel> findLatestByTaskId(UUID taskId);

    /** All of a task's rounds, oldest first. */
    List<RejectionModel> findByTaskId(UUID taskId);

    /** Tasks whose latest round still awaits the Agent and whose response window closed by {@code now}. */
    List<UUID> findTaskIdsResponseExpired(Instant now);

    /** Tasks whose latest round awaits the Client's decision and whose decision window closed by {@code now}. */
    List<UUID> findTaskIdsDecisionExpired(Instant now);

    /**
     * Tasks whose latest round still awaits the Agent, whose rejection notice was not delivered yet,
     * fewer than {@code maxAttempts} times tried, and whose response window is still open at {@code now}.
     */
    List<UUID> findTaskIdsNoticePending(Instant now, int maxAttempts);

    /** Records one delivery attempt of a round's notice; {@code deliveredAt} is null when it failed. */
    void recordNoticeAttempt(UUID rejectionId, Instant deliveredAt);

    /** Keeps the result a revision replaced on the round it answered, as evidence for later rounds. */
    void recordReplacedResult(UUID rejectionId, String resultPayload, String resultUrl);
}
