package com.hireai.support.harness;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackOutcome;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the harness wiring end to end on the unmodified app: a routed task reaches a fake
 * Agent, and the fake Agent's scripted callback reaches the real callback endpoint with the
 * WorkOrder's token, driving the task to PENDING_REVIEW.
 */
class FakeAgentHarnessIntegrationTest extends WholeAppIntegrationTest {

    @Autowired TaskWriteAppService taskWriteAppService;

    @Test
    void aRoutedTaskReachesTheFakeAgentAndItsLateCallbackIsRecorded() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(500))));
        String category = "harness-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());
        shareSigningSecret(agent);
        UUID client = newClientWithCredits();

        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(client, "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));

        CallbackOutcome callback = agent.awaitCallbacks(1, Duration.ofSeconds(20)).get(0);
        assertThat(callback.status()).isEqualTo(200);
        assertThat(agent.workOrders()).hasSize(1);
        assertThat(agent.workOrders().get(0).taskId()).isEqualTo(taskId.toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                        .isEqualTo("PENDING_REVIEW"));
    }

    private UUID newClientWithCredits() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), id);
        return id;
    }

    private void seedActiveAgent(String category, String webhookUrl) {
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
    }
}
