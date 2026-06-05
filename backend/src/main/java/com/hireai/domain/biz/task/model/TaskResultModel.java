package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable child entity of the {@link TaskModel} aggregate: the result an Agent posts back
 * for a task. One row per task (task_results.task_id is UNIQUE). Loaded and saved only through
 * the Task aggregate root. Framework-free.
 */
public final class TaskResultModel {

    private final UUID id;
    private final UUID taskId;
    private final String agentStatus;
    private final String resultPayloadJson;
    private final String resultUrl; // nullable
    private final Instant receivedAt;

    private TaskResultModel(UUID id, UUID taskId, String agentStatus, String resultPayloadJson,
                            String resultUrl, Instant receivedAt) {
        this.id = id;
        this.taskId = taskId;
        this.agentStatus = agentStatus;
        this.resultPayloadJson = resultPayloadJson;
        this.resultUrl = resultUrl;
        this.receivedAt = receivedAt;
    }

    /** Factory for a freshly received result: validates and stamps id + receivedAt. */
    public static TaskResultModel record(UUID taskId, String agentStatus,
                                         String resultPayloadJson, String resultUrl) {
        requirePresent(taskId, "task id");
        requireText(agentStatus, "agent status");
        requirePresent(resultPayloadJson, "result payload");
        return new TaskResultModel(UUID.randomUUID(), taskId, agentStatus.trim(),
                resultPayloadJson, resultUrl, Instant.now());
    }

    /** Rebuild from persisted state (no validation; the row was already valid when written). */
    public static TaskResultModel rehydrate(UUID id, UUID taskId, String agentStatus,
                                            String resultPayloadJson, String resultUrl, Instant receivedAt) {
        return new TaskResultModel(id, taskId, agentStatus, resultPayloadJson, resultUrl, receivedAt);
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public String agentStatus() { return agentStatus; }
    public String resultPayloadJson() { return resultPayloadJson; }
    public String resultUrl() { return resultUrl; }
    public Instant receivedAt() { return receivedAt; }
}
