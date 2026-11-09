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
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Every dispatch attempt lands in the append-only {@code dispatch_attempts} history (#75) and the
 * task owner reads it back through {@code GET /api/tasks/{id}/attempts}. Tasks belong to the test
 * profile's dev user so the task API can be read over HTTP; owner-only access and the API-key
 * route are checked against the secured app in {@link DispatchAttemptAccessIntegrationTest}.
 */
class DispatchAttemptHistoryIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 30.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void aSuccessfulDispatchRecordsOneAcceptedAttempt() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        UUID[] seeded = seedAgent(category, agent.webhookUrl());

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        JsonNode attempts = await().atMost(Duration.ofSeconds(5))
                .until(() -> getAttempts(taskId), list -> list.size() == 1);
        JsonNode only = attempts.get(0);
        assertThat(only.get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(only.get("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(only.get("agentId").asText()).isEqualTo(seeded[0].toString());
        assertThat(only.get("agentVersionId").asText()).isEqualTo(seeded[1].toString());
        assertThat(only.get("occurredAt").asText()).isNotBlank();
    }

    @Test
    void anExhaustedDispatchRecordsOneUnreachableAttempt() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.status(500));
        String category = "x-" + UUID.randomUUID();
        UUID[] seeded = seedAgent(category, agent.webhookUrl());

        UUID taskId = taskWriteAppService.submit(submitInfo(category));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        JsonNode attempts = getAttempts(taskId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("outcome").asText()).isEqualTo("UNREACHABLE");
        assertThat(attempts.get(0).get("attemptNumber").asInt()).isEqualTo(1);
        assertThat(attempts.get(0).get("agentVersionId").asText()).isEqualTo(seeded[1].toString());
    }

    @Test
    void aTaskNeverDispatchedHasAnEmptyHistory() throws Exception {
        UUID taskId = taskWriteAppService.submit(submitInfo("x-" + UUID.randomUUID()));

        assertThat(status(taskId)).isEqualTo("AWAITING_AGENT");
        assertThat(getAttempts(taskId)).isEmpty();
    }

    @Test
    void theHistoryRefusesUpdateAndDelete() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        seedAgent(category, agent.webhookUrl());
        UUID taskId = taskWriteAppService.submit(submitInfo(category));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM dispatch_attempts WHERE task_id = ?", Integer.class, taskId)).isEqualTo(1));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE dispatch_attempts SET outcome = 'BUSY' WHERE task_id = ?", taskId))
                .hasMessageContaining("dispatch_attempts is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dispatch_attempts WHERE task_id = ?", taskId))
                .hasMessageContaining("dispatch_attempts is append-only");
        assertThat(getAttempts(taskId).get(0).get("outcome").asText()).isEqualTo("ACCEPTED");
    }

    private JsonNode getAttempts(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId + "/attempts"), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private TaskSubmitInfo submitInfo(String category) {
        return new TaskSubmitInfo(DEV_USER, "Summarise report", "Summarise the attached quarterly report",
                Money.of("30.00"), new OutputSpec(OutputFormat.JSON, null, "valid JSON summary"), category);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    /** Returns {agentId, versionId}. */
    private UUID[] seedAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
        return new UUID[]{agentId, versionId};
    }
}
