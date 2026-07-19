package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryModelTest {
    private final WebhookBackoffPolicy backoff = new WebhookBackoffPolicy(10, 3600, 3);
    private final Instant t0 = Instant.parse("2026-07-19T00:00:00Z");

    private WebhookDeliveryModel pending() {
        return WebhookDeliveryModel.enqueue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), WebhookEventType.TASK_COMPLETED, "{}", "https://x/y", t0);
    }

    @Test void enqueueStartsPendingDueNow() {
        WebhookDeliveryModel d = pending();
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d.attempts()).isZero();
        assertThat(d.nextAttemptAt()).isEqualTo(t0);
    }
    @Test void markDeliveredIsTerminal() {
        WebhookDeliveryModel d = pending().markDelivered(t0.plusSeconds(1));
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(d.deliveredAt()).isEqualTo(t0.plusSeconds(1));
        assertThat(d.attempts()).isEqualTo(1);
    }
    @Test void failureBacksOffThenGoesDeadAtMax() {
        WebhookDeliveryModel d1 = pending().recordFailure(t0, "500", backoff);
        assertThat(d1.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d1.attempts()).isEqualTo(1);
        assertThat(d1.nextAttemptAt()).isEqualTo(t0.plusSeconds(10));
        assertThat(d1.lastError()).isEqualTo("500");

        WebhookDeliveryModel d2 = d1.recordFailure(t0, "500", backoff); // attempts -> 2
        assertThat(d2.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        WebhookDeliveryModel d3 = d2.recordFailure(t0, "timeout", backoff); // attempts -> 3 == max
        assertThat(d3.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(d3.lastError()).isEqualTo("timeout");
    }
    @Test void requeueResetsDeadToPendingWithFreshBudget() {
        WebhookDeliveryModel dead = pending().recordFailure(t0, "x", backoff)
                .recordFailure(t0, "x", backoff).recordFailure(t0, "x", backoff);
        WebhookDeliveryModel again = dead.requeue(t0.plusSeconds(100));
        assertThat(again.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(again.attempts()).isZero();
        assertThat(again.nextAttemptAt()).isEqualTo(t0.plusSeconds(100));
        assertThat(again.lastError()).isNull();
    }
}
