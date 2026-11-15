package com.hireai.domain.biz.task.item.repository;

import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.enums.TaskStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for a Task's Items (spec S3). Items are always read and written while the
 * caller holds the Task row lock, so the Task row serializes every change to its Items.
 */
public interface TaskItemRepository {

    /** Inserts the Items of a newly submitted Task. */
    void insertAll(TaskItems items);

    /** Saves an Item's new state (status, result, checks). */
    void update(TaskItemModel item);

    /** The Task's Items in Item order; empty for a single-deliverable Task. */
    TaskItems findByTaskId(UUID taskId);

    /** How many Items the Task has; 0 for a single-deliverable Task. */
    int countByTaskId(UUID taskId);

    /** The Items of several Tasks at once (list views); Tasks without Items are absent. */
    Map<UUID, TaskItems> findByTaskIds(Collection<UUID> taskIds);

    /** One Item by its id (a signed source link names the Item). */
    Optional<TaskItemModel> findById(UUID itemId);

    /** The statuses of every Task with an Item whose input is this SourceSnapshot (D32 retention). */
    List<TaskStatus> taskStatusesUsingSource(UUID sourceSnapshotId);
}
