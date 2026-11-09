package com.hireai.task.reliability;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.clock.MutableTestClock;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The sweeper seam (#62): routing stamps the execution deadline from the injected Clock, and the
 * timeout sweeper's application service decides "expired" from the same Clock, so a test can pick
 * the moment and see the outcome for it.
 */
@Import(TestClockConfig.class)
class ExecutionTimeoutClockIntegrationTest extends WholeAppIntegrationTest {

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;

    @Test
    void aTaskTimesOutOnlyOnceTheClockPassesItsDeadline() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()); // accepts, never calls back
        String category = "clock-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl()); // max execution 60s; default grace 60s
        UUID client = newClientWithCredits();

        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(client, "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
        agent.awaitWorkOrders(1, Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));

        Instant deadline = jdbc.queryForObject("SELECT execution_deadline FROM tasks WHERE id = ?",
                Timestamp.class, taskId).toInstant();
        assertThat(deadline).isEqualTo(start.plusSeconds(120));

        clock.set(start.plusSeconds(119));
        assertThat(taskReliabilityAppService.executionExpiredTaskIds()).doesNotContain(taskId);

        clock.set(start.plusSeconds(121));
        assertThat(taskReliabilityAppService.executionExpiredTaskIds()).contains(taskId);
        taskReliabilityAppService.timeoutOne(taskId);

        assertThat(status(taskId)).isEqualTo("TIMED_OUT");
        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?", client);
        assertThat((BigDecimal) wallet.get("available_balance")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) wallet.get("escrow_balance")).isEqualByComparingTo("0.00");
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
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
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
    }
}
