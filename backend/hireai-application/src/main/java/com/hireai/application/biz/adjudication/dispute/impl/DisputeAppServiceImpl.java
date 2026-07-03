package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DisputeAppServiceImpl implements DisputeAppService {

    private static final int TIER_1 = 1;
    private static final int TIER_2 = 2;

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
            // Delayed settlement: an immediate (stub/sync) ruling is a PROPOSAL, not a settlement.
            recordProposedRuling(dispute, toRuling(immediate.get(), TIER_1, RulingDecidedBy.ARBITRATOR));
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
        // Arbitrator ruling is now a PROPOSAL. Escrow stays held until the client accepts/appeals.
        recordProposedRuling(dispute, toRuling(ruling, TIER_1, RulingDecidedBy.ARBITRATOR));
    }

    @Override
    public void escalate(UUID disputeId) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.isResolvable()) {
            log.info("Dispute {} is {}; escalate skipped", disputeId, dispute.status());
            return;
        }
        disputeRepository.save(dispute.escalate());
        log.info("Dispute {} escalated to ESCALATED (needs admin backstop)", disputeId);
    }

    @Override
    public void adminRule(UUID disputeId, RulingCategory category, String rationale, UUID adminId) {
        DisputeModel dispute = requireDispute(disputeId);
        DisputeStatus s = dispute.status();
        if (s != DisputeStatus.OPEN && s != DisputeStatus.ARBITRATING && s != DisputeStatus.ESCALATED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute " + disputeId + " is " + s + "; already settled — admin cannot re-rule");
        }
        log.info("Admin {} ruling dispute {} as {}", adminId, disputeId, category);
        DisputeModel ruled = dispute.recordRuling(toRuling(new RulingInfo(category, rationale), TIER_2, RulingDecidedBy.ADMINISTRATOR));
        settleFromEffective(ruled);
    }

    @Override
    public void acceptRuling(UUID disputeId, UUID clientId) {
        DisputeModel dispute = lockAndRevalidateRuled(disputeId, clientId);
        settleFromEffective(dispute);
    }

    @Override
    public void appeal(UUID disputeId, UUID clientId) {
        DisputeModel dispute = lockAndRevalidateRuled(disputeId, clientId);
        disputeRepository.save(dispute.appeal());
        log.info("Client {} appealed dispute {} to admin backstop", clientId, disputeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> staleArbitratingDisputeIds(Instant cutoff) {
        return disputeRepository.findStaleArbitratingIds(cutoff);
    }

    /**
     * Serializes the RULED→{RESOLVED|ESCALATED} transitions: takes the task pessimistic lock (the
     * same lock settlement uses) so accept/appeal/auto-accept can't both win, then re-reads and
     * re-validates the dispute under the lock. Ownership (Inv #5) is checked here too.
     */
    private DisputeModel lockAndRevalidateRuled(UUID disputeId, UUID clientId) {
        DisputeModel dispute = requireDispute(disputeId);
        if (!dispute.raisedBy().equals(clientId)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "not your dispute: " + disputeId);
        }
        lockTask(dispute.taskId()); // serialization point
        DisputeModel fresh = requireDispute(disputeId); // re-read under lock
        if (fresh.status() != DisputeStatus.RULED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute " + disputeId + " is " + fresh.status() + "; no proposed ruling to act on");
        }
        return fresh;
    }

    /** Records a ruling and moves the dispute to RULED. No money moves. */
    private void recordProposedRuling(DisputeModel dispute, Ruling ruling) {
        disputeRepository.save(dispute.recordRuling(ruling));
        log.info("Dispute {} proposed ruling {} (tier {}); awaiting client", dispute.id(), ruling.category(), ruling.tier());
    }

    private Ruling toRuling(RulingInfo info, int tier, RulingDecidedBy by) {
        return new Ruling(tier, info.category(), info.rationale(), by, Instant.now());
    }

    /** Settles escrow ONCE from the dispute's effective (highest-tier) ruling, then resolves task + dispute. */
    private void settleFromEffective(DisputeModel dispute) {
        RulingCategory category = dispute.effectiveRuling()
                .orElseThrow(() -> new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Dispute " + dispute.id() + " has no ruling to settle"))
                .category();
        TaskModel task = lockTask(dispute.taskId());
        switch (category) {
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
        disputeRepository.save(dispute.resolve());
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
