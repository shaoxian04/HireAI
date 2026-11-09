package com.hireai.task.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.clock.MutableTestClock;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The client deadline (#83), over the public HTTP API as the test-profile Client: an Agent is
 * eligible only if now + its max execution time + grace (60 s, the default) fits before the
 * deadline, at the shortlist, at initial routing and at every failover hop.
 *
 * <p>Exploration is off (epsilon 0) so the seeded reputation decides the pick order, and every slow
 * Agent is seeded as the best-scoring one: only the deadline can keep it out.
 */
@Import(TestClockConfig.class)
@TestPropertySource(properties = "hireai.matching.epsilon=0.0")
class ClientDeadlineIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider), seeded by V1 without a wallet. */
    private static final UUID DEV_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    @Autowired MutableTestClock clock;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_CLIENT);
    }

    @Test
    void aTightDeadlineLeavesASlowAgentOutOfTheShortlistAndTheNearMisses() throws Exception {
        String category = uniqueCategory();
        UUID fast = agentOf(seedAgent(category, fakeAgent(), "50.00", "5.00", 60));
        UUID slow = agentOf(seedAgent(category, fakeAgent(), "95.00", "5.00", 3_600));
        UUID slowAndDear = agentOf(seedAgent(category, fakeAgent(), "95.00", "50.00", 3_600));

        JsonNode open = preview(category, null);
        assertThat(agentIds(open.path("shortlist"))).containsExactly(slow.toString(), fast.toString());
        assertThat(agentIds(open.path("nearMisses"))).containsExactly(slowAndDear.toString());

        // 600 s: 60 + 60 fits, 3600 + 60 does not.
        JsonNode tight = preview(category, 600L);
        assertThat(agentIds(tight.path("shortlist"))).containsExactly(fast.toString());
        assertThat(agentIds(tight.path("nearMisses"))).isEmpty();
    }

    @Test
    void anAgentFitsExactlyWhenItsTimePlusGraceEqualsTheDeadline() throws Exception {
        String category = uniqueCategory();
        UUID exact = agentOf(seedAgent(category, fakeAgent(), "50.00", "5.00", 540));

        assertThat(agentIds(preview(category, 600L).path("shortlist"))).containsExactly(exact.toString());
        assertThat(agentIds(preview(category, 599L).path("shortlist"))).isEmpty();
    }

    @Test
    void aRoutedSubmitWithATightDeadlineSkipsTheSlowAgent() throws Exception {
        String category = uniqueCategory();
        FakeAgent slow = fakeAgent();
        FakeAgent fast = fakeAgent();
        seedAgent(category, slow, "95.00", "5.00", 3_600);
        UUID fastVersion = seedAgent(category, fast, "50.00", "5.00", 60);

        UUID taskId = submitRouted(category, 600L);

        fast.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(assignedVersion(taskId)).isEqualTo(fastVersion);
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() ->
                assertThat(slow.workOrders()).isEmpty());
    }

    @Test
    void withoutADeadlineTheSlowAgentStillWins() throws Exception {
        String category = uniqueCategory();
        FakeAgent slow = fakeAgent();
        UUID slowVersion = seedAgent(category, slow, "95.00", "5.00", 3_600);
        seedAgent(category, fakeAgent(), "50.00", "5.00", 60);

        UUID taskId = submitRouted(category, null);

        slow.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(assignedVersion(taskId)).isEqualTo(slowVersion);
    }

    @Test
    void aFailoverHopThatHappensLaterSkipsAnAgentThatNoLongerFits() throws Exception {
        String category = uniqueCategory();
        // First pick: declines as busy, 2 s after the WorkOrder arrives.
        FakeAgent first = fakeAgent().replyWith(FakeAgentReply.status(429).afterDelay(Duration.ofSeconds(2)));
        // Second by score: 600 + 60 fits a 1000 s deadline at submit, but not 400 s later.
        FakeAgent tooSlowLater = fakeAgent();
        FakeAgent quick = fakeAgent();
        UUID firstVersion = seedAgent(category, first, "95.00", "5.00", 60);
        seedAgent(category, tooSlowLater, "90.00", "5.00", 600);
        UUID quickVersion = seedAgent(category, quick, "50.00", "5.00", 60);

        UUID taskId = submitRouted(category, 1_000L);
        first.awaitWorkOrders(1, Duration.ofSeconds(20));
        clock.advance(Duration.ofSeconds(400));

        quick.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(assignedVersion(taskId)).isEqualTo(quickVersion);
        assertThat(tooSlowLater.workOrders()).isEmpty();
        assertThat(jdbc.queryForList("SELECT agent_version_id || ':' || outcome FROM dispatch_attempts "
                + "WHERE task_id = ? ORDER BY attempt_number", String.class, taskId))
                .containsExactly(firstVersion + ":BUSY", quickVersion + ":ACCEPTED");
    }

    @Test
    void aRoutedTaskNoAgentCanFinishInTimeEndsWithNoEligibleAgentAndARefund() throws Exception {
        String category = uniqueCategory();
        FakeAgent slow = fakeAgent();
        seedAgent(category, slow, "95.00", "5.00", 3_600);
        BigDecimal before = balance();

        UUID taskId = submitRouted(category, 600L);
        assertThat(status(taskId)).isEqualTo("AWAITING_AGENT");

        taskReliabilityAppService.rematchOne(taskId);
        taskReliabilityAppService.rematchOne(taskId);

        assertThat(status(taskId)).isEqualTo("CANCELLED");
        assertThat(get("/api/tasks/" + taskId).path("failureReason").asText()).isEqualTo("NO_ELIGIBLE_AGENT");
        assertThat(balance()).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
        assertThat(slow.workOrders()).isEmpty();
    }

    @Test
    void theDeadlineIsFixedWhenTheTaskIsSubmitted() throws Exception {
        String category = uniqueCategory();
        Instant submittedAt = clock.instant();

        UUID taskId = submitRouted(category, 600L);

        assertThat(jdbc.queryForObject("SELECT client_deadline FROM tasks WHERE id = ?", java.sql.Timestamp.class,
                taskId).toInstant()).isEqualTo(submittedAt.plusSeconds(600));
    }

    @Test
    void aDirectBookingOfAnAgentThatCannotFinishInTimeIsRejectedAndNothingIsFrozen() throws Exception {
        String category = uniqueCategory();
        UUID slow = agentOf(seedAgent(category, fakeAgent(), "95.00", "5.00", 3_600));
        BigDecimal before = balance();
        int tasksBefore = jdbc.queryForObject("SELECT count(*) FROM tasks WHERE client_id = ?", Integer.class, DEV_CLIENT);

        HttpResponse<String> response = send("/api/tasks/direct", """
                {"title":"Digest","description":"Summarise","budget":10.00,"agentId":"%s","deadlineSeconds":600}"""
                .formatted(slow));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("deadline");
        assertThat(balance()).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE client_id = ?", Integer.class, DEV_CLIENT))
                .isEqualTo(tasksBefore);
    }

    @Test
    void aDirectBookingThatFitsKeepsItsDeadline() throws Exception {
        String category = uniqueCategory();
        UUID fast = agentOf(seedAgent(category, fakeAgent(), "50.00", "5.00", 60));
        Instant bookedAt = clock.instant();

        JsonNode task = post("/api/tasks/direct", """
                {"title":"Digest","description":"Summarise","budget":10.00,"agentId":"%s","deadlineSeconds":600}"""
                .formatted(fast));

        assertThat(jdbc.queryForObject("SELECT client_deadline FROM tasks WHERE id = ?", java.sql.Timestamp.class,
                UUID.fromString(task.path("id").asText())).toInstant()).isEqualTo(bookedAt.plusSeconds(600));
    }

    @Test
    void aNonPositiveDeadlineIsRejected() throws Exception {
        String category = uniqueCategory();
        HttpResponse<String> response = send("/api/tasks", submitBody(category, 0L));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    }

    // --- helpers ---

    private JsonNode preview(String category, Long deadlineSeconds) throws Exception {
        String deadline = deadlineSeconds == null ? "" : ",\"deadlineSeconds\":" + deadlineSeconds;
        return post("/api/tasks/match-preview", """
                {"title":"Digest","description":"Summarise","category":"%s","budget":10%s}"""
                .formatted(category, deadline));
    }

    private UUID submitRouted(String category, Long deadlineSeconds) throws Exception {
        JsonNode task = post("/api/tasks", submitBody(category, deadlineSeconds));
        return UUID.fromString(task.path("id").asText());
    }

    private static String submitBody(String category, Long deadlineSeconds) {
        String deadline = deadlineSeconds == null ? "" : ",\"deadlineSeconds\":" + deadlineSeconds;
        return """
                {"title":"Quarterly digest","description":"Summarise the Q3 board pack","category":"%s",
                 "budget":10.00,"outputSpec":{"format":"JSON","acceptanceCriteria":"ok"}%s}"""
                .formatted(category, deadline);
    }

    private static List<String> agentIds(JsonNode options) {
        List<String> ids = new ArrayList<>();
        options.forEach(o -> ids.add(o.path("agentId").asText()));
        return ids;
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?",
                BigDecimal.class, DEV_CLIENT);
    }

    private UUID agentOf(UUID versionId) {
        return jdbc.queryForObject("SELECT agent_id FROM agent_versions WHERE id = ?", UUID.class, versionId);
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private UUID assignedVersion(UUID taskId) {
        return jdbc.queryForObject("SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = send(path, json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> send(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An ACTIVE, listed Agent (its own Builder) whose current version points at {@code agent}. */
    private UUID seedAgent(String category, FakeAgent agent, String reputation, String price,
                           int maxExecutionSeconds) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Deadline Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId,
                new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, ?, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, agent.webhookUrl(),
                maxExecutionSeconds, new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        shareSigningSecret(agent);
        return versionId;
    }
}
