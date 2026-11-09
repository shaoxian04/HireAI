package com.hireai.task.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A direct booking whose Agent is no longer ACTIVE, or whose pinned version was superseded, fails
 * fast at dispatch (#77): CANCELLED with AGENT_UNAVAILABLE, a full refund, a client webhook and an
 * UNAVAILABLE dispatch attempt, and nothing against the Builder's reputation. The re-match sweeper
 * never retries a pinned task. Tasks belong to the test profile's dev user so the task API can be
 * read over HTTP.
 */
class PinnedFailFastIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 30.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void aBookingAgainstASupersededVersionIsCancelledAtOnceWithARefund() throws Exception {
        FakeAgent agent = fakeAgent();
        SeededAgent seeded = seedAgent(agent.webhookUrl(), "ACTIVE");
        UUID oldVersion = supersede(seeded, agent.webhookUrl());

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo(), oldVersion);

        assertFailedFast(taskId, oldVersion);
        // The Agent never got a WorkOrder for the dead version.
        assertThat(agent.workOrders()).isEmpty();
    }

    @Test
    void aBookingAgainstADeactivatedAgentIsCancelledAtOnceWithARefund() throws Exception {
        SeededAgent seeded = seedAgent("https://agent.example/run", "DEACTIVATED");

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo(), seeded.versionId());

        assertFailedFast(taskId, seeded.versionId());
    }

    @Test
    void theWebhookCarriesAgentUnavailable() throws Exception {
        SeededAgent seeded = seedAgent("https://agent.example/run", "SUSPENDED");
        UUID taskId = UUID.randomUUID();
        // The task must be attributed to a subscribed API key before routing runs, so seed it SUBMITTED
        // and dispatch it the way the SUBMITTED sweeper does.
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, category, status, "
                        + "gmt_create, pinned_agent_version_id) VALUES (?, ?, 'Pinned', 'Pinned task', 30.00, "
                        + "'{\"format\":\"JSON\"}'::jsonb, 'general', 'SUBMITTED', now() - interval '1 hour', ?)",
                taskId, DEV_USER, seeded.versionId());
        jdbc.update("UPDATE wallets SET available_balance = available_balance - 30.00, "
                + "escrow_balance = escrow_balance + 30.00 WHERE user_id = ?", DEV_USER);
        subscribeToWebhooks(taskId);

        taskReliabilityAppService.rerouteSubmittedOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        JsonNode webhook = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_deliveries WHERE task_id = ?", String.class, taskId));
        assertThat(webhook.get("type").asText()).isEqualTo("task.failed");
        assertThat(webhook.get("reason").asText()).isEqualTo("CANCELLED");
        assertThat(webhook.get("failure_reason").asText()).isEqualTo("AGENT_UNAVAILABLE");
    }

    @Test
    void aPinnedTaskParkedAwaitingAgentIsCancelledAndRefundedByOneSweepWithoutARetry() throws Exception {
        SeededAgent seeded = seedAgent("https://agent.example/run", "SUSPENDED");
        UUID taskId = UUID.randomUUID();
        // A pinned task parked AWAITING_AGENT with escrow frozen, as dispatch left them before #77.
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, category, status, "
                        + "gmt_create, pinned_agent_version_id) VALUES (?, ?, 'Pinned', 'Pinned task', 30.00, "
                        + "'{\"format\":\"JSON\"}'::jsonb, 'general', 'AWAITING_AGENT', now(), ?)",
                taskId, DEV_USER, seeded.versionId());
        jdbc.update("UPDATE wallets SET available_balance = available_balance - 30.00, "
                + "escrow_balance = escrow_balance + 30.00 WHERE user_id = ?", DEV_USER);

        // The sweep still lists it, so its escrow can never be stranded.
        assertThat(taskReliabilityAppService.awaitingAgentTaskIds()).contains(taskId);
        taskReliabilityAppService.rematchOne(taskId);

        assertFailedFast(taskId, seeded.versionId());
        // Ended, not retried: no match attempt was spent on it.
        assertThat(jdbc.queryForObject("SELECT match_attempts FROM tasks WHERE id = ?", Integer.class, taskId))
                .isZero();
    }

    private void assertFailedFast(UUID taskId, UUID versionId) throws Exception {
        assertThat(status(taskId)).isEqualTo("CANCELLED");
        assertThat(getTask(taskId).get("failureReason").asText()).isEqualTo("AGENT_UNAVAILABLE");
        // Full refund through a recorded REJECT settlement.
        assertThat(jdbc.queryForObject("SELECT type FROM settlements WHERE task_id = ?", String.class, taskId))
                .isEqualTo("REJECT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE related_task_id = ? AND entry_type = 'REFUND'",
                Integer.class, taskId)).isEqualTo(1);
        // One UNAVAILABLE attempt against the pinned version.
        assertThat(jdbc.queryForList("SELECT outcome FROM dispatch_attempts WHERE task_id = ? AND agent_version_id = ?",
                String.class, taskId, versionId)).containsExactly("UNAVAILABLE");
        // Deactivating or publishing never counts against the Builder.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
        // Nothing left for the re-match sweeper.
        assertThat(taskReliabilityAppService.awaitingAgentTaskIds()).doesNotContain(taskId);
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private TaskSubmitInfo submitInfo() {
        return new TaskSubmitInfo(DEV_USER, "Summarise report", "Summarise the attached quarterly report",
                Money.of("30.00"), new OutputSpec(OutputFormat.JSON, null, "valid JSON summary"), "general");
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private record SeededAgent(UUID agentId, UUID versionId) {}

    private SeededAgent seedAgent(String webhookUrl, String status) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', ?, ?, 80.00)", agentId, ownerId, status, versionId);
        insertVersion(agentId, versionId, 1, webhookUrl);
        return new SeededAgent(agentId, versionId);
    }

    /** Publishes a version 2 over the seeded one, as AgentWriteAppService does; returns the superseded id. */
    private UUID supersede(SeededAgent seeded, String webhookUrl) {
        jdbc.update("UPDATE agent_versions SET status = 'DEPRECATED' WHERE id = ?", seeded.versionId());
        UUID newVersion = UUID.randomUUID();
        insertVersion(seeded.agentId(), newVersion, 2, webhookUrl);
        jdbc.update("UPDATE agents SET current_version_id = ? WHERE id = ?", newVersion, seeded.agentId());
        return seeded.versionId();
    }

    private void insertVersion(UUID agentId, UUID versionId, int number, String webhookUrl) {
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, ?, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, number, "{\"format\":\"JSON\"}", new String[]{"general"}, webhookUrl,
                new BigDecimal("10.00"));
    }

    /** Attributes the task to an API key with an active webhook subscription, as a programmatic submit would. */
    private void subscribeToWebhooks(UUID taskId) {
        UUID keyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                keyId, DEV_USER, "hash-" + keyId, "hk_live_" + keyId.toString().substring(0, 6));
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?, ?, 30.00)", taskId, keyId);
        jdbc.update("INSERT INTO client_webhook_subscriptions (id, api_key_id, owner_id, callback_url, signing_secret, "
                        + "active, created_at, updated_at) VALUES (?, ?, ?, 'https://client.example/hook', 'whsec_test', "
                        + "TRUE, now(), now())", UUID.randomUUID(), keyId, DEV_USER);
    }
}
