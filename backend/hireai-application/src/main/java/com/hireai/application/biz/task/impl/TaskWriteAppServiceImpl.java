package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.webhook.WebhookOutboxAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
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

    @Override
    public UUID submit(TaskSubmitInfo taskSubmitInfo) {
        return doSubmit(taskSubmitInfo, null);
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
     * Shared atomic submit: domain submit → repo save → wallet freeze → publish event.
     * {@code directAgentVersionId} is null for normal routing, non-null for direct booking.
     * The transactional semantics are class-level @Transactional (REQUIRED by default).
     */
    private UUID doSubmit(TaskSubmitInfo taskSubmitInfo, UUID directAgentVersionId) {
        String correlationId = UUID.randomUUID().toString();
        TaskModel task = taskSubmitDomainService.submit(taskSubmitInfo);
        UUID taskId = taskRepository.save(task).id();
        walletWriteAppService.freeze(taskSubmitInfo.clientId(), taskSubmitInfo.budget(), taskId, correlationId);
        eventPublisher.publishEvent(new TaskSubmittedDomainEvent(
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(), task.createdAt(),
                directAgentVersionId));
        log.info("Task {} submitted by client {}; budget {} frozen in escrow (directAgentVersionId={})",
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(), directAgentVersionId);
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
    public void markAwaitingCapacity(UUID taskId) {
        TaskModel task = load(taskId);
        taskRepository.save(task.markAwaitingCapacity());
        log.info("Task {} marked AWAITING_CAPACITY (no eligible agent)", taskId);
    }

    @Override
    public int registerMatchAttempt(UUID taskId) {
        taskRepository.incrementMatchAttempts(taskId);
        return taskRepository.matchAttempts(taskId);
    }

    @Override
    public void cancelAwaitingCapacityWithRefund(UUID taskId) {
        TaskModel task = load(taskId);
        if (task.status() != TaskStatus.AWAITING_CAPACITY) {
            log.info("Task {} is {} (not AWAITING_CAPACITY); skipping cancel", taskId, task.status());
            return;
        }
        TaskModel cancelled = task.markCancelled();
        taskRepository.save(cancelled);
        settlementWriteAppService.settleRejected(taskId, cancelled.clientId(), cancelled.budget());
        webhookOutboxAppService.enqueueFailed(cancelled, "CANCELLED");
        log.info("Task {} CANCELLED after re-match exhaustion; escrow fully refunded", taskId);
    }

    private TaskModel load(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
