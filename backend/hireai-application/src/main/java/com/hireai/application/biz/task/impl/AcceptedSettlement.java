package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one way a reviewed result settles as accepted: the task transition, the 85/15 payout and the
 * TASK_ACCEPTED event, in the caller's transaction (which holds the task's row lock). Shared by a
 * Client's accept, the review-window auto-accept (#94) and the acceptances of the rejection path
 * (#95), so they can't drift apart.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AcceptedSettlement {

    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ReputationWriteAppService reputationWriteAppService;

    public TaskModel settle(TaskModel task) {
        TaskModel resolved = task.accept(); // state guard: exactly-once

        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));

        SettlementBreakdown breakdown =
                settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());

        // Same transaction as the settlement: the score is never stale, so routing cannot act on a
        // number that predates the outcome. No sweeper, no cache invalidation (ADR 0003).
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                ReputationEventType.TASK_ACCEPTED);

        taskRepository.save(resolved);
        log.info("Task {} settled as accepted; payout {} to builder {}, commission {}",
                task.id(), breakdown.net(), builderId, breakdown.commission());
        return resolved;
    }
}
