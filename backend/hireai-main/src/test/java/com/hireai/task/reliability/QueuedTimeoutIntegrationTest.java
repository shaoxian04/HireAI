package com.hireai.task.reliability;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.clock.MutableTestClock;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * Timeout blame (#66): an Agent is penalised for a timeout only if it actually had the task. A
 * task whose WorkOrder never left the platform (the dispatch message was lost, so the task is still
 * QUEUED at its deadline) times out with a refund and a client webhook but no reputation event; a
 * task that timed out while EXECUTING keeps EXECUTION_TIMEOUT. Driven through the timeout
 * sweeper's application service with the test Clock.
 */
@Import(TestClockConfig.class)
class QueuedTimeoutIntegrationTest extends WholeAppIntegrationTest {

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;
    @SpyBean TaskDispatchPublisher taskDispatchPublisher;

    private Instant start;

    @BeforeEach
    void resetClock() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
    }

    @Test
    void aTaskThatTimesOutWhileQueuedIsRefundedWithAWebhookAndNoReputationEvent() {
        doNothing().when(taskDispatchPublisher).publish(any()); // the WorkOrder message is lost
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        UUID agentId = seedActiveAgent(category, agent.webhookUrl());
        UUID client = newClientWithCredits();

        UUID taskId = submitRouted(client, category);
        subscribeToWebhooks(client, taskId);
        assertThat(status(taskId)).isEqualTo("QUEUED");

        clock.set(start.plusSeconds(121)); // past max execution 60s + default grace 60s
        assertThat(taskReliabilityAppService.executionExpiredTaskIds()).contains(taskId);
        taskReliabilityAppService.timeoutOne(taskId);

        assertThat(status(taskId)).isEqualTo("TIMED_OUT");
        assertRefunded(client, taskId);
        assertThat(webhookEvents(taskId)).containsExactly("task.failed");
        assertThat(reputationEvents(taskId)).isEmpty();
        assertThat(reputationEventsForAgent(agentId)).isZero();
        assertThat(agent.workOrders()).isEmpty();
    }

    @Test
    void aTaskThatTimesOutWhileExecutingStillPenalisesTheAgent() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()); // accepts, never calls back
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());
        UUID client = newClientWithCredits();

        UUID taskId = submitRouted(client, category);
        subscribeToWebhooks(client, taskId);
        agent.awaitWorkOrders(1, Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));

        clock.set(start.plusSeconds(121));
        assertThat(taskReliabilityAppService.executionExpiredTaskIds()).contains(taskId);
        taskReliabilityAppService.timeoutOne(taskId);

        assertThat(status(taskId)).isEqualTo("TIMED_OUT");
        assertRefunded(client, taskId);
        assertThat(webhookEvents(taskId)).containsExactly("task.failed");
        assertThat(reputationEvents(taskId)).containsExactly("EXECUTION_TIMEOUT");
    }

    private UUID submitRouted(UUID client, String category) {
        return taskWriteAppService.submit(new TaskSubmitInfo(client, "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
    }

    private void assertRefunded(UUID client, UUID taskId) {
        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?", client);
        assertThat((BigDecimal) wallet.get("available_balance")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) wallet.get("escrow_balance")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private java.util.List<String> webhookEvents(UUID taskId) {
        return jdbc.queryForList("SELECT event_type FROM webhook_deliveries WHERE task_id = ?", String.class, taskId);
    }

    private java.util.List<String> reputationEvents(UUID taskId) {
        return jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?", String.class, taskId);
    }

    private int reputationEventsForAgent(UUID agentId) {
        return jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE agent_id = ?", Integer.class, agentId);
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

    /** Seeds an ACTIVE agent (max execution 60s, price 10.00) and returns its agent id. */
    private UUID seedActiveAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
        return agentId;
    }
}
