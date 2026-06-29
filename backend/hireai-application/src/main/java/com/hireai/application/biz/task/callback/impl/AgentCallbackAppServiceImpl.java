package com.hireai.application.biz.task.callback.impl;

import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.callback.AgentCallbackAppService;
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
 * result through the Task aggregate ({@code EXECUTING → RESULT_RECEIVED}) and persists the
 * task_results child via the repository root. A duplicate callback (task already past
 * EXECUTING) is treated as a first-result-wins no-op: the service returns without
 * re-processing and the caller receives 200 — the first result is never overwritten.
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

    @Override
    public void recordResult(UUID taskId, String bearerToken, AgentResultInfo result) {
        DispatchTokenClaims claims = dispatchTokenService.verify(bearerToken);
        if (!claims.taskId().equals(taskId)) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token task " + claims.taskId() + " does not match callback task " + taskId);
        }
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!claims.agentVersionId().equals(task.agentVersionId())) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token agent version " + claims.agentVersionId()
                            + " does not match task " + taskId + " assignment " + task.agentVersionId());
        }
        // First-result-wins idempotency: if the task is no longer EXECUTING, the first callback
        // has already been processed (task is RESULT_RECEIVED or beyond). Return without
        // re-processing — no second insert into task_results, so the UNIQUE constraint is never
        // triggered. The first result is preserved unchanged.
        if (task.status() != TaskStatus.EXECUTING) {
            log.info("Task {} is already in status {} (not EXECUTING); treating duplicate callback as " +
                     "no-op — first result wins", taskId, task.status());
            return;
        }
        TaskResultModel resultModel = TaskResultModel.record(
                taskId, result.agentStatus(), result.resultPayloadJson(), result.resultUrl());
        // Non-COMPLETED MUST branch first: markFailed() requires EXECUTING; recordResult() moves
        // the task to RESULT_RECEIVED, making markFailed() illegal if called afterwards.
        if (!"COMPLETED".equalsIgnoreCase(result.agentStatus())) {
            TaskModel failed = task.markFailed();
            taskRepository.save(failed);
            settlementWriteAppService.settleRejected(taskId, failed.clientId(), failed.budget());
            log.info("Task {} agent reported {} -> FAILED (refunded)", taskId, result.agentStatus());
            return;
        }
        TaskModel recorded = task.recordResult(resultModel);
        taskRepository.save(recorded);
        validationAppService.validateAndGate(recorded);
        log.info("Task {} recorded result with agent status {}", taskId, result.agentStatus());
    }
}
