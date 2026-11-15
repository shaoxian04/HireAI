package com.hireai.infrastructure.repository.task;

import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.application.biz.acceptance.AcceptanceJsonMapper;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
    private final AcceptanceJsonMapper acceptanceJsonMapper;

    public TaskRepositoryImpl(TaskJpaRepository taskJpa, TaskResultJpaRepository taskResultJpa,
                              OutputSpecJsonMapper outputSpecJsonMapper, AcceptanceJsonMapper acceptanceJsonMapper) {
        this.taskJpa = taskJpa;
        this.taskResultJpa = taskResultJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
        this.acceptanceJsonMapper = acceptanceJsonMapper;
    }

    @Override
    public TaskModel save(TaskModel task) {
        taskJpa.save(new TaskDO(
                task.id(), task.clientId(), task.title(), task.description(),
                task.budget().value(), outputSpecJsonMapper.toJson(task.outputSpec()),
                task.contract() == null ? null : acceptanceJsonMapper.toJson(task.contract()),
                task.category(), task.status().name(), task.agentVersionId(), task.createdAt(),
                task.resolution() == null ? null : task.resolution().name(),
                task.resolvedAt(), task.rejectionReason(),
                task.rejectReasonCategory() == null ? null : task.rejectReasonCategory().name(),
                task.failureReason() == null ? null : task.failureReason().name()));

        TaskResultModel result = task.result();
        if (result != null) {
            Optional<TaskResultDO> stored = taskResultJpa.findByTaskId(result.taskId());
            if (stored.isEmpty()) {
                taskResultJpa.save(new TaskResultDO(
                        result.id(), result.taskId(), result.resultPayloadJson(),
                        result.resultUrl(), result.agentStatus(), result.receivedAt()));
            } else if (!stored.get().getId().equals(result.id())) {
                // A revision (#95) is a new result for a task that already has one: it replaces it.
                taskResultJpa.replaceResult(result.taskId(), result.resultPayloadJson(), result.resultUrl(),
                        result.agentStatus(), result.receivedAt());
            }
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

    @Override
    public void stampExecutionDeadline(UUID taskId, Instant deadline) {
        taskJpa.stampExecutionDeadline(taskId, deadline);
    }

    @Override
    public void stampReviewDeadline(UUID taskId, Instant deadline) {
        taskJpa.stampReviewDeadline(taskId, deadline);
    }

    @Override
    public void markReviewAutoAccepted(UUID taskId) {
        taskJpa.markReviewAutoAccepted(taskId);
    }

    @Override
    public void stampReviewReason(UUID taskId, String reason) {
        taskJpa.stampReviewReason(taskId, reason);
    }

    @Override
    public Optional<String> findReviewReason(UUID taskId) {
        return taskJpa.findReviewReason(taskId);
    }

    @Override
    public List<UUID> findEarlierTaskIdsWithSameBrief(UUID taskId, UUID clientId, String title, String description) {
        return taskJpa.findEarlierIdsWithSameBrief(taskId, clientId, title, description);
    }

    @Override
    public Optional<ReviewWindowState> findReviewWindow(UUID taskId) {
        return taskJpa.findReviewDeadline(taskId)
                .map(deadline -> new ReviewWindowState(deadline, taskJpa.isReviewAutoAccepted(taskId)));
    }

    @Override
    public List<UUID> findIdsReviewExpired(Instant now) {
        return taskJpa.findIdsReviewExpired(now);
    }

    @Override
    public void stampClientDeadline(UUID taskId, Instant clientDeadline) {
        taskJpa.stampClientDeadline(taskId, clientDeadline);
    }

    @Override
    public Optional<Instant> findClientDeadline(UUID taskId) {
        return taskJpa.findClientDeadline(taskId);
    }

    @Override
    public void pinAgentVersion(UUID taskId, UUID agentVersionId) {
        taskJpa.pinAgentVersion(taskId, agentVersionId);
    }

    @Override
    public List<UUID> findIdsAwaitingAgent() {
        return taskJpa.findIdsAwaitingAgent();
    }

    @Override
    public List<UUID> findIdsSubmittedBefore(Instant cutoff) {
        return taskJpa.findIdsSubmittedBefore(cutoff);
    }

    @Override
    public List<UUID> findIdsExecutionExpired(Instant now) {
        return taskJpa.findIdsExecutionExpired(now);
    }

    @Override
    public void incrementMatchAttempts(UUID taskId) {
        taskJpa.incrementMatchAttempts(taskId);
    }

    @Override
    public int matchAttempts(UUID taskId) {
        return taskJpa.findMatchAttempts(taskId);
    }

    @Override
    public Optional<UUID> findPinnedAgentVersionId(UUID taskId) {
        return taskJpa.findPinnedAgentVersionId(taskId);
    }

    private TaskModel toModel(TaskDO entity) {
        TaskResultModel result = taskResultJpa.findByTaskId(entity.getId())
                .map(this::toResultModel)
                .orElse(null);
        return new TaskModel(
                entity.getId(), entity.getClientId(), entity.getTitle(), entity.getDescription(),
                Money.of(entity.getBudget()), outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getContract() == null ? null : acceptanceJsonMapper.contractFromJson(entity.getContract()),
                entity.getCategory(), TaskStatus.valueOf(entity.getStatus()),
                entity.getAgentVersionId(), result, entity.getGmtCreate(),
                entity.getResolution() == null ? null : TaskResolution.valueOf(entity.getResolution()),
                entity.getResolvedAt(), entity.getRejectionReason(),
                entity.getRejectReasonCategory() == null ? null : RejectReason.valueOf(entity.getRejectReasonCategory()),
                entity.getFailureReason() == null ? null : FailureReason.valueOf(entity.getFailureReason()));
    }

    private TaskResultModel toResultModel(TaskResultDO entity) {
        return TaskResultModel.rehydrate(entity.getId(), entity.getTaskId(), entity.getAgentStatus(),
                entity.getResultPayload(), entity.getResultUrl(), entity.getReceivedAt());
    }
}
