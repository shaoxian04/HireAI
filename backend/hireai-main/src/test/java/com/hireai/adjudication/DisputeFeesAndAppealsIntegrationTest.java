package com.hireai.adjudication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.clock.TestClockConfig;
import com.hireai.support.harness.WholeAppIntegrationTest;
import com.hireai.support.rejection.RejectionSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Loser-pays arbitration cost, the Appeal Fee and the Builder's web appeal (#96, spec
 * D16/D18/D42/D43). Driven over HTTP as the test profile's fixed dev user, who plays the Client in
 * some tests and the Builder in others; the other side acts through the application service.
 *
 * <p>The stub arbitrator rules A → NOT_FULFILLED, B → PARTIALLY_FULFILLED, C → FULFILLED. The Item
 * price is 20.00, so the 20% cap is 4.00: the 3.00 arbitration cost is charged in full, and the
 * 5.00 Appeal Fee is capped at 4.00 with the platform covering 1.00.
 */
@Import(TestClockConfig.class)
class DisputeFeesAndAppealsIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.randomUUID();

    @DynamicPropertySource
    static void fees(DynamicPropertyRegistry registry) {
        registry.add("hireai.arbitration.cost", () -> "3.00");
        registry.add("hireai.arbitration.appeal-fee", () -> "5.00");
    }

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired DisputeAppService disputeAppService;
    @Autowired com.hireai.application.biz.admin.AdminReadAppService adminReadAppService;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    @Test
    void theBuilderAppealsARulingAgainstItFromTheWebAndBearsBothCapppedFeesWhenTheAdminUpholdsIt() throws Exception {
        UUID client = newUser("CLIENT", "30.00");
        Fixture f = disputedTask(client, DEV_USER, "10.00", RejectReason.A_MISMATCH); // → NOT_FULFILLED

        JsonNode outcome = outcome(f.taskId());
        assertThat(outcome.get("status").asText()).isEqualTo("RULED");
        assertThat(texts(outcome.get("yourSides"))).containsExactly("BUILDER");
        assertThat(texts(outcome.get("awaiting"))).containsExactly("BUILDER");
        assertThat(outcome.get("arbitrationCost").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(outcome.get("appealFee").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(outcome.get("agentResponse").asText()).isEqualTo("STAND_BY");
        assertThat(outcome.get("agentReason").asText()).isEqualTo("the result meets the spec");
        assertThat(outcome.get("appealDeadline").isNull()).isFalse();

        JsonNode mine = data(new RestTemplate().getForObject(platformUrl("/api/disputes/builder"), String.class));
        assertThat(mine.findValuesAsText("taskId")).contains(f.taskId().toString());

        JsonNode appealed = post("/api/disputes/" + f.disputeId() + "/appeal");
        assertThat(appealed.get("status").asText()).isEqualTo("ESCALATED");
        assertThat(appealed.get("appealedBy").asText()).isEqualTo("BUILDER");
        assertThat(wallet(client)).containsExactly(new BigDecimal("10.00"), new BigDecimal("20.00")); // still held

        // The admin's outcome preview includes what the loser bears: 3.00 cost + 4.00 capped Appeal Fee.
        var preview = adminReadAppService.disputeDetail(f.disputeId()).settlementPreview();
        assertThat(preview.fulfilledClientFees()).isEqualByComparingTo("7.00");
        assertThat(preview.notFulfilledBuilderFees()).isEqualByComparingTo("7.00");
        assertThat(preview.partialClientFees()).isEqualByComparingTo("3.50");
        assertThat(preview.partialBuilderFees()).isEqualByComparingTo("3.50");

        disputeAppService.adminRule(f.disputeId(), RulingCategory.NOT_FULFILLED, "upheld", ADMIN);

        assertThat(wallet(client)).containsExactly(new BigDecimal("30.00"), new BigDecimal("0.00")); // full refund
        assertThat(wallet(DEV_USER).get(0)).isEqualByComparingTo(f.builderStart().subtract(new BigDecimal("7.00")));
        assertThat(feeEntries(f.taskId(), DEV_USER)).containsExactlyInAnyOrder(
                "ARBITRATION_FEE:-3.00", "APPEAL_FEE:-4.00");
        JsonNode fees = outcome(f.taskId()).get("fees");
        assertThat(fees.get(0).get("type").asText()).isEqualTo("ARBITRATION_COST");
        assertThat(fees.get(0).get("builderCharged").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(fees.get(1).get("type").asText()).isEqualTo("APPEAL_FEE");
        assertThat(fees.get(1).get("builderCharged").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(fees.get(1).get("platformCovered").decimalValue()).isEqualByComparingTo("1.00");
    }

    @Test
    void theClientAppealsAndWinsSoTheBuilderBearsTheFeesAsFarAsItsEarningsGoAndThePlatformTheRest() throws Exception {
        Fixture f = disputedTask(DEV_USER, newUser("BUILDER", "2.00"), "0.00", RejectReason.C_INCOMPLETE); // → FULFILLED
        BigDecimal clientBefore = wallet(DEV_USER).get(0);

        assertThat(texts(outcome(f.taskId()).get("awaiting"))).containsExactly("CLIENT");
        JsonNode appealed = post("/api/disputes/" + f.disputeId() + "/appeal");
        assertThat(appealed.get("appealedBy").asText()).isEqualTo("CLIENT");

        disputeAppService.adminRule(f.disputeId(), RulingCategory.NOT_FULFILLED, "overruled", ADMIN);

        assertThat(wallet(DEV_USER).get(0)).isEqualByComparingTo(clientBefore.add(new BigDecimal("20.00")));
        assertThat(feeEntries(f.taskId(), DEV_USER)).isEmpty();
        assertThat(feeEntries(f.taskId(), f.builderId())).containsExactly("ARBITRATION_FEE:-2.00");
        JsonNode fees = outcome(f.taskId()).get("fees");
        assertThat(fees.get(0).get("platformCovered").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(fees.get(1).get("builderCharged").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(fees.get(1).get("platformCovered").decimalValue()).isEqualByComparingTo("5.00");
    }

    @Test
    void aClientWhoAcceptsARulingAgainstItSettlesAtOnceAndPaysTheArbitrationCostOnly() throws Exception {
        Fixture f = disputedTask(DEV_USER, newUser("BUILDER", "0.00"), "0.00", RejectReason.C_INCOMPLETE); // → FULFILLED
        BigDecimal clientBefore = wallet(DEV_USER).get(0);

        JsonNode accepted = post("/api/disputes/" + f.disputeId() + "/accept-ruling");

        assertThat(accepted.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(wallet(DEV_USER).get(0)).isEqualByComparingTo(clientBefore.subtract(new BigDecimal("3.00")));
        assertThat(wallet(f.builderId()).get(0)).isEqualByComparingTo("17.00"); // 85% of 20.00, untouched
        assertThat(feeEntries(f.taskId(), DEV_USER)).containsExactly("ARBITRATION_FEE:-3.00");
        assertThat(accepted.get("fees")).hasSize(1);
    }

    @Test
    void theSideARulingFavoursCanNeitherAcceptNorAppealItAndTheRulingWaitsForTheOtherSide() throws Exception {
        UUID builder = newUser("BUILDER", "5.00");
        Fixture f = disputedTask(DEV_USER, builder, "0.00", RejectReason.A_MISMATCH); // → NOT_FULFILLED

        assertThat(texts(outcome(f.taskId()).get("awaiting"))).containsExactly("BUILDER");
        assertThat(texts(outcome(f.taskId()).get("yourSides"))).containsExactly("CLIENT");
        assertConflict("/api/disputes/" + f.disputeId() + "/accept-ruling");
        assertConflict("/api/disputes/" + f.disputeId() + "/appeal");

        disputeAppService.acceptRuling(f.disputeId(), builder);

        assertThat(outcome(f.taskId()).get("status").asText()).isEqualTo("RESOLVED");
        assertThat(wallet(builder).get(0)).isEqualByComparingTo("2.00");
        assertThat(feeEntries(f.taskId(), builder)).containsExactly("ARBITRATION_FEE:-3.00");
    }

    @Test
    void aSplitRulingSettlesOnlyOnceBothSidesAcceptAndTheyShareTheCost() throws Exception {
        UUID builder = newUser("BUILDER", "0.00");
        Fixture f = disputedTask(DEV_USER, builder, "0.00", RejectReason.B_FACTUAL); // → PARTIALLY_FULFILLED
        BigDecimal clientBefore = wallet(DEV_USER).get(0);

        JsonNode afterClient = post("/api/disputes/" + f.disputeId() + "/accept-ruling");
        assertThat(afterClient.get("status").asText()).isEqualTo("RULED");
        assertThat(texts(afterClient.get("awaiting"))).containsExactly("BUILDER");

        disputeAppService.acceptRuling(f.disputeId(), builder);

        assertThat(outcome(f.taskId()).get("status").asText()).isEqualTo("RESOLVED");
        // Split: 10.00 refunded, 8.50 paid; then each side bears half the 3.00 cost.
        assertThat(wallet(DEV_USER).get(0)).isEqualByComparingTo(clientBefore.add(new BigDecimal("8.50")));
        assertThat(wallet(builder).get(0)).isEqualByComparingTo("7.00");
    }

    // --- helpers ---

    private record Fixture(UUID taskId, UUID disputeId, UUID builderId, BigDecimal builderStart) {}

    /** Books a 20.00 task for {@code client} on an Agent owned by {@code builder}, then disputes it. */
    private Fixture disputedTask(UUID client, UUID builder, String builderTopUp, RejectReason reason) {
        if (client.equals(DEV_USER)) {
            fund(DEV_USER, "20.00");
        }
        fund(builder, builderTopUp);
        FakeAgent agent = fakeAgent().replyOnceWith(FakeAgentReply.accepted()
                .withCallback(AgentCallback.completed().afterReply(Duration.ofMillis(200))));
        String category = uniqueCategory();
        seedActiveAgent(builder, category, agent.webhookUrl());
        shareSigningSecret(agent);
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(client, "Summarise report",
                "Summarise the attached quarterly report", Money.of("20.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"), category));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                        .isEqualTo("PENDING_REVIEW"));
        RejectionSteps.rejectThenArbitrate(context, taskId, client, reason, "not what I asked for");
        UUID disputeId = jdbc.queryForObject("SELECT id FROM disputes WHERE task_id = ?", UUID.class, taskId);
        return new Fixture(taskId, disputeId, builder, wallet(builder).get(0));
    }

    private UUID newUser(String role, String balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, ?, 0.00)",
                UUID.randomUUID(), id, new BigDecimal(balance));
        return id;
    }

    private void fund(UUID userId, String amount) {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 0.00, 0.00) "
                + "ON CONFLICT (user_id) DO NOTHING", UUID.randomUUID(), userId);
        jdbc.update("UPDATE wallets SET available_balance = available_balance + ? WHERE user_id = ?",
                new BigDecimal(amount), userId);
    }

    private void seedActiveAgent(UUID ownerId, String category, String webhookUrl) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Fake Agent', 'ACTIVE', ?, 80.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, webhookUrl,
                new BigDecimal("20.00"));
        acceptAnyJsonSchema(versionId); // its own schema would not cover the task's (#91)
    }

    /** [available, escrow] */
    private List<BigDecimal> wallet(UUID userId) {
        return jdbc.queryForObject("SELECT available_balance, escrow_balance FROM wallets WHERE user_id = ?",
                (rs, i) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2)), userId);
    }

    private List<String> feeEntries(UUID taskId, UUID userId) {
        return jdbc.queryForList("SELECT l.entry_type || ':' || l.amount FROM ledger_entries l "
                + "JOIN wallets w ON w.id = l.wallet_id WHERE l.related_task_id = ? AND w.user_id = ? "
                + "AND l.entry_type IN ('ARBITRATION_FEE', 'APPEAL_FEE')", String.class, taskId, userId);
    }

    private JsonNode outcome(UUID taskId) throws Exception {
        return data(new RestTemplate().getForObject(platformUrl("/api/disputes/by-task/" + taskId), String.class));
    }

    private JsonNode post(String path) throws Exception {
        return data(new RestTemplate().postForObject(platformUrl(path), null, String.class));
    }

    private void assertConflict(String path) {
        assertThatThrownBy(() -> new RestTemplate().postForObject(platformUrl(path), null, String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    private JsonNode data(String body) throws Exception {
        return objectMapper.readTree(body).get("data");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
