package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DisputeReadAppServiceImpl implements DisputeReadAppService {

    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;

    public DisputeReadAppServiceImpl(DisputeRepository disputeRepository,
                                     TaskRepository taskRepository,
                                     AgentRepository agentRepository) {
        this.disputeRepository = disputeRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeModel getOutcomeForUser(UUID taskId, UUID currentUserId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> notFound(taskId));
        if (!isParticipant(task, currentUserId)) {
            throw notFound(taskId);
        }
        return disputeRepository.findByTaskId(taskId)
                .orElseThrow(() -> notFound(taskId));
    }

    private boolean isParticipant(TaskModel task, UUID currentUserId) {
        if (task.clientId().equals(currentUserId)) {
            return true;
        }
        return agentRepository.findOwnerByVersionId(task.agentVersionId())
                .map(builderId -> builderId.equals(currentUserId))
                .orElse(false);
    }

    private DomainException notFound(UUID taskId) {
        return new DomainException(ResultCode.NOT_FOUND, "Dispute not found for task: " + taskId);
    }
}
