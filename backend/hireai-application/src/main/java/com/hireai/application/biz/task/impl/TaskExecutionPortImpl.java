package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public void markExecuting(UUID taskId) {
        taskRepository.save(load(taskId).markExecuting());
        log.info("Task {} marked EXECUTING", taskId);
    }

    @Override
    public void markTimedOut(UUID taskId) {
        taskRepository.save(load(taskId).markTimedOut());
        log.info("Task {} marked TIMED_OUT", taskId);
    }

    @Override
    public void markFailed(UUID taskId) {
        TaskModel task = load(taskId);
        if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING) {
            // Duplicate failure signal (e.g. DLQ redelivery, or the timeout sweeper got there first):
            // the terminal state + refund already happened. Idempotent no-op.
            log.info("Task {} already {}; ignoring duplicate failure signal", taskId, task.status());
            return;
        }
        TaskModel failed = task.markFailed();
        taskRepository.save(failed);
        // Stranded-escrow fix (spec §6.3): the DLQ path previously marked FAILED without refunding,
        // freezing the client's escrow forever. Every escrow exit is a recorded settlement.
        settlementWriteAppService.settleRejected(taskId, failed.clientId(), failed.budget());
        log.info("Task {} marked FAILED and escrow refunded", taskId);
    }

    private TaskModel load(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
    }
}
