package com.hireai.application.biz.task.reliability.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.webhook.WebhookOutboxAppService;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reliability orchestration behind the two sweepers (spec §6). rematchOne is deliberately NOT
 * transactional: it drives RoutingAppService, which publishes to RabbitMQ and manages its own
 * commit-before-publish ordering. timeoutOne and the id reads are transactional. Every write it
 * triggers is status-guarded, so overlapping sweeps and races resolve as no-ops.
 */
@Service
@Slf4j
public class TaskReliabilityAppServiceImpl implements TaskReliabilityAppService {

    private final TaskRepository taskRepository;
    private final RoutingAppService routingAppService;
    private final TaskWriteAppService taskWriteAppService;
    private final SettlementWriteAppService settlementWriteAppService;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final int rematchMaxAttempts;

    public TaskReliabilityAppServiceImpl(TaskRepository taskRepository,
                                         RoutingAppService routingAppService,
                                         TaskWriteAppService taskWriteAppService,
                                         SettlementWriteAppService settlementWriteAppService,
                                         WebhookOutboxAppService webhookOutboxAppService,
                                         @Value("${hireai.matching.rematch-max-attempts:3}") int rematchMaxAttempts) {
        if (rematchMaxAttempts < 1) {
            throw new IllegalStateException("rematch-max-attempts must be >= 1; got " + rematchMaxAttempts);
        }
        this.taskRepository = taskRepository;
        this.routingAppService = routingAppService;
        this.taskWriteAppService = taskWriteAppService;
        this.settlementWriteAppService = settlementWriteAppService;
        this.webhookOutboxAppService = webhookOutboxAppService;
        this.rematchMaxAttempts = rematchMaxAttempts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> awaitingCapacityTaskIds() {
        return taskRepository.findIdsAwaitingCapacity();
    }

    @Override
    public void rematchOne(UUID taskId) {
        Optional<TaskModel> maybeTask = taskRepository.findById(taskId);
        if (maybeTask.isEmpty() || maybeTask.get().status() != TaskStatus.AWAITING_CAPACITY) {
            return; // matched or cancelled since the sweep listed it — nothing to do
        }
        int attempts = taskWriteAppService.registerMatchAttempt(taskId);
        Optional<UUID> pinned = taskRepository.findPinnedAgentVersionId(taskId);
        if (pinned.isPresent()) {
            // Direct booking: retry ONLY the client's chosen version — never substitute (spec §6.1).
            routingAppService.dispatchDirect(taskId, pinned.get());
        } else {
            routingAppService.route(taskId);
        }
        boolean stillHeld = taskRepository.findById(taskId)
                .map(t -> t.status() == TaskStatus.AWAITING_CAPACITY)
                .orElse(false);
        if (stillHeld && attempts >= rematchMaxAttempts) {
            taskWriteAppService.cancelAwaitingCapacityWithRefund(taskId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> executionExpiredTaskIds() {
        return taskRepository.findIdsExecutionExpired(Instant.now());
    }

    @Override
    @Transactional
    public void timeoutOne(UUID taskId) {
        // Row-locked load: lock ordering is task-row -> wallet-row (settleRejected locks the wallet
        // next), identical to the review/accept path, so no new deadlock. The residual
        // timeout-vs-agent-callback race window remains money-safe via the settlements.task_id
        // UNIQUE constraint (the callback path is out of this branch's scope).
        TaskModel task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null
                || (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING)) {
            return; // result arrived / already failed since the sweep listed it
        }
        TaskModel timedOut = task.markTimedOut();
        taskRepository.save(timedOut);
        settlementWriteAppService.settleRejected(taskId, timedOut.clientId(), timedOut.budget());
        webhookOutboxAppService.enqueueFailed(timedOut, "TIMED_OUT");
        log.info("Task {} TIMED_OUT past execution deadline; escrow fully refunded", taskId);
    }
}
