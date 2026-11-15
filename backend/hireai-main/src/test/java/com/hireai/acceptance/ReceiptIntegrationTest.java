package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.biz.acceptance.receipt.ReceiptAppService;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.CallbackRequest;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.dispute.RulingSteps;
import com.hireai.support.harness.WholeAppIntegrationTest;
import com.hireai.support.rejection.RejectionSteps;
import com.hireai.utility.canonical.JsonCanonicalizer;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.hash.Sha256;
import com.hireai.utility.result.ResultCode;
import com.hireai.utility.signature.Ed25519;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Signed receipts (#108, spec S11/D40): every settled Item gets one receipt, canonicalised with RFC
 * 8785 and signed with the platform Ed25519 key. Verification here uses only what an outsider has:
 * the downloaded document and the published JWK Set. The signing key is generated for this test
 * class (never committed) and handed to the app as configuration, as a deploy would.
 */
class ReceiptIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair SIGNING_KEY = newKeyPair();
    private static final KeyPair RETIRED_KEY = newKeyPair();

    @DynamicPropertySource
    static void receiptKeys(DynamicPropertyRegistry registry) {
        registry.add("hireai.receipts.signing-key",
                () -> Base64.getEncoder().encodeToString(SIGNING_KEY.getPrivate().getEncoded()));
        registry.add("hireai.receipts.retired-public-keys",
                () -> "receipts-2025:" + Base64.getEncoder().encodeToString(RETIRED_KEY.getPublic().getEncoded()));
    }

    @Autowired ReceiptAppService receiptAppService;
    @Autowired ApplicationContext context;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void fundClient() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 100.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void anAcceptedTaskGetsOneReceiptThatVerifiesWithThePublishedKeyAndExposesTampering() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "10.00");
        UUID taskId = book(seeded.agentId(), "10.00", null);
        awaitStatus(taskId, "PENDING_REVIEW");
        post("/api/tasks/" + taskId + "/accept");

        JsonNode list = getData("/api/tasks/" + taskId + "/receipts");
        assertThat(list).hasSize(1);
        UUID receiptId = UUID.fromString(list.get(0).get("id").asText());

        ResponseEntity<String> download = http.getForEntity(platformUrl("/api/receipts/" + receiptId), String.class);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("hireai-receipt-" + taskId + ".json");
        JsonNode envelope = JSON.readTree(download.getBody());
        JsonNode receipt = envelope.get("receipt");

        // Verified with nothing but the document and the published key set.
        assertThat(verify(envelope)).isTrue();
        assertThat(envelope.get("signature").get("alg").asText()).isEqualTo("Ed25519");
        assertThat(receipt.get("keyId").asText()).isEqualTo(envelope.get("signature").get("keyId").asText())
                .isEqualTo(Ed25519.keyId(Ed25519.raw(SIGNING_KEY.getPublic())));
        // The served bytes are the canonical form itself.
        assertThat(download.getBody()).isEqualTo(JsonCanonicalizer.canonicalize(JSON.convertValue(envelope, Object.class)));

        // Tampering with any signed fact breaks the signature.
        ObjectNode forged = envelope.deepCopy();
        ((ObjectNode) forged.get("receipt").get("settlement")).put("paid", "10.00");
        assertThat(verify(forged)).isFalse();
        ObjectNode reKeyed = envelope.deepCopy();
        ((ObjectNode) reKeyed.get("receipt")).put("receiptId", UUID.randomUUID().toString());
        assertThat(verify(reKeyed)).isFalse();

        // What it covers.
        assertThat(receipt.get("format").asText()).isEqualTo("hireai.receipt.v1");
        assertThat(receipt.get("receiptId").asText()).isEqualTo(receiptId.toString());
        assertThat(receipt.get("task").get("id").asText()).isEqualTo(taskId.toString());
        assertThat(receipt.has("item")).isFalse();
        assertThat(receipt.get("parties").get("client").asText()).isEqualTo(DEV_USER.toString());
        assertThat(receipt.get("parties").get("builder").asText()).isEqualTo(seeded.ownerId().toString());
        assertThat(receipt.get("contract").get("template").asText()).isEqualTo("text_basic@1");
        assertThat(receipt.get("contract").get("sha256").asText()).isEqualTo(storedContractHash(taskId));
        assertThat(receipt.get("input").get("sha256").asText()).isEqualTo(Sha256.hex("Extract the fields"));
        assertThat(receipt.get("delivery").get("payloadSha256").asText()).isEqualTo(Sha256.hex("{\"summary\":\"done\"}"));
        assertThat(receipt.get("checks").get("verdict").asText()).isEqualTo("PASS");
        assertThat(receipt.get("checks").get("results")).isNotEmpty();
        assertThat(receipt.get("judgements")).hasSize(1);
        assertThat(receipt.get("judgements").get(0).get("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(receipt.has("ruling")).isFalse();
        JsonNode settlement = receipt.get("settlement");
        assertThat(settlement.get("outcome").asText()).isEqualTo("ACCEPT");
        assertThat(settlement.get("price").asText()).isEqualTo("10.00");
        assertThat(settlement.get("paid").asText()).isEqualTo("8.50");
        assertThat(settlement.get("commission").asText()).isEqualTo("1.50");
        assertThat(settlement.get("refunded").asText()).isEqualTo("0.00");
        assertThat(ids(settlement.get("ledgerEntries"))).isNotEmpty()
                .isEqualTo(ledgerIds(taskId, "settle-" + taskId));

        // Issued once: reading again returns the same signed bytes, and the row cannot be changed.
        assertThat(getData("/api/tasks/" + taskId + "/receipts")).hasSize(1);
        assertThat(http.getForObject(platformUrl("/api/receipts/" + receiptId), String.class))
                .isEqualTo(download.getBody());
        assertThatThrownBy(() -> jdbc.update("UPDATE receipts SET signature = 'x' WHERE id = ?", receiptId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM receipts WHERE id = ?", receiptId))
                .hasMessageContaining("append-only");
    }

    @Test
    void eachSettledItemOfAMultiItemTaskGetsItsOwnReceipt() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(itemCallback().afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "3.00");
        UUID taskId = book(seeded.agentId(), "3.00", List.of("invoice A", "invoice B #empty", "invoice C #fail"));
        awaitStatus(taskId, "PENDING_REVIEW");

        // Items 2 and 3 were refunded at once; item 1 waits for review.
        assertThat(getData("/api/tasks/" + taskId + "/receipts"))
                .extracting(r -> r.get("itemNo").asInt()).containsExactly(2, 3);
        post("/api/tasks/" + taskId + "/items/1/accept");

        JsonNode list = getData("/api/tasks/" + taskId + "/receipts");
        assertThat(list).extracting(r -> r.get("itemNo").asInt()).containsExactly(1, 2, 3);
        for (JsonNode entry : list) {
            JsonNode envelope = JSON.readTree(http.getForObject(
                    platformUrl("/api/receipts/" + entry.get("id").asText()), String.class));
            assertThat(verify(envelope)).isTrue();
        }

        JsonNode accepted = list.get(0).get("receipt");
        assertThat(accepted.get("item").get("no").asInt()).isEqualTo(1);
        assertThat(accepted.get("item").get("count").asInt()).isEqualTo(3);
        assertThat(accepted.get("input").get("sha256").asText()).isEqualTo(Sha256.hex("invoice A"));
        assertThat(accepted.get("delivery").get("payloadSha256").asText()).isEqualTo(Sha256.hex("done: invoice A"));
        assertThat(accepted.get("judgements").get(0).get("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("settlement").get("outcome").asText()).isEqualTo("ACCEPT");
        assertThat(accepted.get("settlement").get("price").asText()).isEqualTo("3.00");
        assertThat(accepted.get("settlement").get("paid").asText()).isEqualTo("2.55");
        assertThat(ids(accepted.get("settlement").get("ledgerEntries")))
                .isEqualTo(ledgerIds(taskId, "settle-" + taskId + "-item-1"));

        JsonNode specViolation = list.get(1).get("receipt");
        assertThat(specViolation.get("checks").get("verdict").asText()).isEqualTo("FAIL");
        assertThat(specViolation.get("judgements")).isEmpty();
        assertThat(specViolation.get("settlement").get("outcome").asText()).isEqualTo("REJECT");
        assertThat(specViolation.get("settlement").get("refunded").asText()).isEqualTo("3.00");

        JsonNode failed = list.get(2).get("receipt");
        assertThat(failed.get("delivery").get("agentStatus").asText()).isEqualTo("FAILED");
        assertThat(failed.has("checks")).isFalse();
        assertThat(ids(failed.get("settlement").get("ledgerEntries")))
                .isEqualTo(ledgerIds(taskId, "settle-" + taskId + "-item-3"));
    }

    @Test
    void aDisputedTasksReceiptCarriesTheRejectionRoundsAndTheRuling() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "10.00");
        UUID taskId = book(seeded.agentId(), "10.00", null);
        awaitStatus(taskId, "PENDING_REVIEW");

        RejectionSteps.rejectThenArbitrate(context, taskId, DEV_USER, RejectReason.B_FACTUAL, "some errors");
        UUID disputeId = jdbc.queryForObject("SELECT id FROM disputes WHERE task_id = ?", UUID.class, taskId);
        assertThat(getData("/api/tasks/" + taskId + "/receipts")).isEmpty(); // not settled yet
        RulingSteps.acceptAsEverySide(context, disputeId);

        JsonNode list = getData("/api/tasks/" + taskId + "/receipts");
        assertThat(list).hasSize(1);
        JsonNode receipt = list.get(0).get("receipt");
        assertThat(receipt.get("ruling").get("disputeId").asText()).isEqualTo(disputeId.toString());
        assertThat(receipt.get("ruling").get("category").asText()).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(receipt.get("ruling").get("rulings")).isNotEmpty();
        assertThat(receipt.get("judgements")).extracting(j -> j.get("by").asText() + ":"
                        + j.path("decision").asText(j.path("response").asText()))
                .containsExactly("CLIENT:REJECTED", "AGENT:STAND_BY", "CLIENT:ARBITRATE");
        assertThat(receipt.get("judgements").get(0).get("reasonCategory").asText()).isEqualTo("B_FACTUAL");
        assertThat(receipt.get("settlement").get("outcome").asText()).isEqualTo("SPLIT");
        assertThat(receipt.get("settlement").get("paid").asText()).isEqualTo("4.25");
        assertThat(receipt.get("settlement").get("refunded").asText()).isEqualTo("5.00");
        JsonNode envelope = JSON.readTree(http.getForObject(
                platformUrl("/api/receipts/" + list.get(0).get("id").asText()), String.class));
        assertThat(verify(envelope)).isTrue();
    }

    @Test
    void theSweeperIssuesReceiptsNobodyAskedFor() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.failed("cannot do it").afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "5.00");
        UUID taskId = book(seeded.agentId(), "5.00", null);
        awaitStatus(taskId, "FAILED");
        assertThat(receiptCount(taskId)).isZero();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            receiptAppService.issuePending(100);
            assertThat(receiptCount(taskId)).isEqualTo(1);
        });
        receiptAppService.issuePending(100);
        assertThat(receiptCount(taskId)).isEqualTo(1); // never twice

        JsonNode receipt = getData("/api/tasks/" + taskId + "/receipts").get(0).get("receipt");
        assertThat(receipt.get("settlement").get("outcome").asText()).isEqualTo("REJECT");
        assertThat(receipt.get("settlement").get("refunded").asText()).isEqualTo("5.00");
        assertThat(receipt.get("settlement").get("failureReason").asText()).isNotBlank();
    }

    @Test
    void anUnsettledTaskHasNoReceiptAndStrangersSeeNothing() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "4.00");
        UUID taskId = book(seeded.agentId(), "4.00", null);
        awaitStatus(taskId, "PENDING_REVIEW");

        assertThat(getData("/api/tasks/" + taskId + "/receipts")).isEmpty();
        // The Builder of the assigned Agent may read them; anyone else is told nothing exists.
        assertThat(receiptAppService.receiptsForTask(taskId, seeded.ownerId())).isEmpty();
        assertThatThrownBy(() -> receiptAppService.receiptsForTask(taskId, UUID.randomUUID()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.resultCode()).isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void theKeySetPublishesTheSigningKeyAndRetiredKeys() throws Exception {
        JsonNode keys = JSON.readTree(http.getForObject(platformUrl("/api/receipt-keys"), String.class)).get("keys");

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).get("kty").asText()).isEqualTo("OKP");
        assertThat(keys.get(0).get("crv").asText()).isEqualTo("Ed25519");
        assertThat(keys.get(0).get("status").asText()).isEqualTo("active");
        assertThat(Base64.getUrlDecoder().decode(keys.get(0).get("x").asText()))
                .isEqualTo(Ed25519.raw(SIGNING_KEY.getPublic()));
        assertThat(keys.get(1).get("kid").asText()).isEqualTo("receipts-2025");
        assertThat(keys.get(1).get("status").asText()).isEqualTo("retired");
        assertThat(Base64.getUrlDecoder().decode(keys.get(1).get("x").asText()))
                .isEqualTo(Ed25519.raw(RETIRED_KEY.getPublic()));
    }

    // --- verification, as an outsider does it (docs/receipts.md) ---

    private boolean verify(JsonNode envelope) throws Exception {
        JsonNode keys = JSON.readTree(http.getForObject(platformUrl("/api/receipt-keys"), String.class)).get("keys");
        String keyId = envelope.get("signature").get("keyId").asText();
        byte[] raw = null;
        for (JsonNode key : keys) {
            if (key.get("kid").asText().equals(keyId)) {
                raw = Base64.getUrlDecoder().decode(key.get("x").asText());
            }
        }
        if (raw == null) {
            return false;
        }
        byte[] signed = JsonCanonicalizer.canonicalize(JSON.convertValue(envelope.get("receipt"), Object.class))
                .getBytes(StandardCharsets.UTF_8);
        return Ed25519.verify(raw, signed, envelope.get("signature").get("value").asText());
    }

    // --- fixtures ---

    private record Seeded(UUID agentId, UUID ownerId) {
    }

    private Seeded seedTextAgent(FakeAgent agent, String price) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00)",
                UUID.randomUUID(), ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Receipt Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"TEXT\"}", new String[]{uniqueCategory()}, agent.webhookUrl(),
                new BigDecimal(price));
        shareSigningSecret(agent);
        return new Seeded(agentId, ownerId);
    }

    private UUID book(UUID agentId, String budget, List<String> items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Extract invoices");
        body.put("description", "Extract the fields");
        body.put("budget", budget);
        body.put("agentId", agentId.toString());
        if (items != null) {
            body.put("items", items);
        }
        String response = http.postForObject(platformUrl("/api/tasks/direct"), body, String.class);
        return UUID.fromString(JSON.readTree(response).get("data").get("id").asText());
    }

    /** Answers each Item: {@code #fail} reports failure, {@code #empty} delivers an empty text. */
    private static AgentCallback itemCallback() {
        return AgentCallback.custom(order -> CallbackRequest.post(order.callbackUrl())
                .header("Authorization", "Bearer " + order.bearerToken())
                .body(itemResults(order)));
    }

    private static String itemResults(RecordedWorkOrder order) {
        ObjectNode body = JSON.createObjectNode();
        body.put("taskId", order.taskId());
        body.put("agentStatus", "COMPLETED");
        ArrayNode results = body.putArray("items");
        for (JsonNode item : order.json().get("items")) {
            String input = item.get("input").asText();
            ObjectNode result = results.addObject();
            result.put("itemNo", item.get("itemNo").asInt());
            if (input.contains("#fail")) {
                result.put("agentStatus", "FAILED");
            } else {
                result.put("agentStatus", "COMPLETED");
                result.put("resultPayloadJson", input.contains("#empty") ? "" : "done: " + input);
            }
        }
        return body.toString();
    }

    private String storedContractHash(UUID taskId) throws Exception {
        String contract = jdbc.queryForObject("SELECT contract::text FROM tasks WHERE id = ?", String.class, taskId);
        return Sha256.hex(JsonCanonicalizer.canonicalize(JSON.readValue(contract, Object.class)));
    }

    private Set<String> ledgerIds(UUID taskId, String correlationId) {
        return new HashSet<>(jdbc.queryForList("SELECT id::text FROM ledger_entries WHERE related_task_id = ? "
                + "AND correlation_id = ?", String.class, taskId, correlationId));
    }

    private static Set<String> ids(JsonNode entries) {
        List<String> ids = new ArrayList<>();
        entries.forEach(e -> ids.add(e.get("id").asText()));
        return new HashSet<>(ids);
    }

    private int receiptCount(UUID taskId) {
        return jdbc.queryForObject("SELECT count(*) FROM receipts WHERE task_id = ?", Integer.class, taskId);
    }

    private JsonNode getData(String path) throws Exception {
        return JSON.readTree(http.getForObject(platformUrl(path), String.class)).get("data");
    }

    private JsonNode post(String path) throws Exception {
        return JSON.readTree(http.postForObject(platformUrl(path), null, String.class)).get("data");
    }

    private void awaitStatus(UUID taskId, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo(expected));
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); // test-only key, never stored
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
