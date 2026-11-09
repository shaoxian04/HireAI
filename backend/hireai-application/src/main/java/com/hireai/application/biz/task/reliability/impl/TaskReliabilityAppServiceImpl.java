package com.hireai.application.biz.task.reliability.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reliability orchestration behind the three sweepers (spec §6, #67). rematchOne and
 * rerouteSubmittedOne are deliberately NOT transactional: it drives RoutingAppService, which publishes to RabbitMQ and manages its own
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
    private final ReputationWriteAppService reputationWriteAppService;
    /** Re-match sweeper bound: an AWAITING_AGENT task is cancelled + refunded at this attempt. */
    private final int rematchMaxAttempts;
    /** SUBMITTED sweeper bound: a stranded SUBMITTED task is cancelled + refunded at this attempt. */
    private final int submittedMaxAttempts;
    /** How long a task may sit SUBMITTED before the SUBMITTED sweeper treats it as stranded. */
    private final Duration submittedMinAge;
    /** The app's Clock bean (ClockConfig); tests pass their own to pick the moment a sweep runs at. */
    private final Clock clock;

    public TaskReliabilityAppServiceImpl(TaskRepository taskRepository,
                                         RoutingAppService routingAppService,
                                         TaskWriteAppService taskWriteAppService,
                                         SettlementWriteAppService settlementWriteAppService,
                                         WebhookOutboxAppService webhookOutboxAppService,
                                         ReputationWriteAppService reputationWriteAppService,
                                         @Value("${hireai.matching.rematch-max-attempts:2}") int rematchMaxAttempts,
                                         @Value("${hireai.matching.submitted-max-attempts:3}") int submittedMaxAttempts,
                                         @Value("${hireai.matching.submitted-min-age:PT30S}") Duration submittedMinAge,
                                         Clock clock) {
        if (rematchMaxAttempts < 1) {
            throw new IllegalStateException("rematch-max-attempts must be >= 1; got " + rematchMaxAttempts);
        }
        if (submittedMaxAttempts < 1) {
            throw new IllegalStateException("submitted-max-attempts must be >= 1; got " + submittedMaxAttempts);
        }
        this.taskRepository = taskRepository;
        this.routingAppService = routingAppService;
        this.taskWriteAppService = taskWriteAppService;
        this.settlementWriteAppService = settlementWriteAppService;
        this.webhookOutboxAppService = webhookOutboxAppService;
        this.reputationWriteAppService = reputationWriteAppService;
        if (submittedMinAge.isNegative()) {
            throw new IllegalStateException("submitted-min-age must be >= 0; got " + submittedMinAge);
        }
        this.rematchMaxAttempts = rematchMaxAttempts;
        this.submittedMaxAttempts = submittedMaxAttempts;
        this.submittedMinAge = submittedMinAge;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> awaitingAgentTaskIds() {
        return taskRepository.findIdsAwaitingAgent();
    }

    @Override
    public void rematchOne(UUID taskId) {
        Optional<TaskModel> maybeTask = taskRepository.findById(taskId);
        if (maybeTask.isEmpty() || maybeTask.get().status() != TaskStatus.AWAITING_AGENT) {
            return; // matched or cancelled since the sweep listed it — nothing to do
        }
        if (taskRepository.findPinnedAgentVersionId(taskId).isPresent()) {
            // A direct booking fails fast at dispatch when its Agent or version is gone (#77); there
            // is no other Agent to try and the pinned one won't come back, so never retry it here.
            log.info("Re-match sweeper: task {} is a pinned booking; not retried", taskId);
            return;
        }
        int attempts = taskWriteAppService.registerMatchAttempt(taskId);
        routingAppService.route(taskId);
        boolean stillHeld = taskRepository.findById(taskId)
                .map(t -> t.status() == TaskStatus.AWAITING_AGENT)
                .orElse(false);
        if (stillHeld && attempts >= rematchMaxAttempts) {
            taskWriteAppService.cancelAwaitingAgentWithRefund(taskId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> strandedSubmittedTaskIds() {
        return taskRepository.findIdsSubmittedBefore(clock.instant().minus(submittedMinAge));
    }

    @Override
    public void rerouteSubmittedOne(UUID taskId) {
        Optional<TaskModel> maybeTask = taskRepository.findById(taskId);
        if (maybeTask.isEmpty() || maybeTask.get().status() != TaskStatus.SUBMITTED) {
            return; // routed or cancelled since the sweep listed it
        }
        // Shares the match_attempts counter with the re-match sweeper (each has its own limit): a
        // task that reaches AWAITING_AGENT from here carries the attempts it has already used.
        int attempts = taskWriteAppService.registerMatchAttempt(taskId);
        try {
            routeOrDispatchPinned(taskId);
        } catch (RuntimeException e) {
            log.warn("SUBMITTED sweeper: routing failed again for task {} (attempt {})", taskId, attempts, e);
        }
        boolean stillSubmitted = taskRepository.findById(taskId)
                .map(t -> t.status() == TaskStatus.SUBMITTED)
                .orElse(false);
        if (stillSubmitted && attempts >= submittedMaxAttempts) {
            taskWriteAppService.cancelSubmittedWithRefund(taskId);
        }
    }

    private void routeOrDispatchPinned(UUID taskId) {
        Optional<UUID> pinned = taskRepository.findPinnedAgentVersionId(taskId);
        if (pinned.isPresent()) {
            // Direct booking: dispatch ONLY the client's chosen version — never substitute (spec §6.1).
            // If it is no longer available, dispatchDirect cancels the task at once (#77).
            routingAppService.dispatchDirect(taskId, pinned.get());
        } else {
            routingAppService.route(taskId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> executionExpiredTaskIds() {
        return taskRepository.findIdsExecutionExpired(clock.instant());
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
        // Blame only an Agent that had the work: a task still QUEUED never reached it (#66).
        if (task.timeoutIsAgentFault()) {
            reputationWriteAppService.recordOutcome(taskId, timedOut.agentVersionId(),
                    timedOut.clientId(), ReputationEventType.EXECUTION_TIMEOUT);
        }
        webhookOutboxAppService.enqueueFailed(timedOut, "TIMED_OUT");
        log.info("Task {} TIMED_OUT from {} past execution deadline; escrow fully refunded", taskId, task.status());
    }
}
