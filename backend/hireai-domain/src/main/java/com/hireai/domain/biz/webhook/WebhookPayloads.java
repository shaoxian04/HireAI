package com.hireai.domain.biz.webhook;

import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import java.time.Instant;
import java.util.UUID;

/** Thin webhook JSON bodies. All fields are safe types (UUID/enum/number/ISO) — manual JSON is safe. */
public final class WebhookPayloads {
    private WebhookPayloads() {}

    public static String completed(UUID eventId, UUID taskId, Instant at) {
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"" + WebhookEventType.TASK_COMPLETED.wire()
             + "\",\"task_id\":\"" + taskId + "\",\"occurred_at\":\"" + at + "\"}";
    }

    public static String failed(UUID eventId, UUID taskId, String reason, String refundedAmount, Instant at) {
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"" + WebhookEventType.TASK_FAILED.wire()
             + "\",\"task_id\":\"" + taskId + "\",\"reason\":\"" + reason
             + "\",\"refunded\":" + refundedAmount + ",\"occurred_at\":\"" + at + "\"}";
    }
}
