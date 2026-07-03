package com.hireai.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end dispute flow against the synchronous StubArbitrationClient (Testcontainers Postgres;
 * Flyway V1–V23). All four reject paths are covered:
 * <ul>
 *   <li>A_MISMATCH  → NOT_FULFILLED ruling → full refund (SettlementType.REJECT)</li>
 *   <li>B_FACTUAL   → PARTIALLY_FULFILLED ruling → SPLIT (client 50.00 + builder 42.50)</li>
 *   <li>C_INCOMPLETE → FULFILLED ruling → 85/15 payout (SettlementType.ACCEPT)</li>
 *   <li>D_CHANGED_MIND → no dispute, direct 85/15 charge (SettlementType.ACCEPT)</li>
 * </ul>
 * Plus a null-reason guard: reject() with a null category must throw DomainException.
 *
 * <p>Delayed settlement (A/B/C only): the stub arbitrator's ruling is a PROPOSAL — {@code reject()}
 * leaves the task DISPUTED and the dispute RULED, with escrow still held. Each A/B/C test therefore
 * asserts the RULED/held state first, then calls {@code disputeAppService.acceptRuling(...)} to
 * settle (exactly once) before asserting the money movement. D_CHANGED_MIND never opens a dispute,
 * so it still settles synchronously inside {@code reject()}.
 *
 * <p>Seeding mirrors {@code TaskSettlementIntegrationTest}: tasks are driven through domain
 * transitions directly (no app-service submission) to avoid triggering the RabbitMQ routing
 * listener. Client and builder are always distinct users so builder-payout assertions are
 * meaningful. Skips (not fails) when no Docker daemon is reachable.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class DisputeFlowIntegrationTest {

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
    @Autowired TaskRepository taskRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired SettlementRepository settlementRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;

    /** IDs for the seeded scenario; client and builder are always distinct users. */
    record Fixture(UUID taskId, UUID clientId, UUID builderId) {}

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    /**
     * Seeds an agent + version owned by {@code builderId} via raw JDBC.
     * TEXT output spec avoids triggering the JSON-schema validation path in the gate.
     * The agent is ACTIVE so {@code AgentRepository.findOwnerByVersionId} resolves the
     * builder during settlement without a live dispatch round-trip.
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
     * no TaskSubmittedDomainEvent fires and no RabbitMQ broker is required. The builder
     * wallet is opened lazily by the settlement service on first payout.
     */
    private Fixture seedPendingReview(Money budget) {
        UUID clientId = newUser("CLIENT");
        UUID builderId = newUser("BUILDER");
        UUID agentVersionId = newAgentVersion(builderId);

        TaskModel task = TaskModel.submit(clientId, "Dispute IT task", "desc",
                        budget, new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(agentVersionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()))
                .passValidation();
        taskRepository.save(task);

        // Fund and freeze the client wallet so the escrow exactly covers the budget.
        walletWrite.topUp(clientId, budget, "seed-topup-" + task.id());
        walletWrite.freeze(clientId, budget, task.id(), "seed-freeze-" + task.id());

        return new Fixture(task.id(), clientId, builderId);
    }

    /**
     * A_MISMATCH → StubArbitrationClient proposes NOT_FULFILLED → RULED (no settlement yet, escrow
     * held). Client accepts the ruling → settles once → full refund.
     * Expected: task RESOLVED, dispute RESOLVED, client fully refunded, settlement REJECT.
     */
    @Test
    void mismatchDisputeRefundsClientInFull() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.A_MISMATCH, "wrong output");

        // Arbitrator's ruling is a PROPOSAL: dispute RULED, task still DISPUTED, escrow still held.
        TaskModel disputedTask = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(disputedTask.status()).isEqualTo(TaskStatus.DISPUTED);
        UUID disputeId = disputeRepository.findByTaskId(f.taskId()).orElseThrow().id();
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status())
                .isEqualTo(DisputeStatus.RULED);

        WalletModel heldWallet = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(heldWallet.available()).isEqualTo(Money.ZERO);
        assertThat(heldWallet.escrow()).isEqualTo(Money.of("100.00")); // still held

        // Client accepts the proposed ruling -> settles exactly once.
        disputeAppService.acceptRuling(disputeId, f.clientId());

        TaskModel task = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(disputeRepository.findByTaskId(f.taskId()).orElseThrow().status())
                .isEqualTo(DisputeStatus.RESOLVED);

        WalletModel client = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(client.available()).isEqualTo(Money.of("100.00")); // fully refunded
        assertThat(client.escrow()).isEqualTo(Money.ZERO);

        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.REJECT);
    }

    /**
     * B_FACTUAL → StubArbitrationClient proposes PARTIALLY_FULFILLED → RULED (no settlement yet).
     * Client accepts the ruling → settles once → SPLIT.
     * Expected: settlement SPLIT, client refunded 50.00, builder receives 42.50 (50% net of 15% commission).
     */
    @Test
    void factualDisputeSplits() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.B_FACTUAL, "some errors");

        UUID disputeId = disputeRepository.findByTaskId(f.taskId()).orElseThrow().id();
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status())
                .isEqualTo(DisputeStatus.RULED);
        WalletModel heldWallet = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(heldWallet.escrow()).isEqualTo(Money.of("100.00")); // still held pending accept

        disputeAppService.acceptRuling(disputeId, f.clientId());

        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.SPLIT);

        WalletModel client = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(client.available()).isEqualTo(Money.of("50.00")); // half refunded

        WalletModel builder = walletRepository.findByUserId(f.builderId()).orElseThrow();
        assertThat(builder.available()).isEqualTo(Money.of("42.50")); // 50.00 × (1 − 0.15) = 42.50

        // task must self-describe the split outcome
        TaskModel task = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(task.resolution()).isEqualTo(TaskResolution.PARTIALLY_ACCEPTED);
    }

    /**
     * C_INCOMPLETE → StubArbitrationClient proposes FULFILLED → RULED (no settlement yet).
     * Client accepts the ruling → settles once → 85/15 payout.
     * Expected: settlement ACCEPT, builder receives 85.00 (100.00 × (1 − 0.15)).
     */
    @Test
    void incompleteDisputePaysBuilder() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.C_INCOMPLETE, "missing parts");

        UUID disputeId = disputeRepository.findByTaskId(f.taskId()).orElseThrow().id();
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status())
                .isEqualTo(DisputeStatus.RULED);

        disputeAppService.acceptRuling(disputeId, f.clientId());

        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.ACCEPT);

        WalletModel builder = walletRepository.findByUserId(f.builderId()).orElseThrow();
        assertThat(builder.available()).isEqualTo(Money.of("85.00"));
    }

    /**
     * D_CHANGED_MIND → buyer's remorse path: no dispute opened, 85/15 charge (settlement ACCEPT).
     * Expected: no disputes row, builder receives 85.00, settlement ACCEPT.
     */
    @Test
    void changedMindCharges() {
        Fixture f = seedPendingReview(Money.of("100.00"));
        reviewAppService.reject(f.taskId(), f.clientId(), RejectReason.D_CHANGED_MIND, "not needed");

        assertThat(disputeRepository.findByTaskId(f.taskId())).isEmpty(); // no dispute opened

        WalletModel builder = walletRepository.findByUserId(f.builderId()).orElseThrow();
        assertThat(builder.available()).isEqualTo(Money.of("85.00"));

        SettlementModel s = settlementRepository.findByTaskId(f.taskId()).orElseThrow();
        assertThat(s.type()).isEqualTo(SettlementType.ACCEPT);
    }

    /**
     * A null reason category must be rejected immediately (before any wallet or dispute state is touched).
     */
    @Test
    void nullReasonCategoryIsRejected() {
        Fixture f = seedPendingReview(Money.of("100.00"));

        assertThatThrownBy(() -> reviewAppService.reject(f.taskId(), f.clientId(), null, "no category"))
                .isInstanceOf(DomainException.class);
    }
}
