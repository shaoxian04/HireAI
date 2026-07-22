package com.hireai.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.webhook.WebhookPayloads;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadsTest {
    private final UUID ev = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private final UUID task = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private final Instant at = Instant.parse("2026-07-19T09:12:00Z");
    private final ObjectMapper mapper = new ObjectMapper(); // test-only: proves the hand-built JSON is well-formed

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
        assertThat(WebhookPayloads.failed(ev, task, "SPEC_VIOLATION", new BigDecimal("120.00"), at)).isEqualTo(
            "{\"event_id\":\"" + ev + "\",\"type\":\"task.failed\",\"task_id\":\"" + task
            + "\",\"reason\":\"SPEC_VIOLATION\",\"refunded\":120.00,\"occurred_at\":\"2026-07-19T09:12:00Z\"}");
    }
    @Test void failedEscapesReasonSoJsonStaysWellFormed() throws Exception {
        // A reason carrying a double-quote, backslash and control char must be escaped, not injected raw —
        // otherwise a malformed body ships under a valid signature. Parse it back to prove well-formedness.
        String tricky = "bad\"reason\\with\ncontrol";
        String json = WebhookPayloads.failed(ev, task, tricky, new BigDecimal("5.00"), at);
        JsonNode node = mapper.readTree(json); // throws if the escaping produced malformed JSON
        assertThat(node.get("reason").asText()).isEqualTo(tricky); // round-trips the exact reason
        assertThat(node.get("refunded").decimalValue()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(node.get("type").asText()).isEqualTo("task.failed");
    }
}
