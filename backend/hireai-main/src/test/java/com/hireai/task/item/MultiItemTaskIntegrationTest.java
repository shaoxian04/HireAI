package com.hireai.task.item;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.biz.task.TaskReviewAppService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Multi-Item Tasks (#103, spec S3: D3, D14, D22, D27). A Task holds 1..n Items: escrow is frozen once
 * as the per-Item price × the count, every Item is judged and settled on its own, the Task status
 * aggregates them, and a finished Task writes one reputation event (weight 1, quality = the accepted
 * fraction). The fake Agent answers each Item by what its input asks for: {@code #fail} reports the
 * Item failed, {@code #empty} delivers an empty text (fails text_basic), {@code #skip} leaves it out.
 */
@Import(TestClockConfig.class)
class MultiItemTaskIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MutableTestClock clock;
    @Autowired TaskReviewAppService taskReviewAppService;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void setUp() {
        // A Wednesday, so a 24h business-day window is plain clock time.
        clock.set(Instant.parse("2026-11-18T10:00:00Z"));
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), DEV_USER);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + 100.00 WHERE user_id = ?", DEV_USER);
    }

    @Test
    void aBookingWithItemsFreezesPriceTimesCountOnceAndCarriesEachItemInTheWorkOrder() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "4.00");
        BigDecimal escrowBefore = escrow();

        UUID taskId = book(agentId, "4.00", List.of("invoice A", "invoice B", "invoice C"));

        JsonNode workOrder = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0).json();
        assertThat(workOrder.get("items")).hasSize(3);
        assertThat(workOrder.get("items").get(1).get("itemNo").asInt()).isEqualTo(2);
        assertThat(workOrder.get("items").get(1).get("input").asText()).isEqualTo("invoice B");
        assertThat(jdbc.queryForList("SELECT amount FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'ESCROW_FREEZE'", BigDecimal.class, taskId))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("-12.00")); // the freeze is recorded as leaving the available balance
        JsonNode task = getTask(taskId);
        assertThat(task.get("budget").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(task.get("itemCount").asInt()).isEqualTo(3);
        assertThat(task.get("itemPrice").decimalValue()).isEqualByComparingTo("4.00");
        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(escrow().subtract(escrowBefore)).isEqualByComparingTo("12.00");
    }

    @Test
    void eachItemIsJudgedAndSettledOnItsOwnAndTheTaskWritesOneEventWithTheAcceptedFraction() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "3.00");
        UUID taskId = book(agentId, "3.00", List.of("invoice A", "invoice B #empty", "invoice C #fail"));
        awaitStatus(taskId, "PENDING_REVIEW");

        JsonNode items = getTask(taskId).get("items");
        assertThat(items).extracting(i -> i.get("status").asText())
                .containsExactly("PENDING_REVIEW", "SPEC_VIOLATION", "FAILED");
        assertThat(items.get(0).get("resultPayload").asText()).isEqualTo("done: invoice A");
        assertThat(items.get(1).get("checks").get(0).get("passed").asBoolean()).isFalse();
        assertThat(items.get(1).get("refundAmount").decimalValue()).isEqualByComparingTo("3.00");
        // The two bad Items were refunded at once, each on its own; the good one still waits for review.
        assertThat(refunds(taskId)).containsExactlyInAnyOrder(
                "settle-" + taskId + "-item-2", "settle-" + taskId + "-item-3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_events WHERE task_id = ?",
                Integer.class, taskId)).isZero();

        JsonNode resolved = post("/api/tasks/" + taskId + "/items/1/accept");
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(resolved.get("payoutAmount").decimalValue()).isEqualByComparingTo("2.55");
        assertThat(resolved.get("commissionAmount").decimalValue()).isEqualByComparingTo("0.45");
        assertThat(resolved.get("refundAmount").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(resolved.get("items").get(0).get("status").asText()).isEqualTo("ACCEPTED");

        assertThat(jdbc.queryForList("SELECT item_no || ':' || type FROM task_item_settlements WHERE task_id = ? "
                + "ORDER BY item_no", String.class, taskId)).containsExactly("1:ACCEPT", "2:REJECT", "3:REJECT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId))
                .isZero();
        assertThat(jdbc.queryForList("SELECT amount FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'PAYOUT'", BigDecimal.class, taskId))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2.55"), new BigDecimal("2.55"));
        Map<String, Object> event = jdbc.queryForMap("SELECT event_type, quality, weight FROM reputation_events "
                + "WHERE task_id = ?", taskId);
        assertThat(event.get("event_type")).isEqualTo("ITEMS_SETTLED");
        assertThat((BigDecimal) event.get("quality")).isEqualByComparingTo("0.333");
        assertThat((BigDecimal) event.get("weight")).isEqualByComparingTo("1");
        // Nothing of the Task is left in escrow.
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
    }

    @Test
    void acceptingTheTaskAcceptsEveryItemAwaitingReview() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "2.00");
        UUID taskId = book(agentId, "2.00", List.of("a", "b"));
        awaitStatus(taskId, "PENDING_REVIEW");

        JsonNode resolved = post("/api/tasks/" + taskId + "/accept");

        assertThat(resolved.get("resolution").asText()).isEqualTo("ACCEPTED");
        assertThat(resolved.get("items")).extracting(i -> i.get("status").asText())
                .containsExactly("ACCEPTED", "ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT quality FROM reputation_events WHERE task_id = ?",
                BigDecimal.class, taskId)).isEqualByComparingTo("1");
        assertThatThrownBy(() -> post("/api/tasks/" + taskId + "/items/1/accept"))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void anItemTheAgentLeftOutIsRefundedAndATaskWithNoPassingItemEndsAsASpecViolation() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "1.50");
        UUID taskId = book(agentId, "1.50", List.of("x #skip", "y #empty"));

        awaitStatus(taskId, "SPEC_VIOLATION");

        JsonNode task = getTask(taskId);
        assertThat(task.get("failureReason").asText()).isEqualTo("SPEC_VIOLATION");
        assertThat(task.get("items")).extracting(i -> i.get("status").asText())
                .containsExactly("FAILED", "SPEC_VIOLATION");
        assertThat(task.get("refundAmount").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(escrowLeftFor(taskId)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT event_type || ':' || quality FROM reputation_events WHERE task_id = ?",
                String.class, taskId)).isEqualTo("ITEMS_SETTLED:0.000");
    }

    @Test
    void theReviewWindowCountsBusinessDaysAndClosingItAcceptsTheRemainingItems() throws Exception {
        clock.set(Instant.parse("2026-11-20T15:00:00Z")); // a Friday afternoon
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "2.00");
        UUID taskId = book(agentId, "2.00", List.of("a", "b", "c #fail"));
        awaitStatus(taskId, "PENDING_REVIEW");

        Instant deadline = Instant.parse(getTask(taskId).get("reviewDeadline").asText());
        assertThat(deadline).isEqualTo(Instant.parse("2026-11-23T15:00:00Z")); // Monday: the weekend never counts

        clock.set(Instant.parse("2026-11-22T23:00:00Z")); // Sunday night: still open
        assertThat(taskReviewAppService.reviewExpiredTaskIds()).doesNotContain(taskId);
        post("/api/tasks/" + taskId + "/items/1/accept");

        clock.set(deadline);
        assertThat(taskReviewAppService.reviewExpiredTaskIds()).contains(taskId);
        taskReviewAppService.autoAcceptOne(taskId);

        JsonNode task = getTask(taskId);
        assertThat(task.get("resolution").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(task.get("autoAccepted").asBoolean()).isTrue();
        assertThat(task.get("items").get(0).get("autoAccepted")).isNull();
        assertThat(task.get("items").get(1).get("autoAccepted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT quality FROM reputation_events WHERE task_id = ?",
                BigDecimal.class, taskId)).isEqualByComparingTo("0.667");
    }

    @Test
    void aMultiItemTaskCannotBeRejectedWholesale() throws Exception {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "2.00");
        UUID taskId = book(agentId, "2.00", List.of("a", "b"));
        awaitStatus(taskId, "PENDING_REVIEW");

        assertThatThrownBy(() -> http.postForObject(platformUrl("/api/tasks/" + taskId + "/reject"),
                Map.of("reasonCategory", "A_MISMATCH", "reason", "wrong"), String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(status(taskId)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void aRoutedTaskWithItemsMatchesOnThePricePerItem() throws Exception {
        FakeAgent agent = itemAgent();
        String category = uniqueCategory();
        seedTextAgent(agent, "4.00", category);

        // 3 Items at 5.00 each: an Agent priced 4.00 per Item fits; the 15.00 escrow is not its price.
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Extract invoices");
        body.put("description", "Extract each invoice");
        body.put("category", category);
        body.put("budget", "5.00");
        body.put("outputSpec", Map.of("format", "TEXT"));
        body.put("items", List.of("one", "two", "three"));
        UUID taskId = UUID.fromString(JSON.readTree(http.postForObject(platformUrl("/api/tasks"), body, String.class))
                .get("data").get("id").asText());

        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(getTask(taskId).get("budget").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(agent.workOrders().get(0).json().get("items")).hasSize(3);
    }

    @Test
    void anApiTaskSettlesItsPassingItemsAtOnce() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(itemCallback().afterReply(Duration.ofMillis(1500))));
        UUID agentId = seedTextAgent(agent, "2.00");
        UUID taskId = book(agentId, "2.00", List.of("a", "b #fail"));
        markApiSubmitted(taskId);

        awaitStatus(taskId, "RESOLVED");
        JsonNode task = getTask(taskId);
        assertThat(task.get("resolution").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(task.get("items")).extracting(i -> i.get("status").asText()).containsExactly("ACCEPTED", "FAILED");
        assertThat(jdbc.queryForObject("SELECT quality FROM reputation_events WHERE task_id = ?",
                BigDecimal.class, taskId)).isEqualByComparingTo("0.5");
    }

    @Test
    void aSingleDeliverableTaskIsUnchanged() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().signedAt(() -> clock.instant())
                        .afterReply(Duration.ofMillis(200))));
        UUID agentId = seedTextAgent(agent, "2.00");
        UUID taskId = book(agentId, "2.00", null);

        awaitStatus(taskId, "PENDING_REVIEW");
        assertThat(agent.workOrders().get(0).json().has("items")).isFalse();
        JsonNode task = getTask(taskId);
        assertThat(task.has("items")).isFalse();
        assertThat(task.has("itemCount")).isFalse();
        assertThat(task.get("budget").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_items WHERE task_id = ?", Integer.class, taskId))
                .isZero();

        post("/api/tasks/" + taskId + "/accept");
        assertThat(jdbc.queryForList("SELECT event_type FROM reputation_events WHERE task_id = ?", String.class, taskId))
                .containsExactly("TASK_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
    }

    @Test
    void aBookingWithOneItemOrABlankItemIsRefusedAndFreezesNothing() {
        FakeAgent agent = itemAgent();
        UUID agentId = seedTextAgent(agent, "2.00");
        BigDecimal before = escrow();

        for (List<String> items : List.of(List.of("only one"), List.of("a", " "))) {
            assertThatThrownBy(() -> book(agentId, "2.00", items))
                    .isInstanceOfSatisfying(HttpClientErrorException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        assertThat(escrow()).isEqualByComparingTo(before);
    }

    // --- helpers ---

    private FakeAgent itemAgent() {
        return fakeAgent().replyWith(FakeAgentReply.accepted()
                .withCallback(itemCallback().afterReply(Duration.ofMillis(200))));
    }

    /** Answers every Item of the WorkOrder by what its input asks for (see the class comment). */
    private AgentCallback itemCallback() {
        // Signature freshness is judged by the app's (test) clock, so sign at its time.
        return AgentCallback.custom(order -> CallbackRequest.post(order.callbackUrl())
                .header("Authorization", "Bearer " + order.bearerToken())
                .body(itemResults(order)))
                .signedAt(() -> clock.instant());
    }

    private static String itemResults(RecordedWorkOrder order) {
        ObjectNode body = JSON.createObjectNode();
        body.put("taskId", order.taskId());
        body.put("agentStatus", "COMPLETED");
        ArrayNode results = body.putArray("items");
        for (JsonNode item : order.json().get("items")) {
            String input = item.get("input").asText();
            if (input.contains("#skip")) {
                continue;
            }
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

    private UUID seedTextAgent(FakeAgent agent, String price) {
        return seedTextAgent(agent, price, uniqueCategory());
    }

    private UUID seedTextAgent(FakeAgent agent, String price, String category) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00)",
                UUID.randomUUID(), ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Item Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"TEXT\"}", new String[]{category}, agent.webhookUrl(),
                new BigDecimal(price));
        shareSigningSecret(agent);
        return agentId;
    }

    private UUID book(UUID agentId, String budget, List<String> items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Extract invoices");
        body.put("description", "Extract the fields of each invoice");
        body.put("budget", budget);
        body.put("agentId", agentId.toString());
        if (items != null) {
            body.put("items", items);
        }
        String response = http.postForObject(platformUrl("/api/tasks/direct"), body, String.class);
        return UUID.fromString(JSON.readTree(response).get("data").get("id").asText());
    }

    private void markApiSubmitted(UUID taskId) {
        UUID keyId = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix) VALUES (?, ?, ?, 'hk_live_t')",
                keyId, DEV_USER, "hash-" + keyId);
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?, ?, 4.00)", taskId, keyId);
    }

    private JsonNode post(String path) throws Exception {
        return JSON.readTree(http.postForObject(platformUrl(path), null, String.class)).get("data");
    }

    private JsonNode getTask(UUID taskId) throws Exception {
        return JSON.readTree(http.getForObject(platformUrl("/api/tasks/" + taskId), String.class)).get("data");
    }

    private void awaitStatus(UUID taskId, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(taskId)).isEqualTo(expected));
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private BigDecimal escrow() {
        return jdbc.queryForObject("SELECT escrow_balance FROM wallets WHERE user_id = ?", BigDecimal.class, DEV_USER);
    }

    private List<String> refunds(UUID taskId) {
        return jdbc.queryForList("SELECT correlation_id FROM ledger_entries WHERE related_task_id = ? "
                + "AND entry_type = 'REFUND'", String.class, taskId);
    }

    /** What the Task froze minus everything that left escrow for it (released or refunded). */
    private BigDecimal escrowLeftFor(UUID taskId) {
        BigDecimal frozen = jdbc.queryForObject("SELECT -COALESCE(SUM(amount), 0) FROM ledger_entries "
                + "WHERE related_task_id = ? AND entry_type = 'ESCROW_FREEZE'", BigDecimal.class, taskId);
        BigDecimal refunded = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries "
                + "WHERE related_task_id = ? AND entry_type = 'REFUND'", BigDecimal.class, taskId);
        // A release appends PAYOUT (client side, delta 0, recorded amount) and COMMISSION entries from the
        // client's wallet; the builder's credit is a second PAYOUT. Count the client-side releases only.
        BigDecimal released = jdbc.queryForObject("SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e "
                + "JOIN wallets w ON w.id = e.wallet_id WHERE e.related_task_id = ? AND w.user_id = ? "
                + "AND e.entry_type IN ('PAYOUT', 'COMMISSION')", BigDecimal.class, taskId, DEV_USER);
        return frozen.subtract(refunded).subtract(released);
    }
}
