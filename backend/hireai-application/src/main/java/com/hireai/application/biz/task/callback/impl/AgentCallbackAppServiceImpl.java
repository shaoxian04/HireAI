package com.hireai.application.biz.task.callback.impl;

import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Verifies the dispatch token, confirms it authorises THIS task, then records the agent's
 * result. For a COMPLETED result: moves task QUEUED/EXECUTING → RESULT_RECEIVED, persists the
 * task_results child, and invokes the Module 4 validation gate (→ PENDING_REVIEW on pass,
 * SPEC_VIOLATION + auto-refund on fail). For a non-COMPLETED agentStatus: marks task FAILED
 * and refunds the client's escrow. A duplicate callback (task already past EXECUTING) is
 * treated as a first-result-wins no-op: the service returns without re-processing and the
 * caller receives 200 — the first result is never overwritten.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentCallbackAppServiceImpl implements AgentCallbackAppService {

    private final TaskRepository taskRepository;
    private final DispatchTokenService dispatchTokenService;
    private final ValidationAppService validationAppService;
    private final SettlementWriteAppService settlementWriteAppService;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final ReputationWriteAppService reputationWriteAppService;

    @Override
    public void recordResult(UUID taskId, String bearerToken, AgentResultInfo result) {
        DispatchTokenClaims claims = dispatchTokenService.verify(bearerToken);
        if (!claims.taskId().equals(taskId)) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token task " + claims.taskId() + " does not match callback task " + taskId);
        }
        // Row-locked (task row first, then the wallet row inside settlement: the usual lock
        // order), so a concurrent mark-EXECUTING, timeout or second callback reads this
        // callback's committed outcome instead of overwriting it.
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!claims.agentVersionId().equals(task.agentVersionId())) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token agent version " + claims.agentVersionId()
                            + " does not match task " + taskId + " assignment " + task.agentVersionId());
        }
        // A result is accepted while the task is QUEUED or EXECUTING: a fast Agent may call back
        // before the dispatch consumer marks the task EXECUTING. Any later status means the first
        // callback was already processed (or the task ended): first result wins, no-op. There is
        // no second insert into task_results, so its UNIQUE constraint is never triggered.
        if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.EXECUTING) {
            log.info("Task {} is already in status {}; treating callback as a no-op — first result wins",
                     taskId, task.status());
            return;
        }
        // Non-COMPLETED MUST branch first: markFailed() requires QUEUED/EXECUTING; recordResult()
        // moves the task to RESULT_RECEIVED, making markFailed() illegal if called afterwards.
        if (!"COMPLETED".equalsIgnoreCase(result.agentStatus())) {
            TaskModel failed = task.markFailed();
            taskRepository.save(failed);
            settlementWriteAppService.settleRejected(taskId, failed.clientId(), failed.budget());
            // The agent itself reported it could not deliver — a zero-quality sample.
            reputationWriteAppService.recordOutcome(taskId, failed.agentVersionId(),
                    failed.clientId(), ReputationEventType.EXECUTION_FAILED);
            webhookOutboxAppService.enqueueFailed(failed, "FAILED");
            log.info("Task {} agent reported {} -> FAILED (refunded)", taskId, result.agentStatus());
            return;
        }
        TaskResultModel resultModel = TaskResultModel.record(
                taskId, result.agentStatus(), result.resultPayloadJson(), result.resultUrl());
        TaskModel recorded = task.recordResult(resultModel);
        taskRepository.save(recorded);
        log.info("Task {} result recorded; invoking validation gate (agentStatus={})", taskId, result.agentStatus());
        validationAppService.validateAndGate(recorded);
    }
}
