package com.hireai.reputation;

import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
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
 * Emission at the failure-path sites (#37), and — the part that matters most — the two outcomes
 * that must record <strong>nothing</strong>.
 *
 * <p>Those two silences are the reason emission cannot be collapsed to a single choke point. A
 * changed-mind rejection settles exactly like an acceptance (85/15 to the builder) and writes
 * {@code TaskResolution.REJECTED}; a capacity cancellation settles like a refund. Any rule derived
 * from how money moved, or from the task's resolution field, gets both backwards.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ReputationEmissionIntegrationTest {

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
    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService reliabilityAppService;
    @Autowired ValidationAppService validationAppService;
    @Autowired ReputationEventRepository reputationEvents;
    @Autowired TaskRepository taskRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------------------- seed helpers

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    /** Returns [agentId, versionId]. The spec demands a "summary" key, so a bare {} fails it. */
    private UUID[] newAgent(UUID builderId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, 'Emission IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url,
                                            max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return new UUID[]{agentId, versionId};
    }

    private TaskModel seedFundedTask(UUID clientId, UUID versionId) {
        TaskModel task = TaskModel.submit(clientId, "emit", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(versionId)
                .markExecuting();
        taskRepository.save(task);
        walletWrite.topUp(clientId, Money.of("100.00"), "setup-topup-" + task.id());
        walletWrite.freeze(clientId, Money.of("20.00"), task.id(), "setup-freeze-" + task.id());
        return task;
    }

    private TaskModel seedReviewableTask(UUID clientId, UUID versionId) {
        TaskModel task = seedFundedTask(clientId, versionId);
        TaskModel reviewable = task.recordResult(TaskResultModel.rehydrate(
                        UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null,
                        Instant.now()))
                .passValidation();
        taskRepository.save(reviewable);
        return reviewable;
    }

    private List<ReputationEventModel> eventsFor(UUID agentId) {
        return reputationEvents.findRecentByAgentId(agentId, 20);
    }

    private BigDecimal scoreOf(UUID agentId) {
        return jdbc.queryForObject(
                "SELECT reputation_score FROM agents WHERE id = ?", BigDecimal.class, agentId);
    }

    // ------------------------------------------------------------- THE TWO DELIBERATE SILENCES

    /**
     * Buyer's remorse on conformant work. The platform has already classified the output as
     * meeting the declared spec and pays the builder in full — penalising them for the client
     * changing their mind would be punishing a decision the platform itself already ruled was not
     * their fault.
     *
     * <p>The trap: this writes {@code TaskResolution.REJECTED} while settling 85/15. A rule keyed
     * off the resolution column records a failure here, and misses a real one elsewhere.
     */
    @Test
    void aChangedMindRejectionRecordsNoEventDespiteSettlingLikeAnAccept() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);

        reviewAppService.reject(task.id(), client, RejectReason.D_CHANGED_MIND, "changed my mind");

        assertThat(eventsFor(agent[0])).isEmpty();
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("50.00");

        // The money did move, and the resolution does say REJECTED — which is exactly why neither
        // is a safe signal to derive emission from.
        TaskModel resolved = taskRepository.findById(task.id()).orElseThrow();
        assertThat(resolved.resolution().name()).isEqualTo("REJECTED");
    }

    /**
     * Cancelled from AWAITING_CAPACITY: no agent had headroom, so no agent ever received the work.
     * Being busy is not a failure, and there is nobody to attribute one to.
     */
    @Test
    void aCapacityCancellationRecordsNoEventDespiteSettlingLikeARefund() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));

        TaskModel task = TaskModel.submit(client, "no capacity", "desc", Money.of("20.00"),
                new OutputSpec(OutputFormat.TEXT, null, null), "summarisation");
        taskRepository.save(task);
        walletWrite.topUp(client, Money.of("100.00"), "topup-" + task.id());
        walletWrite.freeze(client, Money.of("20.00"), task.id(), "freeze-" + task.id());
        taskWriteAppService.markAwaitingCapacity(task.id());

        taskWriteAppService.cancelAwaitingCapacityWithRefund(task.id());

        assertThat(eventsFor(agent[0])).isEmpty();
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("50.00");
    }

    // ------------------------------------------------------------------------- the failure paths

    @Test
    void anOutputThatMissesTheDeclaredSpecRecordsZeroQuality() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedFundedTask(client, agent[1]);

        // A payload that cannot satisfy the declared TEXT contract.
        TaskModel withResult = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "", null, Instant.now()));
        taskRepository.save(withResult);
        validationAppService.validateAndGate(withResult);

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.SPEC_VIOLATION);
            assertThat(e.quality()).isEqualByComparingTo("0.000");
        });
        // (2.5 + 0) / 6 = 0.4167 reliability → 0.7·41.67 + 0.3·50 = 44.17
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("44.17");
    }

    @Test
    void anExecutionTimeoutRecordsZeroQuality() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedFundedTask(client, agent[1]);

        reliabilityAppService.timeoutOne(task.id());

        assertThat(eventsFor(agent[0])).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.EXECUTION_TIMEOUT);
            assertThat(e.quality()).isEqualByComparingTo("0.000");
        });
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("44.17");
    }

    /** A failure still falls under L1: a builder cannot damage a rival by proxy of their own work. */
    @Test
    void theSelfDealingExclusionAppliesToFailuresToo() {
        UUID builder = newUser("BUILDER");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", builder);
        UUID[] agent = newAgent(builder);
        TaskModel task = seedFundedTask(builder, agent[1]);

        reliabilityAppService.timeoutOne(task.id());

        assertThat(eventsFor(agent[0])).isEmpty();
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("50.00");
    }

    /**
     * Recovery is earned by working. A run of failures followed by successes climbs back, which is
     * why the model needs no time decay — and why a poor agent cannot launder its record by going
     * idle instead.
     */
    @Test
    void anAgentRecoversFromFailuresByDeliveringGoodWork() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));

        reliabilityAppService.timeoutOne(seedFundedTask(client, agent[1]).id());
        reliabilityAppService.timeoutOne(seedFundedTask(client, agent[1]).id());
        BigDecimal afterFailures = scoreOf(agent[0]);

        for (int i = 0; i < 6; i++) {
            reviewAppService.accept(seedReviewableTask(client, agent[1]).id(), client);
        }

        assertThat(afterFailures).isLessThan(BigDecimal.valueOf(50));
        assertThat(scoreOf(agent[0])).isGreaterThan(afterFailures);
        assertThat(eventsFor(agent[0])).hasSize(8);
    }
}
