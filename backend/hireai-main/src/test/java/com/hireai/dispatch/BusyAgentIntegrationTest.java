package com.hireai.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An Agent at capacity declines a WorkOrder with 429 or 503 (#76). The busy reply is final for that
 * Agent: it is not retried, it records a {@code BUSY} dispatch attempt and no reputation event. A
 * pinned (directly booked) task then ends FAILED with {@code AGENT_BUSY}, a refund and a
 * {@code task.failed} webhook. A routed task fails over instead (#78); with no other Agent it ends
 * FAILED with {@code NO_ALTERNATIVE_AGENT} (failover itself is covered by RoutedFailoverIntegrationTest).
 */
class BusyAgentIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Longer than the harness's whole retry schedule (three 500ms delays). */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired ObjectMapper objectMapper;

    private BigDecimal startingBalance;

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 30.00 WHERE user_id = ?", DEV_USER);
        startingBalance = jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void aPinnedBookingToABusyAgentFailsAsAgentBusyWithARefundAWebhookAndNoPenalty(int busyStatus)
            throws Exception {
        // The reply is delayed so the webhook subscription below is in place before the task ends.
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.status(busyStatus).afterDelay(Duration.ofSeconds(1)));
        UUID versionId = seedActiveAgent("x-" + UUID.randomUUID(), agent.webhookUrl());

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo("general"), versionId);
        subscribeToWebhooks(taskId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiFailureReason(taskId)).isEqualTo("AGENT_BUSY");
        assertRefunded(taskId);
        JsonNode webhook = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_deliveries WHERE task_id = ?", String.class, taskId));
        assertThat(webhook.get("type").asText()).isEqualTo("task.failed");
        assertThat(webhook.get("failure_reason").asText()).isEqualTo("AGENT_BUSY");
        assertNotRetriedAndNotPenalised(agent, taskId, versionId);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void aRoutedTaskWhoseOnlyAgentIsBusyFailsWithNoAlternativeWithoutARetryOrPenalty(int busyStatus)
            throws Exception {
        // Routed failover (#78) looks for another Agent; with none in the category the task ends.
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.status(busyStatus));
        String category = "x-" + UUID.randomUUID();
        UUID versionId = seedActiveAgent(category, agent.webhookUrl());

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiFailureReason(taskId)).isEqualTo("NO_ALTERNATIVE_AGENT");
        assertRefunded(taskId);
        assertNotRetriedAndNotPenalised(agent, taskId, versionId);
    }

    private void assertNotRetriedAndNotPenalised(FakeAgent agent, UUID taskId, UUID versionId) {
        // Exactly one WorkOrder: no retry-queue attempt, and no automatic resend by the HTTP client
        // (the shared Apache client used to resend a 429/503 POST once by itself).
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() ->
                assertThat(agent.workOrders()).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
        assertThat(jdbc.queryForList("SELECT outcome FROM dispatch_attempts WHERE task_id = ? "
                + "AND agent_version_id = ? ORDER BY attempt_number", String.class, taskId, versionId))
                .containsExactly("BUSY");
    }

    private void assertRefunded(UUID taskId) {
        assertThat(jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER)).isEqualByComparingTo(startingBalance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    private String apiFailureReason(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        JsonNode reason = objectMapper.readTree(body).get("data").get("failureReason");
        return reason == null ? null : reason.asText();
    }

    private TaskSubmitInfo submitInfo(String category) {
        return new TaskSubmitInfo(DEV_USER, "Summarise report", "Summarise the attached quarterly report",
                Money.of("10.00"), new OutputSpec(OutputFormat.JSON, null, "valid JSON summary"), category);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    /** Attributes the task to an API key with an active webhook subscription, as a programmatic submit would. */
    private void subscribeToWebhooks(UUID taskId) {
        UUID keyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                keyId, DEV_USER, "hash-" + keyId, "hk_live_" + keyId.toString().substring(0, 6));
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?, ?, 10.00)", taskId, keyId);
        jdbc.update("INSERT INTO client_webhook_subscriptions (id, api_key_id, owner_id, callback_url, signing_secret, "
                        + "active, created_at, updated_at) VALUES (?, ?, ?, 'https://client.example/hook', 'whsec_test', "
                        + "TRUE, now(), now())", UUID.randomUUID(), keyId, DEV_USER);
    }

    private UUID seedActiveAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Busy Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
        return versionId;
    }
}
