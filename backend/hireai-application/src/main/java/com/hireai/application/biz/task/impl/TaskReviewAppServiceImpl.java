package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.event.TaskRejectedDomainEvent;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.rejection.model.RejectionWindows;
import com.hireai.domain.biz.task.rejection.repository.RejectionRepository;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final AcceptedSettlement acceptedSettlement;
    private final RejectionRepository rejectionRepository;
    private final RejectionWindows rejectionWindows;
    private final ApplicationEventPublisher eventPublisher;
    /** The app's Clock bean (ClockConfig); tests set it to pick the moment a window closes. */
    private final Clock clock;

    @Override
    public UUID accept(UUID taskId, UUID clientId) {
        TaskModel task = loadOwned(taskId, clientId);
        if (task.status() == TaskStatus.AWAITING_CLIENT_DECISION) {
            // Accepting a result the Agent stood by (#95). Once the decision window has closed the
            // outcome is the same acceptance, recorded as the silence it already was.
            RejectionModel rejection = requireLatestRejection(taskId);
            Instant now = clock.instant();
            rejectionRepository.save(rejection.decisionWindowClosed(now)
                    ? rejection.acceptSilently(now)
                    : rejection.decide(ClientDecision.ACCEPT, now));
        }
        acceptedSettlement.settle(task); // state guard: PENDING_REVIEW or AWAITING_CLIENT_DECISION; exactly-once
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
        acceptedSettlement.settle(task);
        taskRepository.markReviewAutoAccepted(taskId);
        log.info("Task {} auto-accepted: review window closed at {} without a review", taskId, deadline);
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

        // A/B/C → the Agent's turn (#95, D20): revise once, concede or stand by. Nothing is
        // arbitrated and no money moves until that is settled.
        TaskModel awaiting = task.awaitAgentResponse(reasonCategory, reason);
        List<RejectionModel> earlier = rejectionRepository.findByTaskId(taskId);
        boolean revisedBefore = earlier.stream().anyMatch(r -> r.response() == AgentResponse.REVISE);
        RejectionModel round = RejectionModel.open(taskId, earlier.size() + 1, reasonCategory, reason,
                !revisedBefore, clock.instant(), rejectionWindows);
        taskRepository.save(awaiting);
        rejectionRepository.save(round);
        // The notice goes out after commit, so an Agent never hears of a rejection that rolled back.
        eventPublisher.publishEvent(new TaskRejectedDomainEvent(taskId));
        log.info("Task {} rejected ({}), round {}: awaiting the Agent's response until {}",
                taskId, reasonCategory, round.round(), round.responseDeadline());
        return taskId;
    }

    private RejectionModel requireLatestRejection(UUID taskId) {
        return rejectionRepository.findLatestByTaskId(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Task " + taskId + " has no rejection to decide on"));
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
