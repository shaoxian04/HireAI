package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskReviewAppServiceImpl implements TaskReviewAppService {

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final DisputeAppService disputeAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    /** The app's Clock bean (ClockConfig); tests set it to pick the moment a review window closes. */
    private final Clock clock;

    @Override
    public UUID accept(UUID taskId, UUID clientId) {
        TaskModel task = loadOwned(taskId, clientId);
        settleAccepted(task); // state guard: PENDING_REVIEW (caller passed the validation gate); exactly-once
        log.info("Task {} accepted by client {}", taskId, clientId);
        return taskId;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> reviewExpiredTaskIds() {
        return taskRepository.findIdsReviewExpired(clock.instant());
    }

    @Override
    public void autoAcceptOne(UUID taskId) {
        // Row-locked like accept/reject, so the sweeper and a Client clicking at the same moment
        // serialize: whoever loses re-reads the resolved row and stops here instead of settling twice.
        TaskModel task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.PENDING_REVIEW) {
            return; // reviewed, or auto-accepted by an overlapping pass, since the id was read
        }
        Instant deadline = reviewDeadline(taskId);
        if (!ReviewWindow.hasClosed(deadline, clock.instant())) {
            return;
        }
        // The Client's silence counts as acceptance (spec D10): the same settlement as their click.
        settleAccepted(task);
        taskRepository.markReviewAutoAccepted(taskId);
        log.info("Task {} auto-accepted: review window closed at {} without a review", taskId, deadline);
    }

    private void settleAccepted(TaskModel task) {
        TaskModel resolved = task.accept();

        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));

        SettlementBreakdown breakdown =
                settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());

        // Same transaction as the settlement: the score is never stale, so routing cannot act on a
        // number that predates the outcome. No sweeper, no cache invalidation (ADR 0003).
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                ReputationEventType.TASK_ACCEPTED);

        taskRepository.save(resolved);
        log.info("Task {} settled as accepted; payout {} to builder {}, commission {}",
                task.id(), breakdown.net(), builderId, breakdown.commission());
    }

    private Instant reviewDeadline(UUID taskId) {
        return taskRepository.findReviewWindow(taskId).map(ReviewWindowState::deadline).orElse(null);
    }

    @Override
    public UUID reject(UUID taskId, UUID clientId, RejectReason reasonCategory, String reason) {
        if (reasonCategory == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A reject reason category is required");
        }
        TaskModel task = loadOwned(taskId, clientId);
        if (task.status() == TaskStatus.PENDING_REVIEW) {
            Instant deadline = reviewDeadline(taskId);
            if (ReviewWindow.hasClosed(deadline, clock.instant())) {
                // Silence already counted as acceptance; the sweeper settles it on its next pass.
                throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "The review window for task " + taskId + " closed at " + deadline
                                + "; the result is accepted automatically");
            }
        }

        if (reasonCategory == RejectReason.D_CHANGED_MIND) {
            // Buyer's remorse on conformant work → charge 85/15, no dispute.
            UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                    .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                            "No builder for agent version " + task.agentVersionId()));
            settlementWriteAppService.settleAccepted(taskId, clientId, builderId, task.budget());
            taskRepository.save(task.chargeChangedMind(reason));
            return taskId;
        }

        // A/B/C → open a dispute; settlement happens when the ruling lands.
        TaskModel disputed = task.dispute(reasonCategory, reason);
        taskRepository.save(disputed);
        disputeAppService.openDispute(disputed, clientId, reasonCategory);
        return taskId;
    }

    /**
     * Ownership check (Invariant #5): a foreign task is indistinguishable from a missing one.
     * Row-level lock so concurrent resolution attempts serialize (the loser sees RESOLVED and the
     * state guard throws).
     */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }
}
