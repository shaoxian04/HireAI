package com.hireai.task.reliability;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.clock.MutableTestClock;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The re-match sweeper's limit (#73): a routed task that finds no eligible Agent waits in
 * AWAITING_AGENT and, after two re-match attempts that still find none, is cancelled with a full
 * refund and a client webhook. The sweeper seam is driven directly with a fixed {@code Clock}
 * (the sweepers are off under the {@code test} profile).
 */
@Import(TestClockConfig.class)
class AwaitingAgentRematchIntegrationTest extends WholeAppIntegrationTest {

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;

    private Instant start;

    @BeforeEach
    void fixClock() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
    }

    @Test
    void aTaskWithNoEligibleAgentIsCancelledAndRefundedAfterTwoRematchAttempts() {
        UUID client = newClientWithCredits();
        UUID taskId = taskWriteAppService.submit(submitInfo(client, "x-" + UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("AWAITING_AGENT"));
        subscribeToWebhooks(client, taskId);
        assertThat(taskReliabilityAppService.awaitingAgentTaskIds()).contains(taskId);

        clock.advance(Duration.ofSeconds(10));
        taskReliabilityAppService.rematchOne(taskId);
        assertThat(status(taskId)).isEqualTo("AWAITING_AGENT");
        assertThat(escrow(client)).isEqualByComparingTo("30.00");

        clock.advance(Duration.ofSeconds(10));
        taskReliabilityAppService.rematchOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?", client);
        assertThat((BigDecimal) wallet.get("available_balance")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) wallet.get("escrow_balance")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_deliveries WHERE task_id = ?",
                String.class, taskId)).containsExactly("task.failed");
        assertThat(taskReliabilityAppService.awaitingAgentTaskIds()).doesNotContain(taskId);
    }

    private TaskSubmitInfo submitInfo(UUID client, String category) {
        return new TaskSubmitInfo(client, "Summarise report", "Summarise the attached quarterly report",
                Money.of("30.00"), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"),
                category);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private BigDecimal escrow(UUID user) {
        return jdbc.queryForObject("SELECT escrow_balance FROM wallets WHERE user_id = ?", BigDecimal.class, user);
    }

    /** Attributes the task to an API key with an active webhook subscription, as a programmatic submit would. */
    private void subscribeToWebhooks(UUID owner, UUID taskId) {
        UUID keyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                keyId, owner, "hash-" + keyId, "hk_live_" + keyId.toString().substring(0, 6));
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?, ?, 30.00)", taskId, keyId);
        jdbc.update("INSERT INTO client_webhook_subscriptions (id, api_key_id, owner_id, callback_url, signing_secret, "
                        + "active, created_at, updated_at) VALUES (?, ?, ?, 'https://client.example/hook', 'whsec_test', "
                        + "TRUE, now(), now())", UUID.randomUUID(), keyId, owner);
    }

    private UUID newClientWithCredits() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), id);
        return id;
    }
}
