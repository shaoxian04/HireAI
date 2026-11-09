package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.dispatchattempt.model.DispatchAttemptModel;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * Application adapter implementing {@link TaskExecutionPort}. Lets the dispatch consumer
 * (Track B) flip task execution status without importing Task concrete app-service classes.
 * Each method loads the task, applies the guarded transition, and saves the new copy.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskExecutionPortImpl implements TaskExecutionPort {

    private final TaskRepository taskRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final DispatchAttemptRepository dispatchAttemptRepository;
    private final Clock clock;

    @Override
    public void recordDispatchAttempt(UUID taskId, UUID agentVersionId, DispatchOutcome outcome) {
        // The task row lock serialises every writer of this task's history, so the count-plus-one
        // numbering cannot race (the UNIQUE (task_id, attempt_number) key backs it up).
        loadForUpdate(taskId);
        appendAttempt(taskId, agentVersionId, outcome);
    }

    private void appendAttempt(UUID taskId, UUID agentVersionId, DispatchOutcome outcome) {
        int next = dispatchAttemptRepository.countByTaskId(taskId) + 1;
        dispatchAttemptRepository.append(
                DispatchAttemptModel.record(taskId, agentVersionId, next, outcome, clock.instant()));
        log.info("Task {} dispatch attempt {} to version {}: {}", taskId, next, agentVersionId, outcome);
    }

    @Override
    public boolean isAwaitingDispatch(UUID taskId, UUID agentVersionId) {
        // Row-locked so the check reads the committed state of a concurrent callback, timeout
        // or failure rather than a stale snapshot. The lock lasts only for this short tx: the
        // send itself happens outside any transaction, so an Agent that calls back before
        // replying to the WorkOrder never waits on the consumer.
        return taskRepository.findByIdForUpdate(taskId)
                .map(task -> task.status() == TaskStatus.QUEUED
                        && agentVersionId.equals(task.agentVersionId()))
                .orElse(false);
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
        // Mirrors markFailed's guarded+refund discipline (spec §6.3 fix): a bare
        // save(load(taskId).markTimedOut()) with no status guard and no refund would be the same
        // stranded-escrow footgun just fixed below, so this transition gets the same shape even
        // though it has no production caller today (the sweeper timeouts via its own path).
        TaskModel task = loadForUpdate(taskId);
        if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING) {
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
    public void markFailed(UUID taskId) {
        // Row-locked load: lock ordering is task-row -> wallet-row (settleRejected locks the wallet
        // next), identical to the review/accept path, so no new deadlock. The residual
        // timeout-vs-agent-callback race window remains money-safe via the settlements.task_id
        // UNIQUE constraint (the callback path is out of this branch's scope).
        TaskModel task = loadForUpdate(taskId);
        if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING) {
            // Duplicate failure signal (e.g. DLQ redelivery, or the timeout sweeper got there first):
            // the terminal state + refund already happened. Idempotent no-op.
            log.info("Task {} already {}; ignoring duplicate failure signal", taskId, task.status());
            return;
        }
        TaskModel failed = task.markFailed(FailureReason.AGENT_UNREACHABLE);
        taskRepository.save(failed);
        // Stranded-escrow fix (spec §6.3): the DLQ path previously marked FAILED without refunding,
        // freezing the client's escrow forever. Every escrow exit is a recorded settlement.
        settlementWriteAppService.settleRejected(taskId, failed.clientId(), failed.budget());
        // The Agent could not be reached on any attempt: a zero-quality sample against it, and
        // the client's integration learns of the failure. Both join this transaction.
        reputationWriteAppService.recordOutcome(taskId, failed.agentVersionId(), failed.clientId(),
                ReputationEventType.EXECUTION_FAILED);
        webhookOutboxAppService.enqueueFailed(failed, "FAILED");
        appendAttempt(taskId, failed.agentVersionId(), DispatchOutcome.UNREACHABLE);
        log.info("Task {} marked FAILED, escrow refunded, EXECUTION_FAILED recorded", taskId);
    }

    private TaskModel loadForUpdate(UUID taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
