package com.hireai.task.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * A routing failure right after submit (#67): the Client still gets a successful submit response
 * for a task whose escrow is frozen, and the SUBMITTED sweeper later routes it, or cancels it with
 * a refund and a client webhook once the match attempts run out. The routing failure is injected
 * by making {@link RoutingAppService} throw (a stand-in for a database or broker blip); everything
 * else is the real app.
 */
@Import(TestClockConfig.class)
class SubmittedSweeperIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;
    @Autowired ObjectMapper objectMapper;
    @SpyBean RoutingAppService routingAppService;

    private Instant start;

    @BeforeEach
    void resetClock() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
    }

    @Test
    void aRoutingFailureAtSubmitStillReturnsASuccessfulSubmitWithTheTaskSubmitted() throws Exception {
        doThrow(new IllegalStateException("simulated routing failure")).when(routingAppService).route(any());
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 30.00 WHERE user_id = ?", DEV_USER);
        BigDecimal escrowBefore = escrow(DEV_USER);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"title":"Summarise report","description":"Summarise the attached quarterly report",
                 "category":"%s","budget":30.00,
                 "outputSpec":{"format":"JSON","schema":"{\\"type\\":\\"object\\"}","acceptanceCriteria":"valid JSON"}}
                """.formatted("x-" + UUID.randomUUID());
        ResponseEntity<String> response = new RestTemplate().postForEntity(
                platformUrl("/api/tasks"), new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("status").asText()).isEqualTo("SUBMITTED");
        UUID taskId = UUID.fromString(data.get("id").asText());
        assertThat(status(taskId)).isEqualTo("SUBMITTED");
        assertThat(escrow(DEV_USER)).isEqualByComparingTo(escrowBefore.add(new BigDecimal("30.00")));
    }

    @Test
    void theSweeperRoutesAStrandedSubmittedTaskOnALaterRun() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());
        UUID client = newClientWithCredits();
        doThrow(new IllegalStateException("simulated routing failure")).when(routingAppService).route(any());

        UUID taskId = submitRouted(client, category);
        assertThat(status(taskId)).isEqualTo("SUBMITTED");
        assertThat(agent.workOrders()).isEmpty();

        clock.set(start.plusSeconds(20));
        assertThat(taskReliabilityAppService.strandedSubmittedTaskIds()).doesNotContain(taskId);

        doCallRealMethod().when(routingAppService).route(any());
        clock.set(start.plusSeconds(31));
        assertThat(taskReliabilityAppService.strandedSubmittedTaskIds()).contains(taskId);
        taskReliabilityAppService.rerouteSubmittedOne(taskId);

        assertThat(agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0).taskId()).isEqualTo(taskId.toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        assertThat(taskReliabilityAppService.strandedSubmittedTaskIds()).doesNotContain(taskId);
    }

    @Test
    void theSweeperDispatchesAStrandedDirectBookingToItsPinnedVersion() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        UUID versionId = seedActiveAgent("x-" + UUID.randomUUID(), agent.webhookUrl());
        UUID client = newClientWithCredits();
        doThrow(new IllegalStateException("simulated routing failure"))
                .when(routingAppService).dispatchDirect(any(), any());

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo(client, "general"), versionId);
        assertThat(status(taskId)).isEqualTo("SUBMITTED");

        doCallRealMethod().when(routingAppService).dispatchDirect(any(), any());
        clock.set(start.plusSeconds(31));
        taskReliabilityAppService.rerouteSubmittedOne(taskId);

        assertThat(agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0).taskId()).isEqualTo(taskId.toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
    }

    @Test
    void aTaskThatStillCannotBeRoutedAfterThreeAttemptsIsCancelledWithARefundAndAWebhook() {
        UUID client = newClientWithCredits();
        doThrow(new IllegalStateException("simulated routing failure")).when(routingAppService).route(any());
        UUID taskId = submitRouted(client, "x-" + UUID.randomUUID());
        subscribeToWebhooks(client, taskId);
        clock.set(start.plusSeconds(31));

        taskReliabilityAppService.rerouteSubmittedOne(taskId);
        taskReliabilityAppService.rerouteSubmittedOne(taskId);
        assertThat(status(taskId)).isEqualTo("SUBMITTED");
        assertThat(escrow(client)).isEqualByComparingTo("30.00");

        taskReliabilityAppService.rerouteSubmittedOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?", client);
        assertThat((BigDecimal) wallet.get("available_balance")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) wallet.get("escrow_balance")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_deliveries WHERE task_id = ?",
                String.class, taskId)).containsExactly("task.failed");
        assertThat(taskReliabilityAppService.strandedSubmittedTaskIds()).doesNotContain(taskId);
    }

    private UUID submitRouted(UUID client, String category) {
        return taskWriteAppService.submit(submitInfo(client, category));
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

    private UUID seedActiveAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
        return versionId;
    }
}
