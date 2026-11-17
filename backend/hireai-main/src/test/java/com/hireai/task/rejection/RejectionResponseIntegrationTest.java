package com.hireai.task.rejection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The rejection response path (#95, spec D20/D22). A Client's A/B/C rejection no longer opens
 * arbitration at once: the Agent is sent a signed rejection notice and, within 2h, may revise once,
 * concede (refund) or stand by its result with a reason; silence counts as standing by. After a
 * stand-by the Client has 24h to accept or request AI arbitration; silence counts as acceptance.
 * Driven from the outside: HTTP, the notices the fake Agent receives, its signed responses, statuses,
 * ledger rows and reputation events, with the injectable clock deciding when windows close.
 */
@Import(TestClockConfig.class)
class RejectionResponseIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REVISED_PAYLOAD = "{\"summary\":\"revised, with the missing section\"}";

    @Autowired MutableTestClock clock;
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired RejectionAppService rejectionAppService;
    @Autowired ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
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
    void aRejectionForReasonABOrCWaitsForTheAgentAndSendsItASignedNotice() throws Exception {
        Fixture f = taskAwaitingReview();

        reject(f.taskId(), "A_MISMATCH", "the totals are wrong");

        JsonNode task = getTask(f.taskId());
        assertThat(task.get("status").asText()).isEqualTo("AWAITING_AGENT_RESPONSE");
        JsonNode rejection = task.get("rejection");
        assertThat(rejection.get("reasonCategory").asText()).isEqualTo("A_MISMATCH");
        assertThat(rejection.get("reason").asText()).isEqualTo("the totals are wrong");
        assertThat(Instant.parse(rejection.get("responseDeadline").asText())).isEqualTo(start.plus(Duration.ofHours(2)));
        assertThat(rejection.get("revisionAllowed").asBoolean()).isTrue();
        assertThat(disputes(f.taskId())).isZero(); // no arbitration yet

        RecordedWorkOrder notice = awaitNotice(f, 1);
        assertThat(notice.field("event")).isEqualTo("task.rejected");
        assertThat(notice.field("taskId")).isEqualTo(f.taskId().toString());
        assertThat(notice.field("reasonCategory")).isEqualTo("A_MISMATCH");
        assertThat(notice.field("reason")).isEqualTo("the totals are wrong");
        assertThat(notice.json().get("revisionAllowed").asBoolean()).isTrue();
        // The notice carries the frozen Contract exactly as the WorkOrder does (#110).
        assertThat(notice.json().get("contract")).isNotNull().isEqualTo(f.agent().workOrders().get(0).json().get("contract"));
        assertThat(Instant.parse(notice.field("responseDeadline"))).isEqualTo(start.plus(Duration.ofHours(2)));
        assertThat(notice.field("responseUrl"))
                .isEqualTo(platformUrl("/api/agent-callbacks/" + f.taskId() + "/rejection-response"));
        assertThat(notice.bearerToken()).isNotBlank();
        assertThat(notice.header(AgentCallback.SIGNATURE_HEADER)).startsWith("t=");
    }

    @Test
    void aResponseIsRefusedWithout401UnlessItCarriesTheTokenAndAFreshSignature() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "C_INCOMPLETE", "a section is missing");
        RecordedWorkOrder notice = awaitNotice(f, 1);
        String body = responseBody(f.taskId(), "CONCEDE", null, null);

        assertThat(post(notice.field("responseUrl"), notice.bearerToken(), null, body).statusCode()).isEqualTo(401);
        assertThat(post(notice.field("responseUrl"), notice.bearerToken(),
                AgentCallback.signature("agsec_wrong", clock.instant(), bytes(body)), body).statusCode()).isEqualTo(401);
        assertThat(post(notice.field("responseUrl"), notice.bearerToken(),
                AgentCallback.signature(f.agent().signingSecret(), clock.instant().minus(Duration.ofMinutes(10)),
                        bytes(body)), body).statusCode()).isEqualTo(401);
        assertThat(post(notice.field("responseUrl"), "not-a-token",
                AgentCallback.signature(f.agent().signingSecret(), clock.instant(), bytes(body)), body).statusCode())
                .isEqualTo(401);
        // A signed body naming another task is not this task's response.
        String foreign = responseBody(UUID.randomUUID(), "CONCEDE", null, null);
        assertThat(post(notice.field("responseUrl"), notice.bearerToken(),
                AgentCallback.signature(f.agent().signingSecret(), clock.instant(), bytes(foreign)), foreign).statusCode())
                .isEqualTo(401);

        assertThat(status(f.taskId())).isEqualTo("AWAITING_AGENT_RESPONSE");
    }

    @Test
    void aRevisedResultGoesBackToReviewAndAcceptingItWritesTaskAccepted() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "C_INCOMPLETE", "a section is missing");
        clock.advance(Duration.ofMinutes(30));

        HttpResponse<String> reply = respond(f, awaitNotice(f, 1), "REVISE", null, REVISED_PAYLOAD);
        assertThat(reply.statusCode()).isEqualTo(200);

        JsonNode task = getTask(f.taskId());
        assertThat(task.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(Instant.parse(task.get("reviewDeadline").asText()))
                .isEqualTo(start.plus(Duration.ofMinutes(30)).plus(Duration.ofHours(24)));
        assertThat(task.get("rejection").get("response").asText()).isEqualTo("REVISE");
        assertThat(resultPayload(f.taskId())).isEqualTo(REVISED_PAYLOAD);

        new RestTemplate().postForObject(platformUrl("/api/tasks/" + f.taskId() + "/accept"), null, String.class);

        assertThat(getTask(f.taskId()).get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(events(f.taskId())).containsExactly("TASK_ACCEPTED");
        assertThat(payouts(f.taskId())).containsExactly(new BigDecimal("8.50"), new BigDecimal("8.50"));
    }

    @Test
    void anAgentMayReviseOnlyOnce() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "B_FACTUAL", "wrong figures");
        respond(f, awaitNotice(f, 1), "REVISE", null, REVISED_PAYLOAD);

        reject(f.taskId(), "B_FACTUAL", "still wrong");
        RecordedWorkOrder second = awaitNotice(f, 2);
        assertThat(second.json().get("revisionAllowed").asBoolean()).isFalse();
        assertThat(getTask(f.taskId()).get("rejection").get("revisionAllowed").asBoolean()).isFalse();

        assertThat(respond(f, second, "REVISE", null, REVISED_PAYLOAD).statusCode()).isEqualTo(409);
        assertThat(status(f.taskId())).isEqualTo("AWAITING_AGENT_RESPONSE");

        assertThat(respond(f, second, "STAND_BY", "the figures match the source", null).statusCode()).isEqualTo(200);
        assertThat(status(f.taskId())).isEqualTo("AWAITING_CLIENT_DECISION");
    }

    @Test
    void conceding_refundsTheClientInFullAndWritesAZeroQualityEvent() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "A_MISMATCH", "not what I asked");

        assertThat(respond(f, awaitNotice(f, 1), "CONCEDE", null, null).statusCode()).isEqualTo(200);

        JsonNode task = getTask(f.taskId());
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(task.get("resolution").asText()).isEqualTo("REJECTED");
        assertThat(task.get("rejection").get("response").asText()).isEqualTo("CONCEDE");
        assertThat(jdbc.queryForList("SELECT amount FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'REFUND'", BigDecimal.class, f.taskId()))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("10.00"));
        assertThat(jdbc.queryForList("SELECT event_type || ':' || quality FROM reputation_events WHERE task_id = ?",
                String.class, f.taskId())).containsExactly("REJECTION_CONCEDED:0.000");
        assertThat(payouts(f.taskId())).isEmpty();
        assertThat(disputes(f.taskId())).isZero();
    }

    @Test
    void standingByShowsTheReasonAndOnlyTheClientOpensArbitrationWithNoDeposit() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "B_FACTUAL", "the dates are wrong");
        clock.advance(Duration.ofMinutes(10));

        assertThat(respond(f, awaitNotice(f, 1), "STAND_BY", "the dates come from the source document", null)
                .statusCode()).isEqualTo(200);

        JsonNode task = getTask(f.taskId());
        assertThat(task.get("status").asText()).isEqualTo("AWAITING_CLIENT_DECISION");
        JsonNode rejection = task.get("rejection");
        assertThat(rejection.get("response").asText()).isEqualTo("STAND_BY");
        assertThat(rejection.get("responseReason").asText()).isEqualTo("the dates come from the source document");
        assertThat(rejection.get("responseSilent").asBoolean()).isFalse();
        assertThat(Instant.parse(rejection.get("decisionDeadline").asText()))
                .isEqualTo(start.plus(Duration.ofMinutes(10)).plus(Duration.ofHours(24)));
        Map<String, BigDecimal> before = clientWallet();

        new RestTemplate().postForObject(platformUrl("/api/tasks/" + f.taskId() + "/arbitration"), null, String.class);

        assertThat(status(f.taskId())).isEqualTo("DISPUTED");
        assertThat(jdbc.queryForObject("SELECT raised_by FROM disputes WHERE task_id = ?", UUID.class, f.taskId()))
                .isEqualTo(DEV_USER);
        assertThat(jdbc.queryForObject("SELECT reason_category FROM disputes WHERE task_id = ?", String.class,
                f.taskId())).isEqualTo("B_FACTUAL");
        assertThat(clientWallet()).isEqualTo(before); // no deposit: the frozen escrow is the security
        assertThat(getTask(f.taskId()).get("rejection").get("decision").asText()).isEqualTo("ARBITRATE");
    }

    @Test
    void theClientMayAcceptTheResultAfterTheAgentStandsBy() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "A_MISMATCH", "wrong layout");
        respond(f, awaitNotice(f, 1), "STAND_BY", "the layout follows the spec", null);

        new RestTemplate().postForObject(platformUrl("/api/tasks/" + f.taskId() + "/accept"), null, String.class);

        JsonNode task = getTask(f.taskId());
        assertThat(task.get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(task.get("rejection").get("decision").asText()).isEqualTo("ACCEPT");
        assertThat(events(f.taskId())).containsExactly("TASK_ACCEPTED");
        assertThat(payouts(f.taskId())).containsExactly(new BigDecimal("8.50"), new BigDecimal("8.50"));
    }

    @Test
    void agentSilenceCountsAsStandingByAndClientSilenceAsAcceptance() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "C_INCOMPLETE", "missing parts");
        awaitNotice(f, 1);

        clock.set(start.plus(Duration.ofHours(2)).minusSeconds(1));
        assertThat(rejectionAppService.responseExpiredTaskIds()).doesNotContain(f.taskId());
        clock.set(start.plus(Duration.ofHours(2)));
        assertThat(rejectionAppService.responseExpiredTaskIds()).contains(f.taskId());
        rejectionAppService.standBySilentlyOne(f.taskId());
        rejectionAppService.standBySilentlyOne(f.taskId()); // a second pass is a no-op

        JsonNode rejection = getTask(f.taskId()).get("rejection");
        assertThat(status(f.taskId())).isEqualTo("AWAITING_CLIENT_DECISION");
        assertThat(rejection.get("response").asText()).isEqualTo("STAND_BY");
        assertThat(rejection.get("responseSilent").asBoolean()).isTrue();
        assertThat(rejection.get("responseReason")).isNull();
        Instant decisionDeadline = start.plus(Duration.ofHours(26));
        assertThat(Instant.parse(rejection.get("decisionDeadline").asText())).isEqualTo(decisionDeadline);

        clock.set(decisionDeadline.minusSeconds(1));
        assertThat(rejectionAppService.decisionExpiredTaskIds()).doesNotContain(f.taskId());
        clock.set(decisionDeadline);
        assertThat(rejectionAppService.decisionExpiredTaskIds()).contains(f.taskId());
        rejectionAppService.acceptSilentlyOne(f.taskId());
        rejectionAppService.acceptSilentlyOne(f.taskId());

        JsonNode task = getTask(f.taskId());
        assertThat(task.get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(task.get("rejection").get("decision").asText()).isEqualTo("ACCEPT");
        assertThat(task.get("rejection").get("decisionSilent").asBoolean()).isTrue();
        assertThat(events(f.taskId())).containsExactly("TASK_ACCEPTED");
        assertThat(payouts(f.taskId())).containsExactly(new BigDecimal("8.50"), new BigDecimal("8.50"));
    }

    @Test
    void aResponseAfterTheWindowClosedIsRefused() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "A_MISMATCH", "wrong");
        RecordedWorkOrder notice = awaitNotice(f, 1);
        clock.set(start.plus(Duration.ofHours(2)));

        assertThat(respond(f, notice, "CONCEDE", null, null).statusCode()).isEqualTo(409);
        assertThat(status(f.taskId())).isEqualTo("AWAITING_AGENT_RESPONSE");
    }

    @Test
    void theClientCannotRequestArbitrationBeforeTheAgentHasStoodBy() throws Exception {
        Fixture f = taskAwaitingReview();
        reject(f.taskId(), "A_MISMATCH", "wrong");

        assertThatThrownBy(() -> new RestTemplate().postForObject(
                platformUrl("/api/tasks/" + f.taskId() + "/arbitration"), null, String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(disputes(f.taskId())).isZero();
    }

    // --- helpers ---

    private record Fixture(UUID taskId, FakeAgent agent) {}

    private Fixture taskAwaitingReview() {
        FakeAgent agent = fakeAgent().replyOnceWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        String category = uniqueCategory();
        seedActiveAgent(category, agent.webhookUrl());
        shareSigningSecret(agent);
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(DEV_USER, "Summarise report",
                "Summarise the attached quarterly report", Money.of("10.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("PENDING_REVIEW"));
        return new Fixture(taskId, agent);
    }

    private void reject(UUID taskId, String category, String reason) {
        new RestTemplate().postForObject(platformUrl("/api/tasks/" + taskId + "/reject"),
                Map.of("reasonCategory", category, "reason", reason), String.class);
    }

    /** The {@code n}th rejection notice (the WorkOrder came first). */
    private RecordedWorkOrder awaitNotice(Fixture f, int n) {
        List<RecordedWorkOrder> received = f.agent().awaitWorkOrders(n + 1, Duration.ofSeconds(20));
        return received.get(n);
    }

    private HttpResponse<String> respond(Fixture f, RecordedWorkOrder notice, String action, String reason,
                                         String resultPayloadJson) throws Exception {
        String body = responseBody(f.taskId(), action, reason, resultPayloadJson);
        return post(notice.field("responseUrl"), notice.bearerToken(),
                AgentCallback.signature(f.agent().signingSecret(), clock.instant(), bytes(body)), body);
    }

    private String responseBody(UUID taskId, String action, String reason, String resultPayloadJson) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("taskId", taskId.toString());
        body.put("action", action);
        if (reason != null) {
            body.put("reason", reason);
        }
        if (resultPayloadJson != null) {
            body.put("resultPayloadJson", resultPayloadJson);
        }
        return objectMapper.writeValueAsString(body);
    }

    private HttpResponse<String> post(String url, String token, String signature, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header(AgentCallback.SIGNATURE_HEADER, signature);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        String body = new RestTemplate().getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        return objectMapper.readTree(body).get("data");
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private String resultPayload(UUID taskId) {
        return jdbc.queryForObject("SELECT result_payload FROM task_results WHERE task_id = ?", String.class, taskId);
    }

    private int disputes(UUID taskId) {
        return jdbc.queryForObject("SELECT count(*) FROM disputes WHERE task_id = ?", Integer.class, taskId);
    }

    private List<String> events(UUID taskId) {
        return jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?", String.class, taskId);
    }

    private List<BigDecimal> payouts(UUID taskId) {
        return jdbc.queryForList("SELECT amount FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'PAYOUT' ORDER BY amount", BigDecimal.class, taskId);
    }

    private Map<String, BigDecimal> clientWallet() {
        return jdbc.queryForObject("SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?",
                (rs, i) -> Map.of("available", rs.getBigDecimal(1), "escrow", rs.getBigDecimal(2)), DEV_USER);
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
        acceptAnyJsonSchema(versionId); // its own schema would not cover the task's (#91)
    }
}
