package com.hireai.reputation;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;
import com.hireai.domain.biz.reputation.repository.ReputationEventRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispute-ruling emission (#38) and programmatic auto-settle emission (#39).
 *
 * <p>The StubArbitrationClient used by the test profile maps the reject reason onto a proposed
 * ruling, which is what lets one test drive all three categories:
 * A_MISMATCH → NOT_FULFILLED, B_FACTUAL → PARTIALLY_FULFILLED, C_INCOMPLETE → FULFILLED.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class DisputeAndProgrammaticReputationIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TaskReviewAppService reviewAppService;
    @Autowired DisputeAppService disputeAppService;
    @Autowired DisputeRepository disputeRepository;
    @Autowired ValidationAppService validationAppService;
    @Autowired ReputationEventRepository reputationEvents;
    @Autowired TaskRepository taskRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    private UUID[] newAgent(UUID builderId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, 'Dispute IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url,
                                            max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return new UUID[]{agentId, versionId};
    }

    private TaskModel seedReviewableTask(UUID clientId, UUID versionId) {
        TaskModel task = TaskModel.submit(clientId, "dispute me", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(versionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                        UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null,
                        Instant.now()))
                .passValidation();
        taskRepository.save(task);
        walletWrite.topUp(clientId, Money.of("100.00"), "topup-" + task.id());
        walletWrite.freeze(clientId, Money.of("20.00"), task.id(), "freeze-" + task.id());
        return task;
    }

    private List<ReputationEventModel> eventsFor(UUID agentId) {
        return reputationEvents.findRecentByAgentId(agentId, 20);
    }

    private BigDecimal scoreOf(UUID agentId) {
        return jdbc.queryForObject(
                "SELECT reputation_score FROM agents WHERE id = ?", BigDecimal.class, agentId);
    }

    /** Rejects with {@code reason}, then accepts the arbitrator's proposal so it takes effect. */
    private void disputeAndAcceptRuling(TaskModel task, UUID client, RejectReason reason) {
        reviewAppService.reject(task.id(), client, reason, "see rationale");
        UUID disputeId = disputeRepository.findByTaskId(task.id()).orElseThrow().id();
        disputeAppService.acceptRuling(disputeId, client);
    }

    // -------------------------------------------------------------------- #38 dispute rulings

    /**
     * The property worth protecting: being complained about is not itself a failure. A builder who
     * wins records full quality, otherwise a client could damage an agent simply by filing losing
     * disputes.
     */
    @Test
    void aFulfilledRulingRecordsFullQualityAndDoesNotPenaliseTheBuilder() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);

        disputeAndAcceptRuling(task, client, RejectReason.C_INCOMPLETE); // → FULFILLED

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.DISPUTE_WON);
            assertThat(e.quality()).isEqualByComparingTo("1.000");
        });
        // Same as a plain acceptance: (2.5 + 1) / 6 → 55.83. Winning costs nothing.
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("55.83");
    }

    /** A proportionate consequence — half, explicitly distinct from total failure. */
    @Test
    void aPartiallyFulfilledRulingRecordsHalfQuality() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);

        disputeAndAcceptRuling(task, client, RejectReason.B_FACTUAL); // → PARTIALLY_FULFILLED

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.DISPUTE_PARTIAL);
            assertThat(e.quality()).isEqualByComparingTo("0.500");
        });
        // (2.5 + 0.5) / 6 = 0.5 reliability → exactly the prior, so the score holds at 50.00:
        // a half-failure on a first task is neither evidence for nor against.
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("50.00");
    }

    @Test
    void aLostRulingRecordsZeroQuality() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);

        disputeAndAcceptRuling(task, client, RejectReason.A_MISMATCH); // → NOT_FULFILLED

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.DISPUTE_LOST);
            assertThat(e.quality()).isEqualByComparingTo("0.000");
        });
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("44.17");
    }

    /** A partial ruling must sit strictly between a loss and a win, not collapse onto either. */
    @Test
    void thePartialRulingIsStrictlyBetweenALossAndAWin() {
        UUID client = newUser("CLIENT");
        UUID[] won = newAgent(newUser("BUILDER"));
        UUID[] partial = newAgent(newUser("BUILDER"));
        UUID[] lost = newAgent(newUser("BUILDER"));

        disputeAndAcceptRuling(seedReviewableTask(client, won[1]), client, RejectReason.C_INCOMPLETE);
        disputeAndAcceptRuling(seedReviewableTask(client, partial[1]), client, RejectReason.B_FACTUAL);
        disputeAndAcceptRuling(seedReviewableTask(client, lost[1]), client, RejectReason.A_MISMATCH);

        assertThat(scoreOf(won[0])).isGreaterThan(scoreOf(partial[0]));
        assertThat(scoreOf(partial[0])).isGreaterThan(scoreOf(lost[0]));
    }

    /**
     * Tier-2: when an administrator overrides, the event must come from the ruling that actually
     * took effect, not from the arbitrator proposal it superseded. Here the arbitrator proposed
     * NOT_FULFILLED and the admin overrules to FULFILLED — the agent must record a win.
     */
    @Test
    void anAdminOverrideEmitsFromTheRulingThatTookEffect() {
        UUID client = newUser("CLIENT");
        UUID admin = newUser("ADMIN");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);

        reviewAppService.reject(task.id(), client, RejectReason.A_MISMATCH, "not what I asked");
        UUID disputeId = disputeRepository.findByTaskId(task.id()).orElseThrow().id();
        disputeAppService.appeal(disputeId, client);

        disputeAppService.adminRule(disputeId, RulingCategory.FULFILLED, "spec was met", admin);

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e ->
                assertThat(e.eventType()).isEqualTo(ReputationEventType.DISPUTE_WON));
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("55.83");
    }

    /** Settle-exactly-once must mean emit-exactly-once: one dispute yields one event. */
    @Test
    void aDisputeEmitsExactlyOneEvent() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);
        disputeAndAcceptRuling(task, client, RejectReason.C_INCOMPLETE);

        UUID disputeId = disputeRepository.findByTaskId(task.id()).orElseThrow().id();
        // A second acceptance is already a no-op for settlement; it must be one for reputation too.
        try {
            disputeAppService.acceptRuling(disputeId, client);
        } catch (RuntimeException expected) {
            // The dispute is resolved; either a refusal or a no-op is fine here.
        }

        assertThat(eventsFor(agent[0])).hasSize(1);
    }

    // ------------------------------------------------------------ #39 programmatic auto-settle

    /** Marks a task as API-submitted, which is what routes it down the auto-settle branch. */
    private void markApiSubmitted(UUID taskId, UUID ownerId) {
        UUID keyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_keys (id, user_id, key_hash, display_prefix, name, status)
                VALUES (?, ?, ?, ?, 'it-key', 'ACTIVE')""",
                keyId, ownerId, "hash-" + keyId,
                "hk_live_" + keyId.toString().substring(0, 6));
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget) VALUES (?, ?, 20.00)",
                taskId, keyId);
    }

    /**
     * Machine-verified conformance against the binding output_spec is real evidence and is not
     * discounted — the event is full weight, full quality.
     */
    @Test
    void aProgrammaticTaskPassingValidationRecordsFullWeightReliability() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));

        TaskModel task = TaskModel.submit(client, "api task", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(agent[1])
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "a fine summary", null, Instant.now()));
        taskRepository.save(task);
        walletWrite.topUp(client, Money.of("100.00"), "topup-" + task.id());
        walletWrite.freeze(client, Money.of("20.00"), task.id(), "freeze-" + task.id());
        markApiSubmitted(task.id(), client);

        validationAppService.validateAndGate(task);

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.TASK_ACCEPTED);
            assertThat(e.quality()).isEqualByComparingTo("1.000");
            assertThat(e.weight()).isEqualByComparingTo("1.000");
        });
        // No Satisfaction event is possible — nobody human judged it.
        assertThat(reputationEvents.replayAggregates(agent[0]).satisfactionCount()).isZero();
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("55.83");
    }

    /**
     * An API-only agent plateaus below one with human approval, and no rule anywhere says so — the
     * two-component model produces the ceiling on its own, because Satisfaction stays at the prior
     * when no human ever rates the work.
     */
    @Test
    void anApiOnlyAgentPlateausBelowOneWithHumanApproval() {
        UUID client = newUser("CLIENT");
        UUID[] apiOnly = newAgent(newUser("BUILDER"));

        for (int i = 0; i < 12; i++) {
            TaskModel task = TaskModel.submit(client, "api task " + i, "desc", Money.of("20.00"),
                            new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                    .assignAndQueue(apiOnly[1])
                    .markExecuting();
            task = task.recordResult(TaskResultModel.rehydrate(
                    UUID.randomUUID(), task.id(), "COMPLETED", "a fine summary", null, Instant.now()));
            taskRepository.save(task);
            walletWrite.topUp(client, Money.of("100.00"), "topup-" + task.id());
            walletWrite.freeze(client, Money.of("20.00"), task.id(), "freeze-" + task.id());
            markApiSubmitted(task.id(), client);
            validationAppService.validateAndGate(task);
        }

        // Reliability is now high, but Satisfaction is still exactly the prior, so the blend is
        // capped well below what a human-approved agent can reach.
        assertThat(reputationEvents.replayAggregates(apiOnly[0]).reliabilityCount()).isEqualTo(12);
        assertThat(reputationEvents.replayAggregates(apiOnly[0]).satisfactionCount()).isZero();
        assertThat(scoreOf(apiOnly[0])).isLessThan(BigDecimal.valueOf(85));
    }
}
