package com.hireai.offering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Agent gets an AgentSigningSecret: issued once at registration, rotatable by its owner
 * (the new value shown once), never returned by a read. Runs under the DEFAULT (secured) profile,
 * not the harness's permissive {@code test} profile, so denied statuses are the full app's real
 * ones (401, not a slice's 403; see the 401-vs-403 post-mortem).
 */
@ActiveProfiles(inheritProfiles = false)
class AgentSigningSecretIntegrationTest extends WholeAppIntegrationTest {

    private static final String SEED_PASSWORD = "DemoPass123!";
    private static final String AGENT_SPEC =
            "{\"format\":\"JSON\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"acceptanceCriteria\":\"spec\"}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registrationReturnsTheSecretOnceAndNoReadIncludesIt() throws Exception {
        String builder = login("builder@hireai.local");

        HttpResponse<String> registered = send("POST", "/api/agents", builder, registerBody());

        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
        JsonNode data = mapper.readTree(registered.body()).path("data");
        String secret = data.path("signingSecret").asText();
        UUID agentId = UUID.fromString(data.path("id").asText());
        assertThat(secret).startsWith("agsec_").hasSizeGreaterThan(40);
        assertThat(data.path("hasSigningSecret").asBoolean()).isTrue();
        assertThat(storedSecret(agentId)).isEqualTo(secret);

        HttpResponse<String> one = send("GET", "/api/agents/" + agentId, builder, null);
        HttpResponse<String> list = send("GET", "/api/agents", builder, null);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(list.statusCode()).isEqualTo(200);
        for (HttpResponse<String> read : new HttpResponse[] {one, list}) {
            assertThat(read.body()).doesNotContain(secret).doesNotContain("signingSecret");
        }
        assertThat(mapper.readTree(one.body()).path("data").path("hasSigningSecret").asBoolean()).isTrue();
    }

    @Test
    void theOwnerRotatesAndGetsTheNewValueOnce() throws Exception {
        String builder = login("builder@hireai.local");
        UUID agentId = registerAgent(builder);
        String original = storedSecret(agentId);

        HttpResponse<String> rotated = send("POST", "/api/agents/" + agentId + "/rotate-secret", builder, null);

        assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
        String fresh = mapper.readTree(rotated.body()).path("data").path("signingSecret").asText();
        assertThat(fresh).startsWith("agsec_").isNotEqualTo(original);
        assertThat(storedSecret(agentId)).isEqualTo(fresh);

        HttpResponse<String> read = send("GET", "/api/agents/" + agentId, builder, null);
        assertThat(read.body()).doesNotContain(fresh).doesNotContain(original);
    }

    @Test
    void aNonOwnerCannotRotate() throws Exception {
        String owner = login("builder@hireai.local");
        UUID agentId = registerAgent(owner);
        String original = storedSecret(agentId);

        // A signed-in client (not a builder) is denied by the chain: 401 in the full app.
        String client = login("client@hireai.local");
        assertThat(send("POST", "/api/agents/" + agentId + "/rotate-secret", client, null).statusCode())
                .isEqualTo(401);
        // Anonymous: 401.
        assertThat(send("POST", "/api/agents/" + agentId + "/rotate-secret", null, null).statusCode())
                .isEqualTo(401);
        // Another builder passes the chain but fails the owner check; a foreign Agent is
        // indistinguishable from a missing one.
        String otherBuilder = registerAnotherBuilder();
        HttpResponse<String> foreign =
                send("POST", "/api/agents/" + agentId + "/rotate-secret", otherBuilder, null);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("agsec_");

        assertThat(storedSecret(agentId)).isEqualTo(original);
    }

    @Test
    void anAgentRegisteredBeforeSecretsHasNoneUntilItsOwnerRotates() throws Exception {
        String builder = login("builder@hireai.local");
        UUID agentId = registerAgent(builder);
        // Simulate an Agent that predates signing secrets (the migration leaves them NULL).
        jdbc.update("UPDATE agents SET signing_secret = NULL WHERE id = ?", agentId);

        HttpResponse<String> before = send("GET", "/api/agents/" + agentId, builder, null);
        assertThat(mapper.readTree(before.body()).path("data").path("hasSigningSecret").asBoolean()).isFalse();

        HttpResponse<String> rotated = send("POST", "/api/agents/" + agentId + "/rotate-secret", builder, null);
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(storedSecret(agentId))
                .isEqualTo(mapper.readTree(rotated.body()).path("data").path("signingSecret").asText());

        HttpResponse<String> after = send("GET", "/api/agents/" + agentId, builder, null);
        assertThat(mapper.readTree(after.body()).path("data").path("hasSigningSecret").asBoolean()).isTrue();
    }

    @Test
    void lifecycleSavesKeepTheSecret() throws Exception {
        String builder = login("builder@hireai.local");
        UUID agentId = registerAgent(builder);
        String secret = storedSecret(agentId);

        // Full-row agent saves (activate, publish a version) must not wipe the secret column.
        assertThat(send("POST", "/api/agents/" + agentId + "/activate", builder, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/agents/" + agentId + "/versions", builder,
                "{\"price\":7,\"maxExecutionSeconds\":60,\"capabilityCategories\":[\"summarisation\"]}")
                .statusCode()).isEqualTo(200);

        assertThat(storedSecret(agentId)).isEqualTo(secret);
    }

    // ---- helpers ----

    private UUID registerAgent(String jwt) throws Exception {
        HttpResponse<String> r = send("POST", "/api/agents", jwt, registerBody());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return UUID.fromString(mapper.readTree(r.body()).path("data").path("id").asText());
    }

    private String storedSecret(UUID agentId) {
        return jdbc.queryForObject("SELECT signing_secret FROM agents WHERE id = ?", String.class, agentId);
    }

    private String login(String email) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("token").asText();
    }

    /** Self-registers a fresh user and upgrades them to BUILDER; returns the re-issued JWT. */
    private String registerAnotherBuilder() throws Exception {
        String email = "builder-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> reg = send("POST", "/api/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\",\"displayName\":\"Other\"}");
        assertThat(reg.statusCode()).as(reg.body()).isEqualTo(200);
        String clientJwt = mapper.readTree(reg.body()).path("data").path("token").asText();
        HttpResponse<String> upgraded = send("POST", "/api/auth/become-builder", clientJwt, "{\"acceptTerms\":true}");
        assertThat(upgraded.statusCode()).as(upgraded.body()).isEqualTo(200);
        return mapper.readTree(upgraded.body()).path("data").path("token").asText();
    }

    private static String registerBody() {
        return "{\"name\":\"Signer\",\"outputSpec\":" + AGENT_SPEC
                + ",\"capabilityCategories\":[\"summarisation\"],\"webhookUrl\":\"https://agent.example.com/run\""
                + ",\"maxExecutionSeconds\":60,\"price\":5}";
    }

    private HttpResponse<String> send(String method, String path, String jwt, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (jwt != null) {
            b.header("Authorization", "Bearer " + jwt);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
