package com.hireai.infrastructure.repository.task.dispatchattempt;

import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.dispatchattempt.model.DispatchAttemptModel;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
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
                attempt.attemptNumber(), attempt.outcome(), attempt.occurredAt()));
    }

    @Override
    public int countByTaskId(UUID taskId) {
        return jpa.countByTaskId(taskId);
    }

    @Override
    public List<DispatchAttemptView> findByTaskId(UUID taskId) {
        return jpa.findViewsByTaskId(taskId);
    }
}
