package com.hireai.application.biz.webhook;

import com.hireai.domain.biz.task.model.TaskModel;

/** Inserts a webhook delivery row (outbox) at a terminal transition — same transaction as the state change. */
public interface WebhookOutboxAppService {
    void enqueueCompleted(TaskModel task);
    void enqueueFailed(TaskModel task, String reason);
}
