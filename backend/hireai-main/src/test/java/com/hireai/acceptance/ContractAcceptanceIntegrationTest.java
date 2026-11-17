package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #90 (spec #87 D8, D25): automated acceptance judges the Task's frozen Contract, not its output
 * spec, and a schema that is not a valid JSON Schema is refused with 400 at submit, at Agent
 * registration and at booking, with nothing frozen. Driven over the public HTTP API as the test
 * profile's fixed caller (the API-key path is covered in ProgrammaticSubmissionIntegrationTest).
 */
class ContractAcceptanceIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOTAL_SCHEMA = "{\"type\":\"object\",\"required\":[\"total\"]}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    // ---- 400 at submit, registration and booking ----

    @Test
    void aRoutedSubmitWithAProseSchemaIs400AndFreezesNothing() throws Exception {
        BigDecimal before = available();
        long tasksBefore = taskCount();

        HttpResponse<String> response = send("POST", "/api/tasks", routedJson("must contain the totals"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(message(response)).startsWith("outputSpec.schema: is not a valid JSON Schema");
        assertThat(available()).isEqualByComparingTo(before);
        assertThat(taskCount()).isEqualTo(tasksBefore);
    }

    @Test
    void aRoutedSubmitWithJsonThatBreaksTheSchemaRulesIs400() throws Exception {
        HttpResponse<String> response = send("POST", "/api/tasks", routedJson("{\"type\":\"objekt\"}"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(message(response)).contains("JSON Schema 2020-12");
    }

    @Test
    void aSchemaReferringToAnotherDocumentIs400AndTheBackendFetchesNothing() throws Exception {
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 50,
                java.net.InetAddress.getLoopbackAddress())) {
            java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean();
            Thread acceptor = new Thread(() -> {
                try (java.net.Socket ignored = listener.accept()) {
                    connected.set(true);
                } catch (java.io.IOException closed) {
                    // closed below: nothing connected
                }
            });
            acceptor.start();
            String remote = "{\"$ref\":\"http://127.0.0.1:" + listener.getLocalPort() + "/x\"}";

            HttpResponse<String> http = send("POST", "/api/tasks", routedJson(remote));
            HttpResponse<String> file = send("POST", "/api/tasks", routedJson("{\"$ref\":\"file:///etc/passwd\"}"));
            HttpResponse<String> draft = send("POST", "/api/tasks", routedJson(
                    "{\"$schema\":\"http://127.0.0.1:" + listener.getLocalPort() + "/meta\"}"));

            Thread.sleep(300);
            listener.close();
            acceptor.join(2000);
            assertThat(http.statusCode()).as(http.body()).isEqualTo(400);
            assertThat(message(http)).contains("only local references");
            assertThat(file.statusCode()).as(file.body()).isEqualTo(400);
            assertThat(draft.statusCode()).as(draft.body()).isEqualTo(400);
            assertThat(connected).isFalse();
        }
    }

    @Test
    void aRoutedSubmitWithAValidOrBlankSchemaIsAccepted() throws Exception {
        assertThat(send("POST", "/api/tasks", routedJson(TOTAL_SCHEMA)).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/tasks", routedJson("")).statusCode()).isEqualTo(200);
    }

    @Test
    void registeringAnAgentWithAProseSchemaIs400() throws Exception {
        HttpResponse<String> response = send("POST", "/api/agents", """
                {"name":"Prose agent","outputSpec":{"format":"JSON","schema":"a list of totals"},
                 "capabilityCategories":["%s"],"webhookUrl":"https://agent.example.com/run",
                 "maxExecutionSeconds":60,"price":5}""".formatted(uniqueCategory()));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(message(response)).startsWith("outputSpec.schema: is not a valid JSON Schema");
    }

    @Test
    void bookingAnAgentWhoseOldSchemaIsProseIs400NamingTheAgentsSchema() throws Exception {
        // Registered before D8 (raw SQL, as an older row would be): its schema is prose.
        UUID agentId = seedListedAgent(uniqueCategory(), "https://agent.example.com/run",
                "{\"format\":\"JSON\",\"schema\":\"a list of totals\"}");
        BigDecimal before = available();

        HttpResponse<String> response = send("POST", "/api/tasks/direct", bookingJson(agentId));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(message(response)).startsWith("This Agent's output schema is not a valid JSON Schema");
        assertThat(available()).isEqualByComparingTo(before);
    }

    // ---- acceptance judges the Contract ----

    @Test
    void aResultMatchingTheContractSchemaGoesToReview() throws Exception {
        FakeAgent agent = agentReplying("{\\\"total\\\":42}", Duration.ofMillis(200));
        UUID agentId = seedListedAgent(uniqueCategory(), agent.webhookUrl(), jsonSpec(TOTAL_SCHEMA));
        shareSigningSecret(agent);

        String taskId = post("/api/tasks/direct", bookingJson(agentId)).path("id").asText();

        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(checks(taskId)).contains("FORMAT_JSON_PARSEABLE:true", "SCHEMA_MATCH:true");
    }

    @Test
    void aResultBreakingTheContractSchemaIsASpecViolationEvenIfTheOutputSpecWouldPassIt() throws Exception {
        FakeAgent agent = agentReplying("{\\\"summary\\\":\\\"no total\\\"}", Duration.ofSeconds(2));
        UUID agentId = seedListedAgent(uniqueCategory(), agent.webhookUrl(), jsonSpec(TOTAL_SCHEMA));
        shareSigningSecret(agent);

        String taskId = post("/api/tasks/direct", bookingJson(agentId)).path("id").asText();
        // Strip the schema from the task's output_spec before the result arrives: if acceptance still
        // read output_spec, the result would pass. It reads the frozen Contract, which keeps it.
        jdbc.update("UPDATE tasks SET output_spec = '{\"format\":\"JSON\"}'::jsonb WHERE id = ?::uuid", taskId);

        awaitStatus(taskId, "SPEC_VIOLATION");
        assertThat(checks(taskId)).contains("FORMAT_JSON_PARSEABLE:true", "SCHEMA_MATCH:false");
    }

    @Test
    void textBasicPublishesItsOptionalLengthBounds() throws Exception {
        JsonNode text = get("/api/catalogue/templates/text_basic/1");

        assertThat(text.path("checks")).extracting(JsonNode::asText)
                .containsExactly("FORMAT_TEXT_NON_EMPTY", "TEXT_MIN_LENGTH", "TEXT_MAX_LENGTH");
        assertThat(text.path("parameters")).extracting(p -> p.path("name").asText() + ":"
                + p.path("comparison").asText()).containsExactly("min_length:max", "max_length:min");
        // The bounds are additive: a TEXT task still freezes a Contract that sets neither.
        String taskId = post("/api/tasks", """
                {"title":"Summarise","description":"Summarise it","category":"%s","budget":10.00,
                 "outputSpec":{"format":"TEXT"}}""".formatted(uniqueCategory())).path("id").asText();
        assertThat(get("/api/tasks/" + taskId).path("contract").path("params").isEmpty()).isTrue();
    }

    @Test
    void theTemplateImmutabilityTriggerIsBackInForce() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE contract_templates SET definition = '{}'::jsonb WHERE template_key = 'text_basic'"))
                .hasMessageContaining("immutable");
    }

    // ---- helpers ----

    private FakeAgent agentReplying(String escapedPayload, Duration delay) {
        return fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.custom(order ->
                        CallbackRequest.post(order.callbackUrl())
                                .header("Authorization", "Bearer " + order.bearerToken())
                                .body("{\"taskId\":\"" + order.taskId() + "\",\"agentStatus\":\"COMPLETED\","
                                        + "\"resultPayloadJson\":\"" + escapedPayload + "\"}"))
                .afterReply(delay)));
    }

    private String jsonSpec(String schema) throws Exception {
        return "{\"format\":\"JSON\",\"schema\":" + mapper.writeValueAsString(schema) + "}";
    }

    private String routedJson(String schema) throws Exception {
        return """
                {"title":"Extract totals","description":"Pull the totals","category":"%s","budget":10.00,
                 "outputSpec":{"format":"JSON","schema":%s}}""".formatted(uniqueCategory(),
                mapper.writeValueAsString(schema));
    }

    private static String bookingJson(UUID agentId) {
        return "{\"title\":\"Totals\",\"description\":\"Pull the totals\",\"budget\":10.00,\"agentId\":\""
                + agentId + "\"}";
    }

    private void awaitStatus(String taskId, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("status").asText()).isEqualTo(status));
    }

    /** The latest validation report's checks as {@code rule:passed}. */
    private java.util.List<String> checks(String taskId) {
        return jdbc.queryForList("SELECT c ->> 'rule' || ':' || (c ->> 'passed') FROM validation_reports r, "
                + "jsonb_array_elements(r.checks) c WHERE r.task_id = ?::uuid", String.class, taskId);
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER);
    }

    private long taskCount() {
        return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE client_id = ?", Long.class, DEV_USER);
    }

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

    private String message(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body()).path("message").asText();
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
