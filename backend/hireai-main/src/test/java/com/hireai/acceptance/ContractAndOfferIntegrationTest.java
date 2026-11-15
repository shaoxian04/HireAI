package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * S1 of the acceptance-contract spec (#88): the generic Templates are published, every agent
 * version carries an Offer equivalent to its output spec, and every new Task freezes a Contract
 * derived from its output spec, while the output spec and the existing flows stay exactly as before.
 * Driven over the public HTTP API as the test profile's fixed caller.
 */
class ContractAndOfferIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider), seeded by V1 without a wallet. */
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"total\"]}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @Test
    void theGenericTemplatesArePublishedWithTheirParameters() throws Exception {
        JsonNode templates = get("/api/catalogue/templates");

        List<String> refs = new ArrayList<>();
        templates.forEach(t -> refs.add(t.path("key").asText() + "@" + t.path("version").asInt()));
        assertThat(refs).contains("text_basic@1", "custom_json@1", "file_basic@1");

        JsonNode customJson = get("/api/catalogue/templates/custom_json/1");
        assertThat(customJson.path("outputShape").asText()).isEqualTo("JSON");
        assertThat(customJson.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(customJson.path("checks")).extracting(JsonNode::asText)
                .containsExactly("FORMAT_JSON_PARSEABLE", "SCHEMA_MATCH");
        JsonNode schema = customJson.path("parameters").get(0);
        assertThat(schema.path("name").asText()).isEqualTo("schema");
        assertThat(schema.path("comparison").asText()).isEqualTo("enum");
        assertThat(schema.path("bounds").path("maxLength").asInt()).isEqualTo(5000);
        assertThat(customJson.path("rulingSettlement").path("PARTIALLY_FULFILLED").decimalValue())
                .isEqualByComparingTo("0.5");

        JsonNode fileBasic = get("/api/catalogue/templates/file_basic/1");
        assertThat(fileBasic.path("parameters")).extracting(p -> p.path("name").asText() + ":"
                + p.path("comparison").asText()).containsExactly("max_bytes:min", "media_types:subset");
    }

    @Test
    void anUnknownTemplateIsNotFound() throws Exception {
        assertThat(send("GET", "/api/catalogue/templates/no_such_work/1", null).statusCode()).isEqualTo(404);
    }

    @Test
    void aRegisteredAgentShowsAnOfferAcceptingOnlyItsOwnSchemaAndKeepsItAcrossVersions() throws Exception {
        String category = uniqueCategory();
        JsonNode registered = post("/api/agents", """
                {"name":"Invoice reader","outputSpec":{"format":"JSON","schema":%s,"acceptanceCriteria":"totals"},
                 "capabilityCategories":["%s"],"webhookUrl":"https://agent.example.com/run",
                 "maxExecutionSeconds":60,"price":5}""".formatted(mapper.writeValueAsString(SCHEMA), category));
        String agentId = registered.path("id").asText();

        JsonNode offer = get("/api/agents/" + agentId).path("currentVersion").path("offer");
        assertThat(offer.path("price").decimalValue()).isEqualByComparingTo("5");
        assertThat(offer.path("terms")).hasSize(1);
        JsonNode term = offer.path("terms").get(0);
        assertThat(term.path("template").asText()).isEqualTo("custom_json");
        assertThat(term.path("version").asInt()).isEqualTo(1);
        assertThat(term.path("params").path("schema").path("oneOf")).extracting(JsonNode::asText)
                .containsExactly(SCHEMA);
        // The app writes the same Offer the V39 backfill derives for an older version.
        assertThat(jdbc.queryForObject("SELECT offer = offer_from_output_spec(output_spec) FROM agent_versions "
                + "WHERE agent_id = ?::uuid", Boolean.class, agentId)).isTrue();
        // The output spec is still returned unchanged beside the Offer.
        assertThat(get("/api/agents/" + agentId).path("currentVersion").path("outputSpec").path("schema").asText())
                .isEqualTo(SCHEMA);

        post("/api/agents/" + agentId + "/versions",
                "{\"price\":7,\"maxExecutionSeconds\":60,\"capabilityCategories\":[\"" + category + "\"]}");

        JsonNode next = get("/api/agents/" + agentId).path("currentVersion");
        assertThat(next.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(next.path("offer").path("price").decimalValue()).isEqualByComparingTo("7");
        assertThat(next.path("offer").path("terms")).isEqualTo(offer.path("terms"));
    }

    @Test
    void aRoutedSubmitFreezesAContractDerivedFromItsOutputSpec() throws Exception {
        JsonNode json = post("/api/tasks", """
                {"title":"Extract totals","description":"Pull the totals","category":"%s","budget":10.00,
                 "outputSpec":{"format":"JSON","schema":%s,"acceptanceCriteria":"totals present"}}"""
                .formatted(uniqueCategory(), mapper.writeValueAsString(SCHEMA)));
        JsonNode text = post("/api/tasks", """
                {"title":"Summarise","description":"Summarise the memo","category":"%s","budget":10.00,
                 "outputSpec":{"format":"TEXT","acceptanceCriteria":"three bullets"}}""".formatted(uniqueCategory()));

        JsonNode jsonTask = get("/api/tasks/" + json.path("id").asText());
        assertThat(jsonTask.path("contract").path("template").asText()).isEqualTo("custom_json");
        assertThat(jsonTask.path("contract").path("version").asInt()).isEqualTo(1);
        assertThat(jsonTask.path("contract").path("params").path("schema").asText()).isEqualTo(SCHEMA);
        assertThat(jsonTask.path("outputSpec").path("acceptanceCriteria").asText()).isEqualTo("totals present");

        JsonNode textTask = get("/api/tasks/" + text.path("id").asText());
        assertThat(textTask.path("contract").path("template").asText()).isEqualTo("text_basic");
        assertThat(textTask.path("contract").path("params").isEmpty()).isTrue();
    }

    @Test
    void aDirectBookingFreezesTheAgentsOwnContractAndRunsToReviewAsBefore() throws Exception {
        String category = uniqueCategory();
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        UUID agentId = seedListedAgent(category, agent.webhookUrl(), "{\"format\":\"TEXT\"}");
        shareSigningSecret(agent);

        // Seeded with raw SQL: the version's Offer is derived by the database, like the backfill,
        // and a Client sees it on the Agent's public profile.
        assertThat(get("/api/catalogue/agents/" + agentId).path("offer").path("terms").get(0)
                .path("template").asText()).isEqualTo("text_basic");

        JsonNode booked = post("/api/tasks/direct", """
                {"title":"Digest","description":"Summarise the board pack","budget":10.00,"agentId":"%s"}"""
                .formatted(agentId));
        String taskId = booked.path("id").asText();

        assertThat(agent.awaitWorkOrders(1, Duration.ofSeconds(20))).hasSize(1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("status").asText()).isEqualTo("PENDING_REVIEW"));
        JsonNode task = get("/api/tasks/" + taskId);
        assertThat(task.path("contract").path("template").asText()).isEqualTo("text_basic");
        assertThat(task.path("outputSpec").path("format").asText()).isEqualTo("TEXT");
    }

    @Test
    void aTaskSubmittedBeforeContractsKeepsItsOutputSpecAndShowsNoContract() throws Exception {
        // A Task from before V39 has no Contract; it finishes under its frozen output_spec.
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, category) "
                + "VALUES (?, ?, 'Old', 'Submitted before contracts', 10.00, ?::jsonb, 'SUBMITTED', 'other')",
                taskId, DEV_USER, "{\"format\":\"TEXT\",\"acceptanceCriteria\":\"old rules\"}");

        JsonNode task = get("/api/tasks/" + taskId);
        assertThat(task.has("contract")).isFalse();
        assertThat(task.path("outputSpec").path("acceptanceCriteria").asText()).isEqualTo("old rules");
    }

    @Test
    void aFrozenContractCannotBeChanged() throws Exception {
        JsonNode submitted = post("/api/tasks", """
                {"title":"Frozen","description":"Contract is frozen","category":"%s","budget":10.00,
                 "outputSpec":{"format":"TEXT"}}""".formatted(uniqueCategory()));

        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET contract = '{}'::jsonb WHERE id = ?::uuid",
                submitted.path("id").asText()))
                .hasMessageContaining("frozen");
    }

    // ---- helpers ----

    private UUID seedListedAgent(String category, String webhookUrl, String outputSpecJson) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Seeded Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, outputSpecJson, new String[]{category}, webhookUrl, new BigDecimal("10.00"));
        return agentId;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = send("POST", path, json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
