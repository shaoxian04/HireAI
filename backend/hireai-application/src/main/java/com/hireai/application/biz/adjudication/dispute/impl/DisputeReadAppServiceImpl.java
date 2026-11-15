package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeFeeSchedule;
import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.dispute.view.DisputeOutcomeView;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.ledger.settlement.repository.DisputeFeeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.rejection.repository.RejectionRepository;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DisputeReadAppServiceImpl implements DisputeReadAppService {

    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final DisputeQueryPort disputeQueryPort;
    private final DisputeFeeRepository disputeFeeRepository;
    private final RejectionRepository rejectionRepository;
    private final DisputeFeeSchedule feeSchedule;

    public DisputeReadAppServiceImpl(DisputeRepository disputeRepository,
                                     TaskRepository taskRepository,
                                     AgentRepository agentRepository,
                                     DisputeQueryPort disputeQueryPort,
                                     DisputeFeeRepository disputeFeeRepository,
                                     RejectionRepository rejectionRepository,
                                     DisputeFeeSchedule feeSchedule) {
        this.disputeRepository = disputeRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.disputeQueryPort = disputeQueryPort;
        this.disputeFeeRepository = disputeFeeRepository;
        this.rejectionRepository = rejectionRepository;
        this.feeSchedule = feeSchedule;
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeOutcomeView getOutcomeForUser(UUID taskId, UUID currentUserId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> notFound(taskId));
        Set<DisputeParty> sides = sidesOf(task, currentUserId);
        if (sides.isEmpty()) {
            throw notFound(taskId);
        }
        DisputeModel dispute = disputeRepository.findByTaskId(taskId)
                .orElseThrow(() -> notFound(taskId));
        return view(dispute, task, sides);
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeOutcomeView getOutcomeByDispute(UUID disputeId, UUID currentUserId) {
        DisputeModel dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> notFoundById(disputeId));
        TaskModel task = taskRepository.findById(dispute.taskId())
                .orElseThrow(() -> notFoundById(disputeId));
        Set<DisputeParty> sides = sidesOf(task, currentUserId);
        if (sides.isEmpty()) {
            throw notFoundById(disputeId);
        }
        return view(dispute, task, sides);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeMineRow> myDisputes(UUID clientId) {
        return disputeQueryPort.findDisputesForClient(clientId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeMineRow> builderDisputes(UUID builderId) {
        return disputeQueryPort.findDisputesForBuilder(builderId);
    }

    private DisputeOutcomeView view(DisputeModel dispute, TaskModel task, Set<DisputeParty> sides) {
        Instant appealDeadline = dispute.status() == DisputeStatus.RULED
                ? dispute.effectiveRuling().map(Ruling::decidedAt).map(t -> t.plus(feeSchedule.appealWindow()))
                        .orElse(null)
                : null;
        // The round that led to arbitration: the Agent stood by its result (D20).
        Optional<RejectionModel> round = rejectionRepository.findLatestByTaskId(task.id());
        return new DisputeOutcomeView(dispute, sides, dispute.partiesWhoMayAppeal(), appealDeadline,
                feeSchedule.arbitrationCostFor(task.budget()), feeSchedule.appealFeeFor(task.budget()),
                disputeFeeRepository.findByDisputeId(dispute.id()),
                round.map(RejectionModel::reason).orElse(task.rejectionReason()),
                round.map(RejectionModel::response).orElse(null),
                round.map(RejectionModel::responseReason).orElse(null),
                round.map(RejectionModel::responseSilent).orElse(false));
    }

    private Set<DisputeParty> sidesOf(TaskModel task, UUID currentUserId) {
        Set<DisputeParty> sides = EnumSet.noneOf(DisputeParty.class);
        if (task.clientId().equals(currentUserId)) {
            sides.add(DisputeParty.CLIENT);
        }
        if (agentRepository.findOwnerByVersionId(task.agentVersionId())
                .map(builderId -> builderId.equals(currentUserId))
                .orElse(false)) {
            sides.add(DisputeParty.BUILDER);
        }
        return sides;
    }

    private DomainException notFound(UUID taskId) {
        return new DomainException(ResultCode.NOT_FOUND, "Dispute not found for task: " + taskId);
    }

    private DomainException notFoundById(UUID disputeId) {
        return new DomainException(ResultCode.NOT_FOUND, "Dispute not found: " + disputeId);
    }
}
