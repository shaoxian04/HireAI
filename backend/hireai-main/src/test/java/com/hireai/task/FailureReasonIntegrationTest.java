package com.hireai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
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
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Every existing terminal-failure path records its FailureReason (#74), and the reason reaches the
 * client through {@code GET /api/tasks/{id}} and the {@code task.failed} webhook body. Tasks belong
 * to the test profile's dev user so the task API can be read over HTTP.
 */
@Import(TestClockConfig.class)
class FailureReasonIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Requires an "answer" field the fake Agent's standard result does not have. */
    private static final String STRICT_SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"]}";

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;
    @Autowired ObjectMapper objectMapper;
    @SpyBean RoutingAppService routingAppService;

    private Instant start;

    @BeforeEach
    void setUp() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 30.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void anExhaustedDispatchIsAgentUnreachableInTheApiAndTheWebhook() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.status(500));
        String category = "x-" + UUID.randomUUID();
        seedAgent(category, agent.webhookUrl(), "ACTIVE");

        UUID taskId = taskWriteAppService.submit(submitInfo(category, null));
        subscribeToWebhooks(taskId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiFailureReason(taskId)).isEqualTo("AGENT_UNREACHABLE");
        JsonNode webhook = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_deliveries WHERE task_id = ?", String.class, taskId));
        assertThat(webhook.get("type").asText()).isEqualTo("task.failed");
        assertThat(webhook.get("reason").asText()).isEqualTo("FAILED");
        assertThat(webhook.get("failure_reason").asText()).isEqualTo("AGENT_UNREACHABLE");
    }

    @Test
    void anAgentReportedFailureIsAgentReportedFailure() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.completed()
                .withBody("{\"agentStatus\":\"FAILED\",\"resultPayloadJson\":\"{}\",\"message\":\"could not do it\"}")));
        String category = "x-" + UUID.randomUUID();
        seedAgent(category, agent.webhookUrl(), "ACTIVE");
        shareSigningSecret(agent);

        UUID taskId = taskWriteAppService.submit(submitInfo(category, null));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiFailureReason(taskId)).isEqualTo("AGENT_REPORTED_FAILURE");
    }

    @Test
    void aResultThatBreaksTheSpecIsSpecViolation() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.completed()));
        String category = "x-" + UUID.randomUUID();
        seedAgent(category, agent.webhookUrl(), "ACTIVE");
        shareSigningSecret(agent);

        UUID taskId = taskWriteAppService.submit(submitInfo(category, STRICT_SCHEMA));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("SPEC_VIOLATION"));
        assertThat(apiFailureReason(taskId)).isEqualTo("SPEC_VIOLATION");
    }

    @Test
    void anExecutingTaskPastItsDeadlineIsExecutionTimeout() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        seedAgent(category, agent.webhookUrl(), "ACTIVE");
        UUID taskId = taskWriteAppService.submit(submitInfo(category, null));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));

        clock.set(start.plus(Duration.ofHours(1)));
        taskReliabilityAppService.timeoutOne(taskId);

        assertThat(status(taskId)).isEqualTo("TIMED_OUT");
        assertThat(apiFailureReason(taskId)).isEqualTo("EXECUTION_TIMEOUT");
    }

    @Test
    void aRoutedTaskThatNeverFindsAnAgentIsNoEligibleAgent() throws Exception {
        UUID taskId = taskWriteAppService.submit(submitInfo("x-" + UUID.randomUUID(), null));
        assertThat(status(taskId)).isEqualTo("AWAITING_AGENT");

        taskReliabilityAppService.rematchOne(taskId);
        taskReliabilityAppService.rematchOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        assertThat(apiFailureReason(taskId)).isEqualTo("NO_ELIGIBLE_AGENT");
    }

    @Test
    void aDirectBookingWhoseAgentStaysUnavailableIsAgentUnavailable() throws Exception {
        UUID versionId = seedAgent("x-" + UUID.randomUUID(), "https://agent.example/run", "SUSPENDED");
        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo("general", null), versionId);
        assertThat(status(taskId)).isEqualTo("AWAITING_AGENT");

        taskReliabilityAppService.rematchOne(taskId);
        taskReliabilityAppService.rematchOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        assertThat(apiFailureReason(taskId)).isEqualTo("AGENT_UNAVAILABLE");
    }

    @Test
    void aTaskRoutingNeverReachedIsDispatchAbandonedInTheApiAndTheWebhook() throws Exception {
        doThrow(new IllegalStateException("simulated routing failure")).when(routingAppService).route(any());
        UUID taskId = taskWriteAppService.submit(submitInfo("x-" + UUID.randomUUID(), null));
        subscribeToWebhooks(taskId);
        clock.set(start.plusSeconds(31));

        taskReliabilityAppService.rerouteSubmittedOne(taskId);
        taskReliabilityAppService.rerouteSubmittedOne(taskId);
        taskReliabilityAppService.rerouteSubmittedOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        assertThat(apiFailureReason(taskId)).isEqualTo("DISPATCH_ABANDONED");
        JsonNode webhook = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_deliveries WHERE task_id = ?", String.class, taskId));
        assertThat(webhook.get("failure_reason").asText()).isEqualTo("DISPATCH_ABANDONED");
    }

    @Test
    void aLiveTaskHasNoFailureReasonInTheApi() throws Exception {
        UUID taskId = taskWriteAppService.submit(submitInfo("x-" + UUID.randomUUID(), null));

        JsonNode data = getTask(taskId);
        assertThat(data.get("status").asText()).isEqualTo("AWAITING_AGENT");
        assertThat(data.has("failureReason")).isFalse();
    }

    private String apiFailureReason(UUID taskId) throws Exception {
        JsonNode reason = getTask(taskId).get("failureReason");
        return reason == null ? null : reason.asText();
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private TaskSubmitInfo submitInfo(String category, String schema) {
        return new TaskSubmitInfo(DEV_USER, "Summarise report", "Summarise the attached quarterly report",
                Money.of("30.00"), new OutputSpec(OutputFormat.JSON, schema, "valid JSON summary"), category);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
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

    private UUID seedAgent(String category, String webhookUrl, String status) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', ?, ?, 80.00)", agentId, ownerId, status, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
        return versionId;
    }
}
