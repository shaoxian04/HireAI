package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates task READ use cases. Enforces Hard Invariant #5 (server-side identity +
 * ownership): a task is only returned to the client that owns it; otherwise NOT_FOUND,
 * so existence is not leaked across clients.
 */
@Validated
public interface TaskReadAppService {

    TaskModel getForClient(@NonNull UUID taskId, @NonNull UUID clientId);

    List<TaskModel> listForClient(@NonNull UUID clientId, @NonNull TaskQuery query);
}
