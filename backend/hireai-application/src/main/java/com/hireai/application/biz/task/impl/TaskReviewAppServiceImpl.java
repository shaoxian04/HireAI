package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskReviewAppServiceImpl implements TaskReviewAppService {

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final SettlementWriteAppService settlementWriteAppService;

    @Override
    public UUID accept(UUID taskId, UUID clientId) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.accept(); // state guard: PENDING_REVIEW (caller passed the validation gate); exactly-once

        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));

        SettlementBreakdown breakdown =
                settlementWriteAppService.settleAccepted(taskId, clientId, builderId, task.budget());

        taskRepository.save(resolved);
        log.info("Task {} accepted by client {}; payout {} to builder {}, commission {}",
                taskId, clientId, breakdown.net(), builderId, breakdown.commission());
        return taskId;
    }

    @Override
    public UUID reject(UUID taskId, UUID clientId, String reason) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.reject(reason); // state guard: PENDING_REVIEW (caller passed the validation gate)

        settlementWriteAppService.settleRejected(taskId, clientId, task.budget());

        taskRepository.save(resolved);
        log.info("Task {} rejected by client {}; budget {} refunded", taskId, clientId, task.budget());
        return taskId;
    }

    /**
     * Ownership check (Invariant #5): a foreign task is indistinguishable from a missing one.
     * Row-level lock so concurrent resolution attempts serialize (the loser sees RESOLVED and the
     * state guard throws).
     */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }
}
