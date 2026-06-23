package com.hireai.infrastructure.repository.task;

import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link TaskRepository}. Maps
 * {@code TaskModel} &lt;-&gt; JPA entity, serialises the output spec via
 * {@link OutputSpecJsonMapper}, and persists/loads the {@link TaskResultModel} child
 * through the root only.
 */
@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskJpaRepository taskJpa;
    private final TaskResultJpaRepository taskResultJpa;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public TaskRepositoryImpl(TaskJpaRepository taskJpa, TaskResultJpaRepository taskResultJpa,
                              OutputSpecJsonMapper outputSpecJsonMapper) {
        this.taskJpa = taskJpa;
        this.taskResultJpa = taskResultJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @Override
    public TaskModel save(TaskModel task) {
        taskJpa.save(new TaskJpaEntity(
                task.id(), task.clientId(), task.title(), task.description(),
                task.budget().value(), outputSpecJsonMapper.toJson(task.outputSpec()),
                task.category(), task.status().name(), task.agentVersionId(), task.createdAt(),
                task.resolution() == null ? null : task.resolution().name(),
                task.resolvedAt(), task.rejectionReason()));

        TaskResultModel result = task.result();
        if (result != null && taskResultJpa.findByTaskId(result.taskId()).isEmpty()) {
            taskResultJpa.save(new TaskResultJpaEntity(
                    result.id(), result.taskId(), result.resultPayloadJson(),
                    result.resultUrl(), result.agentStatus(), result.receivedAt()));
        }
        return task;
    }

    @Override
    public Optional<TaskModel> findById(UUID taskId) {
        return taskJpa.findById(taskId).map(this::toModel);
    }

    @Override
    public Optional<TaskModel> findByIdForUpdate(UUID taskId) {
        return taskJpa.findByIdForUpdate(taskId).map(this::toModel);
    }

    @Override
    public List<TaskModel> findByClientId(UUID clientId, TaskQuery query) {
        return taskJpa.findByClientIdOrderByGmtCreateDesc(
                        clientId, PageRequest.of(query.page(), query.size()))
                .stream()
                .map(this::toModel)
                .toList();
    }

    private TaskModel toModel(TaskJpaEntity entity) {
        TaskResultModel result = taskResultJpa.findByTaskId(entity.getId())
                .map(this::toResultModel)
                .orElse(null);
        return new TaskModel(
                entity.getId(), entity.getClientId(), entity.getTitle(), entity.getDescription(),
                Money.of(entity.getBudget()), outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getCategory(), TaskStatus.valueOf(entity.getStatus()),
                entity.getAgentVersionId(), result, entity.getGmtCreate(),
                entity.getResolution() == null ? null : TaskResolution.valueOf(entity.getResolution()),
                entity.getResolvedAt(), entity.getRejectionReason());
    }

    private TaskResultModel toResultModel(TaskResultJpaEntity entity) {
        return TaskResultModel.rehydrate(entity.getId(), entity.getTaskId(), entity.getAgentStatus(),
                entity.getResultPayload(), entity.getResultUrl(), entity.getReceivedAt());
    }
}
