package com.hireai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Match-preview is a POST whose body carries the task text (#82), so the text never travels in a
 * URL. Runs under the DEFAULT (secured) profile: the endpoint must be reachable by a human CLIENT
 * and by an API key, and denied statuses are the full app's real ones (401, see the 401-vs-403
 * post-mortem). A preview creates no task and freezes nothing.
 */
@ActiveProfiles(inheritProfiles = false)
class MatchPreviewPostIntegrationTest extends WholeAppIntegrationTest {

    private static final String SEED_PASSWORD = "DemoPass123!";
    private static final UUID SEED_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private String category;
    private UUID cheapAgent;
    private UUID otherAgent;

    @BeforeEach
    void seedTwoListedAgents() {
        category = uniqueCategory();
        cheapAgent = seedListedAgent(category, "8.00");
        otherAgent = seedListedAgent(category, "9.00");
    }

    @Test
    void anApiKeyGetsTheShortlistFromTheBodyAndNothingIsCreatedOrFrozen() throws Exception {
        String key = "ApiKey " + createKey(login("client@hireai.local"));
        int tasksBefore = taskCount();
        Map<String, Object> walletBefore = wallet();

        HttpResponse<String> r = send("POST", "/api/tasks/match-preview", key, body(""));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(shortlistAgentIds(r)).containsExactlyInAnyOrder(cheapAgent.toString(), otherAgent.toString());
        assertThat(taskCount()).isEqualTo(tasksBefore);
        assertThat(wallet()).isEqualTo(walletBefore);
    }

    @Test
    void aWebClientExcludesAnAgentThroughTheBody() throws Exception {
        String jwt = "Bearer " + login("client@hireai.local");

        HttpResponse<String> r = send("POST", "/api/tasks/match-preview", jwt,
                body(",\"deadlineSeconds\":3600,\"exclude\":[\"" + cheapAgent + "\"]"));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(shortlistAgentIds(r)).containsExactly(otherAgent.toString());
    }

    @Test
    void theOldGetFormWithTheTaskInTheQueryStringIsGone() throws Exception {
        String jwt = "Bearer " + login("client@hireai.local");

        HttpResponse<String> r = send("GET",
                "/api/tasks/match-preview?category=" + category + "&budget=10", jwt, null);

        assertThat(r.statusCode()).isNotEqualTo(200);
        assertThat(r.body()).doesNotContain(cheapAgent.toString());
    }

    @Test
    void anAnonymousCallerIsUnauthorized() throws Exception {
        HttpResponse<String> r = send("POST", "/api/tasks/match-preview", null, body(""));

        assertThat(r.statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    private String body(String extraFields) {
        return """
                {"title":"Board pack digest","description":"Summarise the confidential Q3 board pack",
                 "category":"%s","budget":10%s}""".formatted(category, extraFields);
    }

    private List<String> shortlistAgentIds(HttpResponse<String> r) throws Exception {
        List<String> ids = new ArrayList<>();
        mapper.readTree(r.body()).path("data").path("shortlist").forEach(o -> ids.add(o.path("agentId").asText()));
        return ids;
    }

    private int taskCount() {
        return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE client_id = ?", Integer.class, SEED_CLIENT);
    }

    private Map<String, Object> wallet() {
        return jdbc.queryForMap("SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?",
                SEED_CLIENT);
    }

    private UUID seedListedAgent(String category, String price) {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Preview Agent', 'ACTIVE', ?, 5.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category},
                "https://agent.example.com/hook", new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        return agentId;
    }

    private String login(String email) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("token").asText();
    }

    private String createKey(String jwt) throws Exception {
        HttpResponse<String> r = send("POST", "/api/keys", "Bearer " + jwt, "{\"name\":\"preview-it\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode data = mapper.readTree(r.body()).path("data");
        return data.path("rawKey").asText();
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
