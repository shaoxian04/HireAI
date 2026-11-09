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
import com.hireai.support.dns.TestHostResolver;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Agent webhook URLs go through the same SSRF guard as client webhooks (#69): at registration,
 * at version publish, and again before every WorkOrder send, after DNS resolution. The dispatch
 * client never follows a redirect. The test-wide {@link TestHostResolver} stands in for DNS, so a
 * test decides what a name resolves to, and when.
 */
class AgentWebhookSsrfIntegrationTest extends WholeAppIntegrationTest {

    /** The client-webhook check's error for an internal address. */
    private static final String BLOCKED = "Callback URL resolves to a private/blocked address";
    private static final int TOTAL_ATTEMPTS = 4;

    @Autowired TestHostResolver dns;
    @Autowired TaskWriteAppService taskWriteAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/run",
            "https://127.0.0.1/run",
            "https://10.0.0.5/run",
            "https://192.168.1.20/run",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/run"})
    void registeringAnAgentOnAnInternalAddressIsRejectedWithTheClientWebhookError(String webhookUrl) throws Exception {
        HttpResponse<String> response = post("/api/agents", registerBody(webhookUrl));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText()).isEqualTo(BLOCKED);
    }

    @Test
    void registeringAnAgentWhoseHostNameResolvesToAPrivateAddressIsRejected() throws Exception {
        String host = "internal-" + UUID.randomUUID() + ".example.com";
        dns.answer(host, "172.16.4.2");

        HttpResponse<String> response = post("/api/agents", registerBody("https://" + host + "/run"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("message").asText()).isEqualTo(BLOCKED);
    }

    @Test
    void publishingAVersionAfterTheHostMovedToAPrivateAddressIsRejected() throws Exception {
        String host = "builder-" + UUID.randomUUID() + ".example.com";
        UUID agentId = register("https://" + host + "/run");
        dns.answer(host, "10.20.30.40");

        HttpResponse<String> response = post("/api/agents/" + agentId + "/versions",
                "{\"price\":6.00,\"maxExecutionSeconds\":60,\"capabilityCategories\":[\"summarisation\"]}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("message").asText()).isEqualTo(BLOCKED);
    }

    @Test
    void aHostWhoseDnsAnswerChangesToAPrivateAddressAfterRegistrationIsBlockedAtSendTime() throws Exception {
        String host = "rebind-" + UUID.randomUUID() + ".example.com";
        String category = "x-" + UUID.randomUUID();
        UUID agentId = register("https://" + host + "/run", category);
        assertThat(post("/api/agents/" + agentId + "/activate", "").statusCode()).isEqualTo(200);
        UUID versionId = jdbc.queryForObject("SELECT current_version_id FROM agents WHERE id = ?", UUID.class, agentId);
        dns.answer(host, "169.254.169.254");
        int lookupsBeforeSend = (int) dns.lookups().stream().filter(host::equals).count();

        UUID taskId = submit(newClientWithCredits(), category);

        // Every attempt is refused by the guard (the endpoint is unusable), so the task fails with
        // a refund and the failure counts against the Agent.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(dns.lookups().stream().filter(host::equals).count() - lookupsBeforeSend)
                .isEqualTo(TOTAL_ATTEMPTS);
        assertThat(jdbc.queryForList(
                "SELECT e.event_type FROM reputation_events e JOIN agent_versions v ON v.agent_id = e.agent_id "
                        + "WHERE e.task_id = ? AND v.id = ?",
                String.class, taskId, versionId)).containsExactly("EXECUTION_FAILED");
    }

    @Test
    void aWorkOrderThatGetsARedirectIsNotFollowed() {
        FakeAgent internal = fakeAgent().replyWith(FakeAgentReply.accepted());
        // 307 keeps the method and body, so a client that followed it would re-POST the WorkOrder.
        FakeAgent redirector = fakeAgent().replyWith(FakeAgentReply.status(307)
                .withHeader("Location", internal.webhookUrl()));
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, redirector.webhookUrl());

        UUID taskId = submit(newClientWithCredits(), category);

        redirector.awaitWorkOrders(TOTAL_ATTEMPTS, Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(internal.workOrders()).isEmpty();
        assertThat(redirector.workOrders()).hasSize(TOTAL_ATTEMPTS);
    }

    @Test
    void theLocalhostOptInStillDeliversToALocalAgent() {
        FakeAgent local = fakeAgent().replyWith(FakeAgentReply.accepted());
        assertThat(local.webhookUrl()).startsWith("http://localhost");
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, local.webhookUrl());

        UUID taskId = submit(newClientWithCredits(), category);

        local.awaitWorkOrders(1, Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
    }

    private UUID register(String webhookUrl) throws Exception {
        return register(webhookUrl, "summarisation");
    }

    private UUID register(String webhookUrl, String category) throws Exception {
        HttpResponse<String> response = post("/api/agents", registerBody(webhookUrl, category));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return UUID.fromString(mapper.readTree(response.body()).path("data").path("id").asText());
    }

    private static String registerBody(String webhookUrl) {
        return registerBody(webhookUrl, "summarisation");
    }

    private static String registerBody(String webhookUrl, String category) {
        return """
                {"name":"SSRF Agent","outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"},
                 "capabilityCategories":["%s"],"webhookUrl":"%s","maxExecutionSeconds":60,"price":5.00}
                """.formatted(category, webhookUrl);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID submit(UUID client, String category) {
        return taskWriteAppService.submit(new TaskSubmitInfo(client, "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
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

    /** Raw SQL bypasses registration, so an {@code http://localhost} fake agent can be seeded. */
    private void seedActiveAgent(String category, String webhookUrl) {
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
    }
}
