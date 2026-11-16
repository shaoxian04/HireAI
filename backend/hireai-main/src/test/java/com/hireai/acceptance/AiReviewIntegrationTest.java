package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.acceptance.aireview.AiReviewAppService;
import com.hireai.application.port.aireview.AiReviewSubject;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackRequest;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.aireview.ScriptedAiReviewer;
import com.hireai.support.delivery.FakeFileHost;
import com.hireai.support.delivery.TestTls;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #107 (spec #87 S10, D5, D37, D38): AI Review end to end, with the harness's scripted reviewer. The
 * fee is priced and frozen at booking, offered only within 10% of the Item price and only when the
 * Offer accepts it. On the web the verdict is an advisory pre-review report and the Client still
 * decides; on API/MCP it settles the Task deterministically from its ruling category and is final.
 */
class AiReviewIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEXT_OFFER = "{\"terms\":[{\"template\":\"text_basic\",\"version\":1,\"params\":{}}]"
            + ",\"acceptsAiReview\":true}";
    private static final String TEXT_OFFER_WITHOUT_AI_REVIEW =
            "{\"terms\":[{\"template\":\"text_basic\",\"version\":1,\"params\":{}}]}";
    private static final String IMAGE_OFFER = "{\"terms\":[{\"template\":\"file_basic\",\"version\":1,\"params\":{}},"
            + "{\"template\":\"image_generation\",\"version\":1,\"params\":{\"width\":{\"bound\":1024},"
            + "\"height\":{\"bound\":1024},\"background_colour\":{\"any\":true}}}],\"acceptsAiReview\":true}";
    private static final String IMAGE_CONTRACT = "{\"template\":\"image_generation\",\"version\":1,"
            + "\"params\":{\"background_colour\":\"#ffffff\",\"height\":64,\"width\":64}}";

    @DynamicPropertySource
    static void trustTheFakeFileHost(DynamicPropertyRegistry registry) {
        registry.add("hireai.delivery.trust-store", () -> TestTls.trustStore().toString());
        registry.add("hireai.delivery.trust-store-password", () -> TestTls.PASSWORD);
    }

    @Autowired
    private AiReviewAppService aiReviewAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private FakeFileHost files;

    @BeforeEach
    void setUp() throws Exception {
        files = FakeFileHost.start();
        files.serve("/white.png", png(Color.WHITE), "image/png");
        files.serve("/red.png", png(Color.RED), "image/png");
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @AfterEach
    void tearDown() {
        files.close();
    }

    @Test
    void theQuoteSaysWhatItCostsAndWhetherItIsOffered() throws Exception {
        UUID agentId = seedTextAgent(TEXT_OFFER, "https://agent.example/run");
        JsonNode offered = post("/api/tasks/direct/ai-review-quote",
                "{\"agentId\":\"" + agentId + "\",\"budget\":10.00,\"criteria\":2}");
        assertThat(offered.path("fee").decimalValue()).isEqualByComparingTo("0.30");
        assertThat(offered.path("maxFee").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(offered.path("available").asBoolean()).isTrue();
        assertThat(offered.path("maxCriteria").asInt()).isEqualTo(5);

        // At 2.99 the gate is 0.29: a 0.30 review would cost more than 10% of the work.
        JsonNode tooDear = post("/api/tasks/direct/ai-review-quote",
                "{\"agentId\":\"" + agentId + "\",\"budget\":2.99,\"criteria\":2}");
        assertThat(tooDear.path("available").asBoolean()).isFalse();
        assertThat(tooDear.path("reason").asText()).contains("10%");

        UUID unflagged = seedTextAgent(TEXT_OFFER_WITHOUT_AI_REVIEW, "https://agent.example/run2");
        JsonNode notAccepted = post("/api/tasks/direct/ai-review-quote",
                "{\"agentId\":\"" + unflagged + "\",\"budget\":10.00,\"criteria\":1}");
        assertThat(notAccepted.path("available").asBoolean()).isFalse();
        assertThat(notAccepted.path("reason").asText()).contains("does not accept AI Review");
    }

    @Test
    void aBookingAiReviewIsNotOfferedOnIsRefusedAndFreezesNothing() throws Exception {
        BigDecimal before = available();
        UUID unflagged = seedTextAgent(TEXT_OFFER_WITHOUT_AI_REVIEW, "https://agent.example/run3");
        assertThat(postRaw("/api/tasks/direct", booking(unflagged, "10.00", "Polite tone")).statusCode())
                .isEqualTo(400);
        UUID flagged = seedTextAgent(TEXT_OFFER, "https://agent.example/run4");
        assertThat(postRaw("/api/tasks/direct", booking(flagged, "2.99", "Polite tone", "Mentions the refund"))
                .statusCode()).isEqualTo(400);
        assertThat(available()).isEqualByComparingTo(before);
    }

    @Test
    void onTheWebTheVerdictIsAnAdvisoryReportAndTheClientStillDecides() throws Exception {
        BigDecimal before = available();
        FakeAgent agent = completingAgent(null);
        UUID agentId = seedTextAgent(TEXT_OFFER, agent.webhookUrl());
        shareSigningSecret(agent);
        String title = "Web review " + UUID.randomUUID();

        String taskId = post("/api/tasks/direct", titledBooking(agentId, "10.00", title,
                "Polite tone", "Offers a refund #fail")).path("id").asText();
        // The price and the fee are frozen at booking, the fee under its own correlation.
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("10.30")));
        assertThat(ledger(taskId, "ESCROW_FREEZE", "ai-review-" + taskId)).isEqualByComparingTo("-0.30");
        assertThat(get("/api/tasks/" + taskId).path("contract").path("aiReview").path("fee").decimalValue())
                .isEqualByComparingTo("0.30");

        JsonNode review = awaitReview(taskId, "COMPLETED");
        assertThat(review.path("decisive").asBoolean()).isFalse();
        assertThat(review.path("rulingCategory").asText()).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(review.path("verdicts").findValuesAsText("criterion"))
                .containsExactly("Polite tone", "Offers a refund #fail");
        assertThat(review.path("verdicts").findValuesAsText("passed")).containsExactly("true", "false");
        assertThat(review.path("model").asText()).isEqualTo(ScriptedAiReviewer.MODEL);
        // The reviewer judged the delivered result, with the Client's own brief.
        AiReviewSubject subject = ScriptedAiReviewer.shared().subjectsTitled(title).get(0);
        assertThat(subject.text()).contains("done");
        assertThat(subject.isFile()).isFalse();
        assertThat(ledger(taskId, "AI_REVIEW_FEE", "ai-review-" + taskId)).isEqualByComparingTo("0.30");

        // Advisory only: the result waits for the Client, inside the usual review window.
        JsonNode task = get("/api/tasks/" + taskId);
        assertThat(task.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(task.path("reviewDeadline").isMissingNode() || task.path("reviewDeadline").isNull()).isFalse();
        JsonNode accepted = post("/api/tasks/" + taskId + "/accept", "{}");
        assertThat(accepted.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(reputationEvents(taskId)).containsExactly("TASK_ACCEPTED");
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("10.30")));
    }

    @Test
    void onTheApiEveryCriterionMetSettlesTheTaskAsAcceptedAndIsFinal() throws Exception {
        BigDecimal before = available();
        Booked booked = bookOnTheApi("Polite tone", "Mentions the order number");

        awaitStatus(booked.taskId, "RESOLVED");
        JsonNode task = get("/api/tasks/" + booked.taskId);
        assertThat(task.path("resolution").asText()).isEqualTo("ACCEPTED");
        JsonNode review = get("/api/tasks/" + booked.taskId + "/ai-review");
        assertThat(review.path("decisive").asBoolean()).isTrue();
        assertThat(review.path("rulingCategory").asText()).isEqualTo("FULFILLED");
        assertThat(reputationEvents(booked.taskId)).containsExactly("AI_REVIEW_PASSED");
        assertThat(builderAvailable(booked.agentId)).isEqualByComparingTo("8.50");
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("10.30")));
        // Final: there is no review left for anyone to accept or reject.
        assertThat(postRaw("/api/tasks/" + booked.taskId + "/reject",
                "{\"reasonCategory\":\"A_MISMATCH\",\"reason\":\"no\"}").statusCode()).isNotEqualTo(200);
    }

    @Test
    void onTheApiNoCriterionMetRefundsThePriceButNotTheFee() throws Exception {
        BigDecimal before = available();
        Booked booked = bookOnTheApi("Polite tone #fail", "Mentions the order number #fail");

        awaitStatus(booked.taskId, "RESOLVED");
        assertThat(get("/api/tasks/" + booked.taskId).path("resolution").asText()).isEqualTo("REJECTED");
        assertThat(get("/api/tasks/" + booked.taskId + "/ai-review").path("rulingCategory").asText())
                .isEqualTo("NOT_FULFILLED");
        assertThat(reputationEvents(booked.taskId)).containsExactly("AI_REVIEW_FAILED");
        assertThat(builderAvailable(booked.agentId)).isEqualByComparingTo("0.00");
        // The review ran, so its fee is the Client's cost; the price comes back in full.
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("0.30")));
    }

    @Test
    void onTheApiSomeCriteriaMetSplitsTheTask() throws Exception {
        BigDecimal before = available();
        Booked booked = bookOnTheApi("Polite tone", "Mentions the order number #fail");

        awaitStatus(booked.taskId, "RESOLVED");
        assertThat(get("/api/tasks/" + booked.taskId).path("resolution").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(reputationEvents(booked.taskId)).containsExactly("AI_REVIEW_PARTIAL");
        assertThat(builderAvailable(booked.agentId)).isEqualByComparingTo("4.25"); // 85% of half
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("5.30")));
    }

    @Test
    void aReviewerThatNeverAnswersRefundsTheFeeAndLeavesTheResultToTheClient() throws Exception {
        BigDecimal before = available();
        Booked booked = bookOnTheApi("Polite tone #error");

        // First attempt failed; while the decisive review is still deciding, nobody else may settle it.
        await().atMost(Duration.ofSeconds(30)).until(() ->
                get("/api/tasks/" + booked.taskId + "/ai-review").path("attempts").asInt() == 1);
        assertThat(get("/api/tasks/" + booked.taskId).path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(postRaw("/api/tasks/" + booked.taskId + "/accept", "{}").statusCode()).isNotEqualTo(200);

        aiReviewAppService.run(UUID.fromString(booked.taskId)); // the sweeper's retries
        aiReviewAppService.run(UUID.fromString(booked.taskId));

        JsonNode review = get("/api/tasks/" + booked.taskId + "/ai-review");
        assertThat(review.path("status").asText()).isEqualTo("FAILED");
        assertThat(review.path("attempts").asInt()).isEqualTo(3);
        assertThat(ledger(booked.taskId, "REFUND", "ai-review-" + booked.taskId)).isEqualByComparingTo("0.20");
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("10.00")));
        // As if no AI Review had been chosen: the result waits in the Client's review window.
        assertThat(jdbc.queryForObject("SELECT review_deadline IS NOT NULL FROM tasks WHERE id = ?::uuid",
                Boolean.class, booked.taskId)).isTrue();
        assertThat(post("/api/tasks/" + booked.taskId + "/accept", "{}").path("status").asText())
                .isEqualTo("RESOLVED");
    }

    @Test
    void anImageIsReviewedFromItsFrozenCopyAtTheMediaFee() throws Exception {
        BigDecimal before = available();
        FakeAgent agent = fileAgent("/white.png");
        UUID agentId = seedImageAgent(agent.webhookUrl());
        shareSigningSecret(agent);
        String title = "Image review " + UUID.randomUUID();

        String taskId = post("/api/tasks/direct", imageBooking(agentId, title, "A friendly logo", "Blue circle"))
                .path("id").asText();
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("10.60"))); // (0.10+0.20)x2

        awaitReview(taskId, "COMPLETED");
        AiReviewSubject subject = ScriptedAiReviewer.shared().subjectsTitled(title).get(0);
        assertThat(subject.isFile()).isTrue();
        assertThat(subject.mediaType()).isEqualTo("image/png");
        assertThat(subject.content()).isEqualTo(png(Color.WHITE));
        assertThat(subject.sha256()).hasSize(64);
    }

    @Test
    void aTaskThatEndsBeforeAnyResultPassedGetsItsFeeBack() throws Exception {
        BigDecimal before = available();
        FakeAgent agent = fileAgent("/red.png"); // fails the background check on every attempt
        UUID agentId = seedImageAgent(agent.webhookUrl());
        shareSigningSecret(agent);

        String taskId = post("/api/tasks/direct", imageBooking(agentId, "Red " + UUID.randomUUID(), "A logo"))
                .path("id").asText();

        awaitStatus(taskId, "SPEC_VIOLATION");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId + "/ai-review").path("status").asText()).isEqualTo("RELEASED"));
        assertThat(ledger(taskId, "REFUND", "ai-review-" + taskId)).isEqualByComparingTo("0.40");
        assertThat(available()).isEqualByComparingTo(before);
    }

    @Test
    void anotherClientCannotReadTheReview() throws Exception {
        FakeAgent agent = completingAgent(null);
        UUID agentId = seedTextAgent(TEXT_OFFER, agent.webhookUrl());
        shareSigningSecret(agent);
        String taskId = post("/api/tasks/direct", titledBooking(agentId, "10.00", "Mine " + UUID.randomUUID(), "Polite"))
                .path("id").asText();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?) ON CONFLICT DO NOTHING",
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"), "other@test.local");
        jdbc.update("UPDATE tasks SET client_id = ?::uuid WHERE id = ?::uuid",
                "00000000-0000-0000-0000-0000000000aa", taskId);
        assertThat(getRaw("/api/tasks/" + taskId + "/ai-review").statusCode()).isEqualTo(404);
    }

    // ---- helpers ----

    private record Booked(String taskId, UUID agentId) {
    }

    /** A booking through the programmatic channel: the callback attributes the Task to an API key first. */
    private Booked bookOnTheApi(String... criteria) throws Exception {
        FakeAgent agent = completingAgent(this::attributeToApiKey);
        UUID agentId = seedTextAgent(TEXT_OFFER, agent.webhookUrl());
        shareSigningSecret(agent);
        String taskId = post("/api/tasks/direct", titledBooking(agentId, "10.00", "API review " + UUID.randomUUID(),
                criteria)).path("id").asText();
        return new Booked(taskId, agentId);
    }

    private FakeAgent completingAgent(java.util.function.Consumer<String> beforeCallback) {
        return fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.custom(order -> {
            if (beforeCallback != null) {
                beforeCallback.accept(order.taskId());
            }
            return CallbackRequest.post(order.callbackUrl())
                    .header("Authorization", "Bearer " + order.bearerToken())
                    .body(AgentCallback.completedBody(order.taskId()));
        }).afterReply(Duration.ofMillis(100))));
    }

    private FakeAgent fileAgent(String path) {
        return fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.custom(order ->
                CallbackRequest.post(order.callbackUrl())
                        .header("Authorization", "Bearer " + order.bearerToken())
                        .body("{\"taskId\":\"" + order.taskId() + "\",\"agentStatus\":\"COMPLETED\","
                                + "\"resultPayloadJson\":\"{}\",\"resultUrl\":\"" + files.url(path) + "\"}"))
                .afterReply(Duration.ofMillis(100))));
    }

    /** What the programmatic submit does: the Task belongs to an API key (the gate's channel marker). */
    private void attributeToApiKey(String taskId) {
        UUID keyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                keyId, DEV_USER, "hash-" + keyId, "hk_live_" + keyId.toString().substring(0, 6));
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?::uuid, ?, 10.00)", taskId, keyId);
    }

    private static String booking(UUID agentId, String budget, String... criteria) {
        return titledBooking(agentId, budget, "Reply " + UUID.randomUUID(), criteria);
    }

    private static String titledBooking(UUID agentId, String budget, String title, String... criteria) {
        return "{\"title\":\"" + title + "\",\"description\":\"Reply to the customer\",\"budget\":" + budget
                + ",\"agentId\":\"" + agentId + "\",\"aiReview\":{\"criteria\":" + strings(criteria) + "}}";
    }

    private static String imageBooking(UUID agentId, String title, String... criteria) {
        return "{\"title\":\"" + title + "\",\"description\":\"A 64px logo on white\",\"budget\":10.00,"
                + "\"agentId\":\"" + agentId + "\",\"contract\":" + IMAGE_CONTRACT
                + ",\"aiReview\":{\"criteria\":" + strings(criteria) + "}}";
    }

    private static String strings(String... values) {
        return "[" + String.join(",", java.util.Arrays.stream(values).map(v -> "\"" + v + "\"").toList()) + "]";
    }

    private JsonNode awaitReview(String taskId, String status) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            HttpResponse<String> response = getRaw("/api/tasks/" + taskId + "/ai-review");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).path("data").path("status").asText()).isEqualTo(status);
        });
        try {
            return get("/api/tasks/" + taskId + "/ai-review");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitStatus(String taskId, String status) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("status").asText()).isEqualTo(status));
    }

    private BigDecimal ledger(String taskId, String type, String correlation) {
        return jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM ledger_entries WHERE related_task_id = ?::uuid "
                + "AND entry_type = ? AND correlation_id = ?", BigDecimal.class, taskId, type, correlation);
    }

    private List<String> reputationEvents(String taskId) {
        return jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?::uuid", String.class,
                taskId);
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?", BigDecimal.class, DEV_USER);
    }

    private BigDecimal builderAvailable(UUID agentId) {
        return jdbc.queryForObject("SELECT COALESCE((SELECT w.available_balance FROM wallets w JOIN agents a "
                + "ON a.owner_id = w.user_id WHERE a.id = ?), 0.00)", BigDecimal.class, agentId);
    }

    private UUID seedTextAgent(String offer, String webhookUrl) {
        return seedAgent("{\"format\":\"TEXT\"}", offer, webhookUrl);
    }

    private UUID seedImageAgent(String webhookUrl) {
        return seedAgent("{\"format\":\"FILE\"}", IMAGE_OFFER, webhookUrl);
    }

    private UUID seedAgent(String outputSpec, String offer, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Review Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price, offer) "
                        + "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?, ?::jsonb)",
                versionId, agentId, outputSpec, new String[]{uniqueCategory()}, webhookUrl, new BigDecimal("2.00"),
                offer);
        return agentId;
    }

    private static byte[] png(Color background) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, 64, 64);
        g.setColor(Color.BLUE);
        g.fillOval(16, 16, 32, 32);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = getRaw(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> getRaw(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = postRaw(path, json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> postRaw(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
