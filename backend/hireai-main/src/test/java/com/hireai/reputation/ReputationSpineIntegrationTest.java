package com.hireai.reputation;

import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.info.ReputationScore;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;
import com.hireai.domain.biz.reputation.repository.ReputationEventRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reputation spine against real Postgres (Flyway through V27): a client acceptance records an
 * append-only event and moves the agent's score off the 50.00 it has held since registration.
 *
 * <p>Covers the properties that cannot be asserted in a unit test — that the trigger really
 * refuses mutation, that the cached aggregates really agree with a replay of the stream, and that
 * a builder booking their own agent really records nothing.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ReputationSpineIntegrationTest {

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
    @Autowired ReputationWriteAppService reputationWrite;
    @Autowired ReputationEventRepository reputationEvents;
    @Autowired AgentRepository agentRepository;
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

    /** Seeds an agent + ACTIVE version owned by {@code builderId}; returns [agentId, versionId]. */
    private UUID[] newAgent(UUID builderId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, 'Rep IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url,
                                            max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return new UUID[]{agentId, versionId};
    }

    private TaskModel seedReviewableTask(UUID clientId, UUID versionId) {
        TaskModel task = TaskModel.submit(clientId, "rep me", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(versionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                        UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null,
                        Instant.now()))
                .passValidation();
        taskRepository.save(task);
        walletWrite.topUp(clientId, Money.of("100.00"), "setup-topup-" + task.id());
        walletWrite.freeze(clientId, Money.of("20.00"), task.id(), "setup-freeze-" + task.id());
        return task;
    }

    private BigDecimal scoreOf(UUID agentId) {
        return jdbc.queryForObject(
                "SELECT reputation_score FROM agents WHERE id = ?", BigDecimal.class, agentId);
    }

    private long eventCount(UUID agentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reputation_events WHERE agent_id = ?", Long.class, agentId);
    }

    // ------------------------------------------------------------------------------ the cold start

    /**
     * Migration day: V27 backfills every existing agent to zero events, and zero events scores
     * exactly the 50.00 already in the column. Nothing shifts until agents earn events.
     */
    @Test
    void aFreshlyRegisteredAgentSitsAtExactlyFifty() {
        UUID agentId = newAgent(newUser("BUILDER"))[0];

        assertThat(scoreOf(agentId)).isEqualByComparingTo("50.00");

        // Compared field-by-field, not with record equality: BigDecimal.equals is scale-sensitive,
        // so a 0.000 read back from NUMERIC(12,3) is not equal() to a ZERO built in Java.
        ReputationAggregates fresh = agentRepository.findReputationAggregates(agentId).orElseThrow();
        assertThat(fresh.reliabilitySum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fresh.reliabilityCount()).isZero();
        assertThat(fresh.satisfactionSum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fresh.satisfactionCount()).isZero();

        assertThat(reputationWrite.reconcile(agentId).isUnproven()).isTrue();
        assertThat(scoreOf(agentId)).isEqualByComparingTo("50.00");
    }

    // --------------------------------------------------------------------------- the accept path

    @Test
    void acceptingATaskRecordsAnEventAndRaisesTheScore() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);

        reviewAppService.accept(task.id(), client);

        List<ReputationEventModel> events = reputationEvents.findRecentByAgentId(agent[0], 10);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo(ReputationEventType.TASK_ACCEPTED);
            assertThat(e.taskId()).isEqualTo(task.id());
            assertThat(e.quality()).isEqualByComparingTo("1.000");
        });

        // (5·0.5 + 1) / (5 + 1) = 0.5833 reliability; blended with the 0.5 prior → 55.83
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("55.83");
    }

    /**
     * The score is written in the settling transaction, so a routing decision taken immediately
     * afterwards already sees it. No sweeper, no staleness window.
     */
    @Test
    void theScoreIsVisibleTheInstantTheTaskSettles() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));

        for (int i = 0; i < 3; i++) {
            reviewAppService.accept(seedReviewableTask(client, agent[1]).id(), client);
        }

        // (2.5 + 3) / (5 + 3) = 0.6875 → 0.7·68.75 + 0.3·50 = 63.13
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("63.13");
        assertThat(eventCount(agent[0])).isEqualTo(3);
    }

    // ------------------------------------------------------------------- L1 self-dealing exclusion

    /**
     * The cheapest attack on the marketplace, and the one L1 closes: a builder booking their own
     * agent earns nothing at all. Multi-account Sybil farming remains possible and is an accepted,
     * documented limitation (ADR 0003).
     */
    @Test
    void aBuilderBookingTheirOwnAgentRecordsNoEventAtAll() {
        UUID builder = newUser("BUILDER");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", builder);
        UUID[] agent = newAgent(builder);
        TaskModel task = seedReviewableTask(builder, agent[1]);

        reviewAppService.accept(task.id(), builder);

        assertThat(eventCount(agent[0])).isZero();
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("50.00");
    }

    // ------------------------------------------------------------------- append-only (Invariant #2)

    @Test
    void theEventStreamRefusesUpdateAndDelete() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        reviewAppService.accept(seedReviewableTask(client, agent[1]).id(), client);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reputation_events SET quality = 0.000 WHERE agent_id = ?", agent[0]))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM reputation_events WHERE agent_id = ?", agent[0]))
                .hasMessageContaining("append-only");

        assertThat(eventCount(agent[0])).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------ reconciliation

    /**
     * The aggregates on the agents row are a derived cache. This replays the append-only stream
     * and proves they still agree with it — a direct exercise of Invariant #2, and the path that
     * would catch a future emission site that forgot to fold its event in.
     */
    @Test
    void replayingTheStreamReproducesTheCachedAggregates() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        for (int i = 0; i < 4; i++) {
            reviewAppService.accept(seedReviewableTask(client, agent[1]).id(), client);
        }

        ReputationAggregates cached = agentRepository.findReputationAggregates(agent[0]).orElseThrow();
        ReputationAggregates replayed = reputationEvents.replayAggregates(agent[0]);

        assertThat(replayed.reliabilityCount()).isEqualTo(cached.reliabilityCount()).isEqualTo(4);
        assertThat(replayed.reliabilitySum()).isEqualByComparingTo(cached.reliabilitySum());
        assertThat(replayed.satisfactionCount()).isEqualTo(cached.satisfactionCount()).isZero();

        BigDecimal before = scoreOf(agent[0]);
        ReputationScore reconciled = reputationWrite.reconcile(agent[0]);
        assertThat(reconciled.score()).isEqualByComparingTo(before);
    }

    /** A drifted cache is repaired from the stream, which remains the source of truth. */
    @Test
    void reconciliationRepairsADriftedCache() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        reviewAppService.accept(seedReviewableTask(client, agent[1]).id(), client);

        jdbc.update("""
                UPDATE agents SET reliability_sum = 99, reliability_count = 99,
                                  reputation_score = 99.99
                WHERE id = ?""", agent[0]);

        ReputationScore repaired = reputationWrite.reconcile(agent[0]);

        assertThat(repaired.reliabilityCount()).isEqualTo(1);
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("55.83");
    }

    // ------------------------------------------------------------------------------ exactly-once

    @Test
    void thesameOutcomeIsNeverRecordedTwiceForOneTask() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);
        reviewAppService.accept(task.id(), client);

        // A retried settlement must not double-count the same outcome.
        reputationWrite.recordOutcome(task.id(), agent[1], client, ReputationEventType.TASK_ACCEPTED);

        assertThat(eventCount(agent[0])).isEqualTo(1);
        assertThat(scoreOf(agent[0])).isEqualByComparingTo("55.83");
    }
}
