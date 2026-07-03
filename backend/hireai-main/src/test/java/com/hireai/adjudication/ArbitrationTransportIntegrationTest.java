package com.hireai.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.infrastructure.messaging.ArbitrationQueues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end arbitration transport test over a REAL Postgres + RabbitMQ (Testcontainers).
 *
 * <p>Boots the DEFAULT (secured) profile — NO {@code @ActiveProfiles("test")} — so the
 * {@code RabbitArbitrationClient} ({@code @Profile("!test")}) and
 * {@code ArbitrationDlqListener} ({@code @Profile("!test")}) are active instead of the
 * synchronous stub. Skips cleanly when no Docker daemon is reachable.</p>
 *
 * <p>Scenarios:</p>
 * <ol>
 *   <li>Round-trip: reject(A_MISMATCH) → ARBITRATING; POST callback NOT_FULFILLED →
 *       RESOLVED + full refund + SettlementType.REJECT.</li>
 *   <li>First-ruling-wins: second POST on already-RESOLVED dispute → 200, no double settlement.</li>
 *   <li>Auth guard: wrong/absent secret → 401, dispute stays ARBITRATING.</li>
 *   <li>DLQ escalation: publish ArbitrationRequestMessage directly to DLQ →
 *       ArbitrationDlqListener escalates the dispute to ESCALATED (admin backstop; no auto-refund).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class ArbitrationTransportIntegrationTest {

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

    /** The shared secret injected via @DynamicPropertySource and used in callback requests. */
    private static final String CALLBACK_SECRET = "test-secret";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("hireai.arbitration.callback-secret", () -> CALLBACK_SECRET);
    }

    @LocalServerPort
    int port;

    @Autowired TestRestTemplate rest;
    @Autowired TaskReviewAppService reviewAppService;
    @Autowired TaskRepository taskRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired SettlementRepository settlementRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("dispatchRabbitTemplate") RabbitTemplate rabbitTemplate;

    /** IDs for the seeded scenario; client and builder are always distinct users. */
    record Fixture(UUID taskId, UUID clientId, UUID builderId) {}

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // -------------------------------------------------------------------------
    // Seeding helpers (mirrored from DisputeFlowIntegrationTest)
    // -------------------------------------------------------------------------

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    /**
     * Seeds an agent + version owned by {@code builderId} via raw JDBC.
     * TEXT output spec avoids triggering JSON-schema validation in the gate.
     * Agent is ACTIVE so AgentRepository.findOwnerByVersionId resolves the builder during settlement.
     */
    private UUID newAgentVersion(UUID builderId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, 'IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url, max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return versionId;
    }

    /**
     * Seeds a PENDING_REVIEW task with the given budget frozen in the client's escrow.
     * Drives the task through domain transitions directly (no TaskWriteAppService call) so
     * no TaskSubmittedDomainEvent fires and no RabbitMQ dispatch queue is involved.
     */
    private Fixture seedPendingReview(Money budget) {
        UUID clientId = newUser("CLIENT");
        UUID builderId = newUser("BUILDER");
        UUID agentVersionId = newAgentVersion(builderId);

        TaskModel task = TaskModel.submit(clientId, "Arbitration IT task", "desc",
                        budget, new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(agentVersionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()))
                .passValidation();
        taskRepository.save(task);

        walletWrite.topUp(clientId, budget, "seed-topup-" + task.id());
        walletWrite.freeze(clientId, budget, task.id(), "seed-freeze-" + task.id());

        return new Fixture(task.id(), clientId, builderId);
    }

    /**
     * Posts an arbitration ruling callback to the real HTTP endpoint.
     * {@code secret} may be null to send no Authorization header.
     */
    private ResponseEntity<String> postRuling(UUID disputeId, String category, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            headers.setBearerAuth(secret);
        }
        String body = "{\"category\":\"" + category + "\",\"rationale\":\"test rationale\"}";
        return rest.postForEntity(
                url("/api/arbitration-callbacks/" + disputeId + "/ruling"),
                new HttpEntity<>(body, headers), String.class);
    }

    // -------------------------------------------------------------------------
    // Scenario 1: publish-then-callback round-trip
    // -------------------------------------------------------------------------

    /**
     * reject(A_MISMATCH) publishes to Rabbit (RabbitArbitrationClient) and returns → task DISPUTED,
     * dispute ARBITRATING, escrow held, no settlement. POST callback NOT_FULFILLED → task RESOLVED,
     * dispute RESOLVED, client fully refunded, settlement REJECT.
     */
    @Test
    void roundTrip_notFulfilledCallbackRefundsClient() {
        Fixture f = seedPendingReview(Money.of("100.00"));

        // Drive to ARBITRATING in-process (no HTTP; reject is permitted via the app service directly)
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.A_MISMATCH, "wrong output");

        // Assert ARBITRATING state: task DISPUTED, dispute ARBITRATING, escrow held, no settlement
        TaskModel task = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.DISPUTED);

        DisputeModel dispute = disputeRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(dispute.status()).isEqualTo(DisputeStatus.ARBITRATING);

        assertThat(settlementRepository.findByTaskId(f.taskId())).isEmpty();

        WalletModel clientBefore = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(clientBefore.escrow()).isEqualTo(Money.of("100.00")); // still frozen

        // POST ruling callback with the correct shared secret
        ResponseEntity<String> resp = postRuling(dispute.id(), "NOT_FULFILLED", CALLBACK_SECRET);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assert full resolution + refund
        TaskModel resolved = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);

        DisputeModel resolvedDispute = disputeRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(resolvedDispute.status()).isEqualTo(DisputeStatus.RESOLVED);

        WalletModel clientAfter = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(clientAfter.available()).isEqualTo(Money.of("100.00")); // fully refunded
        assertThat(clientAfter.escrow()).isEqualTo(Money.ZERO);

        assertThat(settlementRepository.findByTaskId(f.taskId()).orElseThrow().type())
                .isEqualTo(SettlementType.REJECT);
    }

    // -------------------------------------------------------------------------
    // Scenario 2: first-ruling-wins idempotency
    // -------------------------------------------------------------------------

    /**
     * A second POST on an already-RESOLVED dispute returns 200 with no double settlement:
     * wallet balances and settlement row count are unchanged after the second call.
     */
    @Test
    void firstRulingWins_secondCallbackIsIdempotent() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.A_MISMATCH, "idempotency test");
        DisputeModel dispute = disputeRepository.findByTaskId(f.taskId()).orElseThrow();

        // First ruling resolves the dispute
        ResponseEntity<String> first = postRuling(dispute.id(), "NOT_FULFILLED", CALLBACK_SECRET);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        WalletModel afterFirst = walletRepository.findByUserId(f.clientId()).orElseThrow();
        int settlementCount = jdbc.queryForObject(
                "SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, f.taskId());

        // Guard: verify the first ruling actually settled before checking idempotency;
        // without these, the later "unchanged" comparison can pass vacuously if the first callback silently failed.
        assertThat(afterFirst.available()).isEqualTo(Money.of("100.00")); // fully refunded by first ruling
        assertThat(settlementCount).isEqualTo(1); // exactly one settlement row created by first ruling
        assertThat(disputeRepository.findByTaskId(f.taskId()).orElseThrow().status())
                .isEqualTo(DisputeStatus.RESOLVED); // dispute resolved before duplicate callback

        // Second ruling on the now-RESOLVED dispute
        ResponseEntity<String> second = postRuling(dispute.id(), "NOT_FULFILLED", CALLBACK_SECRET);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Wallet balances must be unchanged (no double-refund)
        WalletModel afterSecond = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(afterSecond.available()).isEqualTo(afterFirst.available());
        assertThat(afterSecond.escrow()).isEqualTo(afterFirst.escrow());

        // Settlement row count must be unchanged (no duplicate settlement)
        int settlementCountAfter = jdbc.queryForObject(
                "SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, f.taskId());
        assertThat(settlementCountAfter).isEqualTo(settlementCount);
    }

    // -------------------------------------------------------------------------
    // Scenario 3: callback authentication guard
    // -------------------------------------------------------------------------

    /**
     * Wrong or absent Authorization header → 401; dispute status is unchanged (ARBITRATING).
     * Uses a fresh ARBITRATING dispute so the auth check does not interfere with other scenarios.
     */
    @Test
    void callbackAuth_wrongOrAbsentSecretIs401() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.A_MISMATCH, "auth guard test");
        DisputeModel dispute = disputeRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(dispute.status()).isEqualTo(DisputeStatus.ARBITRATING);

        // Wrong secret → 401
        ResponseEntity<String> wrongSecret = postRuling(dispute.id(), "NOT_FULFILLED", "wrong-secret");
        assertThat(wrongSecret.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // No Authorization header → 401
        ResponseEntity<String> noSecret = postRuling(dispute.id(), "NOT_FULFILLED", null);
        assertThat(noSecret.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Dispute must still be ARBITRATING — no ruling was applied
        DisputeModel still = disputeRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(still.status()).isEqualTo(DisputeStatus.ARBITRATING);
    }

    // -------------------------------------------------------------------------
    // Scenario 4: DLQ fallback (兜底)
    // -------------------------------------------------------------------------

    /**
     * Publishing an ArbitrationRequestMessage directly to the DLQ (bypassing the main queue and
     * any retry logic) triggers ArbitrationDlqListener, which now calls escalate() → the dispute
     * moves to ESCALATED for the human admin backstop. No money moves: escrow stays frozen and the
     * task stays DISPUTED until an admin rules.
     *
     * <p>Uses RabbitMQ's default exchange (empty string) with the queue name as routing key
     * so the message lands in the DLQ directly without going through the main exchange.</p>
     */
    @Test
    void dlqEscalatesDisputeToAdmin() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.A_MISMATCH, "dlq escalate test");
        DisputeModel dispute = disputeRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(dispute.status()).isEqualTo(DisputeStatus.ARBITRATING);

        // Publish directly to the DLQ via the default RabbitMQ exchange (routing key = queue name).
        // correlationId mirrors DisputeAppServiceImpl.openDispute(): "dispute-" + taskId.
        ArbitrationRequestMessage dlqMsg = new ArbitrationRequestMessage(
                dispute.id(),
                f.taskId(),
                "dispute-" + f.taskId(),
                "TEXT",
                null,
                null,
                null,
                "{\"summary\":\"ok\"}",
                null,
                RejectReason.A_MISMATCH.name());
        rabbitTemplate.convertAndSend("", ArbitrationQueues.DLQ, dlqMsg);

        // Awaitility: poll until ArbitrationDlqListener escalates the dispute (async consumer)
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    DisputeModel d = disputeRepository.findById(dispute.id()).orElseThrow();
                    assertThat(d.status()).isEqualTo(DisputeStatus.ESCALATED);
                });

        // No money moved — escrow stays frozen, awaiting the admin backstop (no auto-refund).
        WalletModel wallet = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(wallet.available()).isEqualTo(Money.ZERO);
        assertThat(wallet.escrow()).isEqualTo(Money.of("100.00"));

        // Task stays DISPUTED until an admin rules.
        TaskModel dlqTask = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(dlqTask.status()).isEqualTo(TaskStatus.DISPUTED);
    }
}
