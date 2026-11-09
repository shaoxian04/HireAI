package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.domain.biz.offering.agent.info.AgentReputationTarget;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import com.hireai.domain.biz.task.info.TaskRoutingView;
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

import java.util.List;
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
    public TaskRoutingView getRoutingView(UUID taskId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        // Serialise the task's stored output_spec snapshot: this is the binding contract
        // (Hard Invariant #4) that must travel with the dispatch, not the agent version's live spec.
        String outputSpecJson = outputSpecJsonMapper.toJson(task.outputSpec());
        return new TaskRoutingView(task.id(), task.title(), task.description(), task.category(),
                task.budget().value(), task.status().name(), outputSpecJson,
                taskRepository.findClientDeadline(task.id()).map(ClientDeadline::new).orElse(null));
    }
}
