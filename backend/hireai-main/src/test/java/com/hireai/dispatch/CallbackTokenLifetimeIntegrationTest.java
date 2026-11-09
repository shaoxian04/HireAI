package com.hireai.dispatch;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.clock.MutableTestClock;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression for #64: the callback token lives until the task's execution deadline plus 5 minutes,
 * so a slow-but-legitimate Agent working within its declared limit is never refused. The old fixed
 * 15-minute TTL rejected any Agent declaring more than about 14 minutes.
 */
@Import(TestClockConfig.class)
class CallbackTokenLifetimeIntegrationTest extends WholeAppIntegrationTest {

    /** Two hours: far beyond the old fixed 15-minute token lifetime. */
    private static final int MAX_EXECUTION_SECONDS = 7_200;
    /** {@code hireai.execution.grace-seconds} default. */
    private static final int GRACE_SECONDS = 60;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;

    private Instant start;
    private String agentSecret;

    @BeforeEach
    void startTheClock() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
    }

    @Test
    void aLongRunningAgentsCallbackIsAcceptedAtItsExecutionDeadline() throws Exception {
        RecordedWorkOrder order = dispatchToSlowAgent();
        Instant deadline = start.plusSeconds(MAX_EXECUTION_SECONDS + GRACE_SECONDS);

        clock.set(deadline);
        HttpResponse<String> response = callBack(order);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(status(UUID.fromString(order.taskId()))).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void aCallbackIsRejectedOnceTheDeadlinePlusFiveMinutesHasPassed() throws Exception {
        RecordedWorkOrder order = dispatchToSlowAgent();
        Instant deadline = start.plusSeconds(MAX_EXECUTION_SECONDS + GRACE_SECONDS);

        clock.set(deadline.plus(Duration.ofMinutes(5)).plusSeconds(1));
        HttpResponse<String> response = callBack(order);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(status(UUID.fromString(order.taskId()))).isEqualTo("EXECUTING");
    }

    private RecordedWorkOrder dispatchToSlowAgent() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()); // accepts, calls back later
        String category = "slow-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());
        shareSigningSecret(agent);
        agentSecret = agent.signingSecret();
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(newClientWithCredits(), "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
        RecordedWorkOrder order = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        return order;
    }

    private HttpResponse<String> callBack(RecordedWorkOrder order) throws Exception {
        String body = AgentCallback.completedBody(order.taskId());
        HttpRequest request = HttpRequest.newBuilder(URI.create(order.callbackUrl()))
                .header("Authorization", "Bearer " + order.bearerToken())
                .header("Content-Type", "application/json")
                // Signed "now" on the platform's (moved) clock, so only the token's lifetime is tested.
                .header(AgentCallback.SIGNATURE_HEADER, AgentCallback.signature(agentSecret, clock.instant(),
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private UUID newClientWithCredits() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), id);
        return id;
    }

    private void seedActiveAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Slow Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, ?, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                MAX_EXECUTION_SECONDS, new BigDecimal("10.00"));
    }
}
