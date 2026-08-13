package com.hireai.reputation;

import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.reputation.ReviewAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
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
import com.hireai.utility.exception.DomainException;
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
 * The earned-review flow (#40): a client may rate a task they accepted, exactly once, and that
 * rating is the only thing feeding Satisfaction.
 *
 * <p>The fabricated V7 seeds are gone and reviews.task_id is NOT NULL UNIQUE, so every star on the
 * site now belongs to work a client approved and paid for in full.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class EarnedReviewIntegrationTest {

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

    @Autowired ReviewAppService reviewService;
    @Autowired TaskReviewAppService taskReviewAppService;
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
                VALUES (?, ?, 'Review IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url,
                                            max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return new UUID[]{agentId, versionId};
    }

    private TaskModel seedReviewableTask(UUID clientId, UUID versionId) {
        TaskModel task = TaskModel.submit(clientId, "rate me", "desc", Money.of("20.00"),
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

    /** Accepts a task and returns it, so a rating is legal on it. */
    private TaskModel acceptedTask(UUID clientId, UUID versionId) {
        TaskModel task = seedReviewableTask(clientId, versionId);
        taskReviewAppService.accept(task.id(), clientId);
        return task;
    }

    private BigDecimal scoreOf(UUID agentId) {
        return jdbc.queryForObject(
                "SELECT reputation_score FROM agents WHERE id = ?", BigDecimal.class, agentId);
    }

    // ------------------------------------------------------------------- the fabricated seeds

    /**
     * V7 seeded three invented 4–5★ reviews per agent, attributed to the demo client with a NULL
     * task. V28 purges them: a client comparing agents must not be reading testimonials we wrote.
     */
    @Test
    void noFabricatedReviewsSurviveAndNoneCanBeCreated() {
        Long orphans = jdbc.queryForObject(
                "SELECT count(*) FROM reviews WHERE task_id IS NULL", Long.class);
        assertThat(orphans).isZero();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reviews (id, client_id, agent_id, rating, review_text)
                VALUES (?, ?, ?, 5, 'fabricated')""",
                UUID.randomUUID(), newUser("CLIENT"), newAgent(newUser("BUILDER"))[0]))
                .isInstanceOf(Exception.class);
    }

    // ------------------------------------------------------------------------ the happy path

    @Test
    void ratingAnAcceptedTaskMovesSatisfactionAndTheScore() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = acceptedTask(client, agent[1]);

        BigDecimal afterAccept = scoreOf(agent[0]);
        reviewService.review(task.id(), client, 5, "Exactly what I asked for.");

        List<ReputationEventModel> events = reputationEvents.findRecentByAgentId(agent[0], 10);
        assertThat(events).extracting(ReputationEventModel::eventType)
                .containsExactlyInAnyOrder(ReputationEventType.TASK_ACCEPTED,
                        ReputationEventType.RATING);
        assertThat(events).filteredOn(e -> e.eventType() == ReputationEventType.RATING)
                .singleElement()
                .satisfies(e -> assertThat(e.quality()).isEqualByComparingTo("1.000"));

        assertThat(scoreOf(agent[0])).isGreaterThan(afterAccept);
    }

    /** Stars map linearly onto the unit interval: 1★ asserts 0.0, so it pulls the score down. */
    @Test
    void aOneStarRatingLowersTheScoreWithoutTouchingReliability() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = acceptedTask(client, agent[1]);

        BigDecimal afterAccept = scoreOf(agent[0]);
        reviewService.review(task.id(), client, 1, "Technically conformant, useless to me.");

        assertThat(scoreOf(agent[0])).isLessThan(afterAccept);

        // Reliability is untouched — the platform still witnessed a delivered, accepted task.
        assertThat(reputationEvents.replayAggregates(agent[0]).reliabilityCount()).isEqualTo(1);
        assertThat(reputationEvents.replayAggregates(agent[0]).satisfactionCount()).isEqualTo(1);
    }

    /**
     * THE property the two-component split exists to preserve. An agent nobody rated sits at the
     * neutral prior on Satisfaction, never at the ceiling — so silence reads as unknown, and
     * soliciting reviews is positive-expected-value for an agent that is actually good.
     */
    @Test
    void anUnratedAgentSitsAtTheNeutralPriorAndGoodReviewsBeatSilence() {
        UUID client = newUser("CLIENT");
        UUID[] rated = newAgent(newUser("BUILDER"));
        UUID[] silent = newAgent(newUser("BUILDER"));

        for (int i = 0; i < 5; i++) {
            TaskModel t = acceptedTask(client, rated[1]);
            reviewService.review(t.id(), client, 4, "good");
            acceptedTask(client, silent[1]);
        }

        assertThat(reputationEvents.replayAggregates(silent[0]).satisfactionCount()).isZero();
        // Identical delivery records; the reviewed one wins because silence is not perfection.
        assertThat(scoreOf(rated[0])).isGreaterThan(scoreOf(silent[0]));
    }

    // ---------------------------------------------------------------------------- the gates

    @Test
    void aTaskCanBeRatedOnlyOnce() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = acceptedTask(client, agent[1]);
        reviewService.review(task.id(), client, 5, "first");

        assertThatThrownBy(() -> reviewService.review(task.id(), client, 1, "second"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already been reviewed");
    }

    /** Invariant #5: a foreign task must be indistinguishable from a missing one. */
    @Test
    void aTaskTheCallerDoesNotOwnIsIndistinguishableFromMissing() {
        UUID owner = newUser("CLIENT");
        UUID stranger = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = acceptedTask(owner, agent[1]);

        assertThatThrownBy(() -> reviewService.review(task.id(), stranger, 5, "nice"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Task not found");
    }

    /**
     * A task that never reached an acceptance is not rateable. The dispute was the client's formal
     * channel; reopening it informally would let a losing client retaliate against the ruling, or
     * punish the same failure twice across both components.
     */
    @Test
    void aTaskThatWasNotAcceptedIsNotRateable() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel pending = seedReviewableTask(client, agent[1]);

        assertThatThrownBy(() -> reviewService.review(pending.id(), client, 5, "nice"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Only a task you accepted");
    }

    /** Changed-mind settles like an acceptance but is not an acceptance, and is not rateable. */
    @Test
    void aChangedMindRejectionIsNotRateable() {
        UUID client = newUser("CLIENT");
        UUID[] agent = newAgent(newUser("BUILDER"));
        TaskModel task = seedReviewableTask(client, agent[1]);
        taskReviewAppService.reject(task.id(), client, RejectReason.D_CHANGED_MIND, "changed my mind");

        assertThatThrownBy(() -> reviewService.review(task.id(), client, 5, "nice"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Only a task you accepted");
    }

    /** A builder cannot inflate their own agent's star average from their own account. */
    @Test
    void aBuilderCannotReviewTheirOwnAgent() {
        UUID builder = newUser("BUILDER");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", builder);
        UUID[] agent = newAgent(builder);
        TaskModel task = acceptedTask(builder, agent[1]);

        assertThatThrownBy(() -> reviewService.review(task.id(), builder, 5, "I am great"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("your own agent");

        Long reviews = jdbc.queryForObject(
                "SELECT count(*) FROM reviews WHERE agent_id = ?", Long.class, agent[0]);
        assertThat(reviews).isZero();
    }
}
