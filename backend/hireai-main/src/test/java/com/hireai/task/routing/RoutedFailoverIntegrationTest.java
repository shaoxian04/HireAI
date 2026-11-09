package com.hireai.task.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackOutcome;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Routed failover (#78), over the whole app with fake Agents: when the Agent the platform picked
 * can't take the task before starting (busy, or unreachable after the retry schedule), the task
 * stays QUEUED and goes to the next untried eligible Agent, at most 3 distinct Agents, then ends
 * FAILED with NO_ALTERNATIVE_AGENT.
 *
 * <p>Exploration is switched off (epsilon 0) so the matcher's pick order is the score order: the
 * seeded reputation and price decide which Agent is tried first.
 */
@TestPropertySource(properties = "hireai.matching.epsilon=0.0")
class RoutedFailoverIntegrationTest extends WholeAppIntegrationTest {

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

    @Test
    void aRoutedTaskWhoseFirstAgentIsBusyCompletesOnTheSecondWithNoPenaltyForTheFirst() {
        String category = "x-" + UUID.randomUUID();
        FakeAgent busy = fakeAgent().replyWith(FakeAgentReply.status(429));
        FakeAgent healthy = fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.completed()));
        UUID busyVersion = seedAgent(category, busy, "95.00", "5.00");
        UUID healthyVersion = seedAgent(category, healthy, "50.00", "8.00");

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        assertThat(assignedVersion(taskId)).isEqualTo(healthyVersion);
        assertThat(busy.workOrders()).hasSize(1);
        assertThat(healthy.workOrders()).hasSize(1);
        assertThat(attempts(taskId)).containsExactly(
                busyVersion + ":BUSY", healthyVersion + ":ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
        // Still in escrow: nothing was refunded or settled along the way.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    @Test
    void threeUnreachableAgentsEndTheTaskWithNoAlternativeAgentAndAFourthIsNeverTried() throws Exception {
        String category = "x-" + UUID.randomUUID();
        FakeAgent first = fakeAgent().replyWith(FakeAgentReply.status(500));
        FakeAgent second = fakeAgent().replyWith(FakeAgentReply.status(500));
        FakeAgent third = fakeAgent().replyWith(FakeAgentReply.status(500));
        FakeAgent fourth = fakeAgent().replyWith(FakeAgentReply.accepted());
        UUID v1 = seedAgent(category, first, "95.00", "5.00");
        UUID v2 = seedAgent(category, second, "90.00", "5.00");
        UUID v3 = seedAgent(category, third, "85.00", "5.00");
        seedAgent(category, fourth, "10.00", "10.00");

        UUID taskId = taskWriteAppService.submit(submitInfo(category));
        subscribeToWebhooks(taskId);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiFailureReason(taskId)).isEqualTo("NO_ALTERNATIVE_AGENT");
        assertThat(first.workOrders()).isNotEmpty();
        assertThat(second.workOrders()).isNotEmpty();
        assertThat(third.workOrders()).isNotEmpty();
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() ->
                assertThat(fourth.workOrders()).isEmpty());
        assertThat(attempts(taskId)).containsExactlyInAnyOrder(
                v1 + ":UNREACHABLE", v2 + ":UNREACHABLE", v3 + ":UNREACHABLE");
        // Each unreachable Agent is held to account once, after its own full retry schedule.
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT agent_id) FROM reputation_events "
                + "WHERE task_id = ? AND event_type = 'EXECUTION_FAILED'", Integer.class, taskId)).isEqualTo(3);
        assertRefundedOnce(taskId);
        JsonNode webhook = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_deliveries WHERE task_id = ?", String.class, taskId));
        assertThat(webhook.get("type").asText()).isEqualTo("task.failed");
        assertThat(webhook.get("failure_reason").asText()).isEqualTo("NO_ALTERNATIVE_AGENT");
    }

    @Test
    void anAgentAlreadyTriedIsNeverPickedAgain() throws Exception {
        String category = "x-" + UUID.randomUUID();
        FakeAgent a = fakeAgent().replyWith(FakeAgentReply.status(503));
        FakeAgent b = fakeAgent().replyWith(FakeAgentReply.status(503));
        UUID va = seedAgent(category, a, "95.00", "5.00");
        UUID vb = seedAgent(category, b, "50.00", "5.00");

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiFailureReason(taskId)).isEqualTo("NO_ALTERNATIVE_AGENT");
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() -> {
            assertThat(a.workOrders()).hasSize(1);
            assertThat(b.workOrders()).hasSize(1);
        });
        assertThat(attempts(taskId)).containsExactly(va + ":BUSY", vb + ":BUSY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
        assertRefundedOnce(taskId);
    }

    @Test
    void aLateCallbackFromTheReplacedAgentGets401AndTheNewAgentsResultStands() {
        String category = "x-" + UUID.randomUUID();
        // The replaced Agent declines as busy but then posts a (signed, well-formed) result anyway.
        FakeAgent replaced = fakeAgent().replyWith(FakeAgentReply.status(503)
                .withCallback(AgentCallback.completed().afterReply(Duration.ofSeconds(2))));
        FakeAgent successor = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofSeconds(4))));
        seedAgent(category, replaced, "95.00", "5.00");
        UUID successorVersion = seedAgent(category, successor, "50.00", "5.00");

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        List<CallbackOutcome> late = replaced.awaitCallbacks(1, Duration.ofSeconds(20));
        assertThat(late.get(0).status()).isEqualTo(401);
        assertThat(successor.awaitCallbacks(1, Duration.ofSeconds(20)).get(0).status()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        assertThat(assignedVersion(taskId)).isEqualTo(successorVersion);
    }

    private void assertRefundedOnce(UUID taskId) {
        assertThat(jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER)).isEqualByComparingTo(startingBalance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    private List<String> attempts(UUID taskId) {
        return jdbc.queryForList("SELECT agent_version_id || ':' || outcome FROM dispatch_attempts "
                + "WHERE task_id = ? ORDER BY attempt_number", String.class, taskId);
    }

    private UUID assignedVersion(UUID taskId) {
        return jdbc.queryForObject("SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
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

    /** An ACTIVE Agent (owned by its own Builder) whose current version points at {@code agent}. */
    private UUID seedAgent(String category, FakeAgent agent, String reputation, String price) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Failover Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId, new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, agent.webhookUrl(),
                new BigDecimal(price));
        shareSigningSecret(agent);
        return versionId;
    }
}
