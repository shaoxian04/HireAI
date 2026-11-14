package com.hireai.dispatch;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackOutcome;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Race-free dispatch (#63): no ordering of the WorkOrder send, the mark-EXECUTING step and the
 * Agent's callback loses a result or sends a WorkOrder twice, and a stale dispatch message for a
 * task that has already ended never reaches the Agent.
 */
class DispatchRaceIntegrationTest extends WholeAppIntegrationTest {

    /** Longer than the harness's whole dispatch retry schedule (3 × 500ms), so a resend would show. */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(5);

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskDispatchPublisher taskDispatchPublisher;
    @SpyBean TaskExecutionPort taskExecutionPort;

    @Test
    void anAgentThatCallsBackBeforeTheTaskIsMarkedExecutingHasItsResultAccepted() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().beforeReply()));
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());
        shareSigningSecret(agent);

        UUID taskId = submit(category);

        CallbackOutcome callback = agent.awaitCallbacks(1, Duration.ofSeconds(20)).get(0);
        assertThat(callback.sentBeforeReply()).isTrue();
        assertThat(callback.status()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_results WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
        // The consumer's mark-EXECUTING step, arriving after the result, must not move it back.
        assertNoChangeFor(QUIET_PERIOD, () -> assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
    }

    @Test
    void aFailureAfterASuccessfulSendProducesExactlyOneWorkOrder() {
        doThrow(new IllegalStateException("database blip")).when(taskExecutionPort).markExecuting(any());
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofSeconds(2))));
        String category = "x-" + UUID.randomUUID();
        seedActiveAgent(category, agent.webhookUrl());
        shareSigningSecret(agent);

        UUID taskId = submit(category);

        agent.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertNoChangeFor(QUIET_PERIOD, () -> assertThat(agent.workOrders()).hasSize(1));
        // The task stayed QUEUED, which is safe: the callback is accepted from QUEUED.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        assertThat(agent.workOrders()).hasSize(1);
    }

    @Test
    void aDispatchMessageForATimedOutTaskIsDroppedAndTheAgentReceivesNothing() {
        assertStaleMessageDropped((id, version) -> taskExecutionPort.markTimedOut(id), "TIMED_OUT");
    }

    @Test
    void aDispatchMessageForAFailedAndRefundedTaskIsDroppedAndTheAgentReceivesNothing() {
        assertStaleMessageDropped(taskExecutionPort::markFailed, "FAILED");
    }

    private void assertStaleMessageDropped(BiConsumer<UUID, UUID> endTask, String endStatus) {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String category = "x-" + UUID.randomUUID();
        UUID versionId = seedActiveAgent(category, agent.webhookUrl());
        UUID taskId = submit(category);
        agent.awaitWorkOrders(1, Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));

        endTask.accept(taskId, versionId);
        assertThat(status(taskId)).isEqualTo(endStatus);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);

        taskDispatchPublisher.publish(new DispatchMessage(taskId, versionId, agent.webhookUrl(),
                UUID.randomUUID().toString(), new TaskDispatchPayload("Summarise report",
                "Summarise the attached quarterly report", category, "{\"format\":\"JSON\"}",
                Instant.now().plusSeconds(120), platformUrl("/api/agent-callbacks/" + taskId + "/result"))));

        assertNoChangeFor(QUIET_PERIOD, () -> assertThat(agent.workOrders()).hasSize(1));
        assertThat(status(taskId)).isEqualTo(endStatus);
    }

    /** Holds {@code assertion} true for the whole {@code period}, failing as soon as it breaks. */
    private static void assertNoChangeFor(Duration period, Runnable assertion) {
        await().during(period).atMost(period.plusSeconds(5)).untilAsserted(assertion::run);
    }

    private UUID submit(String category) {
        return taskWriteAppService.submit(new TaskSubmitInfo(newClientWithCredits(), "Summarise report",
                "Summarise the attached quarterly report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
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

    private UUID seedActiveAgent(String category, String webhookUrl) {
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
        return versionId;
    }
}
