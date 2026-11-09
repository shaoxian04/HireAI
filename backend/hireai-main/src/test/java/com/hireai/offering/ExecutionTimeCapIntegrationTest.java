package com.hireai.offering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An Agent version may declare at most 86,400 seconds (24 hours) of execution time: registration
 * and version publishing reject one second more with a 400 validation error, and the database
 * rejects it even when the API is bypassed.
 */
class ExecutionTimeCapIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider). */
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String AGENT_SPEC =
            "{\"format\":\"JSON\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"acceptanceCriteria\":\"spec\"}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registeringWithMoreThan24HoursIsA400() throws Exception {
        HttpResponse<String> response = post("/api/agents", registerBody(86_401));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText()).contains("maxExecutionSeconds").contains("86400");
    }

    @Test
    void registeringWithExactly24HoursSucceeds() throws Exception {
        HttpResponse<String> response = post("/api/agents", registerBody(86_400));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test
    void publishingAVersionWithMoreThan24HoursIsA400() throws Exception {
        UUID agentId = seedOwnedAgent();

        HttpResponse<String> response = post("/api/agents/" + agentId + "/versions",
                publishBody(86_401));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText()).contains("maxExecutionSeconds").contains("86400");
    }

    @Test
    void publishingAVersionWithExactly24HoursSucceeds() throws Exception {
        UUID agentId = seedOwnedAgent();

        HttpResponse<String> response = post("/api/agents/" + agentId + "/versions",
                publishBody(86_400));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test
    void theDatabaseRejectsMoreThan24HoursWhenTheApiIsBypassed() {
        UUID agentId = seedOwnedAgent();

        // DEPRECATED, so only the execution-time cap (not the one-ACTIVE index) can reject it.
        assertThatThrownBy(() -> insertVersion(agentId, UUID.randomUUID(), 2, 86_401, "DEPRECATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("agent_versions_max_execution_seconds_cap");
    }

    private static String registerBody(int maxExecutionSeconds) {
        return """
                {"name":"Cap Agent","outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"},
                 "capabilityCategories":["summarisation"],"webhookUrl":"https://agent.example.com/hook",
                 "maxExecutionSeconds":%d,"price":5.00}
                """.formatted(maxExecutionSeconds);
    }

    private static String publishBody(int maxExecutionSeconds) {
        return """
                {"price":5.00,"maxExecutionSeconds":%d,"capabilityCategories":["summarisation"]}
                """.formatted(maxExecutionSeconds);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An ACTIVE agent owned by the test profile's dev user, with one version at 60 seconds. */
    private UUID seedOwnedAgent() {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Cap Agent', 'ACTIVE', ?, 80.00)", agentId, DEV_USER, versionId);
        insertVersion(agentId, versionId, 1, 60, "ACTIVE");
        return agentId;
    }

    private void insertVersion(UUID agentId, UUID versionId, int versionNumber, int maxExecutionSeconds,
                               String status) {
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price, status) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
                versionId, agentId, versionNumber, AGENT_SPEC, new String[]{"summarisation"},
                "https://agent.example.com/hook", maxExecutionSeconds, new BigDecimal("10.00"), status);
    }
}
