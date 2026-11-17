package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

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
 * Who may read a receipt (#108, Invariant #5), under the DEFAULT (secured) profile so statuses are
 * the full app's real ones. The task's Client reads its receipts with a JWT or its own API key; the
 * assigned Agent's Builder reads them only signed in as a Builder, never with an API key (a
 * Client-channel credential); anyone else is told the receipt does not exist (404, as every
 * ownership check does); an anonymous caller is unauthorized (401); the public keys are public.
 */
@ActiveProfiles(inheritProfiles = false)
class ReceiptAccessIntegrationTest extends WholeAppIntegrationTest {

    private static final java.util.List<String> EXPORTS = java.util.List.of("/credential", "/otel");
    private static final String SEED_PASSWORD = "DemoPass123!";
    private static final UUID SEED_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Autowired WalletWriteAppService walletWriteAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private String builderEmail;
    private UUID taskId;
    private UUID receiptId;

    @BeforeEach
    void settleATaskOnAnAgentTheBuilderOwns() throws Exception {
        walletWriteAppService.topUp(SEED_CLIENT, Money.of("20.00"), "it-seed-" + UUID.randomUUID());
        builderEmail = "builder-" + UUID.randomUUID() + "@example.com";
        UUID builderId = UUID.fromString(registerFor(builderEmail).path("userId").asText());

        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.failed("cannot do it").afterReply(Duration.ofMillis(200))));
        UUID agentId = seedTextAgent(agent, builderId);

        String clientJwt = login("client@hireai.local");
        HttpResponse<String> booked = send("POST", "/api/tasks/direct", "Bearer " + clientJwt,
                "{\"title\":\"Summarise\",\"description\":\"Summarise the report\",\"budget\":\"5.00\",\"agentId\":\""
                        + agentId + "\"}");
        assertThat(booked.statusCode()).as(booked.body()).isEqualTo(200);
        taskId = UUID.fromString(mapper.readTree(booked.body()).path("data").path("id").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo("FAILED"));

        HttpResponse<String> list = get(receiptsPath(), "Bearer " + clientJwt);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        receiptId = UUID.fromString(mapper.readTree(list.body()).path("data").get(0).path("id").asText());
    }

    @Test
    void theClientReadsWithAJwtOrItsOwnApiKey() throws Exception {
        String clientJwt = login("client@hireai.local");
        String clientKey = createKey(clientJwt);

        assertThat(get(receiptsPath(), "ApiKey " + clientKey).statusCode()).isEqualTo(200);
        HttpResponse<String> download = get("/api/receipts/" + receiptId, "ApiKey " + clientKey);
        assertThat(download.statusCode()).as(download.body()).isEqualTo(200);
        assertThat(mapper.readTree(download.body()).has("signature")).isTrue();
        assertThat(get("/api/receipts/" + receiptId, "Bearer " + clientJwt).statusCode()).isEqualTo(200);
        // The exports (#109) go to the same readers through the same channels.
        for (String export : EXPORTS) {
            assertThat(get("/api/receipts/" + receiptId + export, "ApiKey " + clientKey).statusCode()).isEqualTo(200);
            assertThat(get("/api/receipts/" + receiptId + export, "Bearer " + clientJwt).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void theBuilderReadsSignedInAsABuilderButNeverWithAnApiKey() throws Exception {
        String clientRoleJwt = login(builderEmail);
        String builderKey = createKey(clientRoleJwt);

        // An API key is a Client-channel credential: for the Builder it reads as a stranger.
        assertThat(get(receiptsPath(), "ApiKey " + builderKey).statusCode()).isEqualTo(404);
        assertThat(get("/api/receipts/" + receiptId, "ApiKey " + builderKey).statusCode()).isEqualTo(404);
        // Without the BUILDER role the same person is not reading as the Builder either.
        assertThat(get(receiptsPath(), "Bearer " + clientRoleJwt).statusCode()).isEqualTo(404);

        String builderJwt = becomeBuilder(clientRoleJwt);
        assertThat(get(receiptsPath(), "Bearer " + builderJwt).statusCode()).isEqualTo(200);
        assertThat(get("/api/receipts/" + receiptId, "Bearer " + builderJwt).statusCode()).isEqualTo(200);
        // The API key is still a Client-channel credential after the owner became a Builder.
        assertThat(get("/api/receipts/" + receiptId, "ApiKey " + builderKey).statusCode()).isEqualTo(404);
        for (String export : EXPORTS) {
            assertThat(get("/api/receipts/" + receiptId + export, "Bearer " + builderJwt).statusCode()).isEqualTo(200);
            assertThat(get("/api/receipts/" + receiptId + export, "ApiKey " + builderKey).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void strangersAreToldNothingAndAnonymousCallersAreUnauthorized() throws Exception {
        String stranger = register("stranger-" + UUID.randomUUID() + "@example.com");

        HttpResponse<String> list = get(receiptsPath(), "Bearer " + stranger);
        assertThat(list.statusCode()).isEqualTo(404);
        assertThat(list.body()).doesNotContain(receiptId.toString());
        assertThat(get("/api/receipts/" + receiptId, "Bearer " + stranger).statusCode()).isEqualTo(404);
        assertThat(get("/api/receipts/" + receiptId, "ApiKey " + createKey(stranger)).statusCode()).isEqualTo(404);
        assertThat(get(receiptsPath(), null).statusCode()).isEqualTo(401);
        assertThat(get("/api/receipts/" + receiptId, null).statusCode()).isEqualTo(401);
        for (String export : EXPORTS) {
            assertThat(get("/api/receipts/" + receiptId + export, "Bearer " + stranger).statusCode()).isEqualTo(404);
            assertThat(get("/api/receipts/" + receiptId + export, null).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void thePublicKeysArePublic() throws Exception {
        HttpResponse<String> keys = get("/api/receipt-keys", null);

        assertThat(keys.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(keys.body()).path("keys").get(0).path("crv").asText()).isEqualTo("Ed25519");
    }

    // ---- helpers ----

    private UUID seedTextAgent(FakeAgent agent, UUID ownerId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Receipt Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"TEXT\"}", new String[]{uniqueCategory()}, agent.webhookUrl(),
                new BigDecimal("5.00"));
        shareSigningSecret(agent);
        return agentId;
    }

    private String receiptsPath() {
        return "/api/tasks/" + taskId + "/receipts";
    }

    private String login(String email) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("token").asText();
    }

    private String register(String email) throws Exception {
        return registerFor(email).path("token").asText();
    }

    private JsonNode registerFor(String email) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\",\"displayName\":\"Someone\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data");
    }

    private String becomeBuilder(String jwt) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/become-builder", "Bearer " + jwt, "{\"acceptTerms\":true}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("token").asText();
    }

    private String createKey(String jwt) throws Exception {
        HttpResponse<String> r = send("POST", "/api/keys", "Bearer " + jwt, "{\"name\":\"receipts-it\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("rawKey").asText();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        return send("GET", path, authorization, null);
    }

    private HttpResponse<String> send(String method, String path, String authorization, String json)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
