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
import com.hireai.utility.signature.DidKey;
import com.hireai.utility.signature.Ed25519;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
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
        registry.add("hireai.receipts.party-key", () -> UUID.randomUUID() + "-" + UUID.randomUUID()); // test-only
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
        // Parties are per-task pseudonyms: the document a client hands an auditor names no user.
        assertThat(receipt.get("parties").get("clientRef").asText()).matches("[0-9a-f]{64}");
        assertThat(receipt.get("parties").get("builderRef").asText()).matches("[0-9a-f]{64}")
                .isNotEqualTo(receipt.get("parties").get("clientRef").asText());
        assertThat(receipt.get("parties").get("agentVersionRef").asText()).matches("[0-9a-f]{64}");
        String versionId = jdbc.queryForObject("SELECT agent_version_id::text FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(download.getBody()).doesNotContain(DEV_USER.toString())
                .doesNotContain(seeded.ownerId().toString()).doesNotContain(versionId);
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

        // Items 2 and 3 were refunded at once, but the Task is sampled (#105): no Item receipt is issued while
        // its inspection is open, since a receipt states the sample. Item 1 waits for review.
        assertThat(getData("/api/tasks/" + taskId + "/receipts")).isEmpty();
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
        // Accepting one Item is the reviewer's pass on it (#105): the reviewer's judgement, then the acceptance.
        assertThat(accepted.get("judgements").get(0).get("by").asText()).isEqualTo("REVIEWER");
        assertThat(accepted.get("judgements").get(0).get("decision").asText()).isEqualTo("PASSED");
        JsonNode judgements = accepted.get("judgements");
        assertThat(judgements.get(judgements.size() - 1).get("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("sample").get("sampled").asBoolean()).isTrue();
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

        // Its exports carry the ruling: the split is half the price kept by the Builder's side.
        JsonNode credential = JSON.readTree(http.getForObject(
                platformUrl("/api/receipts/" + list.get(0).get("id").asText() + "/credential"), String.class));
        assertThat(verifyCredential(credential)).isTrue();
        assertThat(schemaErrors("receipt-credential.v1.schema.json", credential)).isEmpty();
        JsonNode otel = otel(list.get(0).get("id").asText());
        assertThat(schemaErrors("receipt-otel.v1.schema.json", otel)).isEmpty();
        JsonNode acceptance = events(otel).get(events(otel).size() - 1);
        assertThat(attr(acceptance, "gen_ai.evaluation.score.label").asText()).isEqualTo("split");
        assertThat(attr(acceptance, "gen_ai.evaluation.score.value").asDouble()).isEqualTo(0.5);
        assertThat(attr(acceptance, "hireai.ruling.category").asText()).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(attr(acceptance, "gen_ai.evaluation.explanation").asText()).contains("ruling PARTIALLY_FULFILLED");
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
        assertThat(receiptAppService.receiptsForTask(taskId, ReceiptAppService.Reader.signedIn(seeded.ownerId(), true))).isEmpty();
        assertThatThrownBy(() -> receiptAppService.receiptsForTask(taskId, ReceiptAppService.Reader.apiKey(seeded.ownerId())))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.resultCode()).isEqualTo(ResultCode.NOT_FOUND));
        assertThatThrownBy(() -> receiptAppService.receiptsForTask(taskId,
                ReceiptAppService.Reader.signedIn(UUID.randomUUID(), true)))
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

    // --- exports (#109): W3C VC 2.0 and OTel gen_ai.evaluation.result, against the published schemas ---

    @Test
    void aReceiptExportsAsAVerifiableCredentialWhoseProofVerifiesWithPublicMaterialOnly() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "10.00");
        UUID taskId = book(seeded.agentId(), "10.00", null);
        awaitStatus(taskId, "PENDING_REVIEW");
        post("/api/tasks/" + taskId + "/accept");
        UUID receiptId = UUID.fromString(getData("/api/tasks/" + taskId + "/receipts").get(0).get("id").asText());
        String stored = http.getForObject(platformUrl("/api/receipts/" + receiptId), String.class);

        ResponseEntity<String> response = http.getForEntity(
                platformUrl("/api/receipts/" + receiptId + "/credential"), String.class);
        assertThat(response.getHeaders().getContentType()).hasToString("application/vc");
        JsonNode credential = JSON.readTree(response.getBody());

        // Valid against the published schemas: the credential, and the receipt it carries.
        assertThat(schemaErrors("receipt-credential.v1.schema.json", credential)).isEmpty();
        ObjectNode carried = JSON.createObjectNode();
        carried.set("receipt", credential.get("credentialSubject").get("receipt"));
        carried.set("signature", credential.get("credentialSubject").get("receiptSignature"));
        assertThat(schemaErrors("receipt.v1.schema.json", carried)).isEmpty();
        assertThat(schemaErrors("receipt.v1.schema.json", JSON.readTree(stored))).isEmpty();
        // The schemas do bite: a wrong cryptosuite, a foreign key or a mangled amount is refused.
        ObjectNode otherSuite = credential.deepCopy();
        ((ObjectNode) otherSuite.get("proof")).put("cryptosuite", "ecdsa-jcs-2019");
        assertThat(schemaErrors("receipt-credential.v1.schema.json", otherSuite)).isNotEmpty();
        ObjectNode otherKey = credential.deepCopy();
        ((ObjectNode) otherKey.get("proof")).put("verificationMethod", "did:key:z6MkA#z6MkB");
        assertThat(schemaErrors("receipt-credential.v1.schema.json", otherKey)).isNotEmpty();
        ObjectNode badAmount = carried.deepCopy();
        ((ObjectNode) badAmount.get("receipt").get("settlement")).put("paid", "8.5");
        assertThat(schemaErrors("receipt.v1.schema.json", badAmount)).isNotEmpty();
        assertThat(schemaErrors("contract.v1.schema.json", JSON.readTree(jdbc.queryForObject(
                "SELECT contract::text FROM tasks WHERE id = ?", String.class, taskId)))).isEmpty();

        // It derives from the stored receipt: the same signed bytes, still verifiable on their own.
        assertThat(JsonCanonicalizer.canonicalize(JSON.convertValue(carried, Object.class))).isEqualTo(stored);
        assertThat(verify(carried)).isTrue();
        assertThat(credential.get("id").asText()).isEqualTo("urn:uuid:" + receiptId);
        assertThat(credential.get("validFrom").asText())
                .isEqualTo(credential.get("credentialSubject").get("receipt").get("issuedAt").asText());

        // The Data Integrity proof verifies with nothing but the document; its key is a published one.
        assertThat(verifyCredential(credential)).isTrue();
        byte[] proofKey = DidKey.publicKey(credential.get("proof").get("verificationMethod").asText());
        assertThat(publishedKeys()).anySatisfy(k -> assertThat(k).isEqualTo(proofKey));
        assertThat(DidKey.of(proofKey)).isEqualTo(credential.get("issuer").asText());

        ObjectNode forged = credential.deepCopy();
        ((ObjectNode) forged.get("credentialSubject").get("receipt").get("settlement")).put("paid", "10.00");
        assertThat(verifyCredential(forged)).isFalse();
        ObjectNode reissued = credential.deepCopy();
        reissued.put("validFrom", "2020-01-01T00:00:00Z");
        assertThat(verifyCredential(reissued)).isFalse();
        ObjectNode backdated = credential.deepCopy();
        ((ObjectNode) backdated.get("proof")).put("created", "2020-01-01T00:00:00Z");
        assertThat(verifyCredential(backdated)).isFalse();

        // Like the receipt, it names no user or Agent version.
        String versionId = jdbc.queryForObject("SELECT agent_version_id::text FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(response.getBody()).doesNotContain(DEV_USER.toString())
                .doesNotContain(seeded.ownerId().toString()).doesNotContain(versionId);
    }

    @Test
    void aReceiptExportsAsOtelEvaluationEventsMatchingWhatItRecords() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(itemCallback().afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "3.00");
        UUID taskId = book(seeded.agentId(), "3.00", List.of("invoice A", "invoice B #empty", "invoice C #fail"));
        awaitStatus(taskId, "PENDING_REVIEW");
        post("/api/tasks/" + taskId + "/items/1/accept");
        JsonNode list = getData("/api/tasks/" + taskId + "/receipts");

        // Item 1: checks passed and the Client accepted.
        JsonNode accepted = otel(list.get(0).get("id").asText());
        assertThat(schemaErrors("receipt-otel.v1.schema.json", accepted)).isEmpty();
        ObjectNode renamed = accepted.deepCopy();
        ((ObjectNode) events(renamed).get(0)).put("eventName", "gen_ai.evaluation");
        assertThat(schemaErrors("receipt-otel.v1.schema.json", renamed)).isNotEmpty();
        List<JsonNode> events = events(accepted);
        assertThat(events).extracting(e -> attr(e, "hireai.evaluation.kind").asText())
                .startsWith("checks").endsWith("acceptance").contains("check");
        JsonNode checks = events.get(0);
        assertThat(attr(checks, "gen_ai.evaluation.name").asText()).isEqualTo("hireai.contract_checks");
        assertThat(attr(checks, "gen_ai.evaluation.score.label").asText()).isEqualTo("pass");
        assertThat(attr(checks, "gen_ai.evaluation.score.value").asDouble()).isEqualTo(1.0);
        JsonNode acceptance = events.get(events.size() - 1);
        assertThat(attr(acceptance, "gen_ai.evaluation.name").asText()).isEqualTo("hireai.acceptance");
        assertThat(attr(acceptance, "gen_ai.evaluation.score.label").asText()).isEqualTo("accept");
        assertThat(attr(acceptance, "gen_ai.evaluation.score.value").asDouble()).isEqualTo(1.0);
        assertThat(attr(acceptance, "hireai.item.no").asText()).isEqualTo("1");
        assertThat(attr(acceptance, "gen_ai.response.id").asText()).isEqualTo(taskId + "/items/1");
        // Each event points at the signed receipt it came from.
        String stored = http.getForObject(platformUrl("/api/receipts/" + list.get(0).get("id").asText()), String.class);
        assertThat(events).allSatisfy(e -> {
            assertThat(attr(e, "hireai.receipt.id").asText()).isEqualTo(list.get(0).get("id").asText());
            assertThat(attr(e, "hireai.receipt.sha256").asText()).isEqualTo(Sha256.hex(stored));
        });
        String settledAt = list.get(0).get("receipt").get("settlement").get("settledAt").asText();
        assertThat(acceptance.get("timeUnixNano").asText()).isEqualTo(nanos(settledAt));

        // Item 2: the empty text failed a check and was refunded.
        List<JsonNode> refused = events(otel(list.get(1).get("id").asText()));
        assertThat(attr(refused.get(0), "gen_ai.evaluation.score.label").asText()).isEqualTo("fail");
        assertThat(attr(refused.get(0), "gen_ai.evaluation.score.value").asDouble()).isLessThan(1.0);
        assertThat(attr(refused.get(refused.size() - 1), "gen_ai.evaluation.score.label").asText()).isEqualTo("reject");
        assertThat(attr(refused.get(refused.size() - 1), "gen_ai.evaluation.score.value").asDouble()).isEqualTo(0.0);

        // Item 3: nothing was delivered, so nothing was checked: only the acceptance outcome.
        JsonNode failed = otel(list.get(2).get("id").asText());
        assertThat(schemaErrors("receipt-otel.v1.schema.json", failed)).isEmpty();
        assertThat(events(failed)).extracting(e -> attr(e, "hireai.evaluation.kind").asText())
                .containsExactly("acceptance");
        assertThat(failed.toString()).doesNotContain(DEV_USER.toString()).doesNotContain(seeded.ownerId().toString());
    }

    @Test
    void exportsGoOnlyToTheReceiptsParties() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.failed("cannot do it").afterReply(Duration.ofMillis(200))));
        Seeded seeded = seedTextAgent(agent, "5.00");
        UUID taskId = book(seeded.agentId(), "5.00", null);
        awaitStatus(taskId, "FAILED");
        UUID receiptId = UUID.fromString(getData("/api/tasks/" + taskId + "/receipts").get(0).get("id").asText());

        ReceiptAppService.Reader builder = ReceiptAppService.Reader.signedIn(seeded.ownerId(), true);
        assertThat(verifyCredential(JSON.readTree(receiptAppService.credential(receiptId, builder)))).isTrue();
        assertThat(schemaErrors("receipt-otel.v1.schema.json",
                JSON.readTree(receiptAppService.otelEvents(receiptId, builder)))).isEmpty();
        ReceiptAppService.Reader stranger = ReceiptAppService.Reader.signedIn(UUID.randomUUID(), true);
        assertThatThrownBy(() -> receiptAppService.credential(receiptId, stranger))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.resultCode()).isEqualTo(ResultCode.NOT_FOUND));
        assertThatThrownBy(() -> receiptAppService.otelEvents(receiptId, ReceiptAppService.Reader.apiKey(seeded.ownerId())))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.resultCode()).isEqualTo(ResultCode.NOT_FOUND));
    }

    /**
     * eddsa-jcs-2022 verification (W3C vc-di-eddsa §3.3.2) with the JDK alone: the key comes from the
     * proof's did:key, the hashes from RFC 8785, proof configuration hash first.
     */
    private static boolean verifyCredential(JsonNode credential) throws Exception {
        ObjectNode unsecured = credential.deepCopy();
        ObjectNode proofOptions = (ObjectNode) unsecured.remove("proof");
        String proofValue = proofOptions.remove("proofValue").asText();
        if (!"DataIntegrityProof".equals(proofOptions.path("type").asText())
                || !"eddsa-jcs-2022".equals(proofOptions.path("cryptosuite").asText())
                || !proofValue.startsWith("z")
                || !proofOptions.path("@context").equals(unsecured.path("@context"))) {
            return false;
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] configHash = sha.digest(JsonCanonicalizer.canonicalize(JSON.convertValue(proofOptions, Object.class))
                .getBytes(StandardCharsets.UTF_8));
        byte[] documentHash = sha.digest(JsonCanonicalizer.canonicalize(JSON.convertValue(unsecured, Object.class))
                .getBytes(StandardCharsets.UTF_8));
        byte[] hashData = new byte[64];
        System.arraycopy(configHash, 0, hashData, 0, 32);
        System.arraycopy(documentHash, 0, hashData, 32, 32);
        byte[] signature = DidKey.decodeBase58btc(proofValue.substring(1));
        byte[] key = DidKey.publicKey(proofOptions.get("verificationMethod").asText());
        return Ed25519.verify(key, hashData, Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
    }

    private List<byte[]> publishedKeys() throws Exception {
        List<byte[]> raw = new ArrayList<>();
        JSON.readTree(http.getForObject(platformUrl("/api/receipt-keys"), String.class)).get("keys")
                .forEach(k -> raw.add(Base64.getUrlDecoder().decode(k.get("x").asText())));
        return raw;
    }

    private JsonNode otel(String receiptId) throws Exception {
        return JSON.readTree(http.getForObject(platformUrl("/api/receipts/" + receiptId + "/otel"), String.class));
    }

    private static List<JsonNode> events(JsonNode export) {
        List<JsonNode> events = new ArrayList<>();
        export.get("resourceLogs").get(0).get("scopeLogs").get(0).get("logRecords").forEach(events::add);
        return events;
    }

    /** The attribute's value, whichever OTLP value kind it has. */
    private static JsonNode attr(JsonNode event, String key) {
        for (JsonNode a : event.get("attributes")) {
            if (a.get("key").asText().equals(key)) {
                return a.get("value").elements().next();
            }
        }
        throw new AssertionError("No attribute " + key + " in " + event);
    }

    private static String nanos(String isoTime) {
        Instant t = Instant.parse(isoTime);
        return BigInteger.valueOf(t.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(t.getNano())).toString();
    }

    /** Validation errors of {@code document} against a schema published in docs/formats. */
    private static Set<ValidationMessage> schemaErrors(String schemaFile, JsonNode document) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("docs/formats"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("docs/formats above the working directory").isNotNull();
        try (InputStream in = Files.newInputStream(dir.resolve("docs/formats").resolve(schemaFile))) {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            return schema.validate(document);
        }
    }

    // --- verification, as an outsider does it (docs/details/receipts.md) ---

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
