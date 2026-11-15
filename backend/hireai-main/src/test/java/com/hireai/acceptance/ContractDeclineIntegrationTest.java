package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ContractDecline (#93, D2/D33): an Agent refuses one specific Contract by answering the WorkOrder
 * with HTTP 422 and {@code {"decline":"CONTRACT","reason":…}}. It behaves like a busy decline (never
 * retried; a routed task fails over, a pinned one ends FAILED with a refund) and writes no
 * reputation event, but the dispatch history records a distinct {@code CONTRACT_DECLINED} outcome
 * with the Agent's reason, and it counts in the Agent's public decline rate (shortlist + agent page).
 */
@TestPropertySource(properties = "hireai.matching.epsilon=0.0")
class ContractDeclineIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Longer than the harness's whole retry schedule (three 500ms delays). */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);
    private static final String DECLINE = "{\"decline\":\"CONTRACT\",\"reason\":\"I only summarise English text\"}";

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
    void aPinnedBookingWhoseAgentDeclinesTheContractFailsWithARefundAWebhookAndNoPenalty() throws Exception {
        // Delayed so the webhook subscription below is in place before the task ends.
        FakeAgent agent = fakeAgent().replyWith(
                FakeAgentReply.status(422).withBody(DECLINE).afterDelay(Duration.ofSeconds(1)));
        UUID[] seeded = seedAgent(uniqueCategory(), agent, "80.00", "10.00");
        UUID versionId = seeded[1];

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo("general"), versionId);
        subscribeToWebhooks(taskId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(apiTask(taskId).get("failureReason").asText()).isEqualTo("CONTRACT_DECLINED");
        assertRefundedOnce(taskId);
        JsonNode webhook = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_deliveries WHERE task_id = ?", String.class, taskId));
        assertThat(webhook.get("type").asText()).isEqualTo("task.failed");
        assertThat(webhook.get("failure_reason").asText()).isEqualTo("CONTRACT_DECLINED");
        assertNotRetriedAndNotPenalised(agent, taskId);

        JsonNode attempts = apiAttempts(taskId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("outcome").asText()).isEqualTo("CONTRACT_DECLINED");
        assertThat(attempts.get(0).get("declineReason").asText()).isEqualTo("I only summarise English text");
        assertThat(attempts.get(0).get("agentName").asText()).isEqualTo("Decline Agent");
    }

    @Test
    void aRoutedTaskWhoseFirstAgentDeclinesTheContractCompletesOnTheSecond() {
        String category = uniqueCategory();
        FakeAgent decliner = fakeAgent().replyWith(FakeAgentReply.status(422).withBody(DECLINE));
        FakeAgent healthy = fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.completed()));
        UUID declinerVersion = seedAgent(category, decliner, "95.00", "5.00")[1];
        UUID healthyVersion = seedAgent(category, healthy, "50.00", "8.00")[1];

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        assertThat(attempts(taskId)).containsExactly(
                declinerVersion + ":CONTRACT_DECLINED", healthyVersion + ":ACCEPTED");
        assertThat(decliner.workOrders()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    @Test
    void aRoutedTaskWhoseOnlyAgentDeclinesTheContractFailsWithNoAlternativeAndARefund() {
        String category = uniqueCategory();
        FakeAgent decliner = fakeAgent().replyWith(FakeAgentReply.status(422).withBody(DECLINE));
        seedAgent(category, decliner, "80.00", "10.00");

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("NO_ALTERNATIVE_AGENT");
        assertRefundedOnce(taskId);
        assertNotRetriedAndNotPenalised(decliner, taskId);
    }

    @Test
    void a422WithoutTheContractDeclineBodyIsAnOrdinaryFailedSendAndIsRetried() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.status(422).withBody("{\"error\":\"bad input\"}"));
        UUID versionId = seedAgent(uniqueCategory(), agent, "80.00", "10.00")[1];

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo("general"), versionId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("AGENT_UNREACHABLE");
        assertThat(agent.workOrders()).hasSizeGreaterThan(1);
        assertThat(attempts(taskId)).containsExactly(versionId + ":UNREACHABLE");
    }

    @Test
    void theDeclineRateCountsContractDeclinesAmongAnsweredWorkOrdersOnTheShortlistAndTheAgentPage()
            throws Exception {
        String category = uniqueCategory();
        UUID[] decliner = seedAgent(category, fakeAgent(), "80.00", "5.00");
        UUID[] reliable = seedAgent(category, fakeAgent(), "80.00", "6.00");
        UUID[] newcomer = seedAgent(category, fakeAgent(), "80.00", "7.00");
        // decliner: 1 decline + 3 accepted (25%); a busy reply is not an answer to the Contract.
        recordAttempt(decliner[1], "CONTRACT_DECLINED");
        recordAttempt(decliner[1], "ACCEPTED");
        recordAttempt(decliner[1], "ACCEPTED");
        recordAttempt(decliner[1], "ACCEPTED");
        recordAttempt(decliner[1], "BUSY");
        // reliable: answered twice, declined nothing; unreachable/unavailable never count.
        recordAttempt(reliable[1], "ACCEPTED");
        recordAttempt(reliable[1], "ACCEPTED");
        recordAttempt(reliable[1], "UNREACHABLE");
        recordAttempt(reliable[1], "UNAVAILABLE");

        JsonNode shortlist = matchPreview(category).get("shortlist");
        assertThat(declineRateOf(shortlist, decliner[0]).toString())
                .isEqualTo("{\"declined\":1,\"answered\":4,\"rate\":0.25}");
        assertThat(declineRateOf(shortlist, reliable[0]).toString())
                .isEqualTo("{\"declined\":0,\"answered\":2,\"rate\":0.0}");
        assertThat(declineRateOf(shortlist, newcomer[0]).toString())
                .isEqualTo("{\"declined\":0,\"answered\":0,\"rate\":null}");

        JsonNode profile = objectMapper.readTree(new RestTemplate().getForObject(
                platformUrl("/api/catalogue/agents/" + decliner[0]), String.class)).get("data");
        assertThat(profile.get("stats").get("contractDeclines").toString())
                .isEqualTo("{\"declined\":1,\"answered\":4,\"rate\":0.25}");
    }

    // ---- helpers ----

    private void assertNotRetriedAndNotPenalised(FakeAgent agent, UUID taskId) {
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() ->
                assertThat(agent.workOrders()).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    private void assertRefundedOnce(UUID taskId) {
        assertThat(jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER)).isEqualByComparingTo(startingBalance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    private JsonNode declineRateOf(JsonNode options, UUID agentId) {
        for (JsonNode o : options) {
            if (o.get("agentId").asText().equals(agentId.toString())) {
                return o.get("contractDeclines");
            }
        }
        throw new AssertionError("Agent " + agentId + " not on the shortlist: " + options);
    }

    private JsonNode matchPreview(String category) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"title":"Summarise report","description":"Summarise the attached quarterly report",
                 "category":"%s","budget":10}""".formatted(category);
        String response = new RestTemplate().postForObject(platformUrl("/api/tasks/match-preview"),
                new HttpEntity<>(body, headers), String.class);
        return objectMapper.readTree(response).get("data");
    }

    /** A history row for a task that never existed: dispatch_attempts holds soft references only. */
    private void recordAttempt(UUID versionId, String outcome) {
        jdbc.update("INSERT INTO dispatch_attempts (id, task_id, agent_version_id, attempt_number, outcome, occurred_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?)", UUID.randomUUID(), UUID.randomUUID(), versionId, outcome,
                Timestamp.from(Instant.now()));
    }

    private JsonNode apiTask(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode apiAttempts(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId + "/attempts"), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private List<String> attempts(UUID taskId) {
        return new ArrayList<>(jdbc.queryForList("SELECT agent_version_id || ':' || outcome FROM dispatch_attempts "
                + "WHERE task_id = ? ORDER BY attempt_number", String.class, taskId));
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

    /** Returns {agentId, versionId}. */
    private UUID[] seedAgent(String category, FakeAgent agent, String reputation, String price) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Decline Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId, new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, agent.webhookUrl(),
                new BigDecimal(price));
        shareSigningSecret(agent);
        return new UUID[]{agentId, versionId};
    }
}
