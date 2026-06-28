package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
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
    private final WalletRepository walletRepository;
    private final SettlementDomainService settlementDomainService;

    @Override
    public UUID accept(UUID taskId, UUID clientId) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.accept(); // state guard: only RESULT_RECEIVED; exactly-once

        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));

        WalletModel clientWallet = requireWallet(clientId);
        boolean selfSettle = clientId.equals(builderId);
        WalletModel builderWallet = selfSettle ? clientWallet : loadOrOpen(builderId);

        String correlationId = "settle-" + taskId;
        SettlementBreakdown breakdown = settlementDomainService.settleAcceptance(
                clientWallet, builderWallet, task.budget(), taskId, correlationId);

        taskRepository.save(resolved);
        walletRepository.save(clientWallet);
        if (!selfSettle) {
            walletRepository.save(builderWallet);
        }
        log.info("Task {} accepted by client {}; payout {} to builder {}, commission {}",
                taskId, clientId, breakdown.net(), builderId, breakdown.commission());
        return taskId;
    }

    @Override
    public UUID reject(UUID taskId, UUID clientId, String reason) {
        TaskModel task = loadOwned(taskId, clientId);
        TaskModel resolved = task.reject(reason); // state guard: only RESULT_RECEIVED

        WalletModel clientWallet = requireWallet(clientId);
        settlementDomainService.settleRejection(clientWallet, task.budget(), taskId, "settle-" + taskId);

        taskRepository.save(resolved);
        walletRepository.save(clientWallet);
        log.info("Task {} rejected by client {}; budget {} refunded", taskId, clientId, task.budget());
        return taskId;
    }

    /**
     * Ownership check (Invariant #5): a foreign task is indistinguishable from a missing one.
     * Takes a row-level lock on the task so concurrent resolution attempts serialize (the loser
     * sees RESOLVED and the state guard throws).
     */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

    private WalletModel requireWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }

    private WalletModel loadOrOpen(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(WalletModel.openFor(userId)));
    }
}
