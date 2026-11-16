package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.biz.acceptance.receipt.ReceiptAppService;
import com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate;
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
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@code ap_invoice_extraction@1} end to end (#106, spec S8, D7, D45): a buyer uploads invoice PDFs under an
 * Engagement, books them as the Items of one Task under the invoice Contract, the fake Agent delivers each
 * invoice's fields, and the checks, the Findings, sampling, the reviewer and the per-Item settlement follow,
 * down to the receipts. The fake Agent stages each Item by its input: {@code ok}, {@code dup} (the same
 * supplier and invoice number as Item 1), {@code reported} / {@code silent} (a grand total that doesn't add
 * up, with or without an anomaly), {@code broken} (not JSON).
 */
@Import(TestClockConfig.class)
// Two of the batch's seven invoices are flagged: a cap of 30% (2) holds both out for the reviewer's own check.
@org.springframework.test.context.TestPropertySource(properties = "hireai.acceptance.flagged-share=0.30")
class InvoiceExtractionIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INVOICE_OFFER = "{\"terms\":[{\"template\":\"ap_invoice_extraction\",\"version\":1,"
            + "\"params\":{\"expectation\":{\"any\":true}}}]}";

    @Autowired MutableTestClock clock;
    @Autowired ReceiptAppService receiptAppService;
    @Autowired com.hireai.application.biz.task.TaskReviewAppService taskReviewAppService;

    private final RestTemplate http = new RestTemplate();
    private final HttpClient client = HttpClient.newHttpClient();
    /** Unique per test: duplicates are the same client's, and the client and content store outlive a test. */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-18T10:00:00Z")); // a Wednesday
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 200.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void theTemplateIsSeededWithItsChecksAndFaithfulToSourceByDefault() throws Exception {
        JsonNode template = get("/api/catalogue/templates/ap_invoice_extraction/1");

        List<String> checks = new ArrayList<>();
        template.get("checks").forEach(c -> checks.add(c.asText()));
        assertThat(checks).containsExactlyElementsOf(InvoiceExtractionTemplate.CHECKS);
        List<String> eligible = jdbc.queryForList("SELECT jsonb_array_elements_text(definition -> 'anomalyEligible') "
                + "FROM contract_templates WHERE template_key = 'ap_invoice_extraction' AND version = 1", String.class);
        assertThat(eligible).containsExactlyInAnyOrderElementsOf(InvoiceExtractionTemplate.ANOMALY_ELIGIBLE);
        assertThat(template.get("expectationMode").asText()).isEqualTo("FAITHFUL_TO_SOURCE");
        assertThat(template.get("outputShape").asText()).isEqualTo("JSON");
    }

    @Test
    void anInvoiceBatchRunsFromUploadThroughChecksFindingsSamplingAndTheReviewerToPerItemSettlement()
            throws Exception {
        FakeAgent agent = invoiceAgent();
        UUID agentId = seedInvoiceAgent(agent, "2.00");
        UUID engagementId = openEngagement(agentId);
        byte[] repeated = pdf("repeated");
        List<Map<String, Object>> items = List.of(
                item(engagementId, "a.pdf", pdf("a"), "ok"),
                item(engagementId, "b.pdf", pdf("b"), "ok"),
                item(engagementId, "c.pdf", repeated, "dup"),          // same supplier + number as Item 1
                item(engagementId, "c-again.pdf", repeated, "ok"),    // the same file as Item 3
                item(engagementId, "d.pdf", pdf("d"), "reported"),
                item(engagementId, "e.pdf", pdf("e"), "silent"),
                item(engagementId, "f.pdf", pdf("f"), "broken"));

        UUID taskId = book(agentId, engagementId, items, null);
        awaitStatus(taskId, "PENDING_REVIEW");

        JsonNode task = getTask(taskId);
        assertThat(task.get("contract").get("template").asText()).isEqualTo("ap_invoice_extraction");
        // The Agent got each document through its own signed link, and the invoice terms in the WorkOrder brief.
        RecordedWorkOrder order = workOrders(agent).get(0);
        assertThat(order.json().get("items")).hasSize(7);
        assertThat(order.json().get("items").get(0).get("source").get("sha256").asText()).hasSize(64);

        // A malformed result is refunded at once and never reaches the reviewer.
        JsonNode broken = item(task, 7);
        assertThat(broken.get("status").asText()).isEqualTo("SPEC_VIOLATION");
        assertThat(check(broken, "INVOICE_JSON").get("outcome").asText()).isEqualTo("REJECT");
        assertThat(broken.get("refundAmount").decimalValue()).isEqualByComparingTo("2.00");

        // Figures that don't add up go to the reviewer, reported or not (faithful to source, D7), never to rework.
        JsonNode reported = item(task, 5);
        assertThat(reported.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(reported.get("needsReview").asBoolean()).isTrue();
        assertThat(check(reported, "INVOICE_TOTAL").get("outcome").asText()).isEqualTo("REVIEW");
        assertThat(check(reported, "INVOICE_TOTAL").get("detail").asText()).contains("The Agent reported");
        assertThat(reported.get("anomalies").get(0).get("check").asText()).isEqualTo("INVOICE_TOTAL");
        assertThat(codes(reported)).containsExactly("INVOICE_ARITHMETIC");
        JsonNode silent = item(task, 6);
        assertThat(silent.get("needsReview").asBoolean()).isTrue();
        assertThat(check(silent, "INVOICE_TOTAL").get("detail").asText()).contains("did not report");
        assertThat(silent.get("anomalies")).isNull();

        // Duplicates are Findings beside a passing Item: they never change the verdict or the pay.
        JsonNode dup = item(task, 3);
        assertThat(dup.get("needsReview")).isNull();
        assertThat(codes(dup)).containsExactly("DUPLICATE_INVOICE");
        assertThat(dup.get("findings").get(0).get("detail").asText()).contains("Item 1 of this task");
        JsonNode sameFile = item(task, 4);
        assertThat(codes(sameFile)).containsExactly("DUPLICATE_DOCUMENT");
        assertThat(sameFile.get("findings").get(0).get("detail").asText()).contains("Item 3 of this task");
        assertThat(item(task, 1).get("findings")).isNull();

        // The sample is drawn from the Items every check passed; the flagged ones are the reviewer's own.
        JsonNode inspection = get("/api/tasks/" + taskId + "/inspection");
        List<Integer> sampled = new ArrayList<>();
        inspection.get("draws").get(0).get("itemNos").forEach(n -> sampled.add(n.asInt()));
        assertThat(sampled).containsExactly(1, 2, 3, 4); // a lot of 4 is inspected in full
        for (int n : sampled) {
            judge(taskId, n, Map.of("passed", true));
        }
        // The lot is accepted, but the two flagged invoices still wait for the reviewer.
        task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(get("/api/tasks/" + taskId + "/inspection").get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(item(task, 5).get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(item(task, 6).get("status").asText()).isEqualTo("PENDING_REVIEW");

        // The reviewer confirms the reported anomaly (the document's total really is wrong: paid) and finds the
        // silent one wrong; the Agent concedes it (refunded).
        judge(taskId, 5, Map.of("passed", true));
        judge(taskId, 6, Map.of("passed", false, "reasonCategory", "B_FACTUAL",
                "reason", "The document's total is 144.00; the agent copied it wrong"));
        concede(agent, taskId, 6);

        task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(task.get("resolution").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(statuses(task)).containsExactly("ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED",
                "CONCEDED", "SPEC_VIOLATION");
        // 5 paid x 2.00 (1.70 to the builder, 0.30 fee each) + 2 refunded = the 14.00 frozen.
        assertThat(sum(task, "payoutAmount")).isEqualByComparingTo("8.50");
        assertThat(sum(task, "commissionAmount")).isEqualByComparingTo("1.50");
        assertThat(sum(task, "refundAmount")).isEqualByComparingTo("4.00");
        assertThat(jdbc.queryForObject("SELECT quality FROM reputation_events WHERE task_id = ?", BigDecimal.class,
                taskId)).isEqualByComparingTo("0.714");

        // Each settled Item's receipt records its checks, a flagged one with its review outcome.
        receiptAppService.issuePending(100);
        JsonNode receipts = JSON.readTree(http.getForObject(platformUrl("/api/tasks/" + taskId + "/receipts"),
                String.class)).get("data");
        assertThat(receipts).hasSize(7);
        JsonNode flaggedReceipt = null;
        for (JsonNode r : receipts) {
            if (r.get("itemNo").asInt() == 5) {
                flaggedReceipt = r.get("receipt");
            }
        }
        assertThat(flaggedReceipt).isNotNull();
        assertThat(flaggedReceipt.get("checks").get("verdict").asText()).isEqualTo("REVIEW");
        assertThat(flaggedReceipt.get("settlement").get("outcome").asText()).isEqualTo("ACCEPT");
        // The source document's hash (S11), and the Item's Findings, are stated too.
        assertThat(flaggedReceipt.get("source").get("sha256").asText()).hasSize(64);
        assertThat(flaggedReceipt.get("source").get("mediaType").asText()).isEqualTo("application/pdf");
        assertThat(flaggedReceipt.get("findings").get(0).get("code").asText()).isEqualTo("INVOICE_ARITHMETIC");
        // A receipt leaves HireAI: its Findings name no task, user or document (no UUID), tax id or invoice number.
        for (JsonNode r : receipts) {
            JsonNode findings = r.get("receipt").get("findings");
            if (findings == null) {
                continue;
            }
            assertThat(findings.toString())
                    .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .doesNotContain("GB 123 4567 89").doesNotContain("INV-").doesNotContain("Item ");
            findings.forEach(f -> assertThat(f.fieldNames()).toIterable().containsExactly("code"));
        }
    }

    @Test
    void businessValidRefundsAnUnreportedMismatchButSendsAReportedOneToReview() throws Exception {
        FakeAgent agent = invoiceAgent();
        UUID agentId = seedInvoiceAgent(agent, "1.00");
        UUID engagementId = openEngagement(agentId);
        List<Map<String, Object>> items = List.of(
                item(engagementId, "a.pdf", pdf("a"), "ok"),
                item(engagementId, "b.pdf", pdf("b"), "silent"),
                item(engagementId, "c.pdf", pdf("c"), "reported"));

        UUID taskId = book(agentId, engagementId, items, "business_valid");
        awaitStatus(taskId, "PENDING_REVIEW");

        JsonNode task = getTask(taskId);
        assertThat(item(task, 2).get("status").asText()).isEqualTo("SPEC_VIOLATION");
        assertThat(check(item(task, 2), "INVOICE_TOTAL").get("outcome").asText()).isEqualTo("REJECT");
        assertThat(item(task, 3).get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(item(task, 3).get("needsReview").asBoolean()).isTrue();
        assertThat(item(task, 1).get("needsReview")).isNull();
    }

    @Test
    void anAgentFlaggingEveryItemChangesNeitherTheSampledLotNorAnyVerdict() throws Exception {
        FakeAgent agent = invoiceAgent();
        UUID agentId = seedInvoiceAgent(agent, "1.00");
        UUID engagementId = openEngagement(agentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            items.add(item(engagementId, i + ".pdf", pdf("flag-" + i), "flags"));
        }

        UUID taskId = book(agentId, engagementId, items, null);
        awaitStatus(taskId, "PENDING_REVIEW");

        // Each Item carries an anomaly naming nothing, one naming a check that passed and one naming no check at
        // all: none of them softened a failed check, so none holds its Item out of the random sample.
        JsonNode task = getTask(taskId);
        for (JsonNode i : task.get("items")) {
            assertThat(i.get("status").asText()).isEqualTo("PENDING_REVIEW");
            assertThat(i.get("needsReview")).as("Item %s", i.get("itemNo")).isNull();
            assertThat(i.get("anomalies")).hasSize(3); // still shown to the reviewer
            List<String> rules = new ArrayList<>();
            i.get("checks").forEach(c -> rules.add(c.get("rule").asText()));
            assertThat(rules).doesNotContain("SELF_REPORTED_ANOMALY");
        }
        List<Integer> sampled = new ArrayList<>();
        get("/api/tasks/" + taskId + "/inspection").get("draws").get(0).get("itemNos").forEach(n -> sampled.add(n.asInt()));
        assertThat(sampled).containsExactly(1, 2, 3, 4);
    }

    @Test
    void aFlaggedItemNobodyJudgesIsAcceptedAndPaidWhenTheReviewWindowCloses()
            throws Exception {
        FakeAgent agent = invoiceAgent();
        UUID agentId = seedInvoiceAgent(agent, "1.00");
        UUID engagementId = openEngagement(agentId);
        List<Map<String, Object>> items = List.of(
                item(engagementId, "a.pdf", pdf("w-a"), "ok"),
                item(engagementId, "b.pdf", pdf("w-b"), "ok"),
                item(engagementId, "c.pdf", pdf("w-c"), "silent"));

        UUID taskId = book(agentId, engagementId, items, null);
        awaitStatus(taskId, "PENDING_REVIEW");
        judge(taskId, 1, Map.of("passed", true));
        judge(taskId, 2, Map.of("passed", true));
        assertThat(item(getTask(taskId), 3).get("status").asText()).isEqualTo("PENDING_REVIEW");

        clock.set(Instant.parse(getTask(taskId).get("reviewDeadline").asText()));
        taskReviewAppService.autoAcceptOne(taskId);

        JsonNode task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        // No refund unless the Agent is shown wrong (owner decision on #87): silence accepts the flagged Item.
        assertThat(statuses(task)).containsExactly("ACCEPTED", "ACCEPTED", "ACCEPTED");
        assertThat(item(task, 3).get("payoutAmount").decimalValue()).isPositive();
        // It counts as accepted in the Task's one reputation event.
        assertThat(jdbc.queryForObject("SELECT quality FROM reputation_events WHERE task_id = ?", BigDecimal.class,
                taskId)).isEqualByComparingTo("1.000");
    }

    @Test
    void onTheApiChannelFlaggedItemsBeyondTheCapAreNeverPaidBeforeTheReviewWindowCloses() throws Exception {
        FakeAgent agent = invoiceAgent(Duration.ofSeconds(2));
        UUID agentId = seedInvoiceAgent(agent, "1.00");
        UUID engagementId = openEngagement(agentId);
        List<Map<String, Object>> items = List.of(
                item(engagementId, "a.pdf", pdf("api-a"), "ok"),
                item(engagementId, "b.pdf", pdf("api-b"), "ok"),
                item(engagementId, "c.pdf", pdf("api-c"), "silent"),
                item(engagementId, "d.pdf", pdf("api-d"), "silent"),
                item(engagementId, "e.pdf", pdf("api-e"), "silent"));

        UUID taskId = book(agentId, engagementId, items, null);
        // Attribute the Task to an API key before the Agent answers: its passing Items then settle at once.
        UUID keyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix) VALUES (?, ?, ?, 'hk_live_test')",
                keyId, DEV_USER, "hash-" + keyId);
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?, ?, 5.00)", taskId, keyId);
        awaitStatus(taskId, "PENDING_REVIEW");

        // 3 of 5 flagged, the cap (30%) is 1: two flagged Items are beyond it, yet none of the three is paid now.
        JsonNode task = getTask(taskId);
        assertThat(statuses(task)).containsExactly("ACCEPTED", "ACCEPTED", "PENDING_REVIEW", "PENDING_REVIEW",
                "PENDING_REVIEW");
        for (JsonNode i : task.get("items")) {
            boolean flagged = i.path("needsReview").asBoolean(false);
            // The flag the client sees is exactly what settlement does: flagged means not paid with the lot.
            assertThat(flagged).as("Item %s", i.get("itemNo")).isEqualTo(i.get("payoutAmount") == null);
        }

        clock.set(Instant.parse(task.get("reviewDeadline").asText()));
        taskReviewAppService.autoAcceptOne(taskId);
        assertThat(statuses(getTask(taskId))).containsOnly("ACCEPTED");
    }

    @Test
    void aFailedLotReworksTheFlaggedItemsBeyondTheCapButNotThoseWithinIt() throws Exception {
        FakeAgent agent = invoiceAgent();
        UUID agentId = seedInvoiceAgent(agent, "1.00");
        UUID engagementId = openEngagement(agentId);
        // 30 invoices, Items 1-11 with figures that don't add up. The cap (30% of 30) is 9: Items 1-9 are the
        // reviewer's own checks, Items 10 and 11 stand in the lot with the 19 clean ones.
        List<Map<String, Object>> items = new ArrayList<>();
        for (int n = 1; n <= 30; n++) {
            items.add(item(engagementId, "f-" + n + ".pdf", pdf("fail-" + n), n <= 11 ? "silent" : "ok"));
        }
        UUID taskId = book(agentId, engagementId, items, null);
        awaitStatus(taskId, "PENDING_REVIEW");
        List<Integer> withinCap = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        List<Integer> beyondCap = List.of(10, 11);

        Set<Integer> judged = new HashSet<>();
        for (int draw = 0; draw < 2; draw++) {
            List<Integer> sample = new ArrayList<>();
            get("/api/tasks/" + taskId + "/inspection").get("draws").get(draw).get("itemNos")
                    .forEach(n -> sample.add(n.asInt()));
            assertThat(sample).hasSize(8).doesNotContainAnyElementsOf(withinCap);
            // One wrong Item in each sample: the lot fails, and the whole Task goes back once.
            judge(taskId, sample.get(0), Map.of("passed", false, "reasonCategory", "B_FACTUAL", "reason", "wrong"));
            concede(agent, taskId, sample.get(0));
            for (int n : sample.subList(1, sample.size())) {
                judge(taskId, n, Map.of("passed", true));
            }
            judged.addAll(sample);
        }

        RecordedWorkOrder rework = await().atMost(Duration.ofSeconds(20)).until(() -> agent.workOrders().stream()
                .filter(o -> o.json().has("rework")).findFirst(), java.util.Optional::isPresent).get();
        List<Integer> returned = new ArrayList<>();
        rework.json().get("items").forEach(i -> returned.add(i.get("itemNo").asInt()));
        // Flagged Items beyond the cap share the lot's penalty unless the sample already judged them; those
        // within it stay the reviewer's own, untouched by the lot's outcome.
        for (int n : beyondCap) {
            assertThat(judged.contains(n) || returned.contains(n)).as("Item %d", n).isTrue();
        }
        assertThat(returned).hasSize(30 - 9 - 16).doesNotContainAnyElementsOf(withinCap)
                .doesNotContainAnyElementsOf(judged);
        JsonNode task = getTask(taskId);
        for (int n : withinCap) {
            assertThat(item(task, n).get("status").asText()).isEqualTo("PENDING_REVIEW");
            assertThat(item(task, n).get("payoutAmount")).isNull();
        }
    }

    @Test
    void aBookingUnderTheInvoiceContractNeedsAnAgentWhoseOfferCoversIt() throws Exception {
        FakeAgent agent = invoiceAgent();
        UUID agentId = seedInvoiceAgent(agent, "1.00");
        jdbc.update("UPDATE agent_versions SET offer = ?::jsonb WHERE agent_id = ?", ANY_JSON_SCHEMA_OFFER, agentId);
        UUID engagementId = openEngagement(agentId);
        List<Map<String, Object>> items = List.of(item(engagementId, "a.pdf", pdf("a"), "ok"),
                item(engagementId, "b.pdf", pdf("b"), "ok"));

        assertThatThrownBy(() -> book(agentId, engagementId, items, null))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    // ---- the fake Agent: one invoice per Item, staged by the Item's input ----

    private FakeAgent invoiceAgent() {
        return invoiceAgent(Duration.ofMillis(200));
    }

    private FakeAgent invoiceAgent(Duration replyAfter) {
        return fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(AgentCallback.custom(order -> {
            if (order.field("event") != null) {
                throw new IllegalStateException("a notice: the test answers it itself");
            }
            return CallbackRequest.post(order.callbackUrl())
                    .header("Authorization", "Bearer " + order.bearerToken())
                    .body(invoiceResults(order));
        }).afterReply(replyAfter).signedAt(() -> clock.instant())));
    }

    private String invoiceResults(RecordedWorkOrder order) {
        ObjectNode body = JSON.createObjectNode();
        body.put("taskId", order.taskId());
        body.put("agentStatus", "COMPLETED");
        ArrayNode results = body.putArray("items");
        for (JsonNode item : order.json().get("items")) {
            int no = item.get("itemNo").asInt();
            String stage = item.get("input").asText();
            ObjectNode result = results.addObject();
            result.put("itemNo", no);
            result.put("agentStatus", "COMPLETED");
            if (stage.equals("broken")) {
                result.put("resultPayloadJson", "Invoice from Acme, total 144.00");
                continue;
            }
            String number = stage.equals("dup") ? "INV-" + run + "-1" : "INV-" + run + "-" + no;
            boolean wrongTotal = stage.equals("reported") || stage.equals("silent");
            result.put("resultPayloadJson", invoiceJson(number, wrongTotal ? "150.00" : "144.00"));
            if (stage.equals("flags")) {
                ArrayNode anomalies = result.putArray("anomalies");
                anomalies.addObject().put("description", "Something looks odd about this invoice");
                anomalies.addObject().put("check", "INVOICE_TOTAL").put("description", "The total may be wrong");
                anomalies.addObject().put("check", "MADE_UP_CHECK").put("description", "Please look at this one");
            }
            if (stage.equals("reported")) {
                ObjectNode anomaly = result.putArray("anomalies").addObject();
                anomaly.put("check", "INVOICE_TOTAL");
                anomaly.put("description", "The document's grand total is 150.00, but its lines and tax come to 144.00");
            }
        }
        return body.toString();
    }

    private static String invoiceJson(String number, String grandTotal) {
        return "{\"supplier_name\":\"Acme Supplies Ltd\",\"supplier_tax_id\":\"GB 123 4567 89\","
                + "\"invoice_number\":\"" + number + "\",\"invoice_date\":\"2026-11-02\",\"currency\":\"GBP\","
                + "\"po_number\":\"PO-77\",\"line_items\":[{\"description\":\"Widgets\",\"quantity\":2,"
                + "\"unit_price\":50.00,\"amount\":100.00},{\"description\":\"Delivery\",\"quantity\":1,"
                + "\"unit_price\":20.00,\"amount\":20.00}],\"subtotal\":120.00,\"tax_rate\":20,"
                + "\"tax_total\":24.00,\"grand_total\":" + grandTotal + "}";
    }

    private void concede(FakeAgent agent, UUID taskId, int itemNo) throws Exception {
        RecordedWorkOrder notice = await().atMost(Duration.ofSeconds(20)).until(() -> agent.workOrders().stream()
                .filter(o -> "task.item_rejected".equals(o.field("event")))
                .filter(o -> o.json().get("itemNo").asInt() == itemNo).findFirst(), java.util.Optional::isPresent).get();
        String raw = JSON.writeValueAsString(Map.of("taskId", taskId.toString(), "action", "CONCEDE"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(notice.field("responseUrl")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + notice.bearerToken())
                .header(AgentCallback.SIGNATURE_HEADER, AgentCallback.signature(agent.signingSecret(), clock.instant(),
                        raw.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(raw))
                .build();
        HttpResponse<String> reply = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
    }

    // ---- setup ----

    private UUID seedInvoiceAgent(FakeAgent agent, String price) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00)",
                UUID.randomUUID(), ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Invoice Extractor', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price, offer) VALUES (?, ?, 1, ?::jsonb, ?, ?, 600, ?, ?::jsonb)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{uniqueCategory()}, agent.webhookUrl(),
                new BigDecimal(price), INVOICE_OFFER);
        shareSigningSecret(agent);
        return agentId;
    }

    private UUID openEngagement(UUID agentId) throws Exception {
        Map<String, Object> body = Map.of("agentId", agentId.toString(), "confidentialConsent", true);
        return UUID.fromString(JSON.readTree(http.postForObject(platformUrl("/api/engagements"), body, String.class))
                .get("data").get("id").asText());
    }

    private byte[] pdf(String name) {
        return ("%PDF-1.7\n" + run + " invoice " + name).getBytes(StandardCharsets.US_ASCII);
    }

    /** Uploads one document and returns the booking's Item for it, its input staging the fake Agent's answer. */
    private Map<String, Object> item(UUID engagementId, String filename, byte[] content, String stage) throws Exception {
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
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceId", JSON.readTree(response).get("data").get("id").asText());
        item.put("input", stage);
        return item;
    }

    private UUID book(UUID agentId, UUID engagementId, List<Map<String, Object>> items, String expectation)
            throws Exception {
        Map<String, Object> contract = new HashMap<>();
        contract.put("template", "ap_invoice_extraction");
        contract.put("version", 1);
        contract.put("params", expectation == null ? Map.of() : Map.of("expectation", expectation));
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Extract this week's supplier invoices");
        body.put("description", "Extract each invoice's fields as the invoice extraction Template describes");
        body.put("budget", jdbc.queryForObject("SELECT v.price FROM agent_versions v JOIN agents a "
                + "ON a.current_version_id = v.id WHERE a.id = ?", BigDecimal.class, agentId).toPlainString());
        body.put("agentId", agentId.toString());
        body.put("engagementId", engagementId.toString());
        body.put("items", items);
        body.put("contract", contract);
        String response = http.postForObject(platformUrl("/api/tasks/direct"), body, String.class);
        return UUID.fromString(JSON.readTree(response).get("data").get("id").asText());
    }

    // ---- reads ----

    private void judge(UUID taskId, int itemNo, Map<String, Object> body) {
        http.postForObject(platformUrl("/api/tasks/" + taskId + "/items/" + itemNo + "/judgement"), body, String.class);
    }

    private JsonNode get(String path) throws Exception {
        return JSON.readTree(http.getForObject(platformUrl(path), String.class)).get("data");
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        return get("/api/tasks/" + taskId);
    }

    private List<RecordedWorkOrder> workOrders(FakeAgent agent) {
        return agent.workOrders().stream().filter(o -> o.field("event") == null).toList();
    }

    private static JsonNode item(JsonNode task, int itemNo) {
        for (JsonNode i : task.get("items")) {
            if (i.get("itemNo").asInt() == itemNo) {
                return i;
            }
        }
        throw new AssertionError("No Item " + itemNo);
    }

    private static JsonNode check(JsonNode item, String rule) {
        for (JsonNode c : item.get("checks")) {
            if (c.get("rule").asText().equals(rule)) {
                return c;
            }
        }
        throw new AssertionError("No check " + rule + " on " + item);
    }

    private static List<String> codes(JsonNode item) {
        List<String> codes = new ArrayList<>();
        if (item.get("findings") != null) {
            item.get("findings").forEach(f -> codes.add(f.get("code").asText()));
        }
        return codes;
    }

    private static List<String> statuses(JsonNode task) {
        List<String> statuses = new ArrayList<>();
        task.get("items").forEach(i -> statuses.add(i.get("status").asText()));
        return statuses;
    }

    private static BigDecimal sum(JsonNode task, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode i : task.get("items")) {
            if (i.get(field) != null) {
                total = total.add(i.get(field).decimalValue());
            }
        }
        return total;
    }

    private void awaitStatus(UUID taskId, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo(expected));
    }
}
