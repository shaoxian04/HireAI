package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationDeliverable;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.infrastructure.adjudication.StubArbitrationClient;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackRequest;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.delivery.FakeFileHost;
import com.hireai.support.delivery.TestTls;
import com.hireai.support.dns.TestHostResolver;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #97 (spec #87 D24): when a FILE result arrives the platform fetches the Agent's link once through
 * the SSRF-guarded client, checks it against the Contract, hashes it and freezes a copy. The
 * Client's view and download, and the arbitrator, all read that DeliverySnapshot: swapping or
 * deleting the Agent's link afterwards changes nothing. The Agent's files are served over real
 * HTTPS by a {@link FakeFileHost} on localhost, trusted through {@code hireai.delivery.trust-store}.
 */
@Import(DeliverySnapshotIntegrationTest.RecordingArbitration.class)
class DeliverySnapshotIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] OTHER_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9};
    private static final byte[] HTML = "<!doctype html><title>not an image</title>".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void trustTheFakeFileHost(DynamicPropertyRegistry registry) {
        registry.add("hireai.delivery.trust-store", () -> TestTls.trustStore().toString());
        registry.add("hireai.delivery.trust-store-password", () -> TestTls.PASSWORD);
        registry.add("hireai.delivery.max-bytes", () -> "1000");
    }

    /** The test profile's stub arbitrator, recording what each dispute handed it. */
    @TestConfiguration
    static class RecordingArbitration {
        static final List<ArbitrationDeliverable> SEEN = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        ArbitrationGateway recordingArbitrationGateway() {
            StubArbitrationClient stub = new StubArbitrationClient();
            return new ArbitrationGateway() {
                @Override
                public Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task, ArbitrationDeliverable d) {
                    SEEN.add(d);
                    return stub.requestRuling(dispute, task, d);
                }
            };
        }
    }

    @Autowired
    private DisputeAppService disputeAppService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TestHostResolver dns;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private FakeFileHost files;

    @BeforeEach
    void setUp() {
        files = FakeFileHost.start();
        RecordingArbitration.SEEN.clear();
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @AfterEach
    void tearDown() {
        files.close();
    }

    @Test
    void aSwappedOrDeletedLinkAfterDeliveryChangesNothingTheClientOrArbitratorSees() throws Exception {
        files.serve("/result.png", PNG, "image/png");
        String taskId = bookFileAgentDelivering(files.url("/result.png"), Duration.ofMillis(200));

        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(checks(taskId)).containsExactly("FORMAT_FILE_HTTPS_URL:true", "FILE_FETCHED:true");
        JsonNode deliverable = get("/api/tasks/" + taskId + "/result").path("deliverable");
        String sha256 = sha256Hex(PNG);
        assertThat(deliverable.path("sha256").asText()).isEqualTo(sha256);
        assertThat(deliverable.path("mediaType").asText()).isEqualTo("image/png");
        assertThat(deliverable.path("sizeBytes").asLong()).isEqualTo(PNG.length);

        // The Agent swaps the file behind its link, then deletes it.
        files.serve("/result.png", OTHER_PNG, "image/png");
        assertDownloadIs(taskId, PNG);
        files.remove("/result.png");
        assertDownloadIs(taskId, PNG);
        assertThat(get("/api/tasks/" + taskId + "/result").path("deliverable").path("sha256").asText()).isEqualTo(sha256);
        assertThat(files.requests()).containsExactly("/result.png"); // fetched once, at delivery

        // The arbitrator is handed the frozen bytes, not the link.
        TaskModel task = taskRepository.findById(UUID.fromString(taskId)).orElseThrow();
        disputeAppService.openDispute(task, DEV_USER, RejectReason.A_MISMATCH);
        assertThat(RecordingArbitration.SEEN).singleElement().satisfies(d -> {
            assertThat(d.sha256()).isEqualTo(sha256);
            assertThat(d.mediaType()).isEqualTo("image/png");
            assertThat(Base64.getDecoder().decode(d.contentBase64())).isEqualTo(PNG);
        });
        assertThat(files.requests()).hasSize(1);
    }

    @Test
    void theDownloadIsServedSafelyAndOnlyToTheTasksClient() throws Exception {
        files.serve("/result.png", PNG, "image/png");
        String taskId = bookFileAgentDelivering(files.url("/result.png"), Duration.ofMillis(200));
        awaitStatus(taskId, "PENDING_REVIEW");

        HttpResponse<byte[]> download = download(taskId);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment");
        assertThat(download.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(download.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("sandbox");

        // Another client's task: the test profile's fixed caller does not own it.
        jdbc.update("UPDATE tasks SET client_id = (SELECT id FROM users WHERE id <> ? LIMIT 1) WHERE id = ?::uuid",
                DEV_USER, taskId);
        assertThat(download(taskId).statusCode()).isEqualTo(404);
    }

    @Test
    void aWrongMediaTypeIsASpecViolationAndTheClientIsRefunded() throws Exception {
        files.serve("/result.png", HTML, "image/png"); // claims PNG, is HTML
        BigDecimal before = available();
        String taskId = bookFileAgentDelivering(files.url("/result.png"), Duration.ofSeconds(2));
        requireInContract(taskId, "{\"media_types\":[\"image/jpeg\",\"image/png\"]}");

        awaitStatus(taskId, "SPEC_VIOLATION");
        assertThat(checks(taskId)).containsExactly("FORMAT_FILE_HTTPS_URL:true", "FILE_FETCHED:true",
                "FILE_MEDIA_TYPE:false");
        assertThat(detail(taskId, "FILE_MEDIA_TYPE")).contains("text/html").contains("image/png");
        assertThat(available()).isEqualByComparingTo(before);
        // The snapshot is still kept: it is the evidence of what was delivered.
        assertThat(get("/api/tasks/" + taskId + "/result").path("deliverable").path("mediaType").asText())
                .isEqualTo("text/html");
    }

    @Test
    void anAcceptedMediaTypeAndSizePass() throws Exception {
        files.serve("/result.png", PNG, "application/octet-stream");
        String taskId = bookFileAgentDelivering(files.url("/result.png"), Duration.ofSeconds(2));
        requireInContract(taskId, "{\"media_types\":[\"image/png\"],\"max_bytes\":" + PNG.length + "}");

        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(checks(taskId)).containsExactly("FORMAT_FILE_HTTPS_URL:true", "FILE_FETCHED:true",
                "FILE_MEDIA_TYPE:true", "FILE_MAX_BYTES:true");
    }

    @Test
    void aFileOverTheContractsSizeLimitFailsAndNothingIsKept() throws Exception {
        files.serve("/result.png", PNG, "image/png");
        String taskId = bookFileAgentDelivering(files.url("/result.png"), Duration.ofSeconds(2));
        requireInContract(taskId, "{\"max_bytes\":8}");

        awaitStatus(taskId, "SPEC_VIOLATION");
        assertThat(checks(taskId)).containsExactly("FORMAT_FILE_HTTPS_URL:true", "FILE_MAX_BYTES:false");
        assertThat(get("/api/tasks/" + taskId + "/result").has("deliverable")).isFalse();
        assertThat(send("GET", "/api/tasks/" + taskId + "/deliverable").statusCode()).isEqualTo(404);
    }

    @Test
    void aFileOverThePlatformLimitFailsTheFetch() throws Exception {
        files.serve("/big.bin", new byte[1001], "application/octet-stream");
        String taskId = bookFileAgentDelivering(files.url("/big.bin"), Duration.ofMillis(200));

        awaitStatus(taskId, "SPEC_VIOLATION");
        assertThat(checks(taskId)).containsExactly("FORMAT_FILE_HTTPS_URL:true", "FILE_FETCHED:false");
    }

    @Test
    void aDeadLinkOrARedirectIsASpecViolationAndTheRedirectIsNotFollowed() throws Exception {
        String dead = bookFileAgentDelivering(files.url("/missing.png"), Duration.ofMillis(200));
        files.redirect("/moved.png", files.url("/elsewhere.png")).serve("/elsewhere.png", PNG, "image/png");
        String moved = bookFileAgentDelivering(files.url("/moved.png"), Duration.ofMillis(200));

        awaitStatus(dead, "SPEC_VIOLATION");
        awaitStatus(moved, "SPEC_VIOLATION");
        assertThat(detail(dead, "FILE_FETCHED")).contains("404");
        assertThat(detail(moved, "FILE_FETCHED")).contains("redirect");
        assertThat(files.requests()).doesNotContain("/elsewhere.png");
    }

    @Test
    void aLinkResolvingToAnInternalAddressIsNeverFetched() throws Exception {
        dns.answer("files.internal.example", "10.1.2.3");
        String taskId = bookFileAgentDelivering("https://files.internal.example/result.png", Duration.ofMillis(200));

        awaitStatus(taskId, "SPEC_VIOLATION");
        assertThat(detail(taskId, "FILE_FETCHED")).contains("public address").doesNotContain("10.1.2.3");
    }

    @Test
    void snapshotsCannotBeChangedOrDeleted() throws Exception {
        files.serve("/result.png", PNG, "image/png");
        String taskId = bookFileAgentDelivering(files.url("/result.png"), Duration.ofMillis(200));
        awaitStatus(taskId, "PENDING_REVIEW");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE delivery_snapshots SET sha256 = repeat('0', 64) WHERE task_id = ?::uuid", taskId))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM delivery_snapshots WHERE task_id = ?::uuid", taskId))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE delivery_snapshot_contents SET content = '\\x00'::bytea WHERE sha256 = ?", sha256Hex(PNG)))
                .hasMessageContaining("immutable");
    }

    // ---- helpers ----

    /** Books a seeded FILE Agent that calls back {@code delay} after the WorkOrder with {@code url}. */
    private String bookFileAgentDelivering(String url, Duration delay) throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.custom(order ->
                        CallbackRequest.post(order.callbackUrl())
                                .header("Authorization", "Bearer " + order.bearerToken())
                                .body("{\"taskId\":\"" + order.taskId() + "\",\"agentStatus\":\"COMPLETED\","
                                        + "\"resultPayloadJson\":\"{}\",\"resultUrl\":\"" + url + "\"}"))
                .afterReply(delay)));
        UUID agentId = seedListedAgent(uniqueCategory(), agent.webhookUrl());
        shareSigningSecret(agent);
        return post("/api/tasks/direct", "{\"title\":\"Logo\",\"description\":\"Draw the logo\",\"budget\":10.00,"
                + "\"agentId\":\"" + agentId + "\"}").path("id").asText();
    }

    /**
     * Sets file_basic parameters on a just-booked task's Contract, before its result arrives. No
     * submit path on this branch sets them (#91/#100 add an explicit Contract), so the test does what
     * that will do, bypassing the freeze trigger for this one statement. {@code paramsJson} must be in
     * the app's canonical form (set values sorted), or the app's next save of the row rewrites it and
     * the trigger refuses that.
     */
    private void requireInContract(String taskId, String paramsJson) {
        // Only once dispatch has saved the task as EXECUTING: a save that still holds the old row
        // would otherwise rewrite the Contract and trip the trigger. The callback follows 2s later.
        awaitStatus(taskId, "EXECUTING");
        jdbc.execute("DO $$ BEGIN PERFORM set_config('session_replication_role', 'replica', true); "
                + "UPDATE tasks SET contract = jsonb_build_object('template', 'file_basic', 'version', 1, "
                + "'params', '" + paramsJson + "'::jsonb) WHERE id = '" + taskId + "'; END $$");
    }

    private void awaitStatus(String taskId, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("status").asText()).isEqualTo(status));
    }

    private List<String> checks(String taskId) {
        return jdbc.queryForList("SELECT c ->> 'rule' || ':' || (c ->> 'passed') FROM validation_reports r, "
                + "jsonb_array_elements(r.checks) WITH ORDINALITY AS x(c, n) WHERE r.task_id = ?::uuid ORDER BY n",
                String.class, taskId);
    }

    private String detail(String taskId, String rule) {
        return jdbc.queryForObject("SELECT c ->> 'detail' FROM validation_reports r, jsonb_array_elements(r.checks) c "
                + "WHERE r.task_id = ?::uuid AND c ->> 'rule' = ?", String.class, taskId, rule);
    }

    private void assertDownloadIs(String taskId, byte[] expected) throws Exception {
        HttpResponse<byte[]> response = download(taskId);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expected);
    }

    private HttpResponse<byte[]> download(String taskId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl("/api/tasks/" + taskId + "/deliverable"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
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
                + "VALUES (?, ?, 'File Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, '{\"format\":\"FILE\"}'::jsonb, ?, ?, 60, ?)",
                versionId, agentId, new String[]{category}, webhookUrl, new BigDecimal("10.00"));
        return agentId;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(platformUrl(path))).method(method,
                HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
