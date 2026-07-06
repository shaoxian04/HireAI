package com.hireai.task.routing;

import com.hireai.application.biz.offering.agent.AgentWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration suite for the matching engine + reliability sweepers (spec:
 * docs/superpowers/specs/2026-07-04-matching-engine-design.md, tests 35-38). Modeled on
 * {@link RoutingIntegrationTest}: a real Postgres (Flyway V1-V24) and a real RabbitMQ, both
 * Testcontainers, auto-skipping without Docker. {@code hireai.matching.epsilon} is forced to 0
 * so {@code selectOne} is a pure argmax over the scored candidates (no exploration sampling) —
 * every winner assertion below is exact, not probabilistic.
 *
 * <p>Runs under the {@code test} profile only (not {@code dev}, unlike RoutingIntegrationTest):
 * {@code CapacityRematchSweeper}/{@code ExecutionTimeoutSweeper} are {@code @Profile("!test")},
 * so cases 36/37 below drive {@link TaskReliabilityAppService#rematchOne} directly instead of
 * racing a background sweep. None of the seeded agents' webhooks are ever actually reachable
 * (dummy https URLs, never asserted on) — every case here asserts on the routing DECISION
 * (agent_version_id / status / wallet / settlement), never on a completed agent callback, so a
 * failed/DLQ'd dispatch attempt in the background never affects an assertion.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class MatchingEngineIntegrationTest {

    /** Skip (do not fail) the whole class when no Docker daemon is reachable. */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        // Force the winner selection to be a deterministic argmax (case 35 hand-computes exact
        // scores below; any epsilon>0 would make the pick probabilistic).
        registry.add("hireai.matching.epsilon", () -> "0");
    }

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReliabilityAppService taskReliabilityAppService;
    @Autowired AgentWriteAppService agentWriteAppService;
    @Autowired AgentRepository agentRepository;
    @Autowired WalletReadAppService walletReadAppService;
    @Autowired JdbcTemplate jdbc;

    /**
     * The Testcontainers Postgres is shared across this class's test methods (static
     * @Container), so seeded tasks/agents persist between tests unless cleared. Reset before
     * each test so the candidate query's per-agent in_flight/sample_count never leaks across
     * cases (same rationale as CandidateCountsIntegrationTest's clearRoutableData).
     * agent_profiles is cleared too: unlike CandidateCountsIntegrationTest (which seeds agents
     * via raw JDBC), this suite registers agents through the real AgentWriteAppService, which
     * also creates a storefront/agent_profiles row referencing the agent (FK).
     */
    @BeforeEach
    void clearRoutableData() {
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM agent_profiles");
        jdbc.update("DELETE FROM agent_versions");
        jdbc.update("DELETE FROM agents");
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", id);
        return id;
    }

    private void seedWallet(UUID clientId, String availableBalance) {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, ?, 0.00)",
                UUID.randomUUID(), clientId, new BigDecimal(availableBalance));
    }

    private TaskSubmitInfo info(UUID clientId, String category, String budget) {
        return new TaskSubmitInfo(clientId, "Summarise report", "Summarise the attached quarterly report",
                Money.of(budget), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"),
                category);
    }

    private record AgentSeed(UUID agentId, UUID versionId) {}

    /**
     * One ACTIVE agent, registered + activated through the real app service (so its reputation
     * defaults to {@code AgentModel.DEFAULT_REPUTATION} = 50.00 rather than a hand-set value),
     * advertising {category} at {price} with a declared {maxConcurrent}. The webhook is a dummy
     * https URL — it is never actually reachable, which is fine (see class javadoc).
     */
    private AgentSeed registerActiveAgent(String category, String price, int maxConcurrent, String webhookUrl) {
        UUID owner = newBuilder();
        UUID agentId = agentWriteAppService.register(new AgentRegisterInfo(owner, "Matching Agent",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of(category), webhookUrl, 60, new BigDecimal(price), maxConcurrent));
        agentWriteAppService.activate(agentId, owner);
        UUID versionId = agentRepository.findById(agentId).orElseThrow().currentVersion().id();
        return new AgentSeed(agentId, versionId);
    }

    /**
     * Inserts {count} filler tasks pointed at {agentVersionId} in {status}, purely to drive the
     * candidate query's in_flight (QUEUED/EXECUTING) or sample_count (RESOLVED/FAILED/
     * TIMED_OUT/SPEC_VIOLATION) subqueries — mirrors CandidateCountsIntegrationTest's insertTask.
     */
    private void seedFillerTasks(UUID clientId, UUID agentVersionId, String status, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, " +
                            "agent_version_id) VALUES (?, ?, 'filler', 'filler', 1.00, ?::jsonb, ?, ?)",
                    UUID.randomUUID(), clientId, "{\"format\":\"JSON\"}", status, agentVersionId);
        }
    }

    // Case 35: three ACTIVE agents in the same category, all within budget (30.00), engineered so
    // every scoring factor except reputation differs:
    //   Agent A: price 10.00, idle (0 in-flight), 0 samples.
    //   Agent B: price 25.00, 4 in-flight (of maxConcurrent 5), 100 samples.
    //   Agent C: price 28.00, idle (0 in-flight), 50 samples.
    // All three are freshly registered, so reputation = AgentModel.DEFAULT_REPUTATION = 50.00 for
    // each — its 0.4-weighted contribution (0.4*0.5=0.20) is IDENTICAL across all three and never
    // changes the ranking. Hand-computed scores (weights 0.4 rep / 0.2 value / 0.2 load / 0.2
    // explore; valueFit=(budget-price)/budget, loadHeadroom=1-inFlight/maxConcurrent,
    // exploration=1/(1+sampleCount)):
    //   A: valueFit=(30-10)/30=0.6667, load=1-0/5=1.0000, explore=1/(1+0)  =1.0000
    //      score = 0.20 + 0.2*0.6667 + 0.2*1.0000 + 0.2*1.0000 = 0.7333
    //   B: valueFit=(30-25)/30=0.1667, load=1-4/5=0.2000, explore=1/(1+100)=0.0099
    //      score = 0.20 + 0.2*0.1667 + 0.2*0.2000 + 0.2*0.0099 = 0.2753
    //   C: valueFit=(30-28)/30=0.0667, load=1-0/5=1.0000, explore=1/(1+50) =0.0196
    //      score = 0.20 + 0.2*0.0667 + 0.2*1.0000 + 0.2*0.0196 = 0.4173
    // Agent A wins outright (0.7333 > 0.4173 > 0.2753); epsilon=0 makes selectOne a pure argmax,
    // so the winner is exact, not probabilistic.
    @Test
    void scoredMatcherPicksTheHighestWeightedCandidate() {
        UUID client = newClient();
        seedWallet(client, "100.00");
        UUID filler = newClient();
        String category = "summarisation";

        AgentSeed agentA = registerActiveAgent(category, "10.00", 5, "https://unused.example/hook-a");
        AgentSeed agentB = registerActiveAgent(category, "25.00", 5, "https://unused.example/hook-b");
        seedFillerTasks(filler, agentB.versionId(), "QUEUED", 4);
        seedFillerTasks(filler, agentB.versionId(), "RESOLVED", 100);
        AgentSeed agentC = registerActiveAgent(category, "28.00", 5, "https://unused.example/hook-c");
        seedFillerTasks(filler, agentC.versionId(), "RESOLVED", 50);

        UUID taskId = taskWriteAppService.submit(info(client, category, "30.00"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            UUID assigned = jdbc.queryForObject(
                    "SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
            assertThat(assigned).isNotNull();
        });

        UUID assignedVersion = jdbc.queryForObject(
                "SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
        assertThat(assignedVersion).isEqualTo(agentA.versionId());
    }

    // Case 36: submit into a category with NO eligible agent -> AWAITING_CAPACITY. Then a
    // matching agent is registered+activated (capacity now exists), and
    // TaskReliabilityAppService.rematchOne is called directly (the CapacityRematchSweeper is
    // @Profile("!test") and never runs here) -> the task is rescued into QUEUED/EXECUTING.
    @Test
    void rematchRescuesAnAwaitingCapacityTaskOnceCapacityAppears() {
        UUID client = newClient();
        seedWallet(client, "100.00");
        String category = "rescue-cat";

        UUID taskId = taskWriteAppService.submit(info(client, category, "30.00"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isEqualTo("AWAITING_CAPACITY");
        });

        AgentSeed rescuer = registerActiveAgent(category, "10.00", 5, "https://unused.example/hook-rescue");

        taskReliabilityAppService.rematchOne(taskId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isIn("QUEUED", "EXECUTING");
        });
        UUID assignedVersion = jdbc.queryForObject(
                "SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
        assertThat(assignedVersion).isEqualTo(rescuer.versionId());
    }

    // Case 37: submit into a category that NEVER gets an eligible agent. Three rematchOne calls
    // exhaust the default rematch-max-attempts=3 bound -> CANCELLED with a full escrow refund:
    // the client wallet's available balance returns to exactly its pre-submit value, and a
    // REJECT settlement row is recorded for the task (Hard Invariants #1/#2).
    @Test
    void exhaustedRematchCancelsAndFullyRefundsEscrow() {
        UUID client = newClient();
        seedWallet(client, "100.00");
        String category = "exhausted-cat";

        Money preSubmitBalance = walletReadAppService.getByUserId(client).available();

        UUID taskId = taskWriteAppService.submit(info(client, category, "30.00"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isEqualTo("AWAITING_CAPACITY");
        });

        taskReliabilityAppService.rematchOne(taskId); // attempt 1 of 3
        taskReliabilityAppService.rematchOne(taskId); // attempt 2 of 3
        taskReliabilityAppService.rematchOne(taskId); // attempt 3 of 3 -> exhausted -> CANCELLED + refund

        String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(status).isEqualTo("CANCELLED");

        Money postExhaustionBalance = walletReadAppService.getByUserId(client).available();
        assertThat(postExhaustionBalance).isEqualTo(preSubmitBalance);

        Integer settlementCount = jdbc.queryForObject(
                "SELECT count(*) FROM settlements WHERE task_id = ? AND type = 'REJECT'", Integer.class, taskId);
        assertThat(settlementCount).isEqualTo(1);
    }

    // Case 38: registering an agent with maxConcurrent=8 through AgentWriteAppService.register
    // flows through to both read paths that feed the scorer's loadHeadroom factor: the aggregate
    // read (agentRepository.findById(...).currentVersion().maxConcurrent()) and the candidate
    // read (findActiveCandidates(...).maxConcurrent()). Registration alone leaves the agent
    // PENDING_VERIFICATION (not routable), so it is activated first.
    @Test
    void registeredMaxConcurrentFlowsThroughToTheCandidateRead() {
        UUID owner = newBuilder();
        String category = "capacity-cat";
        UUID agentId = agentWriteAppService.register(new AgentRegisterInfo(owner, "Capacity Agent",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of(category), "https://unused.example/hook-capacity", 60, new BigDecimal("10.00"), 8));
        agentWriteAppService.activate(agentId, owner);

        AgentModel agent = agentRepository.findById(agentId).orElseThrow();
        assertThat(agent.currentVersion().maxConcurrent()).isEqualTo(8);

        List<AgentCandidate> candidates =
                agentRepository.findActiveCandidates(category, new BigDecimal("30.00"));
        AgentCandidate candidate = candidates.stream()
                .filter(c -> c.agentId().equals(agentId))
                .findFirst()
                .orElseThrow();
        assertThat(candidate.maxConcurrent()).isEqualTo(8);
    }
}
