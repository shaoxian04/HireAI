package com.hireai.dispatch;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Dispatch retry (#68): a failed WorkOrder send is retried from a delayed retry queue instead of
 * blocking the consumer, so one hanging or unreachable Agent never delays anyone else's dispatch.
 * A dispatch that runs out of attempts fails the task with a refund, a client webhook and an
 * {@code EXECUTION_FAILED} reputation event. The harness configures a short retry schedule
 * (three short delays, four attempts in total).
 */
class DispatchRetryIntegrationTest extends WholeAppIntegrationTest {

    private static final int TOTAL_ATTEMPTS = 4;
    /** {@code hireai.dispatch.platform-retries} default: re-schedules after a platform-side failure. */
    private static final int PLATFORM_RETRIES = 2;

    @Autowired TaskWriteAppService taskWriteAppService;
    @SpyBean DispatchTokenService dispatchTokenService;
    @SpyBean TaskExecutionPort taskExecutionPort;

    @Test
    void anAgentThatKeepsFailingGetsEveryAttemptThenTheTaskFailsWithARefundWebhookAndReputationEvent() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.status(500));
        String category = "x-" + UUID.randomUUID();
        UUID versionId = seedActiveAgent(category, agent.webhookUrl());
        UUID client = newClientWithCredits();

        UUID taskId = submit(client, category);
        subscribeToWebhooks(client, taskId);

        List<RecordedWorkOrder> orders = agent.awaitWorkOrders(TOTAL_ATTEMPTS, Duration.ofSeconds(30));
        assertThat(orders).allSatisfy(order -> assertThat(order.taskId()).isEqualTo(taskId.toString()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));

        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?", client);
        assertThat((BigDecimal) wallet.get("available_balance")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) wallet.get("escrow_balance")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_deliveries WHERE task_id = ?",
                String.class, taskId)).containsExactly("task.failed");
        assertThat(jdbc.queryForList(
                "SELECT e.event_type FROM reputation_events e JOIN agent_versions v ON v.agent_id = e.agent_id "
                        + "WHERE e.task_id = ? AND v.id = ?",
                String.class, taskId, versionId)).containsExactly("EXECUTION_FAILED");
        // No fifth attempt after the dead-letter.
        assertThat(agent.workOrders()).hasSize(TOTAL_ATTEMPTS);
    }

    @Test
    void anAgentThatRecoversBeforeTheLastAttemptGetsTheTask() {
        FakeAgent agent = fakeAgent()
                .replyOnceWith(FakeAgentReply.status(500))
                .replyOnceWith(FakeAgentReply.status(500))
                .replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());

        UUID taskId = submit(newClientWithCredits(), category);

        agent.awaitWorkOrders(3, Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        assertThat(agent.workOrders()).hasSize(3);
    }

    @Test
    void aHangingAgentDoesNotDelayASecondTasksDispatch() {
        FakeAgent hanging = fakeAgent().replyWith(FakeAgentReply.hang());
        FakeAgent healthy = fakeAgent().replyWith(FakeAgentReply.accepted());
        String hangingCategory = "x-" + UUID.randomUUID();
        String healthyCategory = "x-" + UUID.randomUUID();
        seedActiveAgent(hangingCategory, hanging.webhookUrl());
        seedActiveAgent(healthyCategory, healthy.webhookUrl());

        UUID stuckTask = submit(newClientWithCredits(), hangingCategory);
        hanging.awaitWorkOrders(1, Duration.ofSeconds(20));

        UUID secondTask = submit(newClientWithCredits(), healthyCategory);

        // Well inside the 15s work-order read timeout the first dispatch is still waiting on.
        healthy.awaitWorkOrders(1, Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(status(secondTask)).isEqualTo("EXECUTING"));
        assertThat(status(stuckTask)).isEqualTo("QUEUED");
    }

    @Test
    void anUnreachableAgentDoesNotDelayASecondTasksDispatch() throws IOException {
        FakeAgent healthy = fakeAgent().replyWith(FakeAgentReply.accepted());
        String deadCategory = "x-" + UUID.randomUUID();
        String healthyCategory = "x-" + UUID.randomUUID();
        seedActiveAgent(deadCategory, unreachableWebhookUrl());
        seedActiveAgent(healthyCategory, healthy.webhookUrl());

        UUID deadTask = submit(newClientWithCredits(), deadCategory);
        UUID secondTask = submit(newClientWithCredits(), healthyCategory);

        healthy.awaitWorkOrders(1, Duration.ofSeconds(5));
        // The unreachable Agent's dispatch is still waiting out its retry delays.
        assertThat(status(deadTask)).isEqualTo("QUEUED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(status(secondTask)).isEqualTo("EXECUTING"));
        // ...and then runs out of attempts.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(deadTask)).isEqualTo("FAILED"));
    }

    @Test
    void aTransientPlatformSideFailureBeforeTheSendIsRescheduledWithoutUsingAnAgentAttempt() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        UUID versionId = seedActiveAgent(category, agent.webhookUrl());
        doThrow(new IllegalStateException("token signing briefly unavailable"))
                .doCallRealMethod()
                .when(dispatchTokenService).issue(any(), eq(versionId), any());

        UUID taskId = submit(newClientWithCredits(), category);

        // Re-scheduled instead of dropped: the task does not sit QUEUED until its execution timeout.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        assertThat(agent.workOrders()).hasSize(1);
        // The platform's failure used no Agent attempt and is not in the Agent's history.
        assertThat(jdbc.queryForList("SELECT attempt_number || ':' || outcome FROM dispatch_attempts "
                + "WHERE task_id = ?", String.class, taskId)).containsExactly("1:ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    @Test
    void aFailureEndingABusyDeclinedTaskIsRescheduledInsteadOfStrandingIt() {
        FakeAgent busy = fakeAgent().replyWith(FakeAgentReply.status(429));
        String category = "x-" + UUID.randomUUID();
        UUID versionId = seedActiveAgent(category, busy.webhookUrl());
        doThrow(new IllegalStateException("database briefly unavailable"))
                .doCallRealMethod()
                .when(taskExecutionPort).declineAsBusy(any(), eq(versionId));

        UUID taskId = submit(newClientWithCredits(), category);

        // Offered again after the platform retry delay; the second decline ends the task (no other Agent).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(busy.workOrders()).hasSize(2);
        assertThat(jdbc.queryForList("SELECT outcome FROM dispatch_attempts WHERE task_id = ?",
                String.class, taskId)).containsExactly("BUSY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    @Test
    void aPlatformSideFailureBeforeTheSendIsNeverBlamedOnTheAgent() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        UUID versionId = seedActiveAgent(category, agent.webhookUrl());
        doThrow(new IllegalStateException("token signing unavailable"))
                .when(dispatchTokenService).issue(any(), eq(versionId), any());
        UUID client = newClientWithCredits();

        UUID taskId = submit(client, category);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(dispatchTokenService, atLeastOnce()).issue(any(), eq(versionId), any()));
        // Longer than the harness's whole retry schedule: had the failure consumed Agent attempts,
        // the task would have dead-lettered to FAILED with EXECUTION_FAILED by now.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(status(taskId)).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                    Integer.class, taskId)).isZero();
        });
        // Re-scheduled a bounded number of times, then given up (the first try plus the re-schedules).
        verify(dispatchTokenService, times(1 + PLATFORM_RETRIES)).issue(any(), eq(versionId), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_attempts WHERE task_id = ?",
                Integer.class, taskId)).isZero();
        assertThat(agent.workOrders()).isEmpty();
        // The escrow stays frozen until the execution-timeout sweeper refunds the QUEUED task
        // without blame (#66).
        assertThat(jdbc.queryForObject("SELECT escrow_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, client)).isEqualByComparingTo("30.00");
    }

    private static String unreachableWebhookUrl() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return "http://localhost:" + port + "/run";
    }

    private UUID submit(UUID client, String category) {
        return taskWriteAppService.submit(new TaskSubmitInfo(client, "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
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
        return versionId;
    }
}
