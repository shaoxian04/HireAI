package com.hireai.domain.biz.task.dispatchattempt.repository;

import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.dispatchattempt.model.DispatchAttemptModel;

import java.util.List;
import java.util.UUID;

/**
 * Persistence contract for the append-only dispatch history. There is deliberately no update or
 * delete: the database refuses both (V33).
 */
public interface DispatchAttemptRepository {

    void append(DispatchAttemptModel attempt);

    /** How many attempts this task has recorded; the next attempt's number is this plus one. */
    int countByTaskId(UUID taskId);

    /** The task's history in attempt order, each with the Agent that owns the version. */
    List<DispatchAttemptView> findByTaskId(UUID taskId);
}
