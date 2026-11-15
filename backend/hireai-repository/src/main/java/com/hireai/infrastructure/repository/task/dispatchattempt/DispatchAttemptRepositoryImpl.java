package com.hireai.infrastructure.repository.task.dispatchattempt;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.dispatchattempt.info.ContractDeclineRate;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.dispatchattempt.model.DispatchAttemptModel;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append-only: no update or delete path here, and the V33 triggers refuse both anyway. */
@Repository
public class DispatchAttemptRepositoryImpl implements DispatchAttemptRepository {

    private final DispatchAttemptJpaRepository jpa;

    public DispatchAttemptRepositoryImpl(DispatchAttemptJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void append(DispatchAttemptModel attempt) {
        jpa.save(new DispatchAttemptDO(attempt.id(), attempt.taskId(), attempt.agentVersionId(),
                attempt.attemptNumber(), attempt.outcome(), attempt.occurredAt(), attempt.declineReason()));
    }

    @Override
    public int countByTaskId(UUID taskId) {
        return jpa.countByTaskId(taskId);
    }

    @Override
    public List<DispatchAttemptView> findByTaskId(UUID taskId) {
        return jpa.findViewsByTaskId(taskId);
    }

    @Override
    public Map<UUID, ContractDeclineRate> declineRatesByAgentId(Collection<UUID> agentIds) {
        Map<UUID, ContractDeclineRate> rates = new HashMap<>();
        if (agentIds.isEmpty()) {
            return rates;
        }
        Map<UUID, Map<DispatchOutcome, Long>> counts = new HashMap<>();
        for (Object[] row : jpa.countOutcomesByAgentId(agentIds)) {
            counts.computeIfAbsent((UUID) row[0], id -> new EnumMap<>(DispatchOutcome.class))
                    .put((DispatchOutcome) row[1], (Long) row[2]);
        }
        for (UUID agentId : agentIds) {
            Map<DispatchOutcome, Long> agentCounts = counts.get(agentId);
            rates.put(agentId, agentCounts == null ? ContractDeclineRate.NONE
                    : ContractDeclineRate.fromOutcomeCounts(agentCounts));
        }
        return rates;
    }
}
