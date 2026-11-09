package com.hireai.offering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform-owned category list (#80) over the public HTTP API as the test-profile user: every
 * entry point that takes a category rejects an unknown slug with a 400 naming the valid ones, the
 * catalogue serves the seeded list, and a Builder's suggested category is stored.
 */
class CategoryListIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider): Builder and Client at once. */
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<String> SEEDED = List.of("document-extraction", "summarisation", "translation",
            "writing", "research", "data-analysis", "data-transformation", "classification", "code", "image",
            "audio", "customer-support", "other");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @Test
    void theCatalogueServesTheSeededListWithLabels() throws Exception {
        JsonNode categories = data(send("GET", "/api/catalogue/categories", null), 200);

        List<String> slugs = new ArrayList<>();
        categories.forEach(c -> {
            if (!c.path("category").asText().startsWith("x-")) { // other tests' uniqueCategory() rows
                slugs.add(c.path("category").asText());
            }
        });
        assertThat(slugs).containsExactlyElementsOf(SEEDED);
        assertThat(categories.get(0).path("label").asText()).isEqualTo("Document extraction");
        assertThat(categories.get(0).has("agentCount")).isTrue();
    }

    @Test
    void registeringWithAnUnknownCategoryIsA400ListingTheValidOnes() throws Exception {
        assertUnknownCategory(send("POST", "/api/agents", registerBody("[\"legal-poetry\"]", null)), "legal-poetry");
    }

    @Test
    void publishingAVersionWithAnUnknownCategoryIsA400() throws Exception {
        UUID agentId = registeredAgentId(registerBody("[\"summarisation\"]", null));

        assertUnknownCategory(send("POST", "/api/agents/" + agentId + "/versions",
                "{\"price\":6.00,\"maxExecutionSeconds\":60,\"capabilityCategories\":[\"summarization\"]}"),
                "summarization");
    }

    @Test
    void aRoutedSubmitWithAnUnknownCategoryIsA400AndFreezesNothing() throws Exception {
        BigDecimal before = available();

        assertUnknownCategory(send("POST", "/api/tasks", """
                {"title":"T","description":"d","category":"sumarisation","budget":10.00,
                 "outputSpec":{"format":"TEXT","acceptanceCriteria":"ok"}}"""), "sumarisation");
        assertThat(available()).isEqualByComparingTo(before);
    }

    @Test
    void aDirectBookingWithAnUnknownCategoryIsA400() throws Exception {
        UUID agentId = seedListedAgent("summarisation");

        assertUnknownCategory(send("POST", "/api/tasks/direct", """
                {"title":"T","description":"d","budget":10.00,"agentId":"%s","category":"nope"}"""
                .formatted(agentId)), "nope");
    }

    @Test
    void aDirectBookingInAKnownCategoryTheAgentServesUsesIt() throws Exception {
        UUID agentId = seedListedAgent("summarisation", "translation");

        JsonNode task = data(send("POST", "/api/tasks/direct", """
                {"title":"T","description":"d","budget":10.00,"agentId":"%s","category":"Translation"}"""
                .formatted(agentId)), 200);

        assertThat(task.path("category").asText()).isEqualTo("translation");
    }

    @Test
    void aDirectBookingInACategoryTheAgentDoesNotServeIsA400() throws Exception {
        UUID agentId = seedListedAgent("summarisation");

        HttpResponse<String> response = send("POST", "/api/tasks/direct", """
                {"title":"T","description":"d","budget":10.00,"agentId":"%s","category":"translation"}"""
                .formatted(agentId));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("message").asText()).contains("translation");
    }

    @Test
    void matchPreviewWithAnUnknownCategoryIsA400() throws Exception {
        assertUnknownCategory(send("POST", "/api/tasks/match-preview", """
                {"title":"T","description":"d","category":"nope","budget":10}"""), "nope");
    }

    @Test
    void aSuggestedCategoryIsStoredOnRegistrationAndOnPublish() throws Exception {
        UUID agentId = registeredAgentId(registerBody("[\"other\"]", "Legal clause review"));
        data(send("POST", "/api/agents/" + agentId + "/versions", """
                {"price":6.00,"maxExecutionSeconds":60,"capabilityCategories":["other"],
                 "suggestedCategory":"  Contract redlining "}"""), 200);

        List<String> stored = jdbc.queryForList("SELECT suggested_category FROM category_suggestions "
                + "WHERE agent_id = ? AND owner_id = ? ORDER BY gmt_create", String.class, agentId, DEV_USER);
        assertThat(stored).containsExactly("Legal clause review", "Contract redlining");
        Integer versions = jdbc.queryForObject("SELECT COUNT(DISTINCT agent_version_id) FROM category_suggestions "
                + "WHERE agent_id = ?", Integer.class, agentId);
        assertThat(versions).isEqualTo(2);
    }

    @Test
    void registrationStoresTheNormalisedSlug() throws Exception {
        UUID agentId = registeredAgentId(registerBody("[\"  Document-Extraction \"]", null));

        String[] stored = jdbc.queryForObject("SELECT capability_categories FROM agent_versions WHERE agent_id = ?",
                (rs, i) -> (String[]) rs.getArray(1).getArray(), agentId);
        assertThat(stored).containsExactly("document-extraction");
    }

    private void assertUnknownCategory(HttpResponse<String> response, String slug) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText())
                .contains("Unknown category '" + slug + "'")
                .contains("Valid categories:")
                .contains("document-extraction")
                .contains("other");
    }

    private static String registerBody(String categoriesJson, String suggested) {
        String suggestion = suggested == null ? "" : ",\"suggestedCategory\":\"" + suggested + "\"";
        return """
                {"name":"Category Agent","outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"},
                 "capabilityCategories":%s,"webhookUrl":"https://agent.example.com/hook",
                 "maxExecutionSeconds":60,"price":5.00%s}
                """.formatted(categoriesJson, suggestion);
    }

    private UUID registeredAgentId(String body) throws Exception {
        return UUID.fromString(data(send("POST", "/api/agents", body), 200).path("id").asText());
    }

    private UUID seedListedAgent(String... categories) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Listed Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, 10.00)",
                versionId, agentId, "{\"format\":\"JSON\"}", categories, "https://agent.example.com/hook");
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        return agentId;
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER);
    }

    private JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
