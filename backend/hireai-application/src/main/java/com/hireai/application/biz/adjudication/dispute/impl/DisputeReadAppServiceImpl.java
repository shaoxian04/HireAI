package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DisputeReadAppServiceImpl implements DisputeReadAppService {

    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final DisputeQueryPort disputeQueryPort;

    public DisputeReadAppServiceImpl(DisputeRepository disputeRepository,
                                     TaskRepository taskRepository,
                                     AgentRepository agentRepository,
                                     DisputeQueryPort disputeQueryPort) {
        this.disputeRepository = disputeRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.disputeQueryPort = disputeQueryPort;
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

    @Override
    @Transactional(readOnly = true)
    public DisputeModel getOutcomeByDispute(UUID disputeId, UUID currentUserId) {
        DisputeModel dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> notFoundById(disputeId));
        TaskModel task = taskRepository.findById(dispute.taskId())
                .orElseThrow(() -> notFoundById(disputeId));
        if (!isParticipant(task, currentUserId)) {
            throw notFoundById(disputeId);
        }
        return dispute;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeMineRow> myDisputes(UUID clientId) {
        return disputeQueryPort.findDisputesForClient(clientId);
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

    private DomainException notFoundById(UUID disputeId) {
        return new DomainException(ResultCode.NOT_FOUND, "Dispute not found: " + disputeId);
    }
}
