package com.hireai.dispatch;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Availability is re-checked before every send (#58): an Agent deactivated, or a version superseded,
 * after the task was QUEUED but before its WorkOrder went out is "unavailable at dispatch". A pinned
 * task fails fast (CANCELLED, AGENT_UNAVAILABLE, refund); a routed one fails over to the next Agent.
 * Neither ever counts against the Builder, and the unavailable Agent never receives the WorkOrder.
 *
 * <p>The window between QUEUED and the send is opened by holding back the first WorkOrder message of
 * a task in a spied {@link TaskDispatchPublisher}, changing the Agent, then publishing it for real.
 */
@TestPropertySource(properties = "hireai.matching.epsilon=0.0")
class DispatchAvailabilityIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired TaskWriteAppService taskWriteAppService;
    @SpyBean TaskDispatchPublisher publisher;

    /** The held-back first WorkOrder message of each task, keyed by task id. */
    private final ConcurrentMap<UUID, DispatchMessage> held = new ConcurrentHashMap<>();
    private BigDecimal startingBalance;

    @BeforeEach
    void holdBackFirstWorkOrders() {
        held.clear();
        doAnswer(invocation -> {
            DispatchMessage message = invocation.getArgument(0);
            if (held.putIfAbsent(message.taskId(), message) == null) {
                return null;
            }
            return invocation.callRealMethod();
        }).when(publisher).publish(any());
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 30.00 WHERE user_id = ?", DEV_USER);
        startingBalance = jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER);
    }

    @Test
    void aPinnedAgentDeactivatedBetweenQueuedAndSendIsCancelledAsUnavailableAndNeverBlamed() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        Seeded seeded = seedAgent("general", agent, "80.00", "10.00");
        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo("general"), seeded.versionId());
        DispatchMessage message = awaitHeld(taskId);
        assertThat(status(taskId)).isEqualTo("QUEUED");

        jdbc.update("UPDATE agents SET status = 'DEACTIVATED' WHERE id = ?", seeded.agentId());
        release(message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("CANCELLED"));
        assertThat(failureReason(taskId)).isEqualTo("AGENT_UNAVAILABLE");
        assertThat(attempts(taskId)).containsExactly(seeded.versionId() + ":UNAVAILABLE");
        assertThat(agent.workOrders()).isEmpty();
        assertNoReputationEvents(taskId);
        assertRefundedOnce(taskId);
    }

    @Test
    void aPinnedVersionSupersededBetweenQueuedAndSendIsCancelledAsUnavailable() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        Seeded seeded = seedAgent("general", agent, "80.00", "10.00");
        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo("general"), seeded.versionId());
        DispatchMessage message = awaitHeld(taskId);

        supersede(seeded, agent);
        release(message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("CANCELLED"));
        assertThat(failureReason(taskId)).isEqualTo("AGENT_UNAVAILABLE");
        assertThat(agent.workOrders()).isEmpty();
        assertNoReputationEvents(taskId);
        assertRefundedOnce(taskId);
    }

    @Test
    void aRoutedAgentDeactivatedBetweenQueuedAndSendFailsOverWithNoPenaltyAndNeverRecordsExecutionFailed() {
        String category = uniqueCategory();
        FakeAgent first = fakeAgent().replyWith(FakeAgentReply.accepted());
        FakeAgent second = fakeAgent().replyWith(FakeAgentReply.accepted());
        Seeded firstSeeded = seedAgent(category, first, "95.00", "5.00");
        Seeded secondSeeded = seedAgent(category, second, "50.00", "8.00");
        UUID taskId = taskWriteAppService.submit(submitInfo(category));
        DispatchMessage message = awaitHeld(taskId);
        assertThat(message.agentVersionId()).isEqualTo(firstSeeded.versionId());

        jdbc.update("UPDATE agents SET status = 'DEACTIVATED' WHERE id = ?", firstSeeded.agentId());
        release(message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        assertThat(attempts(taskId)).containsExactly(
                firstSeeded.versionId() + ":UNAVAILABLE", secondSeeded.versionId() + ":ACCEPTED");
        assertThat(first.workOrders()).isEmpty();
        assertThat(second.workOrders()).hasSize(1);
        assertNoReputationEvents(taskId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    private DispatchMessage awaitHeld(UUID taskId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> held.containsKey(taskId));
        return held.get(taskId);
    }

    private void release(DispatchMessage message) {
        // The answer passes every message after the first of its task through to the real publisher.
        publisher.publish(message);
    }

    private void assertNoReputationEvents(UUID taskId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();
    }

    private void assertRefundedOnce(UUID taskId) {
        assertThat(jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_USER)).isEqualByComparingTo(startingBalance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    private List<String> attempts(UUID taskId) {
        return jdbc.queryForList("SELECT agent_version_id || ':' || outcome FROM dispatch_attempts "
                + "WHERE task_id = ? ORDER BY attempt_number", String.class, taskId);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private String failureReason(UUID taskId) {
        return jdbc.queryForObject("SELECT failure_reason FROM tasks WHERE id = ?", String.class, taskId);
    }

    private TaskSubmitInfo submitInfo(String category) {
        return new TaskSubmitInfo(DEV_USER, "Summarise report", "Summarise the attached quarterly report",
                Money.of("10.00"), new OutputSpec(OutputFormat.JSON, null, "valid JSON summary"), category);
    }

    private record Seeded(UUID agentId, UUID versionId) {}

    /** An ACTIVE, listed Agent (owned by its own Builder) whose current version points at {@code agent}. */
    private Seeded seedAgent(String category, FakeAgent agent, String reputation, String price) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Availability Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId,
                new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        insertVersion(agentId, versionId, 1, category, agent, price);
        shareSigningSecret(agent);
        return new Seeded(agentId, versionId);
    }

    /** Publishes a version 2 over the seeded one, as AgentWriteAppService does. */
    private void supersede(Seeded seeded, FakeAgent agent) {
        jdbc.update("UPDATE agent_versions SET status = 'DEPRECATED' WHERE id = ?", seeded.versionId());
        UUID newVersion = UUID.randomUUID();
        insertVersion(seeded.agentId(), newVersion, 2, "general", agent, "10.00");
        jdbc.update("UPDATE agents SET current_version_id = ? WHERE id = ?", newVersion, seeded.agentId());
    }

    private void insertVersion(UUID agentId, UUID versionId, int number, String category, FakeAgent agent,
                               String price) {
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, ?, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, number, "{\"format\":\"JSON\"}", new String[]{category}, agent.webhookUrl(),
                new BigDecimal(price));
    }
}
