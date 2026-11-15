package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.item.TaskItemAppService;
import com.hireai.domain.shared.model.Money;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.dispatchattempt.model.DispatchAttemptModel;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.model.ClientDeadline;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.coverage.service.OfferCoverageDomainService;
import com.hireai.domain.biz.acceptance.template.info.ContractResolution;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskWriteAppServiceImpl implements TaskWriteAppService {

    private final TaskRepository taskRepository;
    private final TaskSubmitDomainService taskSubmitDomainService;
    private final WalletWriteAppService walletWriteAppService;
    private final ApplicationEventPublisher eventPublisher;
    private final SettlementWriteAppService settlementWriteAppService;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final DispatchAttemptRepository dispatchAttemptRepository;
    /** The app's Clock bean (ClockConfig): stamps the client deadline and the UNAVAILABLE dispatch attempt. */
    private final Clock clock;
    /** Checks the frozen Contract against its Template before the Task is saved (ADR 0009). */
    private final TemplateReadAppService templateReadAppService;
    /** Creates a multi-Item Task's Items in the submit transaction (spec S3). */
    private final TaskItemAppService taskItemAppService;
    /** Reads the active Agents' Offers, to choose a routed task's Template version (spec D36). */
    private final AgentRepository agentRepository;
    private final OfferCoverageDomainService offerCoverageDomainService;

    @Override
    public UUID submit(TaskSubmitInfo taskSubmitInfo) {
        return doSubmit(withNewContract(taskSubmitInfo), null);
    }

    /**
     * A routed task's Contract (its own, else the output spec's equivalent) uses the current Template
     * version, or during a transition the previous one when no active Agent in the category and budget
     * has an Offer covering the current one (spec D36). The task response then says so.
     */
    private TaskSubmitInfo withNewContract(TaskSubmitInfo info) {
        if (info.contract() == null && info.outputSpec() == null) {
            return info; // the domain submit refuses it ("output spec is required")
        }
        Contract requested = info.contract() != null ? info.contract() : info.outputSpec().toContract();
        Supplier<List<AgentCandidate>> candidates = memoize(() -> agentRepository.findActiveCandidates(
                info.category() == null ? null : info.category().trim().toLowerCase(), info.budget().value()));
        ContractResolution resolution = templateReadAppService.resolveNew(requested, terms ->
                candidates.get().stream().anyMatch(c -> offerCoverageDomainService
                        .coverage(terms.template(), terms.contract(), c.offer()).covered()));
        if (resolution.fallback() != null) {
            log.info("Routed task by client {} falls back: {}", info.clientId(), resolution.fallback().summary());
        }
        return new TaskSubmitInfo(info.clientId(), info.title(), info.description(), info.budget(),
                info.outputSpec(), info.category(), info.deadlineSeconds(), resolution.terms().contract(),
                info.items());
    }

    private static <T> Supplier<T> memoize(Supplier<T> source) {
        return new Supplier<>() {
            private T value;

            @Override
            public T get() {
                if (value == null) {
                    value = source.get();
                }
                return value;
            }
        };
    }

    @Override
    public UUID submitDirectlyBooked(TaskSubmitInfo taskSubmitInfo, UUID agentVersionId) {
        UUID taskId = doSubmit(taskSubmitInfo, agentVersionId);
        // Same transaction as the submit: the re-match sweeper must be able to tell pinned tasks
        // from open tasks and never substitute another agent for a direct booking (spec §6.1).
        taskRepository.pinAgentVersion(taskId, agentVersionId);
        return taskId;
    }

    /**
     * Shared atomic submit: domain submit → Contract check → repo save → wallet freeze → publish event.
     * {@code directAgentVersionId} is null for normal routing, non-null for direct booking.
     * The transactional semantics are class-level @Transactional (REQUIRED by default).
     */
    private UUID doSubmit(TaskSubmitInfo taskSubmitInfo, UUID directAgentVersionId) {
        String correlationId = UUID.randomUUID().toString();
        // A multi-Item Task's budget of record is what it freezes: the per-Item price × the Item count.
        Money escrow = taskSubmitInfo.escrow();
        TaskModel task = taskSubmitDomainService.submit(taskSubmitInfo.withEscrowAsBudget());
        templateReadAppService.requireValid(task.contract());
        UUID taskId = taskRepository.save(task).id();
        if (taskSubmitInfo.hasItems()) {
            taskItemAppService.createForSubmit(taskId, taskSubmitInfo.items(), taskSubmitInfo.budget());
        }
        if (taskSubmitInfo.deadlineSeconds() != null) {
            // Fixed now, at submit: every later selection (re-match, failover) measures against it.
            taskRepository.stampClientDeadline(taskId,
                    ClientDeadline.after(clock.instant(), taskSubmitInfo.deadlineSeconds()).at());
        }
        walletWriteAppService.freeze(taskSubmitInfo.clientId(), escrow, taskId, correlationId);
        eventPublisher.publishEvent(new TaskSubmittedDomainEvent(
                taskId, taskSubmitInfo.clientId(), escrow, task.createdAt(),
                directAgentVersionId));
        log.info("Task {} submitted by client {}; {} frozen in escrow ({} Items; directAgentVersionId={})",
                taskId, taskSubmitInfo.clientId(), escrow, taskSubmitInfo.items().size(), directAgentVersionId);
        return taskId;
    }

    /**
     * Routing transitions run in their OWN independent transaction. Routing is triggered from a
     * {@code @TransactionalEventListener(AFTER_COMMIT)} callback (RoutingEventListener), where the
     * thread is mid transaction-completion: a default REQUIRED transaction opened there is never
     * committed (a documented Spring gotcha — writes silently vanish). REQUIRES_NEW forces a fresh,
     * independently-committing transaction so the QUEUED write is durable before the dispatch
     * message is published (see the routing plan: "Why publish-after-commit").
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assignAndQueue(UUID taskId, UUID agentVersionId, Instant executionDeadline) {
        TaskModel task = load(taskId);
        taskRepository.save(task.assignAndQueue(agentVersionId));
        taskRepository.stampExecutionDeadline(taskId, executionDeadline);
        log.info("Task {} assigned to agent version {} and queued (deadline {})",
                taskId, agentVersionId, executionDeadline);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAwaitingAgent(UUID taskId, String noMatchDetail) {
        TaskModel task = load(taskId);
        taskRepository.save(task.markAwaitingAgent());
        taskRepository.stampFailureDetail(taskId, noMatchDetail == null || noMatchDetail.length() <= 1000
                ? noMatchDetail : noMatchDetail.substring(0, 999) + "…"); // the V42 column's limit
        log.info("Task {} marked AWAITING_AGENT (no eligible agent)", taskId);
    }

    @Override
    public int registerMatchAttempt(UUID taskId) {
        taskRepository.incrementMatchAttempts(taskId);
        return taskRepository.matchAttempts(taskId);
    }

    @Override
    public void cancelAwaitingAgentWithRefund(UUID taskId) {
        TaskModel task = load(taskId);
        if (task.status() != TaskStatus.AWAITING_AGENT) {
            log.info("Task {} is {} (not AWAITING_AGENT); skipping cancel", taskId, task.status());
            return;
        }
        // A direct booking waits here only because its pinned version was unavailable.
        boolean pinned = taskRepository.findPinnedAgentVersionId(taskId).isPresent();
        cancelWithRefund(task, FailureReason.noAgentFound(pinned));
        log.info("Task {} CANCELLED after re-match exhaustion; escrow fully refunded", taskId);
    }

    @Override
    public void cancelSubmittedWithRefund(UUID taskId) {
        // Locked like the timeout path (task row -> wallet row), so a concurrent routing write
        // can't move the task to QUEUED between this status check and the refund.
        TaskModel task = loadForUpdate(taskId);
        if (task.status() != TaskStatus.SUBMITTED) {
            log.info("Task {} is {} (not SUBMITTED); skipping cancel", taskId, task.status());
            return;
        }
        // Routing threw on every sweep: the platform never got the task to an Agent.
        cancelWithRefund(task, FailureReason.DISPATCH_ABANDONED);
        log.info("Task {} CANCELLED after routing failed on every SUBMITTED sweep; escrow fully refunded", taskId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPinnedAsUnavailable(UUID taskId, UUID agentVersionId) {
        // Same lock order as the other refund paths: task row, then wallet row in settleRejected.
        TaskModel task = loadForUpdate(taskId);
        if (task.status() != TaskStatus.SUBMITTED && task.status() != TaskStatus.AWAITING_AGENT) {
            log.info("Task {} is {}; skipping pinned fail-fast cancel", taskId, task.status());
            return;
        }
        cancelAsUnavailable(task, agentVersionId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelUnavailableAtDispatch(UUID taskId, UUID agentVersionId) {
        cancelAsUnavailable(loadForUpdate(taskId), agentVersionId);
    }

    private void cancelAsUnavailable(TaskModel task, UUID agentVersionId) {
        endWithRefund(task.cancelAsUnavailable());
        appendAttempt(task.id(), agentVersionId, DispatchOutcome.UNAVAILABLE);
        log.info("Task {} CANCELLED (AGENT_UNAVAILABLE): pinned version {} is no longer available; "
                + "escrow fully refunded", task.id(), agentVersionId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reassign(UUID taskId, UUID newAgentVersionId, Instant executionDeadline) {
        TaskModel task = loadForUpdate(taskId);
        UUID previous = task.agentVersionId();
        taskRepository.save(task.reassign(newAgentVersionId));
        taskRepository.stampExecutionDeadline(taskId, executionDeadline);
        log.info("Task {} reassigned from agent version {} to {} (deadline {})",
                taskId, previous, newAgentVersionId, executionDeadline);
    }


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void failWithRefund(UUID taskId, FailureReason reason) {
        endWithRefund(loadForUpdate(taskId).markFailed(reason));
        log.info("Task {} FAILED ({}); escrow fully refunded", taskId, reason);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendDispatchAttempt(UUID taskId, UUID agentVersionId, DispatchOutcome outcome) {
        appendAttempt(taskId, agentVersionId, outcome);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendContractDecline(UUID taskId, UUID agentVersionId, String reason) {
        append(DispatchAttemptModel.contractDeclined(taskId, agentVersionId,
                dispatchAttemptRepository.countByTaskId(taskId), reason, clock.instant()));
    }

    /** The one place a dispatch attempt is numbered and appended; the caller holds the task row lock. */
    private void appendAttempt(UUID taskId, UUID agentVersionId, DispatchOutcome outcome) {
        append(DispatchAttemptModel.next(taskId, agentVersionId,
                dispatchAttemptRepository.countByTaskId(taskId), outcome, clock.instant()));
    }

    private void append(DispatchAttemptModel attempt) {
        UUID taskId = attempt.taskId();
        UUID agentVersionId = attempt.agentVersionId();
        DispatchOutcome outcome = attempt.outcome();
        dispatchAttemptRepository.append(attempt);
        log.info("Task {} dispatch attempt {} to version {}: {}", taskId, attempt.attemptNumber(),
                agentVersionId, outcome);
    }

    /**
     * The one place a task that ended without a result settles: save the ended copy, refund the
     * full escrow (a recorded settlement, Hard Invariants #1/#2) and queue the client's
     * {@code task.failed} webhook, whose reason is the terminal status.
     */
    private void endWithRefund(TaskModel ended) {
        taskRepository.save(ended);
        settlementWriteAppService.settleRejected(ended.id(), ended.clientId(), ended.budget());
        webhookOutboxAppService.enqueueFailed(ended, ended.status().name());
    }

    private TaskModel loadForUpdate(UUID taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }

    private void cancelWithRefund(TaskModel task, FailureReason reason) {
        endWithRefund(task.markCancelled(reason));
    }

    private TaskModel load(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
