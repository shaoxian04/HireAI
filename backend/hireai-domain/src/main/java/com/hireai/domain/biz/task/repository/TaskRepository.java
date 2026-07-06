package com.hireai.domain.biz.task.repository;

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

    /** Operational column write (unmapped on the entity). Pins the agent version for direct bookings. */
    void pinAgentVersion(UUID taskId, UUID agentVersionId);

    /*
     * Operational reliability columns — unmapped on TaskDO by design; see V24.
     */

    List<UUID> findIdsAwaitingCapacity();

    List<UUID> findIdsExecutionExpired(java.time.Instant now);

    void incrementMatchAttempts(UUID taskId);

    int matchAttempts(UUID taskId);

    Optional<UUID> findPinnedAgentVersionId(UUID taskId);
}
