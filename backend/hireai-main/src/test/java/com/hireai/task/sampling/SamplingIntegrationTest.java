package com.hireai.task.sampling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.task.sampling.SamplingAppService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.sampling.model.SampleDraw;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Sampling inspection of multi-Item Tasks (#105, spec S8: D4, D6, D17′, D19, D21), driven from the outside:
 * a booking with Items, the fake Agent's WorkOrders, item notices and signed responses, the reviewer's
 * judgements over HTTP, and the ledger, settlements and reputation rows they leave.
 */
@Import(TestClockConfig.class)
class SamplingIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MutableTestClock clock;
    @Autowired TaskReviewAppService taskReviewAppService;
    @Autowired SamplingAppService samplingAppService;
    @Autowired TaskExecutionPort taskExecutionPort;
    @Autowired com.hireai.application.biz.acceptance.receipt.ReceiptAppService receiptAppService;

    private final RestTemplate rest = new RestTemplate();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-18T10:00:00Z")); // a Wednesday
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 2500.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void aFiveHundredItemTaskIsSampledByTheTableFromASeedRecordedAfterDelivery() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "0.10");
        UUID taskId = book(agentId, "0.10", inputs(500), null);
        awaitStatus(taskId, "PENDING_REVIEW");

        JsonNode inspection = inspection(taskId);
        assertThat(inspection.get("level").asText()).isEqualTo("II");
        assertThat(inspection.get("round").asInt()).isEqualTo(1);
        assertThat(inspection.get("status").asText()).isEqualTo("SAMPLING");
        assertThat(inspection.get("aql").decimalValue()).isEqualByComparingTo("2.5");
        JsonNode draw = inspection.get("draws").get(0);
        // ISO 2859-1: 500 Items at level II is letter H; AQL 2.5 double plan 32 + 32, 1/3 then 4/5.
        assertThat(draw.get("codeLetter").asText()).isEqualTo("H");
        assertThat(draw.get("lotSize").asInt()).isEqualTo(500);
        assertThat(draw.get("firstSampleSize").asInt()).isEqualTo(32);
        assertThat(draw.get("firstAccept").asInt()).isEqualTo(1);
        assertThat(draw.get("firstReject").asInt()).isEqualTo(3);
        assertThat(draw.get("secondAccept").asInt()).isEqualTo(4);
        List<Integer> sampled = itemNos(draw);
        assertThat(sampled).hasSize(32);
        // D6: anyone holding the recorded seed redraws exactly this sample.
        long seed = Long.parseLong(draw.get("seed").asText());
        assertThat(SampleDraw.draw(seed, IntStream.rangeClosed(1, 500).boxed().toList(), 32)).isEqualTo(sampled);
        // The Agent never saw it: the WorkOrder carries no sample or seed, and the draw came after delivery.
        RecordedWorkOrder workOrder = workOrders(agent).get(0);
        assertThat(workOrder.bodyText()).doesNotContain(draw.get("seed").asText()).doesNotContain("sample");
        assertThat(Instant.parse(draw.get("drawnAt").asText())).isAfterOrEqualTo(clock.instant().minusSeconds(1));
    }

    @Test
    void aFailedFirstSampleDrawsASecondAndAPassingSecondAcceptsTheWholeLot() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "0.10");
        UUID taskId = book(agentId, "0.10", inputs(500), null);
        awaitStatus(taskId, "PENDING_REVIEW");
        List<Integer> first = itemNos(inspection(taskId).get("draws").get(0));

        // Two sampled Items are wrong (1 < 2 < 3: inconclusive); the agent concedes both.
        reject(taskId, first.get(0), "B_FACTUAL", "the total is 120.00, not 102.00");
        reject(taskId, first.get(1), "B_FACTUAL", "the invoice number is wrong");
        concede(agent, taskId, first.get(0));
        concede(agent, taskId, first.get(1));
        // Refunded mid-inspection, yet no receipt yet: it would state the seed and membership while the
        // second sample can still be drawn. Neither the read nor the sweeper issues it.
        String seed = inspection(taskId).get("draws").get(0).get("seed").asText();
        receiptAppService.issuePending(1000);
        assertThat(receiptsJson(taskId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts WHERE task_id = ?", Integer.class, taskId))
                .isZero();
        for (int n : first.subList(2, first.size())) {
            pass(taskId, n);
        }

        JsonNode inspection = inspection(taskId);
        assertThat(inspection.get("draws")).hasSize(2);
        // Nothing the agent (or anyone reading the task) sees carries a seed while the inspection is open.
        String secondSeed = inspection.get("draws").get(1).get("seed").asText();
        String taskBody = rest.getForObject(platformUrl("/api/tasks/" + taskId), String.class);
        assertThat(taskBody).doesNotContain(seed).doesNotContain(secondSeed).doesNotContain("\"seed\"");
        for (RecordedWorkOrder sent : agent.workOrders()) {
            assertThat(sent.bodyText()).doesNotContain(seed).doesNotContain(secondSeed).doesNotContain("\"seed\"");
        }
        assertThat(receiptsJson(taskId)).isEmpty();
        assertThat(inspection.get("draws").get(0).get("defects").asInt()).isEqualTo(2);
        assertThat(inspection.get("draws").get(0).get("decision").asText()).isEqualTo("DRAW_SECOND");
        List<Integer> second = itemNos(inspection.get("draws").get(1));
        assertThat(second).hasSize(32).doesNotContainAnyElementsOf(first);
        assertThat(inspection.get("draws").get(1).get("seed").asText())
                .isNotEqualTo(inspection.get("draws").get(0).get("seed").asText());

        for (int n : second) {
            pass(taskId, n);
        }

        // 2 of 64: within the cumulative acceptance number 4, so every Item still awaiting review is accepted.
        JsonNode task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(task.get("resolution").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(statusCounts(task)).isEqualTo(Map.of("ACCEPTED", 498L, "CONCEDED", 2L));
        assertThat(inspection(taskId).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(inspection(taskId).get("draws").get(1).get("decision").asText()).isEqualTo("ACCEPT_LOT");
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT quality FROM reputation_events WHERE task_id = ?",
                BigDecimal.class, taskId)).isEqualByComparingTo("0.996");
        // The reviewer's judgements are kept: 62 passes, 2 rejections, all inside the random sample.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_judgements WHERE task_id = ? AND sampled",
                Integer.class, taskId)).isEqualTo(64);
        // Each settled Item's signed receipt carries its sample membership and the seeds (D6, D40).
        JsonNode receipts = receiptsJson(taskId);
        assertThat(receipts).hasSize(500);
        JsonNode sampledReceipt = null;
        for (JsonNode r : receipts) {
            if (r.get("itemNo").asInt() == first.get(0)) {
                sampledReceipt = r.get("receipt");
            }
        }
        assertThat(sampledReceipt).isNotNull();
        assertThat(sampledReceipt.get("sample").get("sampled").asBoolean()).isTrue();
        assertThat(sampledReceipt.get("sample").get("draws").get(0).get("seed").asText())
                .isEqualTo(inspection(taskId).get("draws").get(0).get("seed").asText());
        assertThat(sampledReceipt.get("judgements").get(0).get("by").asText()).isEqualTo("REVIEWER");
        assertThat(sampledReceipt.get("settlement").get("outcome").asText()).isEqualTo("REJECT");
    }

    @Test
    void failingTwiceReworksTheWholeTaskOnceAndThenSettlesTheUnverifiedItemsProRata() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "4.00");
        UUID taskId = book(agentId, "4.00", inputs(100), null); // lot 100, level II: letter F, 13 + 13, 0/2, 1/2
        awaitStatus(taskId, "PENDING_REVIEW");
        List<Integer> first = itemNos(inspection(taskId).get("draws").get(0));
        assertThat(first).hasSize(13);

        reject(taskId, first.get(0), "A_MISMATCH", "the vendor is missing");
        reject(taskId, first.get(1), "A_MISMATCH", "the date is wrong");
        concede(agent, taskId, first.get(0));
        concede(agent, taskId, first.get(1));
        for (int n : first.subList(2, first.size())) {
            pass(taskId, n);
        }

        // 2 wrong >= the rejection number 2: D19 still draws the second sample before any rework.
        JsonNode afterFirst = inspection(taskId);
        assertThat(afterFirst.get("draws").get(0).get("decision").asText()).isEqualTo("DRAW_SECOND");
        List<Integer> second = itemNos(afterFirst.get("draws").get(1));
        assertThat(second).hasSize(13).doesNotContainAnyElementsOf(first);
        for (int n : second) {
            pass(taskId, n);
        }

        // 2 wrong over both samples > Ac2 1: the whole Task goes back once.
        RecordedWorkOrder rework = await().atMost(Duration.ofSeconds(20))
                .until(() -> workOrders(agent), orders -> orders.size() == 2).get(1);
        assertThat(rework.json().get("rework").get("failedChecks").get(0).get("rule").asText())
                .isEqualTo("SAMPLE_REJECTED");
        // Only the Items not yet settled go back: 100 - 24 accepted - 2 conceded.
        assertThat(rework.json().get("items")).hasSize(74);
        awaitInspectionRound(taskId, 2);

        JsonNode inspection = inspection(taskId);
        assertThat(inspection.get("draws").get(1).get("decision").asText()).isEqualTo("REWORK");
        JsonNode resample = inspection.get("draws").get(2);
        assertThat(resample.get("round").asInt()).isEqualTo(2);
        assertThat(resample.get("lotSize").asInt()).isEqualTo(74); // letter E: 8 + 8, 0/2, 1/2
        List<Integer> again = itemNos(resample);
        assertThat(again).hasSize(8).doesNotContainAnyElementsOf(first).doesNotContainAnyElementsOf(second);

        reject(taskId, again.get(0), "C_INCOMPLETE", "line items are missing");
        reject(taskId, again.get(1), "C_INCOMPLETE", "the tax is missing");
        concede(agent, taskId, again.get(0));
        concede(agent, taskId, again.get(1));
        for (int n : again.subList(2, again.size())) {
            pass(taskId, n);
        }
        List<Integer> againSecond = itemNos(inspection(taskId).get("draws").get(3));
        assertThat(againSecond).hasSize(8);
        reject(taskId, againSecond.get(0), "C_INCOMPLETE", "the supplier is missing");
        reject(taskId, againSecond.get(1), "C_INCOMPLETE", "the currency is missing");
        concede(agent, taskId, againSecond.get(0));
        concede(agent, taskId, againSecond.get(1));
        for (int n : againSecond.subList(2, againSecond.size())) {
            pass(taskId, n);
        }

        // Failed again (4 wrong of 16): the 58 unverified Items settle at 4.00 x (1 - 4/16) = 3.00 each.
        JsonNode task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(statusCounts(task)).isEqualTo(Map.of("ACCEPTED", 36L, "CONCEDED", 6L, "PRO_RATA", 58L));
        JsonNode proRata = itemsWith(task, "PRO_RATA").get(0);
        assertThat(proRata.get("payoutAmount").decimalValue()).isEqualByComparingTo("2.55");
        assertThat(proRata.get("commissionAmount").decimalValue()).isEqualByComparingTo("0.45");
        assertThat(proRata.get("refundAmount").decimalValue()).isEqualByComparingTo("1.00");
        JsonNode done = inspection(taskId);
        assertThat(done.get("status").asText()).isEqualTo("PRO_RATA");
        assertThat(done.get("errorRate").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_item_settlements WHERE task_id = ? AND type = 'SPLIT' "
                + "AND net = 2.55 AND commission = 0.45 AND refund = 1.00", Integer.class, taskId)).isEqualTo(58);
        // Everything frozen left escrow exactly once: 400.00 = paid + fees + refunds.
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
        assertThat(task.get("payoutAmount").decimalValue().add(task.get("commissionAmount").decimalValue())
                .add(task.get("refundAmount").decimalValue())).isEqualByComparingTo("400.00");
    }

    @Test
    void aSampledRejectionTakesTheResponsePathWithTheBuyerDeciding() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "2.00");
        UUID taskId = book(agentId, "2.00", inputs(3), null); // a lot of 3 is inspected in full
        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(itemNos(inspection(taskId).get("draws").get(0))).containsExactly(1, 2, 3);

        // Item 1: the agent revises it once; the reviewer judges the revision.
        reject(taskId, 1, "B_FACTUAL", "the total is wrong");
        RecordedWorkOrder notice = awaitItemNotice(agent, 1, 1);
        assertThat(notice.field("event")).isEqualTo("task.item_rejected");
        assertThat(notice.json().get("itemNo").asInt()).isEqualTo(1);
        assertThat(notice.field("itemInput")).isEqualTo("invoice 1");
        assertThat(notice.field("itemResult")).isEqualTo("done: invoice 1");
        assertThat(notice.json().get("revisionAllowed").asBoolean()).isTrue();
        // The response window counts business days: 2h from Wednesday 10:00.
        assertThat(Instant.parse(notice.field("responseDeadline"))).isEqualTo(clock.instant().plus(Duration.ofHours(2)));
        assertThat(respond(agent, taskId, notice, "REVISE", null, "done again: invoice 1").statusCode()).isEqualTo(200);
        JsonNode revised = itemsWith(getTask(taskId), "PENDING_REVIEW").get(0);
        assertThat(revised.get("itemNo").asInt()).isEqualTo(1);
        assertThat(revised.get("resultPayload").asText()).isEqualTo("done again: invoice 1");
        pass(taskId, 1);

        // Item 2: the agent stands by it; the buyer may not arbitrate one Item yet, and accepts it.
        reject(taskId, 2, "A_MISMATCH", "wrong currency");
        RecordedWorkOrder standBy = awaitItemNotice(agent, 2, 1);
        assertThat(respond(agent, taskId, standBy, "STAND_BY", "the currency is on the invoice", null)
                .statusCode()).isEqualTo(200);
        JsonNode rejection = itemRejection(inspection(taskId), 2);
        assertThat(rejection.get("response").asText()).isEqualTo("STAND_BY");
        assertThat(rejection.get("responseReason").asText()).isEqualTo("the currency is on the invoice");
        assertThatThrownBy(() -> decide(taskId, 2, "ARBITRATE"))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        decide(taskId, 2, "ACCEPT");

        // Item 3: rejected, and the agent's silence counts as standing by; the buyer's as accepting.
        reject(taskId, 3, "C_INCOMPLETE", "the address is missing");
        UUID item3 = jdbc.queryForObject("SELECT id FROM task_items WHERE task_id = ? AND item_no = 3",
                UUID.class, taskId);
        clock.set(clock.instant().plus(Duration.ofHours(3)));
        assertThat(samplingAppService.itemResponseExpiredIds()).contains(item3);
        samplingAppService.standBySilentlyItem(item3);
        clock.set(clock.instant().plus(Duration.ofHours(25)));
        assertThat(samplingAppService.itemDecisionExpiredIds()).contains(item3);
        samplingAppService.acceptSilentlyItem(item3);

        JsonNode task = getTask(taskId);
        assertThat(task.get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(statusCounts(task)).isEqualTo(Map.of("ACCEPTED", 3L));
        assertThat(inspection(taskId).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
    }

    @Test
    void theReviewersExtraChecksAreHandledPerItemAndDoNotFeedTheErrorRate() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "0.10");
        UUID taskId = book(agentId, "0.10", inputs(500), null);
        awaitStatus(taskId, "PENDING_REVIEW");
        List<Integer> sample = itemNos(inspection(taskId).get("draws").get(0));
        List<Integer> outside = IntStream.rangeClosed(1, 500).boxed().filter(n -> !sample.contains(n)).limit(3).toList();

        for (int n : outside) { // a full check is allowed within the window
            reject(taskId, n, "B_FACTUAL", "wrong figure");
            concede(agent, taskId, n);
        }
        for (int n : sample) {
            pass(taskId, n);
        }

        JsonNode task = getTask(taskId);
        assertThat(task.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(statusCounts(task)).isEqualTo(Map.of("ACCEPTED", 497L, "CONCEDED", 3L));
        JsonNode inspection = inspection(taskId);
        assertThat(inspection.get("draws")).hasSize(1); // 0 wrong in the sample: accepted on the first
        assertThat(inspection.get("draws").get(0).get("defects").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_judgements WHERE task_id = ? AND NOT sampled",
                Integer.class, taskId)).isEqualTo(3);
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
    }

    @Test
    void aBuyerMayChooseAStricterLevelOnlyWithinTheOffer() throws Exception {
        FakeAgent agent = itemAgent();
        UUID strict = seedTextAgent(agent, "0.10");
        jdbc.update("UPDATE agent_versions SET offer = offer || '{\"strictestInspectionLevel\":\"III\"}'::jsonb "
                + "WHERE agent_id = ?", strict);
        UUID taskId = book(strict, "0.10", inputs(500), "III");
        awaitStatus(taskId, "PENDING_REVIEW");
        JsonNode inspection = inspection(taskId);
        assertThat(inspection.get("level").asText()).isEqualTo("III");
        assertThat(inspection.get("draws").get(0).get("codeLetter").asText()).isEqualTo("J"); // 50 + 50
        assertThat(itemNos(inspection.get("draws").get(0))).hasSize(50);

        UUID plain = seedTextAgent(itemAgent(), "0.10");
        BigDecimal before = escrow();
        for (String level : List.of("III", "I")) {
            assertThatThrownBy(() -> book(plain, "0.10", inputs(10), level))
                    .isInstanceOfSatisfying(HttpClientErrorException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        assertThat(escrow()).isEqualByComparingTo(before);
    }

    @Test
    void nothingIsJudgedAfterTheWindowAndItsCloseAcceptsWhatIsLeft() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "0.10");
        UUID taskId = book(agentId, "0.10", inputs(60), null);
        awaitStatus(taskId, "PENDING_REVIEW");
        int sampled = itemNos(inspection(taskId).get("draws").get(0)).get(0);
        pass(taskId, sampled);

        clock.set(Instant.parse(getTask(taskId).get("reviewDeadline").asText()));
        assertThatThrownBy(() -> reject(taskId, sampled == 1 ? 2 : 1, "B_FACTUAL", "late"))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        taskReviewAppService.autoAcceptOne(taskId);

        assertThat(getTask(taskId).get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(inspection(taskId).get("status").asText()).isEqualTo("WAIVED");
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
    }

    @Test
    void aReworkThatTimesOutRefundsOnlyWhatIsStillInEscrow() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "1.00");
        UUID taskId = book(agentId, "1.00", inputs(40), null); // letter D: single 8, Ac 0
        awaitStatus(taskId, "PENDING_REVIEW");
        agent.replyWith(FakeAgentReply.accepted()); // the rework never comes back
        // A failed single-plan sample draws a second of the same size (D19); it fails too, so the Task is reworked.
        for (int draw = 0; draw < 2; draw++) {
            List<Integer> sample = itemNos(inspection(taskId).get("draws").get(draw));
            assertThat(sample).hasSize(8);
            reject(taskId, sample.get(0), "B_FACTUAL", "wrong");
            concede(agent, taskId, sample.get(0));
            for (int n : sample.subList(1, sample.size())) {
                pass(taskId, n);
            }
        }
        awaitStatus(taskId, "QUEUED", "EXECUTING");

        taskExecutionPort.markTimedOut(taskId);

        assertThat(status(taskId)).isEqualTo("TIMED_OUT");
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
        // 14 paid, 2 conceded, 24 refunded with the timeout: never the paid ones twice.
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'REFUND'", BigDecimal.class, taskId)).isEqualByComparingTo("26.00");
        // The Task ended, so the inspection can draw nothing more: the settled Items' receipts are issued now.
        List<JsonNode> itemReceipts = new ArrayList<>();
        JsonNode whole = null;
        for (JsonNode r : receiptsJson(taskId)) {
            if (r.get("itemNo").isNull()) {
                whole = r.get("receipt");
            } else {
                itemReceipts.add(r.get("receipt"));
            }
        }
        assertThat(itemReceipts).hasSize(16);
        assertThat(itemReceipts.get(0).get("sample").get("outcome").asText()).isEqualTo("REWORKING");
        // The whole-Task receipt for the timeout states what that refund returned: what was still held.
        assertThat(whole.get("settlement").get("price").asText()).isEqualTo("24.00");
        assertThat(whole.get("settlement").get("refunded").asText()).isEqualTo("24.00");
    }

    // --- the fake Agent ---

    private FakeAgent itemAgent() {
        return fakeAgent().replyWith(FakeAgentReply.accepted().withCallback(itemCallback()));
    }

    /** Answers every WorkOrder (the first and the rework) with each Item done; ignores notices. */
    private AgentCallback itemCallback() {
        return AgentCallback.custom(order -> {
            if (order.field("event") != null) {
                throw new IllegalStateException("a notice: the test answers it itself");
            }
            return CallbackRequest.post(order.callbackUrl())
                    .header("Authorization", "Bearer " + order.bearerToken())
                    .body(itemResults(order));
        }).afterReply(Duration.ofMillis(200)).signedAt(() -> clock.instant());
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
            result.put("resultPayloadJson", "done: " + item.get("input").asText());
        }
        return body.toString();
    }

    private List<RecordedWorkOrder> workOrders(FakeAgent agent) {
        return agent.workOrders().stream().filter(o -> o.field("event") == null).toList();
    }

    private RecordedWorkOrder awaitItemNotice(FakeAgent agent, int itemNo, int round) {
        return await().atMost(Duration.ofSeconds(20)).until(() -> agent.workOrders().stream()
                        .filter(o -> "task.item_rejected".equals(o.field("event")))
                        .filter(o -> o.json().get("itemNo").asInt() == itemNo && o.json().get("round").asInt() == round)
                        .findFirst(), java.util.Optional::isPresent).get();
    }

    private void concede(FakeAgent agent, UUID taskId, int itemNo) throws Exception {
        HttpResponse<String> reply = respond(agent, taskId, awaitItemNotice(agent, itemNo, 1), "CONCEDE", null, null);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
    }

    private HttpResponse<String> respond(FakeAgent agent, UUID taskId, RecordedWorkOrder notice, String action,
                                         String reason, String payload) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId.toString());
        body.put("action", action);
        if (reason != null) {
            body.put("reason", reason);
        }
        if (payload != null) {
            body.put("resultPayloadJson", payload);
        }
        String raw = JSON.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(notice.field("responseUrl")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + notice.bearerToken())
                .header(AgentCallback.SIGNATURE_HEADER, AgentCallback.signature(agent.signingSecret(), clock.instant(),
                        raw.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(raw))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // --- the reviewer ---

    private void pass(UUID taskId, int itemNo) throws Exception {
        judge(taskId, itemNo, Map.of("passed", true));
    }

    private void reject(UUID taskId, int itemNo, String category, String reason) throws Exception {
        judge(taskId, itemNo, Map.of("passed", false, "reasonCategory", category, "reason", reason));
    }

    private void judge(UUID taskId, int itemNo, Map<String, Object> body) {
        rest.postForObject(platformUrl("/api/tasks/" + taskId + "/items/" + itemNo + "/judgement"), body, String.class);
    }

    private void decide(UUID taskId, int itemNo, String decision) {
        rest.postForObject(platformUrl("/api/tasks/" + taskId + "/items/" + itemNo + "/decision"),
                Map.of("decision", decision), String.class);
    }

    // --- setup and reads ---

    private static List<String> inputs(int count) {
        List<String> inputs = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            inputs.add("invoice " + i);
        }
        return inputs;
    }

    private UUID seedTextAgent(FakeAgent agent, String price) {
        UUID ownerId = UUID.randomUUID();
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
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 600, ?)",
                versionId, agentId, "{\"format\":\"TEXT\"}", new String[]{uniqueCategory()}, agent.webhookUrl(),
                new BigDecimal(price));
        shareSigningSecret(agent);
        return agentId;
    }

    private UUID book(UUID agentId, String price, List<String> items, String level) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Extract invoices");
        body.put("description", "Extract the fields of each invoice");
        body.put("budget", price);
        body.put("agentId", agentId.toString());
        body.put("items", items);
        if (level != null) {
            body.put("inspectionLevel", level);
        }
        String response = rest.postForObject(platformUrl("/api/tasks/direct"), body, String.class);
        return UUID.fromString(JSON.readTree(response).get("data").get("id").asText());
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        return JSON.readTree(rest.getForObject(platformUrl("/api/tasks/" + taskId), String.class)).get("data");
    }

    private JsonNode receiptsJson(UUID taskId) throws Exception {
        return JSON.readTree(rest.getForObject(platformUrl("/api/tasks/" + taskId + "/receipts"), String.class))
                .get("data");
    }

    private JsonNode inspection(UUID taskId) throws Exception {
        return JSON.readTree(rest.getForObject(platformUrl("/api/tasks/" + taskId + "/inspection"), String.class))
                .get("data");
    }

    private void awaitInspectionRound(UUID taskId, int round) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode inspection = inspection(taskId);
            assertThat(inspection.get("round").asInt()).isEqualTo(round);
            assertThat(inspection.get("draws")).hasSizeGreaterThanOrEqualTo(round);
        });
    }

    private static JsonNode itemRejection(JsonNode inspection, int itemNo) {
        JsonNode latest = null;
        for (JsonNode r : inspection.get("itemRejections")) {
            if (r.get("itemNo").asInt() == itemNo) {
                latest = r.get("rejection");
            }
        }
        return latest;
    }

    private static List<Integer> itemNos(JsonNode draw) {
        List<Integer> nos = new ArrayList<>();
        draw.get("itemNos").forEach(n -> nos.add(n.asInt()));
        return nos;
    }

    private static Map<String, Long> statusCounts(JsonNode task) {
        List<String> statuses = new ArrayList<>();
        task.get("items").forEach(i -> statuses.add(i.get("status").asText()));
        return statuses.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static List<JsonNode> itemsWith(JsonNode task, String status) {
        List<JsonNode> found = new ArrayList<>();
        task.get("items").forEach(i -> {
            if (status.equals(i.get("status").asText())) {
                found.add(i);
            }
        });
        return found;
    }

    private void awaitStatus(UUID taskId, String... expected) {
        Set<String> allowed = new HashSet<>(List.of(expected));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(allowed).contains(status(taskId)));
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private BigDecimal escrow() {
        return jdbc.queryForObject("SELECT escrow_balance FROM wallets WHERE user_id = ?", BigDecimal.class, DEV_USER);
    }

    /** What the Task froze minus everything that left escrow for it (released, split or refunded). */
    private BigDecimal escrowLeftFor(UUID taskId) {
        BigDecimal frozen = jdbc.queryForObject("SELECT -COALESCE(SUM(amount), 0) FROM ledger_entries "
                + "WHERE related_task_id = ? AND entry_type = 'ESCROW_FREEZE'", BigDecimal.class, taskId);
        BigDecimal refunded = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries "
                + "WHERE related_task_id = ? AND entry_type = 'REFUND'", BigDecimal.class, taskId);
        // Client-side releases only (the builder's credit is a second PAYOUT/SPLIT entry on its own wallet).
        BigDecimal released = jdbc.queryForObject("SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e "
                + "JOIN wallets w ON w.id = e.wallet_id WHERE e.related_task_id = ? AND w.user_id = ? "
                + "AND e.entry_type IN ('PAYOUT', 'COMMISSION', 'SPLIT')", BigDecimal.class, taskId, DEV_USER);
        return frozen.subtract(refunded).subtract(released);
    }
}
