package com.hireai.domain.biz.task.repository;

import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.model.TaskModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Task aggregate. One repository per aggregate root.
 * The interface lives in the domain layer and carries no framework imports; the JPA
 * implementation lives in infrastructure.
 */
public interface TaskRepository {

    TaskModel save(TaskModel task);

    Optional<TaskModel> findById(UUID taskId);

    /**
     * Loads the task taking a row-level write lock for the duration of the current
     * transaction. Used by the review/settlement path to serialize concurrent
     * accept/reject attempts: the second transaction blocks here, then re-reads the
     * already-RESOLVED row and fails the domain state guard instead of double-settling.
     */
    Optional<TaskModel> findByIdForUpdate(UUID taskId);

    List<TaskModel> findByClientId(UUID clientId, TaskQuery query);

    /** Operational column write (unmapped on the entity — see plan Global Constraints). */
    void stampExecutionDeadline(UUID taskId, java.time.Instant deadline);

    /** The execution deadline stamped at the latest assignment, failover or rework; empty if none. */
    Optional<java.time.Instant> findExecutionDeadline(UUID taskId);

    /**
     * Operational column write (unmapped on the entity): when the Client's review window closes,
     * set once as the task enters PENDING_REVIEW (#94).
     */
    void stampReviewDeadline(UUID taskId, java.time.Instant deadline);

    /**
     * The same Client's other Tasks, submitted before {@code taskId}, with exactly this title and
     * description: the outside data the duplicate-brief Finding reads (spec D7). Oldest first.
     */
    /**
     * Operational column write (unmapped on the entity): why a result awaits review although no
     * check rejected it, {@code ANOMALY} or {@code UNDECIDED}, stamped as it enters PENDING_REVIEW.
     */
    void stampReviewReason(UUID taskId, String reason);

    /** The task's review reason; empty for a result every check passed. */
    Optional<String> findReviewReason(UUID taskId);

    List<UUID> findEarlierTaskIdsWithSameBrief(UUID taskId, UUID clientId, String title, String description);

    /** Operational column write (unmapped on the entity): the platform accepted on the Client's behalf. */
    void markReviewAutoAccepted(UUID taskId);

    /** The task's review window; empty when it never had one (an API task, or one never reviewed). */
    Optional<ReviewWindowState> findReviewWindow(UUID taskId);

    /** Ids still PENDING_REVIEW whose review window closed at or before {@code now}. */
    List<UUID> findIdsReviewExpired(java.time.Instant now);

    /**
     * Operational column write (unmapped on the entity): the Client's deadline, set once at submit
     * when the Client gave one (#83).
     */
    void stampClientDeadline(UUID taskId, java.time.Instant clientDeadline);

    /** The Client's deadline stamped at submit; empty when the Client gave none. */
    Optional<java.time.Instant> findClientDeadline(UUID taskId);

    /** Operational column write (unmapped on the entity). Pins the agent version for direct bookings. */
    void pinAgentVersion(UUID taskId, UUID agentVersionId);

    /*
     * Operational reliability columns — unmapped on TaskDO by design; see V24.
     */

    List<UUID> findIdsAwaitingAgent();

    /** Ids still SUBMITTED (never routed) whose task was created before {@code cutoff}. */
    List<UUID> findIdsSubmittedBefore(java.time.Instant cutoff);

    List<UUID> findIdsExecutionExpired(java.time.Instant now);

    void incrementMatchAttempts(UUID taskId);

    int matchAttempts(UUID taskId);

    Optional<UUID> findPinnedAgentVersionId(UUID taskId);
}
