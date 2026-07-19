package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookPayloads;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadsTest {
    private final UUID ev = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private final UUID task = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private final Instant at = Instant.parse("2026-07-19T09:12:00Z");

    @Test void wireStrings() {
        assertThat(WebhookEventType.TASK_COMPLETED.wire()).isEqualTo("task.completed");
        assertThat(WebhookEventType.TASK_FAILED.wire()).isEqualTo("task.failed");
    }
    @Test void completedIsThin() {
        assertThat(WebhookPayloads.completed(ev, task, at)).isEqualTo(
            "{\"event_id\":\"" + ev + "\",\"type\":\"task.completed\",\"task_id\":\"" + task
            + "\",\"occurred_at\":\"2026-07-19T09:12:00Z\"}");
    }
    @Test void failedCarriesReasonAndRefund() {
        assertThat(WebhookPayloads.failed(ev, task, "SPEC_VIOLATION", "120", at)).isEqualTo(
            "{\"event_id\":\"" + ev + "\",\"type\":\"task.failed\",\"task_id\":\"" + task
            + "\",\"reason\":\"SPEC_VIOLATION\",\"refunded\":120,\"occurred_at\":\"2026-07-19T09:12:00Z\"}");
    }
}
