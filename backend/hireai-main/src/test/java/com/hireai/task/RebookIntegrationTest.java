package com.hireai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The web "Choose another agent" rebook (#79), over the public HTTP API as the test-profile Client:
 * the failed task names the Agent it was booked with, match-preview with that Agent excluded never
 * offers it, and booking another Agent creates a new task while the failed task's row and ledger
 * stay exactly as they were.
 */
class RebookIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider), seeded by V1 without a wallet. */
    private static final UUID DEV_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired TaskWriteAppService taskWriteAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevClient() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_CLIENT);
    }

    @Test
    void rebookingAFailedTaskExcludesTheFailedAgentAndLeavesTheFailedTaskUntouched() throws Exception {
        String category = uniqueCategory();
        FakeAgent busy = fakeAgent().replyWith(FakeAgentReply.status(429));
        FakeAgent healthy = fakeAgent();
        SeededAgent failedAgent = seedListedAgent(category, busy.webhookUrl(), "10.00", "ACTIVE");
        SeededAgent otherAgent = seedListedAgent(category, healthy.webhookUrl(), "12.00", "ACTIVE");

        JsonNode booked = post("/api/tasks/direct", """
                {"title":"Quarterly digest","description":"Summarise the Q3 board pack",
                 "budget":10.00,"agentId":"%s"}""".formatted(failedAgent.agentId()));
        UUID failedTaskId = UUID.fromString(booked.path("id").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, failedTaskId))
                        .isEqualTo("FAILED"));

        // The failed task tells the web Client everything the rebook form needs.
        JsonNode failed = get("/api/tasks/" + failedTaskId);
        assertThat(failed.path("agentId").asText()).isEqualTo(failedAgent.agentId().toString());
        assertThat(failed.path("category").asText()).isEqualTo(category);
        Map<String, Object> rowBefore = taskRow(failedTaskId);
        List<Map<String, Object>> ledgerBefore = ledgerOf(failedTaskId);

        String preview = """
                {"title":"Quarterly digest","description":"Summarise the Q3 board pack",
                 "category":"%s","budget":20%s}""";
        assertThat(agentIds(post("/api/tasks/match-preview", preview.formatted(category, ""))))
                .contains(failedAgent.agentId().toString());
        JsonNode excluded = post("/api/tasks/match-preview",
                preview.formatted(category, ",\"exclude\":[\"" + failedAgent.agentId() + "\"]"));
        assertThat(agentIds(excluded))
                .containsExactly(otherAgent.agentId().toString())
                .doesNotContain(failedAgent.agentId().toString());

        JsonNode rebooked = post("/api/tasks/direct", """
                {"title":"Quarterly digest","description":"Summarise the Q3 board pack",
                 "budget":12.00,"agentId":"%s"}""".formatted(otherAgent.agentId()));

        UUID newTaskId = UUID.fromString(rebooked.path("id").asText());
        assertThat(newTaskId).isNotEqualTo(failedTaskId);
        assertThat(rebooked.path("budget").decimalValue()).isEqualByComparingTo("12.00"); // the Agent's exact price
        healthy.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(taskRow(failedTaskId)).isEqualTo(rowBefore);
        assertThat(ledgerOf(failedTaskId)).isEqualTo(ledgerBefore);
    }

    @Test
    void aDirectBookingThatWasNeverAssignedStillNamesThePinnedAgent() throws Exception {
        // A suspended Agent never gets the task assigned, so only the pin records who was chosen.
        SeededAgent suspended = seedListedAgent("x-" + UUID.randomUUID(), fakeAgent().webhookUrl(), "10.00",
                "SUSPENDED");
        UUID taskId = taskWriteAppService.submitDirectlyBooked(new TaskSubmitInfo(DEV_CLIENT, "Digest",
                "Summarise", Money.of("10.00"), new OutputSpec(OutputFormat.JSON, null, "ok"), "general"),
                suspended.versionId());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                        .isNotEqualTo("SUBMITTED"));

        assertThat(get("/api/tasks/" + taskId).path("agentId").asText())
                .isEqualTo(suspended.agentId().toString());
    }

    @Test
    void aRoutedTaskThatNeverFoundAnAgentNamesNone() throws Exception {
        JsonNode task = post("/api/tasks", """
                {"title":"Digest","description":"Summarise","category":"%s","budget":10.00,
                 "outputSpec":{"format":"JSON","acceptanceCriteria":"ok"}}""".formatted(uniqueCategory()));

        assertThat(get("/api/tasks/" + task.path("id").asText()).has("agentId")).isFalse();
    }

    private static List<String> agentIds(JsonNode preview) {
        List<String> ids = new ArrayList<>();
        preview.path("shortlist").forEach(o -> ids.add(o.path("agentId").asText()));
        preview.path("nearMisses").forEach(o -> ids.add(o.path("agentId").asText()));
        return ids;
    }

    private Map<String, Object> taskRow(UUID taskId) {
        return jdbc.queryForMap("SELECT status, failure_reason, agent_version_id, budget, resolution, resolved_at "
                + "FROM tasks WHERE id = ?", taskId);
    }

    private List<Map<String, Object>> ledgerOf(UUID taskId) {
        return jdbc.queryForList("SELECT id, entry_type, amount FROM ledger_entries WHERE related_task_id = ? "
                + "ORDER BY created_at, id", taskId);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
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

    private SeededAgent seedListedAgent(String category, String webhookUrl, String price, String status) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', ?, ?, 80.00)", agentId, ownerId, status, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        return new SeededAgent(agentId, versionId);
    }
}
