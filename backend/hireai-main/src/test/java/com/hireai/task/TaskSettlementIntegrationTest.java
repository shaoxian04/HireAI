package com.hireai.task;

import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.ledger.wallet.enums.LedgerEntryType;
import com.hireai.domain.biz.ledger.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletLedgerQuery;
import com.hireai.utility.exception.DomainException;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end settlement against real Postgres (Flyway V1–V9, append-only ledger triggers):
 * freeze → accept → 85/15 split with ledger entries sharing one correlation id;
 * freeze → reject → full refund; the exactly-once, ownership, and CONCURRENCY guards
 * (two racing accepts must settle exactly once — the task row lock serializes them).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class TaskSettlementIntegrationTest {

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
    @Autowired TaskRepository taskRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired WalletReadAppService walletRead;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)",
                id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    /**
     * Seed an agent + version owned by {@code builderId}; returns the version id.
     * Uses raw JDBC so no Spring context transaction wrapping is needed, and the
     * agent_version FK in agents.current_version_id is a plain UUID (no DB FK).
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
     * A RESULT_RECEIVED task with its budget already frozen in the client's wallet.
     * The task row is built purely from domain transitions (no separate submit app-service
     * call) to avoid triggering RabbitMQ routing — there is no broker in this IT.
     */
    private TaskModel seedReviewableTask(UUID clientId, UUID versionId, String budget) {
        TaskModel task = TaskModel.submit(clientId, "settle me", "desc",
                        Money.of(budget), new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(versionId)
                .markExecuting();
        task = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()));
        taskRepository.save(task);
        walletWrite.topUp(clientId, Money.of("100.00"), "setup-topup-" + task.id());
        walletWrite.freeze(clientId, Money.of(budget), task.id(), "setup-freeze-" + task.id());
        return task;
    }

    @Test
    void acceptSettlesEscrowToBuilderNetOfCommission() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder), "20.00");

        reviewAppService.accept(task.id(), client);

        WalletModel clientWallet = walletRead.getByUserId(client);
        assertThat(clientWallet.available()).isEqualTo(Money.of("80.00"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);

        WalletModel builderWallet = walletRead.getByUserId(builder); // opened on first payout
        assertThat(builderWallet.available()).isEqualTo(Money.of("17.00"));

        List<LedgerEntryModel> clientLedger = walletRead.getLedger(client, WalletLedgerQuery.firstPage());
        assertThat(clientLedger).extracting(LedgerEntryModel::type)
                .contains(LedgerEntryType.PAYOUT, LedgerEntryType.COMMISSION);
        List<LedgerEntryModel> builderLedger = walletRead.getLedger(builder, WalletLedgerQuery.firstPage());
        assertThat(builderLedger).extracting(LedgerEntryModel::type)
                .containsExactly(LedgerEntryType.PAYOUT);

        TaskModel resolved = taskRepository.findById(task.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.ACCEPTED);
        assertThat(resolved.resolvedAt()).isNotNull();
    }

    @Test
    void rejectRefundsTheFullBudgetAndStoresTheReason() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder), "20.00");

        reviewAppService.reject(task.id(), client, "wrong format");

        WalletModel clientWallet = walletRead.getByUserId(client);
        assertThat(clientWallet.available()).isEqualTo(Money.of("100.00"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);

        TaskModel resolved = taskRepository.findById(task.id()).orElseThrow();
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(resolved.rejectionReason()).isEqualTo("wrong format");
    }

    @Test
    void secondResolutionAttemptIsRejected() {
        UUID client = newUser("CLIENT");
        TaskModel task = seedReviewableTask(client, newAgentVersion(newUser("BUILDER")), "20.00");
        reviewAppService.accept(task.id(), client);

        assertThatThrownBy(() -> reviewAppService.accept(task.id(), client))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
        assertThatThrownBy(() -> reviewAppService.reject(task.id(), client, null))
                .isInstanceOf(DomainException.class);

        // money moved exactly once
        assertThat(walletRead.getByUserId(client).available()).isEqualTo(Money.of("80.00"));
    }

    @Test
    void nonOwnerCannotResolve() {
        UUID client = newUser("CLIENT");
        UUID stranger = newUser("CLIENT");
        walletWrite.topUp(stranger, Money.of("1.00"), "noop-" + stranger); // stranger has a wallet; gate must trip first
        TaskModel task = seedReviewableTask(client, newAgentVersion(newUser("BUILDER")), "20.00");

        assertThatThrownBy(() -> reviewAppService.accept(task.id(), stranger))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void concurrentAcceptsSettleExactlyOnce() throws Exception {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder), "20.00");

        int racers = 2;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        reviewAppService.accept(task.id(), client);
                        wins.incrementAndGet();
                    } catch (DomainException e) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(wins.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);

        // the builder was paid exactly once and escrow emptied exactly once
        assertThat(walletRead.getByUserId(builder).available()).isEqualTo(Money.of("17.00"));
        WalletModel clientWallet = walletRead.getByUserId(client);
        assertThat(clientWallet.available()).isEqualTo(Money.of("80.00"));
        assertThat(clientWallet.escrow()).isEqualTo(Money.ZERO);
        List<LedgerEntryModel> builderLedger = walletRead.getLedger(builder, WalletLedgerQuery.firstPage());
        assertThat(builderLedger).hasSize(1); // exactly one PAYOUT credit
    }
}
