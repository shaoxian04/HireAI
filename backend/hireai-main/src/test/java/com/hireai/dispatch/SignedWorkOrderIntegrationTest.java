package com.hireai.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Agent can prove a WorkOrder came from HireAI: the WorkOrder its webhook receives carries
 * {@code X-HireAI-Signature: t=<ts>,v1=<hex HMAC-SHA256(secret, "<ts>.<body>")>} over the exact
 * bytes received, keyed by the Agent's current signing secret. Verification here is done the way a
 * Builder would (recompute, constant-time compare, timestamp window), independently of the
 * platform's own helper.
 */
class SignedWorkOrderIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider): Builder of the agent and Client. */
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Pattern SIGNATURE = Pattern.compile("^t=(\\d+),v1=([0-9a-f]{64})$");
    private static final String AGENT_SPEC =
            "{\"format\":\"JSON\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"acceptanceCriteria\":\"spec\"}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @Test
    void theWorkOrderVerifiesWithTheSecretReturnedAtRegistration() throws Exception {
        FakeAgent agent = fakeAgent();
        String category = uniqueCategory();
        JsonNode registered = registerAndActivate(category, agent);
        String secret = registered.path("signingSecret").asText();
        assertThat(secret).startsWith("agsec_");

        submitRouted(category);
        RecordedWorkOrder order = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0);

        assertThat(verifies(order, secret)).isTrue();
        // The bearer token and the correlation header are still sent alongside the signature.
        assertThat(order.bearerToken()).isNotBlank();
        assertThat(order.header("X-Correlation-ID")).isNotBlank();
    }

    @Test
    void afterRotationWorkOrdersVerifyWithTheNewSecretAndNotTheOldOne() throws Exception {
        FakeAgent agent = fakeAgent();
        String category = uniqueCategory();
        JsonNode registered = registerAndActivate(category, agent);
        String oldSecret = registered.path("signingSecret").asText();
        String agentId = registered.path("id").asText();

        String newSecret = postData("/api/agents/" + agentId + "/rotate-secret", "")
                .path("signingSecret").asText();
        assertThat(newSecret).isNotEqualTo(oldSecret);

        submitRouted(category);
        RecordedWorkOrder order = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0);

        assertThat(verifies(order, newSecret)).isTrue();
        assertThat(verifies(order, oldSecret)).isFalse();
    }

    @Test
    void anAgentWithoutASecretReceivesAnUnsignedWorkOrder() throws Exception {
        FakeAgent agent = fakeAgent();
        String category = uniqueCategory();
        seedAgentWithoutSecret(category, agent.webhookUrl());

        submitRouted(category);
        RecordedWorkOrder order = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0);

        assertThat(order.header("X-HireAI-Signature")).isNull();
        assertThat(order.bearerToken()).isNotBlank();
    }

    /** What a Builder's Agent does: parse, check the window, recompute, compare in constant time. */
    private static boolean verifies(RecordedWorkOrder order, String secret) throws Exception {
        String header = order.header("X-HireAI-Signature");
        assertThat(header).as("X-HireAI-Signature").isNotNull();
        Matcher m = SIGNATURE.matcher(header);
        assertThat(m.matches()).as(header).isTrue();
        long ts = Long.parseLong(m.group(1));
        assertThat(Math.abs(Instant.now().getEpochSecond() - ts)).isLessThanOrEqualTo(300);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
        byte[] expected = mac.doFinal(order.body());
        return MessageDigest.isEqual(expected, HexFormat.of().parseHex(m.group(2)));
    }

    /**
     * Registers through the API (so the secret is the one a Builder is shown), then points the
     * version at the local fake Agent; registration itself only accepts public HTTPS URLs.
     */
    private JsonNode registerAndActivate(String category, FakeAgent agent) throws Exception {
        JsonNode registered = postData("/api/agents", """
                {"name":"Signed Agent","outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"},
                 "capabilityCategories":["%s"],"webhookUrl":"https://agent.example.com/hook",
                 "maxExecutionSeconds":60,"price":5.00}
                """.formatted(category));
        UUID agentId = UUID.fromString(registered.path("id").asText());
        jdbc.update("UPDATE agent_versions SET webhook_url = ? WHERE agent_id = ?", agent.webhookUrl(), agentId);
        postData("/api/agents/" + agentId + "/activate", "");
        return registered;
    }

    private void submitRouted(String category) throws Exception {
        postData("/api/tasks", """
                {"title":"Signed task","description":"Anything","category":"%s","budget":30.00,
                 "outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"}}
                """.formatted(category));
    }

    private JsonNode postData(String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private void seedAgentWithoutSecret(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Unsigned Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, AGENT_SPEC, new String[]{category}, webhookUrl, new BigDecimal("10.00"));
    }
}
