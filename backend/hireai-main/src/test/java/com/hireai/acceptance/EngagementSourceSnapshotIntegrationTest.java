package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackRequest;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Engagements and SourceSnapshots (#104, spec S4: D29-D32, D44, ADR 0010), driven over HTTP against the
 * whole app. A buyer opens an Engagement with an Agent it chose (recording its consent), uploads
 * documents into private storage, and books a multi-Item Task whose Items are those documents. Each Item
 * in the WorkOrder carries a link signed for it alone that expires at the execution deadline; every
 * download is logged; after the last window closes plus the retention period, the content goes and the
 * hash stays. Documents never travel on the marketplace (routed) path.
 */
@Import(TestClockConfig.class)
class EngagementSourceSnapshotIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] INVOICE_A = "%PDF-1.7\ninvoice A: total 120.00".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INVOICE_B = "%PDF-1.7\ninvoice B: total 75.50".getBytes(StandardCharsets.US_ASCII);

    @Autowired MutableTestClock clock;
    @Autowired SourceSnapshotAppService sourceSnapshotAppService;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-18T10:00:00Z")); // a Wednesday
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 100.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void documentsUploadedUnderAnEngagementReachTheAgentThroughLinksSignedPerItemAndEveryDownloadIsLogged()
            throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, UUID.randomUUID());
        JsonNode engagement = openEngagement(agentId, null, null);
        assertThat(engagement.get("status").asText()).isEqualTo("ACTIVE");
        UUID engagementId = id(engagement);

        JsonNode a = upload(engagementId, "invoice-a.pdf", INVOICE_A);
        JsonNode b = upload(engagementId, "invoice-b.pdf", INVOICE_B);
        assertThat(a.get("sha256").asText()).isEqualTo(sha256(INVOICE_A));
        assertThat(a.get("mediaType").asText()).isEqualTo("application/pdf");
        assertThat(a.get("sizeBytes").asLong()).isEqualTo(INVOICE_A.length);

        UUID taskId = book(agentId, engagementId, List.of(Map.of("sourceId", a.get("id").asText()),
                Map.of("sourceId", b.get("id").asText(), "input", "Invoice B, page 1")));

        RecordedWorkOrder order = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0);
        JsonNode items = order.json().get("items");
        assertThat(items.get(0).get("input").asText()).isEqualTo("invoice-a.pdf"); // no text: the file name
        assertThat(items.get(1).get("input").asText()).isEqualTo("Invoice B, page 1");
        JsonNode sourceA = items.get(0).get("source");
        JsonNode sourceB = items.get(1).get("source");
        assertThat(sourceA.get("sha256").asText()).isEqualTo(sha256(INVOICE_A));
        assertThat(sourceA.get("filename").asText()).isEqualTo("invoice-a.pdf");
        assertThat(sourceA.get("mediaType").asText()).isEqualTo("application/pdf");
        assertThat(sourceA.get("sizeBytes").asLong()).isEqualTo(INVOICE_A.length);
        Instant deadline = Instant.parse(order.json().get("executionDeadline").asText());
        assertThat(Instant.parse(sourceA.get("expiresAt").asText()))
                .isEqualTo(Instant.ofEpochSecond(deadline.getEpochSecond()));
        // The WorkOrder (links included) is HMAC-signed with the Agent's secret, as every WorkOrder is.
        assertThat(order.header("X-HireAI-Signature")).isNotBlank();

        // Each link serves its own Item's document, with no other credential.
        ResponseEntity<byte[]> served = http.getForEntity(sourceA.get("url").asText(), byte[].class);
        assertThat(served.getBody()).isEqualTo(INVOICE_A);
        assertThat(served.getHeaders().getFirst("X-HireAI-Sha256")).isEqualTo(sha256(INVOICE_A));
        assertThat(served.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(http.getForEntity(sourceB.get("url").asText(), byte[].class).getBody()).isEqualTo(INVOICE_B);

        // A tampered token (another Item's id under this MAC) is refused, and not attributed to anyone.
        String token = sourceA.get("url").asText().substring(sourceA.get("url").asText().lastIndexOf('/') + 1);
        String forged = sourceB.get("url").asText().substring(sourceB.get("url").asText().lastIndexOf('/') + 1)
                .split("\\.")[0] + "." + token.split("\\.")[1];
        assertStatus(() -> http.getForEntity(platformUrl("/api/source-links/" + forged), String.class), 401);
        assertStatus(() -> http.getForEntity(platformUrl("/api/source-links/not-a-token"), String.class), 401);

        // After the execution deadline the link is refused, and the attempt is logged too.
        clock.set(deadline.plusSeconds(1));
        assertStatus(() -> http.getForEntity(sourceA.get("url").asText(), byte[].class), 401);

        JsonNode sources = get("/api/engagements/" + engagementId + "/sources");
        JsonNode loggedA = find(sources, a.get("id").asText()).get("downloads");
        assertThat(loggedA).extracting(d -> d.get("outcome").asText()).containsExactly("SERVED", "EXPIRED");
        assertThat(loggedA.get(0).get("taskId").asText()).isEqualTo(taskId.toString());
        assertThat(loggedA.get(0).get("itemNo").asInt()).isEqualTo(1);
        assertThat(find(sources, b.get("id").asText()).get("downloads")).hasSize(1);

        JsonNode task = get("/api/tasks/" + taskId);
        assertThat(task.get("items").get(0).get("sourceId").asText()).isEqualTo(a.get("id").asText());
    }

    @Test
    void documentsTravelOnlyUnderTheBuyersOwnActiveEngagementWithThatAgent() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, UUID.randomUUID());
        UUID otherAgentId = seedTextAgent(itemAgent(), UUID.randomUUID());
        UUID engagementId = id(openEngagement(agentId, null, null));
        String doc = upload(engagementId, "a.pdf", INVOICE_A).get("id").asText();
        BigDecimal escrowBefore = escrow();

        // Without the Engagement: refused, as on the marketplace (routed) path.
        assertStatus(() -> book(agentId, null, List.of(Map.of("sourceId", doc), "text")), 400);
        Map<String, Object> routed = new HashMap<>(Map.of("title", "T", "description", "D", "budget", "2.00",
                "category", uniqueCategory(), "outputSpec", Map.of("format", "TEXT"),
                "items", List.of(Map.of("sourceId", doc), Map.of("sourceId", doc))));
        assertStatus(() -> http.postForObject(platformUrl("/api/tasks"), routed, String.class), 400);
        // Under the Engagement, but booking another Agent.
        assertStatus(() -> book(otherAgentId, engagementId, List.of(Map.of("sourceId", doc), "text")), 400);
        // A document from another Engagement of the same buyer.
        UUID secondEngagement = id(openEngagement(otherAgentId, null, null));
        String foreignDoc = upload(secondEngagement, "b.pdf", INVOICE_B).get("id").asText();
        assertStatus(() -> book(agentId, engagementId, List.of(Map.of("sourceId", foreignDoc), "text")), 400);
        // Someone else's Engagement is not found, and neither are its documents.
        UUID strangers = seedForeignEngagement(agentId);
        assertStatus(() -> book(agentId, strangers, List.of("a", "b")), 404);
        assertStatus(() -> get("/api/engagements/" + strangers), 404);
        assertStatus(() -> get("/api/engagements/" + strangers + "/sources"), 404);
        assertStatus(() -> upload(strangers, "x.pdf", INVOICE_A), 404);
        // An ended Engagement takes nothing new.
        post("/api/engagements/" + engagementId + "/end");
        assertStatus(() -> upload(engagementId, "c.pdf", INVOICE_A), 409);
        assertStatus(() -> book(agentId, engagementId, List.of(Map.of("sourceId", doc), "text")), 409);

        assertThat(escrow()).isEqualByComparingTo(escrowBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_items WHERE source_snapshot_id IN (?::uuid, ?::uuid)",
                Integer.class, doc, foreignDoc)).isZero();
        // Consent is the reason an Engagement exists.
        assertStatus(() -> http.postForObject(platformUrl("/api/engagements"),
                Map.of("agentId", agentId.toString(), "confidentialConsent", false), String.class), 400);
    }

    @Test
    void aTemplateVariantWaitsForTheAgentsBuilderAndOnceAcceptedIsTheBookingsContract() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, UUID.randomUUID());
        Map<String, Object> variant = Map.of("template", "text_basic", "version", 1, "params", Map.of("min_length", 3));
        // The Agent's own Offer does not cover the variant: a booking under it without the Engagement fails.
        Map<String, Object> plain = bookingBody(agentId, null, List.of("a", "b"));
        plain.put("contract", variant);
        assertStatus(() -> http.postForObject(platformUrl("/api/tasks/direct"), plain, String.class), 400);

        JsonNode engagement = openEngagement(agentId, variant, 7);
        assertThat(engagement.get("status").asText()).isEqualTo("AWAITING_AGENT_ACCEPTANCE");
        assertThat(engagement.get("retentionDays").asInt()).isEqualTo(7);
        UUID engagementId = id(engagement);
        assertStatus(() -> upload(engagementId, "a.pdf", INVOICE_A), 409);
        // Only the Builder owning the Agent may accept: the test user does not own it yet.
        assertStatus(() -> post("/api/engagements/" + engagementId + "/accept"), 404);

        jdbc.update("UPDATE agents SET owner_id = ? WHERE id = ?", DEV_USER, agentId);
        assertThat(get("/api/engagements/incoming")).extracting(e -> e.get("id").asText())
                .contains(engagementId.toString());
        JsonNode accepted = post("/api/engagements/" + engagementId + "/accept");
        assertThat(accepted.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(accepted.get("agentAcceptedAt")).isNotNull();

        String doc = upload(engagementId, "a.pdf", INVOICE_A).get("id").asText();
        UUID taskId = book(agentId, engagementId, List.of(Map.of("sourceId", doc), "second"));
        JsonNode contract = get("/api/tasks/" + taskId).get("contract");
        assertThat(contract.get("template").asText()).isEqualTo("text_basic");
        assertThat(contract.get("params").get("min_length").asInt()).isEqualTo(3);
        awaitStatus(taskId, "PENDING_REVIEW");
    }

    @Test
    void afterTheLastWindowClosesAndTheRetentionPeriodPassesTheContentGoesAndTheHashStays() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, UUID.randomUUID());
        UUID engagementId = id(openEngagement(agentId, null, 2));
        // Bytes of its own: the content store is shared by hash with the other tests' documents.
        byte[] scan = ("%PDF-1.7\nretention " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        JsonNode doc = upload(engagementId, "a.pdf", scan);
        UUID docId = UUID.fromString(doc.get("id").asText());
        UUID taskId = book(agentId, engagementId, List.of(Map.of("sourceId", docId.toString()), "text"));
        awaitStatus(taskId, "PENDING_REVIEW");

        // A window is still open (the review): the clock does not start.
        clock.set(clock.instant().plus(Duration.ofDays(5)));
        assertThat(sourceSnapshotAppService.applyRetention(docId)).isFalse();
        assertThat(retentionStartedAt(docId)).isNull();

        post("/api/tasks/" + taskId + "/accept");
        Instant closed = clock.instant();
        assertThat(sourceSnapshotAppService.applyRetention(docId)).isFalse();
        assertThat(retentionStartedAt(docId)).isEqualTo(closed);

        clock.set(closed.plus(Duration.ofDays(2)).minusSeconds(1));
        assertThat(sourceSnapshotAppService.applyRetention(docId)).isFalse();
        assertThat(sourceSnapshotAppService.retentionCandidateIds()).contains(docId);

        clock.set(closed.plus(Duration.ofDays(2)));
        assertThat(sourceSnapshotAppService.applyRetention(docId)).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_snapshot_contents WHERE sha256 = ?",
                Integer.class, sha256(scan))).isZero();
        JsonNode retired = find(get("/api/engagements/" + engagementId + "/sources"), docId.toString());
        assertThat(retired.get("sha256").asText()).isEqualTo(sha256(scan));
        assertThat(retired.get("contentDeletedAt")).isNotNull();
        assertThat(sourceSnapshotAppService.retentionCandidateIds()).doesNotContain(docId);
        // A retired document cannot be booked again.
        assertStatus(() -> book(agentId, engagementId, List.of(Map.of("sourceId", docId.toString()), "text")), 409);
    }

    @Test
    void retiringADocumentKeepsBytesAnotherDocumentStillHolds() throws Exception {
        UUID agentId = seedTextAgent(itemAgent(), UUID.randomUUID());
        UUID first = id(openEngagement(agentId, null, 1));
        UUID otherAgent = seedTextAgent(itemAgent(), UUID.randomUUID());
        UUID second = id(openEngagement(otherAgent, null, 30));
        byte[] shared = ("%PDF-1.7\nthe same scan, uploaded twice " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        UUID early = UUID.fromString(upload(first, "a.pdf", shared).get("id").asText());
        upload(second, "a-copy.pdf", shared);

        sourceSnapshotAppService.applyRetention(early); // never booked: the clock starts now
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        assertThat(sourceSnapshotAppService.applyRetention(early)).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_snapshot_contents WHERE sha256 = ?",
                Integer.class, sha256(shared))).isEqualTo(1);
    }

    // --- helpers ---

    private FakeAgent itemAgent() {
        return fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.custom(order -> CallbackRequest.post(order.callbackUrl())
                                .header("Authorization", "Bearer " + order.bearerToken())
                                .body(itemResults(order)))
                        .signedAt(() -> clock.instant())
                        .afterReply(Duration.ofMillis(300))));
    }

    private static String itemResults(RecordedWorkOrder order) {
        ObjectNode body = JSON.createObjectNode();
        body.put("taskId", order.taskId());
        body.put("agentStatus", "COMPLETED");
        ArrayNode results = body.putArray("items");
        for (JsonNode item : order.json().get("items")) {
            ObjectNode result = results.addObject();
            result.put("itemNo", item.get("itemNo").asInt());
            result.put("agentStatus", "COMPLETED");
            result.put("resultPayloadJson", "extracted: " + item.get("input").asText());
        }
        return body.toString();
    }

    private UUID seedTextAgent(FakeAgent agent, UUID ownerId) {
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00)",
                UUID.randomUUID(), ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Invoice Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, 2.00)",
                versionId, agentId, "{\"format\":\"TEXT\"}", new String[]{uniqueCategory()}, agent.webhookUrl());
        shareSigningSecret(agent);
        return agentId;
    }

    /** An Engagement of another buyer with {@code agentId}. */
    private UUID seedForeignEngagement(UUID agentId) {
        UUID buyer = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", buyer, buyer + "@test.local");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO engagements (id, client_id, agent_id, status, consented_at, retention_days, "
                + "created_at) VALUES (?, ?, ?, 'ACTIVE', now(), 30, now())", id, buyer, agentId);
        return id;
    }

    private JsonNode openEngagement(UUID agentId, Map<String, Object> variant, Integer retentionDays) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId.toString());
        body.put("confidentialConsent", true);
        if (variant != null) {
            body.put("templateVariant", variant);
        }
        if (retentionDays != null) {
            body.put("retentionDays", retentionDays);
        }
        return JSON.readTree(http.postForObject(platformUrl("/api/engagements"), body, String.class)).get("data");
    }

    private JsonNode upload(UUID engagementId, String filename, byte[] content) throws Exception {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        String response = http.postForObject(platformUrl("/api/engagements/" + engagementId + "/sources"),
                new HttpEntity<>(form, headers), String.class);
        return JSON.readTree(response).get("data");
    }

    private Map<String, Object> bookingBody(UUID agentId, UUID engagementId, List<?> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Extract invoices");
        body.put("description", "Extract the fields of each invoice");
        body.put("budget", "2.00");
        body.put("agentId", agentId.toString());
        body.put("items", items);
        if (engagementId != null) {
            body.put("engagementId", engagementId.toString());
        }
        return body;
    }

    private UUID book(UUID agentId, UUID engagementId, List<?> items) throws Exception {
        String response = http.postForObject(platformUrl("/api/tasks/direct"),
                bookingBody(agentId, engagementId, items), String.class);
        return UUID.fromString(JSON.readTree(response).get("data").get("id").asText());
    }

    private JsonNode get(String path) throws Exception {
        return JSON.readTree(http.getForObject(platformUrl(path), String.class)).get("data");
    }

    private JsonNode post(String path) throws Exception {
        return JSON.readTree(http.exchange(platformUrl(path), HttpMethod.POST, HttpEntity.EMPTY, String.class)
                .getBody()).get("data");
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode n : list) {
            if (n.get("id").asText().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("No " + id + " in " + list);
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    private Instant retentionStartedAt(UUID docId) {
        java.sql.Timestamp t = jdbc.queryForObject("SELECT retention_started_at FROM source_snapshots WHERE id = ?",
                java.sql.Timestamp.class, docId);
        return t == null ? null : t.toInstant();
    }

    private void awaitStatus(UUID taskId, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo(expected));
    }

    private BigDecimal escrow() {
        return jdbc.queryForObject("SELECT escrow_balance FROM wallets WHERE user_id = ?", BigDecimal.class, DEV_USER);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private interface Call {
        Object run() throws Exception;
    }

    private static void assertStatus(Call call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(HttpClientErrorException.class,
                e -> assertThat(e.getStatusCode().value()).as(e.getResponseBodyAsString()).isEqualTo(status));
    }
}
