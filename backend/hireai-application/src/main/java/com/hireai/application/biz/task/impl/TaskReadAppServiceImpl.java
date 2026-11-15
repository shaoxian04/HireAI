package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;
import com.hireai.domain.biz.offering.agent.info.AgentReputationTarget;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.ClientDeadline;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.hireai.domain.biz.task.item.info.DispatchItem;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskReadAppServiceImpl implements TaskReadAppService {

    private final TaskRepository taskRepository;
    private final OutputSpecJsonMapper outputSpecJsonMapper;
    private final DispatchAttemptRepository dispatchAttemptRepository;
    private final AgentRepository agentRepository;
    private final TaskItemRepository taskItemRepository;
    private final DeliverySnapshotAppService deliverySnapshotAppService;
    private final TemplateReadAppService templateReadAppService;

    @Override
    public Optional<String> findFailureDetail(TaskModel task) {
        boolean noMatch = task.status() == TaskStatus.AWAITING_AGENT
                || task.failureReason() == FailureReason.NO_ELIGIBLE_AGENT;
        return noMatch ? taskRepository.findFailureDetail(task.id()) : Optional.empty();
    }

    @Override
    public Optional<VersionFallback> findTemplateFallback(TaskModel task) {
        return task.contract() == null ? Optional.empty() : templateReadAppService.fallbackOf(task.contract());
    }

    @Override
    public Optional<UUID> findBookedAgentId(TaskModel task) {
        UUID versionId = task.agentVersionId() != null
                ? task.agentVersionId()
                : taskRepository.findPinnedAgentVersionId(task.id()).orElse(null);
        if (versionId == null) {
            return Optional.empty();
        }
        // No status filter: the Agent may have been deactivated since, which is often why it failed.
        return agentRepository.findReputationTargetByVersionId(versionId)
                .map(AgentReputationTarget::agentId);
    }

    @Override
    public Optional<ReviewWindowState> findReviewWindow(TaskModel task) {
        return taskRepository.findReviewWindow(task.id());
    }

    @Override
    public List<DispatchAttemptView> listDispatchAttempts(UUID taskId, UUID clientId) {
        getForClient(taskId, clientId); // ownership guard (Hard Invariant #5): NOT_FOUND for others
        return dispatchAttemptRepository.findByTaskId(taskId);
    }

    @Override
    public TaskModel getForClient(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

    @Override
    public Optional<DeliverySnapshot> findDeliverySnapshot(TaskResultModel result) {
        return deliverySnapshotAppService.findForResult(result);
    }

    @Override
    public FrozenDeliverable getDeliverable(UUID taskId, UUID clientId) {
        TaskResultModel result = getResult(taskId, clientId);
        DeliverySnapshot snapshot = deliverySnapshotAppService.findForResult(result)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No delivered file for task: " + taskId));
        byte[] content = deliverySnapshotAppService.content(snapshot)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "The delivered file for task " + taskId + " is no longer kept"));
        return new FrozenDeliverable(snapshot, content);
    }

    @Override
    public TaskResultModel getResult(UUID taskId, UUID clientId) {
        TaskModel task = getForClient(taskId, clientId);
        TaskResultModel result = task.result();
        if (result == null) {
            throw new DomainException(ResultCode.NOT_FOUND, "No result for task: " + taskId);
        }
        return result;
    }

    @Override
    public List<TaskModel> listForClient(UUID clientId, TaskQuery query) {
        return taskRepository.findByClientId(clientId, query);
    }

    @Override
    public List<DispatchItem> workOrderItems(UUID taskId) {
        return taskItemRepository.findByTaskId(taskId).all().stream()
                .map(item -> new DispatchItem(item.itemNo(), item.input()))
                .toList();
    }

    @Override
    public TaskItems findItems(TaskModel task) {
        return taskItemRepository.findByTaskId(task.id());
    }

    @Override
    public Map<UUID, TaskItems> findItems(Collection<UUID> taskIds) {
        return taskIds.isEmpty() ? Map.of() : taskItemRepository.findByTaskIds(taskIds);
    }

    @Override
    public TaskRoutingView getRoutingView(UUID taskId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        // Serialise the task's stored output_spec snapshot: this is the binding contract
        // (Hard Invariant #4) that must travel with the dispatch, not the agent version's live spec.
        String outputSpecJson = outputSpecJsonMapper.toJson(task.outputSpec());
        // Matching compares an Agent's price with what one Item pays: a multi-Item Task's budget of
        // record is the per-Item price × the Item count (spec S3), and an Offer's price is per Item.
        int itemCount = taskItemRepository.countByTaskId(task.id());
        BigDecimal budgetPerItem = itemCount == 0 ? task.budget().value()
                : task.budget().value().divide(BigDecimal.valueOf(itemCount), 2, RoundingMode.UNNECESSARY);
        return new TaskRoutingView(task.id(), task.title(), task.description(), task.category(),
                budgetPerItem, task.status().name(), outputSpecJson,
                taskRepository.findClientDeadline(task.id()).map(ClientDeadline::new).orElse(null),
                // A task from before V39 has no Contract: its output spec's equivalent (D39) is it.
                task.contract() != null ? task.contract() : task.outputSpec().toContract());
    }
}
