package com.hireai.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an Agent receives: a routed submit and a direct booking, both through the public HTTP
 * endpoints, each deliver a WorkOrder carrying the task's real title and description, its IDs,
 * the binding output spec, the execution deadline and the callback URL, and nothing about the
 * Client (no identity, no budget) or the retired expected-deliverable field.
 */
class WorkOrderContentIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider), seeded by V1 without a wallet. */
    private static final UUID DEV_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String AGENT_SPEC =
            "{\"format\":\"JSON\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"acceptanceCriteria\":\"agent spec\"}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevClient() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_CLIENT);
    }

    @Test
    void aRoutedSubmitDeliversTheSubmittedContentToTheAgent() throws Exception {
        FakeAgent agent = fakeAgent();
        String category = "x-" + UUID.randomUUID();
        SeededAgent seeded = seedActiveAgent(category, agent.webhookUrl(), false);

        JsonNode task = post("/api/tasks", """
                {"title":"Quarterly digest","description":"Summarise the Q3 board pack into five bullets",
                 "category":"%s","budget":30.00,
                 "outputSpec":{"format":"JSON","schema":"{\\"type\\":\\"array\\"}","acceptanceCriteria":"five bullets"}}
                """.formatted(category));
        UUID taskId = UUID.fromString(task.path("id").asText());

        JsonNode body = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0).json();

        assertWorkOrder(body, taskId, seeded.versionId(), "Quarterly digest",
                "Summarise the Q3 board pack into five bullets", category);
        assertThat(body.path("outputSpec").path("format").asText()).isEqualTo("JSON");
        assertThat(body.path("outputSpec").path("schema").asText()).isEqualTo("{\"type\":\"array\"}");
        assertThat(body.path("outputSpec").path("acceptanceCriteria").asText()).isEqualTo("five bullets");
    }

    @Test
    void aDirectBookingDeliversTheSubmittedContentToTheAgent() throws Exception {
        FakeAgent agent = fakeAgent();
        String category = "x-" + UUID.randomUUID();
        SeededAgent seeded = seedActiveAgent(category, agent.webhookUrl(), true);

        JsonNode task = post("/api/tasks/direct", """
                {"title":"Translate memo","description":"Translate the attached memo into formal French",
                 "budget":25.00,"agentId":"%s"}
                """.formatted(seeded.agentId()));
        UUID taskId = UUID.fromString(task.path("id").asText());

        JsonNode body = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0).json();

        assertWorkOrder(body, taskId, seeded.versionId(), "Translate memo",
                "Translate the attached memo into formal French", category);
        // A direct booking binds the Agent's own spec (Hard Invariant #4).
        assertThat(body.path("outputSpec").path("acceptanceCriteria").asText()).isEqualTo("agent spec");
    }

    private void assertWorkOrder(JsonNode body, UUID taskId, UUID versionId, String title,
                                 String description, String category) {
        assertThat(body.path("taskId").asText()).isEqualTo(taskId.toString());
        assertThat(body.path("agentVersionId").asText()).isEqualTo(versionId.toString());
        assertThat(body.path("title").asText()).isEqualTo(title);
        assertThat(body.path("description").asText()).isEqualTo(description);
        assertThat(body.path("category").asText()).isEqualTo(category);
        assertThat(body.path("callbackUrl").asText())
                .isEqualTo(platformUrl("/api/agent-callbacks/" + taskId + "/result"));

        Instant stamped = jdbc.queryForObject("SELECT execution_deadline FROM tasks WHERE id = ?",
                Timestamp.class, taskId).toInstant();
        assertThat(Instant.parse(body.path("executionDeadline").asText())).isEqualTo(stamped);

        java.util.List<String> fields = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("taskId", "agentVersionId", "title", "description",
                        "category", "outputSpec", "executionDeadline", "callbackUrl");
        assertThat(body.toString()).doesNotContain(DEV_CLIENT.toString());
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private record SeededAgent(UUID agentId, UUID versionId) {
    }

    private SeededAgent seedActiveAgent(String category, String webhookUrl, boolean listed) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, AGENT_SPEC, new String[]{category}, webhookUrl, new BigDecimal("10.00"));
        if (listed) {
            jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        }
        return new SeededAgent(agentId, versionId);
    }
}
