package com.hireai.application.biz.agentcallback.impl;

import com.hireai.application.biz.agentcallback.AgentCallbackAppService;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Verifies the dispatch token, confirms it authorises THIS task, then records the agent's
 * result through the Task aggregate ({@code EXECUTING → RESULT_RECEIVED}) and persists the
 * task_results child via the repository root.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentCallbackAppServiceImpl implements AgentCallbackAppService {

    private final TaskRepository taskRepository;
    private final DispatchTokenService dispatchTokenService;

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
        TaskResultModel resultModel = TaskResultModel.record(
                taskId, result.agentStatus(), result.resultPayloadJson(), result.resultUrl());
        taskRepository.save(task.recordResult(resultModel));
        log.info("Task {} recorded result with agent status {}", taskId, result.agentStatus());
    }
}
