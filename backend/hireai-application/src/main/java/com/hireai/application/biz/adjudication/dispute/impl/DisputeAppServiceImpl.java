package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DisputeAppServiceImpl implements DisputeAppService {

    private static final int TIER_1 = 1;

    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ArbitrationGateway arbitrationGateway;

    @Override
    public UUID openDispute(TaskModel disputedTask, UUID raisedBy, RejectReason reasonCategory) {
        String correlationId = "dispute-" + disputedTask.id();
        DisputeModel dispute = DisputeModel.open(disputedTask.id(), raisedBy, reasonCategory, correlationId);
        disputeRepository.save(dispute);

        Optional<RulingInfo> immediate = arbitrationGateway.requestRuling(dispute, disputedTask);
        if (immediate.isPresent()) {
            settleAndResolve(dispute, immediate.get(), RulingDecidedBy.ARBITRATOR);
        } else {
            disputeRepository.save(dispute.startArbitrating());
            log.info("Dispute {} handed off for async arbitration", dispute.id());
        }
        return dispute.id();
    }

    @Override
    public void applyRuling(UUID disputeId, RulingInfo ruling) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.isResolvable()) {
            log.info("Dispute {} already {}; ruling ignored (first-ruling-wins)", disputeId, dispute.status());
            return;
        }
        settleAndResolve(dispute, ruling, RulingDecidedBy.ARBITRATOR);
    }

    @Override
    public void resolveByFallback(UUID disputeId) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.isResolvable()) {
            log.info("Dispute {} already {}; fallback ignored", disputeId, dispute.status());
            return;
        }
        Ruling fallback = new Ruling(TIER_1, RulingCategory.NOT_FULFILLED,
                "arbitration unavailable; refunded by platform fallback", RulingDecidedBy.FALLBACK, Instant.now());
        TaskModel task = lockTask(dispute.taskId());
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        taskRepository.save(task.resolveDispute(TaskResolution.REJECTED));
        disputeRepository.save(dispute.resolveByFallback(fallback));
        log.info("Dispute {} resolved by refund fallback", disputeId);
    }

    /** Records the ruling, settles deterministically by category, and resolves both task and dispute. */
    private void settleAndResolve(DisputeModel dispute, RulingInfo info, RulingDecidedBy decidedBy) {
        Ruling ruling = new Ruling(TIER_1, info.category(), info.rationale(), decidedBy, Instant.now());
        DisputeModel ruled = dispute.recordRuling(ruling);

        TaskModel task = lockTask(dispute.taskId());
        switch (info.category()) {
            case FULFILLED -> {
                UUID builderId = requireBuilder(task);
                settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.ACCEPTED));
            }
            case PARTIALLY_FULFILLED -> {
                UUID builderId = requireBuilder(task);
                settlementWriteAppService.settleSplit(task.id(), task.clientId(), builderId, task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.PARTIALLY_ACCEPTED));
            }
            case NOT_FULFILLED -> {
                settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
                taskRepository.save(task.resolveDispute(TaskResolution.REJECTED));
            }
        }
        disputeRepository.save(ruled.resolve());
    }

    private TaskModel lockTask(UUID taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }

    private UUID requireBuilder(TaskModel task) {
        return agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No builder for agent version " + task.agentVersionId()));
    }

    private DisputeModel requireDispute(UUID disputeId) {
        return disputeRepository.findById(disputeId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Dispute not found: " + disputeId));
    }
}
