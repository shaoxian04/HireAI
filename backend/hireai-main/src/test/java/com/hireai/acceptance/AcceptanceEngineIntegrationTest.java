package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackRequest;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.delivery.FakeFileHost;
import com.hireai.support.delivery.TestTls;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #98 (spec #87 S5, D7, D17): the acceptance engine on {@code image_generation@1}, end to end. The
 * Agent's images are real PNGs served over HTTPS by a {@link FakeFileHost}; the platform freezes
 * each one and judges its pixels. A rejected result goes back to the same Agent with the failing
 * checks, at most twice, then the Task ends SPEC_VIOLATION with a refund; a self-reported anomaly
 * sends the result to the Client's review instead; a duplicate brief is a Finding that never
 * changes what anyone is paid.
 */
class AcceptanceEngineIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** What every task here is held to: a 64 x 64 image on white, booked as an explicit Contract (#91). */
    private static final String IMAGE_CONTRACT = "{\"template\":\"image_generation\",\"version\":1,"
            + "\"params\":{\"background_colour\":\"#ffffff\",\"height\":64,\"width\":64}}";
    /** The seeded Agent's Offer: its own file_basic term plus image_generation up to 1024 px, any colour. */
    private static final String IMAGE_OFFER = "{\"terms\":[{\"template\":\"file_basic\",\"version\":1,\"params\":{}},"
            + "{\"template\":\"image_generation\",\"version\":1,\"params\":{\"width\":{\"bound\":1024},"
            + "\"height\":{\"bound\":1024},\"background_colour\":{\"any\":true}}}]}";

    @DynamicPropertySource
    static void trustTheFakeFileHost(DynamicPropertyRegistry registry) {
        registry.add("hireai.delivery.trust-store", () -> TestTls.trustStore().toString());
        registry.add("hireai.delivery.trust-store-password", () -> TestTls.PASSWORD);
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private FakeFileHost files;

    @BeforeEach
    void setUp() throws Exception {
        files = FakeFileHost.start();
        files.serve("/white.png", png(64, 64, Color.WHITE), "image/png");
        files.serve("/red.png", png(64, 64, Color.RED), "image/png");
        files.serve("/small.png", png(32, 32, Color.WHITE), "image/png");
        files.serve("/logo.webp", "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1), "image/webp");
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @AfterEach
    void tearDown() {
        files.close();
    }

    @Test
    void theImageGenerationTemplateIsPublishedWithItsCheckLibraryEntries() throws Exception {
        JsonNode template = get("/api/catalogue/templates/image_generation/1");

        List<String> checks = mapper.convertValue(template.path("checks"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(checks).containsExactlyElementsOf(ImageGenerationTemplate.CHECKS);
        assertThat(template.path("expectationMode").asText()).isEqualTo("BUSINESS_VALID");
        assertThat(template.path("parameters").findValuesAsText("name"))
                .containsExactly("formats", "width", "height", "background_colour", "colour_tolerance");
        // The checks an anomaly may soften: the seeded definition and the engine agree.
        List<String> eligible = jdbc.queryForList("SELECT jsonb_array_elements_text(definition -> 'anomalyEligible') "
                + "FROM contract_templates WHERE template_key = 'image_generation' AND version = 1", String.class);
        assertThat(eligible).containsExactlyInAnyOrderElementsOf(ImageGenerationTemplate.ANOMALY_ELIGIBLE);
    }

    @Test
    void aBookingWhoseImageTermsCannotBeCheckedIsRefusedAndFreezesNothing() throws Exception {
        UUID agentId = seedListedAgent(uniqueCategory(), "https://agent.example/run");
        String bad = "{\"title\":\"Logo\",\"description\":\"x\",\"budget\":10.00,\"agentId\":\"" + agentId
                + "\",\"contract\":{\"template\":\"image_generation\",\"version\":1,\"params\":{%s}}}";

        assertThat(postRaw("/api/tasks/direct", bad.formatted("\"background_colour\":\"white\"")).statusCode())
                .isEqualTo(400);
        assertThat(postRaw("/api/tasks/direct", bad.formatted("\"width\":64.5")).statusCode()).isEqualTo(400);
    }

    @Test
    void anImageMeetingTheContractPassesOnItsPixels() throws Exception {
        Booking booking = book(order -> "/white.png");

        awaitStatus(booking.taskId, "PENDING_REVIEW");
        JsonNode report = get("/api/tasks/" + booking.taskId + "/validation");
        assertThat(report.path("verdict").asText()).isEqualTo("PASS");
        assertThat(outcomes(report)).containsExactly("FORMAT_FILE_HTTPS_URL:PASS", "FILE_FETCHED:PASS",
                "IMAGE_FORMAT:PASS", "IMAGE_DIMENSIONS:PASS", "IMAGE_BACKGROUND_COLOUR:PASS");
        assertThat(report.path("rejectedAttempts").asInt()).isZero();
        assertThat(report.path("maxReworks").asInt()).isEqualTo(2);
        assertThat(booking.agent.workOrders()).hasSize(1);
        assertThat(booking.agent.workOrders().get(0).json().has("rework")).isFalse();
    }

    @Test
    void aWrongBackgroundIsReworkedByTheSameAgentWithTheFailingCheckThenPasses() throws Exception {
        BigDecimal before = available();
        Booking booking = book(order -> order.json().has("rework") ? "/white.png" : "/red.png");

        awaitStatus(booking.taskId, "PENDING_REVIEW");
        List<RecordedWorkOrder> orders = booking.agent.awaitWorkOrders(2, Duration.ofSeconds(30));
        JsonNode rework = orders.get(1).json().path("rework");
        assertThat(rework.path("reworkNo").asInt()).isEqualTo(1);
        assertThat(rework.path("maxReworks").asInt()).isEqualTo(2);
        assertThat(rework.path("failedChecks").findValuesAsText("rule")).containsExactly("IMAGE_BACKGROUND_COLOUR");
        assertThat(rework.path("failedChecks").get(0).path("detail").asText()).contains("#ff0000").contains("#ffffff");

        JsonNode report = get("/api/tasks/" + booking.taskId + "/validation");
        assertThat(report.path("verdict").asText()).isEqualTo("PASS");
        assertThat(report.path("attemptNo").asInt()).isEqualTo(2);
        assertThat(report.path("rejectedAttempts").asInt()).isEqualTo(1);
        assertThat(report.path("previousAttempts").get(0).path("verdict").asText()).isEqualTo("FAIL");
        assertThat(report.path("previousAttempts").get(0).path("failedChecks").findValuesAsText("rule"))
                .containsExactly("IMAGE_BACKGROUND_COLOUR");
        // The result the Client reviews is the reworked one; the rework moved no money and cost no reputation.
        assertThat(get("/api/tasks/" + booking.taskId + "/result").path("resultUrl").asText()).endsWith("/white.png");
        assertThat(reputationEvents(booking.taskId)).isZero();
        assertThat(available()).isEqualByComparingTo(before.subtract(new BigDecimal("10.00")));
    }

    @Test
    void checksStillFailingAfterEveryReworkAreASpecViolationRefundedAndNotAppealable() throws Exception {
        BigDecimal before = available();
        Booking booking = book(order -> "/red.png");

        awaitStatus(booking.taskId, "SPEC_VIOLATION");
        assertThat(booking.agent.workOrders()).hasSize(3); // the first, then 2 reworks
        assertThat(booking.agent.workOrders().get(2).json().path("rework").path("reworkNo").asInt()).isEqualTo(2);
        JsonNode report = get("/api/tasks/" + booking.taskId + "/validation");
        assertThat(report.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(report.path("rejectedAttempts").asInt()).isEqualTo(3);
        assertThat(report.path("previousAttempts")).hasSize(2);
        assertThat(available()).isEqualByComparingTo(before);
        assertThat(jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?::uuid",
                String.class, booking.taskId)).containsExactly("SPEC_VIOLATION");
        // A deterministic rejection is not appealable: there is nothing for the Client to reject or dispute.
        HttpResponse<String> reject = postRaw("/api/tasks/" + booking.taskId + "/reject",
                "{\"reasonCategory\":\"A_MISMATCH\",\"reason\":\"the background is red\"}");
        assertThat(reject.statusCode()).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM disputes WHERE task_id = ?::uuid", Integer.class,
                booking.taskId)).isZero();
    }

    @Test
    void aSelfReportedAnomalySendsTheResultToReviewAndIsPaidWhenTheClientConfirmsIt() throws Exception {
        Booking booking = book(order -> "/red.png",
                ",\"anomalies\":[{\"check\":\"IMAGE_BACKGROUND_COLOUR\",\"description\":"
                        + "\"A white logo on white would be invisible, so I used red.\"}]");

        awaitStatus(booking.taskId, "PENDING_REVIEW");
        JsonNode report = get("/api/tasks/" + booking.taskId + "/validation");
        assertThat(report.path("verdict").asText()).isEqualTo("REVIEW");
        assertThat(outcomes(report)).contains("IMAGE_BACKGROUND_COLOUR:REVIEW");
        assertThat(report.path("anomalies").get(0).path("description").asText()).contains("invisible");
        assertThat(booking.agent.workOrders()).hasSize(1); // reviewed, not reworked
        assertThat(reviewReason(booking.taskId)).isEqualTo("ANOMALY");

        JsonNode accepted = post("/api/tasks/" + booking.taskId + "/accept", "{}");
        assertThat(accepted.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?::uuid",
                String.class, booking.taskId)).containsExactly("TASK_ACCEPTED");
    }

    @Test
    void pixelsThePlatformCannotReadAreLeftToTheClientNotGuessed() throws Exception {
        Booking booking = book(order -> "/logo.webp");

        awaitStatus(booking.taskId, "PENDING_REVIEW");
        JsonNode report = get("/api/tasks/" + booking.taskId + "/validation");
        assertThat(report.path("verdict").asText()).isEqualTo("REVIEW");
        assertThat(outcomes(report)).contains("IMAGE_FORMAT:PASS", "IMAGE_DIMENSIONS:REVIEW",
                "IMAGE_BACKGROUND_COLOUR:REVIEW");
        assertThat(reviewReason(booking.taskId)).isEqualTo("UNDECIDED");
    }

    @Test
    void anAnomalyCannotExcuseAWrongSizeSoTheImageIsStillReworked() throws Exception {
        Booking booking = book(order -> order.json().has("rework") ? "/white.png" : "/small.png",
                ",\"anomalies\":[{\"check\":\"IMAGE_DIMENSIONS\",\"description\":\"32px is plenty.\"}]");

        awaitStatus(booking.taskId, "PENDING_REVIEW");
        assertThat(booking.agent.workOrders()).hasSize(2);
        assertThat(booking.agent.workOrders().get(1).json().path("rework").path("failedChecks")
                .findValuesAsText("rule")).containsExactly("IMAGE_DIMENSIONS");
    }

    @Test
    void aDuplicateBriefIsShownToTheClientAndNeverChangesTheOutcome() throws Exception {
        String brief = "The same logo, " + UUID.randomUUID();
        Booking first = book(order -> "/white.png", "", brief);
        awaitStatus(first.taskId, "PENDING_REVIEW");
        // The same brief again; the first image is the wrong size, the rework the right one.
        Booking second = book(order -> order.json().has("rework") ? "/white.png" : "/small.png", "", brief);

        awaitStatus(second.taskId, "PENDING_REVIEW");
        JsonNode firstReport = get("/api/tasks/" + first.taskId + "/validation");
        JsonNode report = get("/api/tasks/" + second.taskId + "/validation");
        assertThat(firstReport.path("findings")).isEmpty();
        // Reworked once for the size (the Agent answers a rework with the right image)...
        assertThat(report.path("rejectedAttempts").asInt()).isEqualTo(1);
        // ...and the Finding rides along with every attempt, outside the verdict.
        assertThat(report.path("verdict").asText()).isEqualTo("PASS");
        assertThat(report.path("findings")).singleElement().satisfies(f -> {
            assertThat(f.path("code").asText()).isEqualTo("DUPLICATE_BRIEF");
            assertThat(f.path("detail").asText()).contains(first.taskId);
        });
        assertThat(outcomes(report)).noneMatch(o -> o.startsWith("DUPLICATE_BRIEF"));

        JsonNode accepted = post("/api/tasks/" + second.taskId + "/accept", "{}");
        assertThat(accepted.path("payoutAmount").decimalValue()).isEqualByComparingTo("8.50"); // the full 85%
    }

    // ---- helpers ----

    private record Booking(String taskId, FakeAgent agent) {
    }

    private Booking book(Function<RecordedWorkOrder, String> imagePath) throws Exception {
        return book(imagePath, "", "A 64px logo on white, " + UUID.randomUUID());
    }

    private Booking book(Function<RecordedWorkOrder, String> imagePath, String extraFields) throws Exception {
        return book(imagePath, extraFields, "A 64px logo on white, " + UUID.randomUUID());
    }

    /** Books a seeded image Agent under {@link #IMAGE_CONTRACT}; {@code imagePath} picks the image per WorkOrder. */
    private Booking book(Function<RecordedWorkOrder, String> imagePath, String extraFields, String brief)
            throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.custom(order ->
                    CallbackRequest.post(order.callbackUrl())
                            .header("Authorization", "Bearer " + order.bearerToken())
                            .body("{\"taskId\":\"" + order.taskId() + "\",\"agentStatus\":\"COMPLETED\","
                                    + "\"resultPayloadJson\":\"{}\",\"resultUrl\":\"" + files.url(imagePath.apply(order))
                                    + "\"" + extraFields + "}"))
                .afterReply(Duration.ofMillis(100))));
        UUID agentId = seedListedAgent(uniqueCategory(), agent.webhookUrl());
        shareSigningSecret(agent);
        String taskId = post("/api/tasks/direct", "{\"title\":\"Logo\",\"description\":\"" + brief + "\","
                + "\"budget\":10.00,\"agentId\":\"" + agentId + "\",\"contract\":" + IMAGE_CONTRACT + "}")
                .path("id").asText();
        return new Booking(taskId, agent);
    }

    private void awaitStatus(String taskId, String status) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("status").asText()).isEqualTo(status));
    }

    private static List<String> outcomes(JsonNode report) {
        List<String> out = new java.util.ArrayList<>();
        report.path("checks").forEach(c -> out.add(c.path("rule").asText() + ":" + c.path("outcome").asText()));
        return out;
    }

    private String reviewReason(String taskId) {
        return jdbc.queryForObject("SELECT review_reason FROM tasks WHERE id = ?::uuid", String.class, taskId);
    }

    private int reputationEvents(String taskId) {
        return jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?::uuid", Integer.class, taskId);
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT available_balance FROM wallets WHERE user_id = ?", BigDecimal.class, DEV_USER);
    }

    private UUID seedListedAgent(String category, String webhookUrl) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Image Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price, offer) "
                        + "VALUES (?, ?, 1, '{\"format\":\"FILE\"}'::jsonb, ?, ?, 60, ?, ?::jsonb)",
                versionId, agentId, new String[]{category}, webhookUrl, new BigDecimal("10.00"), IMAGE_OFFER);
        return agentId;
    }

    private static byte[] png(int width, int height, Color background) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2); // the logo, off the edge
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
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
