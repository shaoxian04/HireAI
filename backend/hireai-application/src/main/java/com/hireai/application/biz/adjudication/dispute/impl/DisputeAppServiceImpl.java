package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationDeliverable;
import com.hireai.application.biz.adjudication.dispute.DisputeFeeSchedule;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final DeliverySnapshotAppService deliverySnapshotAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final DisputeFeeSchedule feeSchedule;
    private final Clock clock;
    /** A Task submitted with an API key is on the API/MCP channel, where only the Client may appeal (D38). */
    private final ApiKeyTaskRepository apiKeyTaskRepository;

    @Override
    public UUID openDispute(TaskModel disputedTask, UUID raisedBy, RejectReason reasonCategory) {
        String correlationId = "dispute-" + disputedTask.id();
        DisputeModel dispute = DisputeModel.open(disputedTask.id(), raisedBy, reasonCategory, correlationId);
        disputeRepository.save(dispute);

        // The arbitrator judges the frozen DeliverySnapshot, never the Agent's link (D24).
        ArbitrationDeliverable deliverable = deliverySnapshotAppService.forArbitration(disputedTask.result()).orElse(null);
        Optional<RulingInfo> immediate = arbitrationGateway.requestRuling(dispute, disputedTask, deliverable);
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
    public void acceptRuling(UUID disputeId, UUID userId) {
        DisputeModel dispute = lockAndRevalidateRuled(disputeId);
        Set<DisputeParty> sides = requireSides(dispute, userId);
        Instant now = clock.instant();
        DisputeModel next = dispute;
        Set<DisputeParty> acting = actingSides(dispute, sides);
        for (DisputeParty side : acting) {
            next = next.accept(side, now);
        }
        if (acting.isEmpty()) {
            dispute.accept(sides.iterator().next(), now); // throws: nothing for this side to accept
        }
        disputeRepository.save(next);
        log.info("{} accepted the proposed ruling on dispute {}", acting, disputeId);
        if (next.acceptedByEverySideThatMayAppeal()) {
            settleFromEffective(next);
        }
    }

    @Override
    public void appeal(UUID disputeId, UUID userId) {
        DisputeModel dispute = lockAndRevalidateRuled(disputeId);
        Set<DisputeParty> sides = requireSides(dispute, userId);
        Set<DisputeParty> acting = actingSides(dispute, sides);
        DisputeParty side = acting.isEmpty() ? sides.iterator().next() : acting.iterator().next();
        if (side == DisputeParty.BUILDER && apiKeyTaskRepository.findApiKeyIdByTask(dispute.taskId()).isPresent()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Dispute " + disputeId + " is on a task "
                    + "submitted through the API; a Builder may appeal a ruling only on a web-channel task");
        }
        disputeRepository.save(dispute.appeal(side, clock.instant()));
        log.info("{} appealed dispute {} to the admin backstop", side, disputeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> staleArbitratingDisputeIds(Instant cutoff) {
        return disputeRepository.findStaleArbitratingIds(cutoff);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> staleRuledDisputeIds(Instant cutoff) {
        return disputeRepository.findStaleRuledIds(cutoff);
    }

    @Override
    public void autoAcceptOne(UUID disputeId) {
        DisputeModel dispute = requireDispute(disputeId);
        lockTask(dispute.taskId());
        DisputeModel fresh = requireDispute(disputeId);
        if (fresh.status() == DisputeStatus.RULED) {
            settleFromEffective(fresh);
            log.info("Auto-accepted stale proposed ruling for dispute {}", disputeId);
        }
    }

    /**
     * Serializes the RULED→{RESOLVED|ESCALATED} transitions: takes the task pessimistic lock (the
     * same lock settlement uses) so accept/appeal/auto-accept can't both win, then re-reads and
     * re-validates the dispute under the lock. Ownership (Inv #5) is checked by {@link #requireSides}.
     */
    private DisputeModel lockAndRevalidateRuled(UUID disputeId) {
        DisputeModel dispute = requireDispute(disputeId);
        lockTask(dispute.taskId()); // serialization point
        DisputeModel fresh = requireDispute(disputeId); // re-read under lock
        if (fresh.status() != DisputeStatus.RULED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Dispute " + disputeId + " is " + fresh.status() + "; no proposed ruling to act on");
        }
        return fresh;
    }

    /**
     * The side(s) {@code userId} takes in this dispute (Inv #5): the task's Client, the Builder who
     * owns the Agent that ran it, or both on a self-booked task. Anyone else is refused.
     */
    private Set<DisputeParty> requireSides(DisputeModel dispute, UUID userId) {
        TaskModel task = taskRepository.findById(dispute.taskId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + dispute.taskId()));
        Set<DisputeParty> sides = EnumSet.noneOf(DisputeParty.class);
        if (task.clientId().equals(userId)) {
            sides.add(DisputeParty.CLIENT);
        }
        if (agentRepository.findOwnerByVersionId(task.agentVersionId()).map(userId::equals).orElse(false)) {
            sides.add(DisputeParty.BUILDER);
        }
        if (sides.isEmpty()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "not your dispute: " + dispute.id());
        }
        return sides;
    }

    /** The user's sides that may still accept or appeal the proposed ruling. */
    private static Set<DisputeParty> actingSides(DisputeModel dispute, Set<DisputeParty> sides) {
        Set<DisputeParty> acting = EnumSet.noneOf(DisputeParty.class);
        acting.addAll(sides);
        acting.retainAll(dispute.partiesWhoMayAppeal());
        return acting;
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
        Optional<UUID> builder = agentRepository.findOwnerByVersionId(task.agentVersionId());
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
        // Emitted here, from the EFFECTIVE (highest-tier) ruling, so an administrator's tier-2
        // override scores the ruling that actually took effect rather than the arbitrator proposal
        // it superseded. Being complained about is not itself a failure — a builder who wins
        // records full quality and loses nothing for having been disputed (#38).
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                reputationEventFor(category));
        chargeFees(dispute, task, builder, category);
        disputeRepository.save(dispute.resolve());
    }

    /**
     * Loser pays (D43): after the settlement, the side(s) the effective ruling goes against bear the
     * arbitration cost and, if the ruling was appealed, the Appeal Fee (D42). Each is capped at 20% of
     * the Item price; the platform covers the excess. Deterministic, from the category alone (Inv #3).
     */
    private void chargeFees(DisputeModel dispute, TaskModel task, Optional<UUID> builder, RulingCategory category) {
        Set<DisputeParty> payers = EnumSet.copyOf(category.partiesItGoesAgainst());
        if (builder.isEmpty()) {
            payers.remove(DisputeParty.BUILDER); // no earnings wallet to charge: the platform covers it
        }
        UUID builderId = builder.orElse(task.clientId());
        if (feeSchedule.arbitrationCost().isPositive()) {
            settlementWriteAppService.chargeDisputeFee(dispute.id(), task.id(), task.clientId(), builderId,
                    DisputeFeeType.ARBITRATION_COST, feeSchedule.arbitrationCost(), task.budget(), payers);
        }
        if (dispute.appealedBy() != null && feeSchedule.appealFee().isPositive()) {
            settlementWriteAppService.chargeDisputeFee(dispute.id(), task.id(), task.clientId(), builderId,
                    DisputeFeeType.APPEAL_FEE, feeSchedule.appealFee(), task.budget(), payers);
        }
    }

    /**
     * Maps a ruling to what it asserts about the agent, mirroring how each already settles: a
     * partially-fulfilled ruling is a proportionate consequence, distinct from total failure.
     */
    private static ReputationEventType reputationEventFor(RulingCategory category) {
        return switch (category) {
            case FULFILLED -> ReputationEventType.DISPUTE_WON;
            case PARTIALLY_FULFILLED -> ReputationEventType.DISPUTE_PARTIAL;
            case NOT_FULFILLED -> ReputationEventType.DISPUTE_LOST;
        };
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
