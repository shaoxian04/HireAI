package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Application adapter implementing {@link TaskExecutionPort}. Lets the dispatch consumer
 * (Track B) flip task execution status without importing Task concrete app-service classes.
 * Each method row-locks the task, asks the {@link TaskModel} whether the signal still applies,
 * and sequences the domain transition through {@link TaskWriteAppService} (which owns the
 * ending-with-refund and the dispatch-history numbering) or routed failover.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskExecutionPortImpl implements TaskExecutionPort {

    private final TaskRepository taskRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final TaskWriteAppService taskWriteAppService;
    private final AgentRepository agentRepository;
    private final RoutingAppService routingAppService;

    @Override
    public void recordDispatchAttempt(UUID taskId, UUID agentVersionId, DispatchOutcome outcome) {
        // The task row lock serialises every writer of this task's history.
        loadForUpdate(taskId);
        taskWriteAppService.appendDispatchAttempt(taskId, agentVersionId, outcome);
    }

    @Override
    public boolean prepareDispatch(UUID taskId, UUID agentVersionId) {
        // Row-locked so the check reads the committed state of a concurrent callback, timeout
        // or failure rather than a stale snapshot. The lock lasts only for this short tx: the
        // send itself happens outside any transaction, so an Agent that calls back before
        // replying to the WorkOrder never waits on the consumer.
        Optional<TaskModel> maybeTask = taskRepository.findByIdForUpdate(taskId);
        if (maybeTask.isEmpty() || !maybeTask.get().isAwaitingDispatchTo(agentVersionId)) {
            return false;
        }
        TaskModel task = maybeTask.get();
        // Re-checked before every send (#58): the Agent must still be ACTIVE and the version current.
        if (agentRepository.findCandidateByVersionId(agentVersionId).isPresent()) {
            return true;
        }
        // Unavailable at dispatch: the Builder deactivated the Agent or published a new version.
        // Maintenance is never held against the Builder, so no reputation event either way.
        if (failsOver(task)) {
            log.info("Task {}: version {} is no longer available at dispatch; failing over", taskId,
                    agentVersionId);
            taskWriteAppService.appendDispatchAttempt(taskId, agentVersionId, DispatchOutcome.UNAVAILABLE);
            routingAppService.failOver(taskId);
        } else {
            log.info("Task {}: pinned version {} is no longer available at dispatch; cancelling", taskId,
                    agentVersionId);
            taskWriteAppService.cancelUnavailableAtDispatch(taskId, agentVersionId);
        }
        return false;
    }

    @Override
    public void markExecuting(UUID taskId) {
        // Row-locked: an early callback (QUEUED → RESULT_RECEIVED) or a timeout that committed
        // first is seen here, and markExecuting() is then a no-op instead of overwriting it.
        TaskModel task = loadForUpdate(taskId);
        TaskModel executing = task.markExecuting();
        if (executing == task) {
            log.info("Task {} already {}; mark EXECUTING is a no-op", taskId, task.status());
            return;
        }
        taskRepository.save(executing);
        log.info("Task {} marked EXECUTING", taskId);
    }

    @Override
    public void markTimedOut(UUID taskId) {
        // Mirrors markFailed's guarded+refund discipline (spec §6.3 fix), though it has no
        // production caller today (the sweeper times out via its own path).
        TaskModel task = loadForUpdate(taskId);
        if (!task.isInFlight()) {
            // Duplicate timeout signal: the terminal state + refund already happened. Idempotent no-op.
            log.info("Task {} already {}; ignoring duplicate timeout signal", taskId, task.status());
            return;
        }
        TaskModel timedOut = task.markTimedOut();
        taskRepository.save(timedOut);
        settlementWriteAppService.settleRejected(taskId, timedOut.clientId(), timedOut.budget());
        log.info("Task {} marked TIMED_OUT and escrow refunded", taskId);
    }

    @Override
    public void markFailed(UUID taskId, UUID agentVersionId) {
        // Row-locked load: lock ordering is task-row -> wallet-row (the refund locks the wallet
        // next), identical to the review/accept path, so no new deadlock.
        TaskModel task = loadForUpdate(taskId);
        if (!task.isInFlightOn(agentVersionId)) {
            // A duplicate failure signal (DLQ redelivery, or the timeout sweeper got there first), or a
            // dead letter for an Agent the task already failed over from. Idempotent no-op.
            log.info("Task {} is {} on version {}; ignoring dead letter for version {}", taskId,
                    task.status(), task.agentVersionId(), agentVersionId);
            return;
        }
        // The Agent could not be reached on any attempt of the retry schedule: a zero-quality
        // sample against it whether or not the task fails over. Joins this transaction.
        reputationWriteAppService.recordOutcome(taskId, agentVersionId, task.clientId(),
                ReputationEventType.EXECUTION_FAILED);
        taskWriteAppService.appendDispatchAttempt(taskId, agentVersionId, DispatchOutcome.UNREACHABLE);
        if (failsOver(task)) {
            routingAppService.failOver(taskId);
            return;
        }
        taskWriteAppService.failWithRefund(taskId, FailureReason.AGENT_UNREACHABLE);
        log.info("Task {} marked FAILED, escrow refunded, EXECUTION_FAILED recorded", taskId);
    }

    @Override
    public void declineAsBusy(UUID taskId, UUID agentVersionId) {
        // Same lock order as markFailed (task row, then wallet row in the refund).
        TaskModel task = loadForUpdate(taskId);
        if (!task.isAwaitingDispatchTo(agentVersionId)) {
            log.info("Task {} is {} (version {}); ignoring busy reply from version {}", taskId,
                    task.status(), task.agentVersionId(), agentVersionId);
            return;
        }
        // Declining at capacity is part of the acknowledgement contract: no reputation event.
        taskWriteAppService.appendDispatchAttempt(taskId, agentVersionId, DispatchOutcome.BUSY);
        if (failsOver(task)) {
            routingAppService.failOver(taskId);
            return;
        }
        taskWriteAppService.failWithRefund(taskId, FailureReason.AGENT_BUSY);
        log.info("Task {} declined as busy by version {}: FAILED (AGENT_BUSY), escrow refunded", taskId,
                agentVersionId);
    }

    /** Routed failover (#78) or the end of the task: the rule is the task's own. */
    private boolean failsOver(TaskModel task) {
        return task.failsOverOnDispatchFailure(taskRepository.findPinnedAgentVersionId(task.id()).isPresent());
    }

    private TaskModel loadForUpdate(UUID taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
