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

    List<TaskModel> findByClientId(UUID clientId, TaskQuery query);
}
