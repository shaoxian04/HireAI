package com.hireai.admin;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end admin backstop: seeds an ESCALATED dispute (no money moved) and verifies that
 * {@code adminRule} settles once + resolves, that a second ruling is rejected (409-mapped conflict),
 * and that the sweeper's service path (staleArbitratingDisputeIds → escalate) flips an aged
 * ARBITRATING dispute to ESCALATED. Runs under the {@code test} profile (synchronous stub arbitrator
 * + permissive security); the dispute is seeded directly so it stays un-settled for the admin to rule.
 * Skips (not fails) without Docker.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class AdminDisputeFlowIntegrationTest {

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

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired DisputeAppService disputeAppService;
    @Autowired AdminReadAppService adminReadAppService;
    @Autowired TaskReviewAppService reviewAppService;
    @Autowired TaskRepository taskRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;

    record Fixture(UUID taskId, UUID clientId, UUID disputeId) {}

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    private UUID newAgentVersion(UUID builderId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, 'Admin IT Agent', 'ACTIVE', ?)""", agentId, builderId, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url, max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return versionId;
    }

    /** Seeds a DISPUTED task with the budget frozen in escrow + an ESCALATED dispute (no money moved). */
    private Fixture seedEscalatedDispute(Money budget) {
        UUID clientId = newUser("CLIENT");
        UUID builderId = newUser("BUILDER");
        UUID agentVersionId = newAgentVersion(builderId);

        TaskModel task = TaskModel.submit(clientId, "Admin backstop task", "desc",
                        budget, new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(agentVersionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.record(task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "backstop");
        taskRepository.save(task);

        walletWrite.topUp(clientId, budget, "seed-topup-" + task.id());
        walletWrite.freeze(clientId, budget, task.id(), "seed-freeze-" + task.id());

        DisputeModel dispute = DisputeModel.open(task.id(), clientId, RejectReason.A_MISMATCH,
                        "dispute-" + task.id())
                .startArbitrating()
                .escalate();
        disputeRepository.save(dispute);

        return new Fixture(task.id(), clientId, dispute.id());
    }

    /**
     * Seeds a PENDING_REVIEW task for {@code clientId} with the budget frozen in escrow — no
     * dispute yet. Used by the full client-reject → propose → appeal → admin-override flow, which
     * drives the dispute through the app services rather than constructing it directly.
     */
    private UUID seedReviewableTask(UUID clientId, Money budget) {
        UUID builderId = newUser("BUILDER");
        UUID agentVersionId = newAgentVersion(builderId);

        TaskModel task = TaskModel.submit(clientId, "Appeal flow task", "desc",
                        budget, new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(agentVersionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.record(task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null))
                .passValidation();
        taskRepository.save(task);

        walletWrite.topUp(clientId, budget, "seed-topup-" + task.id());
        walletWrite.freeze(clientId, budget, task.id(), "seed-freeze-" + task.id());

        return task.id();
    }

    private int settlementCount(UUID taskId) {
        return jdbc.queryForObject("SELECT count(*) FROM settlements WHERE task_id = ?", Integer.class, taskId);
    }

    @Test
    void adminRuleNotFulfilledRefundsClientAndResolves() {
        Fixture f = seedEscalatedDispute(Money.of("30.00"));

        disputeAppService.adminRule(f.disputeId(), RulingCategory.NOT_FULFILLED, "backstop refund", ADMIN_ID);

        WalletModel client = walletRepository.findByUserId(f.clientId()).orElseThrow();
        assertThat(client.available()).isEqualTo(Money.of("30.00")); // full refund
        assertThat(client.escrow()).isEqualTo(Money.ZERO);

        TaskModel task = taskRepository.findById(f.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.RESOLVED);

        AdminViews.DisputeDetail detail = adminReadAppService.disputeDetail(f.disputeId());
        assertThat(detail.status()).isEqualTo("RESOLVED");
        assertThat(detail.actionable()).isFalse();
        assertThat(detail.rulings()).anyMatch(r -> r.decidedBy().equals("ADMINISTRATOR") && r.tier() == 2);
    }

    @Test
    void adminRuleOnResolvedDisputeThrowsConflict() {
        Fixture f = seedEscalatedDispute(Money.of("30.00"));
        disputeAppService.adminRule(f.disputeId(), RulingCategory.NOT_FULFILLED, "first", ADMIN_ID);

        assertThatThrownBy(() ->
                disputeAppService.adminRule(f.disputeId(), RulingCategory.FULFILLED, "second", ADMIN_ID))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void sweepQueryFindsAgedArbitratingAndEscalates() {
        UUID disputeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO disputes (id, task_id, raised_by, reason_category, status, correlation_id, gmt_create)
                VALUES (?, ?, ?, 'A_MISMATCH', 'ARBITRATING', 'corr', now() - INTERVAL '10 minutes')
                """, disputeId, UUID.randomUUID(), UUID.randomUUID());

        List<UUID> stale = disputeAppService.staleArbitratingDisputeIds(Instant.now().minus(Duration.ofMinutes(2)));
        assertThat(stale).contains(disputeId);

        disputeAppService.escalate(disputeId);
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status())
                .isEqualTo(DisputeStatus.ESCALATED);
    }

    /**
     * Full appeal E2E: client rejects (B_FACTUAL) → dispute opens; the stub arbitrator proposes a
     * ruling synchronously → RULED (no settlement, escrow held). Client appeals → ESCALATED (admin
     * queue), still no settlement. Admin overrides with a DIFFERENT category (NOT_FULFILLED,
     * overriding the arbitrator's tier-1 PARTIALLY_FULFILLED proposal) → the tier-2 ruling is
     * effective (highest tier wins) → settles exactly once, full refund.
     */
    @Test
    void rejectProposeAppealAdmin_settlesExactlyOnce() {
        UUID clientId = newUser("CLIENT");
        UUID taskId = seedReviewableTask(clientId, Money.of("100.00"));

        // client rejects (B_FACTUAL) -> dispute opens; stub proposes a ruling synchronously -> RULED
        reviewAppService.reject(taskId, clientId, RejectReason.B_FACTUAL, "off-topic");
        UUID disputeId = disputeRepository.findByTaskId(taskId).orElseThrow().id();
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status()).isEqualTo(DisputeStatus.RULED);
        assertThat(walletRepository.findByUserId(clientId).orElseThrow().escrow())
                .isEqualTo(Money.of("100.00")); // still held

        // client appeals -> ESCALATED (admin queue), still no settlement
        disputeAppService.appeal(disputeId, clientId);
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status()).isEqualTo(DisputeStatus.ESCALATED);
        assertThat(walletRepository.findByUserId(clientId).orElseThrow().escrow())
                .isEqualTo(Money.of("100.00"));
        assertThat(settlementCount(taskId)).isEqualTo(0);

        // admin overrides to NOT_FULFILLED -> settles once, full refund
        disputeAppService.adminRule(disputeId, RulingCategory.NOT_FULFILLED, "human override", ADMIN_ID);
        assertThat(disputeRepository.findById(disputeId).orElseThrow().status()).isEqualTo(DisputeStatus.RESOLVED);

        WalletModel client = walletRepository.findByUserId(clientId).orElseThrow();
        assertThat(client.available()).isEqualTo(Money.of("100.00")); // refunded
        assertThat(client.escrow()).isEqualTo(Money.ZERO);

        TaskModel task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.RESOLVED);

        assertThat(settlementCount(taskId)).isEqualTo(1); // exactly once
    }
}
