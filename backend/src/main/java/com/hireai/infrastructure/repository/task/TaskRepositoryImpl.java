package com.hireai.infrastructure.repository.task;

import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link TaskRepository}. Maps
 * {@code TaskModel} &lt;-&gt; JPA entity and serialises the output spec via
 * {@link OutputSpecJsonMapper}.
 */
@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskJpaRepository taskJpa;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public TaskRepositoryImpl(TaskJpaRepository taskJpa, OutputSpecJsonMapper outputSpecJsonMapper) {
        this.taskJpa = taskJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @Override
    public TaskModel save(TaskModel task) {
        taskJpa.save(new TaskJpaEntity(
                task.id(), task.clientId(), task.title(), task.description(),
                task.budget().value(), outputSpecJsonMapper.toJson(task.outputSpec()),
                task.status().name(), task.createdAt()));
        return task;
    }

    @Override
    public Optional<TaskModel> findById(UUID taskId) {
        return taskJpa.findById(taskId).map(this::toModel);
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
        return new TaskModel(
                entity.getId(), entity.getClientId(), entity.getTitle(), entity.getDescription(),
                Money.of(entity.getBudget()), outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                TaskStatus.valueOf(entity.getStatus()), entity.getGmtCreate());
    }
}
