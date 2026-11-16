package com.hireai.task.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.clock.MutableTestClock;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The Client review window (#94, spec D10/D41): a result awaiting review carries a deadline 24h
 * after it passed validation; once the injected clock passes it, the review is accepted and settled
 * automatically, exactly once, so a Client who never reviews cannot hold the Builder's pay forever.
 */
@Import(TestClockConfig.class)
class ReviewWindowIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReviewAppService taskReviewAppService;
    @Autowired ObjectMapper objectMapper;

    private Instant start;

    @BeforeEach
    void setUp() {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 10.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void aResultAwaitingReviewShowsWhenItsReviewWindowCloses() throws Exception {
        UUID taskId = taskAwaitingReview();

        JsonNode task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(Instant.parse(task.get("reviewDeadline").asText())).isEqualTo(start.plus(Duration.ofHours(24)));
    }

    /** The booking screen states the windows a booking agrees to (#101), read from config, never restated. */
    @Test
    void theReviewAndResponseWindowsABookingAgreesToAreReadable() throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/catalogue/review-windows"), String.class);
        JsonNode windows = objectMapper.readTree(body).get("data");

        assertThat(windows.get("reviewSeconds").asLong()).isEqualTo(Duration.ofHours(24).toSeconds());
        assertThat(windows.get("agentResponseSeconds").asLong()).isEqualTo(Duration.ofHours(2).toSeconds());
        assertThat(windows.get("clientDecisionSeconds").asLong()).isEqualTo(Duration.ofHours(24).toSeconds());
    }

    @Test
    void anUnreviewedResultIsAcceptedAndSettledOnceItsWindowCloses() throws Exception {
        UUID taskId = taskAwaitingReview();

        clock.set(start.plus(Duration.ofHours(24)).minusSeconds(1));
        assertThat(taskReviewAppService.reviewExpiredTaskIds()).doesNotContain(taskId);

        clock.set(start.plus(Duration.ofHours(24)));
        assertThat(taskReviewAppService.reviewExpiredTaskIds()).contains(taskId);
        taskReviewAppService.autoAcceptOne(taskId);

        JsonNode task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(task.get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(task.get("autoAccepted").asBoolean()).isTrue();
        assertThat(task.get("payoutAmount").decimalValue()).isEqualByComparingTo("8.50");
        assertPaidOnce(taskId);
    }

    @Test
    void anExpiredReviewIsAutoAcceptedExactlyOnceEvenWhenSweepsOverlap() throws Exception {
        UUID taskId = taskAwaitingReview();
        clock.set(start.plus(Duration.ofHours(25)));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> sweeps = List.of(
                    pool.submit(() -> { go.await(); taskReviewAppService.autoAcceptOne(taskId); return null; }),
                    pool.submit(() -> { go.await(); taskReviewAppService.autoAcceptOne(taskId); return null; }));
            go.countDown();
            for (Future<?> sweep : sweeps) {
                sweep.get(20, TimeUnit.SECONDS); // neither overlapping pass throws
            }
        } finally {
            pool.shutdownNow();
        }
        taskReviewAppService.autoAcceptOne(taskId); // a later pass is a no-op too

        assertThat(status(taskId)).isEqualTo("RESOLVED");
        assertPaidOnce(taskId);
    }

    @Test
    void aResultTheClientReviewedInTimeIsNotAutoAccepted() throws Exception {
        UUID taskId = taskAwaitingReview();
        clock.set(start.plus(Duration.ofHours(1)));
        new RestTemplate().postForObject(platformUrl("/api/tasks/" + taskId + "/reject"),
                Map.of("reasonCategory", "D_CHANGED_MIND", "reason", "not needed any more"), String.class);

        clock.set(start.plus(Duration.ofHours(48)));
        assertThat(taskReviewAppService.reviewExpiredTaskIds()).doesNotContain(taskId);
        taskReviewAppService.autoAcceptOne(taskId); // a stale id from an earlier read is a no-op

        JsonNode task = getTask(taskId);
        assertThat(task.get("resolution").asText()).isEqualTo("REJECTED");
        assertThat(task.get("autoAccepted")).isNull();
    }

    @Test
    void theClientCanNoLongerRejectOnceTheWindowHasClosed() throws Exception {
        UUID taskId = taskAwaitingReview();
        clock.set(start.plus(Duration.ofHours(24)));

        assertThatThrownBy(() -> new RestTemplate().postForObject(platformUrl("/api/tasks/" + taskId + "/reject"),
                Map.of("reasonCategory", "A_MISMATCH", "reason", "late complaint"), String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(status(taskId)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM disputes WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    private void assertPaidOnce(UUID taskId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        // One 85/15 acceptance: the net leaves the client's escrow and reaches the builder, once each.
        assertThat(jdbc.queryForList("SELECT amount FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'PAYOUT'", BigDecimal.class, taskId))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("8.50"), new BigDecimal("8.50"));
        assertThat(jdbc.queryForList("SELECT amount FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'COMMISSION'", BigDecimal.class, taskId))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1.50"));
        assertThat(jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?",
                String.class, taskId)).containsExactly("TASK_ACCEPTED");
    }

    private UUID taskAwaitingReview() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        String category = uniqueCategory();
        seedActiveAgent(category, agent.webhookUrl());
        shareSigningSecret(agent);
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(DEV_USER, "Summarise report",
                "Summarise the attached quarterly report", Money.of("10.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        return taskId;
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private void seedActiveAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00)",
                UUID.randomUUID(), ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("10.00"));
        acceptAnyJsonSchema(versionId);
    }
}
