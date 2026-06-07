package com.hireai.wallet;

import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.wallet.WalletWriteAppService;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Earnings read against real Postgres + real settlement: accepted tasks earn net-of-commission,
 * rejected/open tasks don't, the self-settle case counts once, strangers see zeros. Settlement
 * is performed through the real TaskReviewAppService so the numbers this view derives from the
 * tasks table are provably consistent with what the wallets actually received.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class BuilderEarningsIntegrationTest {

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

    @Autowired BuilderEarningsReadAppService earningsService;
    @Autowired TaskReviewAppService reviewAppService;
    @Autowired TaskRepository taskRepository;
    @Autowired WalletWriteAppService walletWrite;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, ?)",
                id, id + "@test.local", role);
        return id;
    }

    /** Seed an agent + version owned by {@code builderId} with a visible name. */
    private UUID newAgentVersion(UUID builderId, String name) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, current_version_id)
                VALUES (?, ?, ?, 'ACTIVE', ?)""", agentId, builderId, name, versionId);
        jdbc.update("""
                INSERT INTO agent_versions (id, agent_id, version_number, output_spec,
                                            capability_categories, webhook_url, max_execution_seconds, price)
                VALUES (?, ?, 1, '{"format":"TEXT"}'::jsonb, ARRAY['summarisation'],
                        'https://agent.test/run', 60, 10.00)""", versionId, agentId);
        return versionId;
    }

    /** A RESULT_RECEIVED task with its budget frozen — built from domain transitions (no broker). */
    private TaskModel seedReviewableTask(UUID clientId, UUID versionId, String budget) {
        TaskModel task = TaskModel.submit(clientId, "earn from me", "desc",
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
    void earningsAggregateAcrossAcceptedRejectedAndOpenTasks() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        UUID versionA = newAgentVersion(builder, "Agent A");
        UUID versionB = newAgentVersion(builder, "Agent B");

        TaskModel acceptedTask = seedReviewableTask(client, versionA, "12.00");
        TaskModel rejectedTask = seedReviewableTask(client, versionA, "20.00");
        seedReviewableTask(client, versionB, "20.00"); // stays RESULT_RECEIVED (pending)
        reviewAppService.accept(acceptedTask.id(), client);
        reviewAppService.reject(rejectedTask.id(), client, "not good enough");

        Earnings e = earningsService.earningsFor(builder);

        assertThat(e.lifetimeEarned()).isEqualByComparingTo("10.20");   // net of 12.00
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("17.00"); // net of 20.00
        assertThat(e.paidTaskCount()).isEqualTo(1);

        assertThat(e.perAgent()).hasSize(2);
        var agentA = e.perAgent().stream().filter(a -> a.agentName().equals("Agent A")).findFirst().orElseThrow();
        var agentB = e.perAgent().stream().filter(a -> a.agentName().equals("Agent B")).findFirst().orElseThrow();
        assertThat(agentA.earned()).isEqualByComparingTo("10.20");
        assertThat(agentA.pendingIfAccepted()).isEqualByComparingTo("0.00");
        assertThat(agentB.earned()).isEqualByComparingTo("0.00");
        assertThat(agentB.pendingIfAccepted()).isEqualByComparingTo("17.00");

        assertThat(e.payouts()).hasSize(1);
        assertThat(e.payouts().get(0).taskTitle()).isEqualTo("earn from me");
        assertThat(e.payouts().get(0).agentName()).isEqualTo("Agent A");
        assertThat(e.payouts().get(0).amount()).isEqualByComparingTo("10.20");
        assertThat(e.payouts().get(0).settledAt()).isNotNull();
    }

    @Test
    void selfSettleCountsThePayoutExactlyOnce() {
        UUID builderClient = newUser("BUILDER"); // same person on both sides — legal
        UUID version = newAgentVersion(builderClient, "My Own Agent");
        TaskModel task = seedReviewableTask(builderClient, version, "12.00");

        reviewAppService.accept(task.id(), builderClient);

        Earnings e = earningsService.earningsFor(builderClient);
        assertThat(e.lifetimeEarned()).isEqualByComparingTo("10.20"); // once, not 20.40
        assertThat(e.paidTaskCount()).isEqualTo(1);
        assertThat(e.payouts()).hasSize(1);
    }

    @Test
    void strangerSeesZerosAndEmptyLists() {
        UUID client = newUser("CLIENT");
        UUID builder = newUser("BUILDER");
        TaskModel task = seedReviewableTask(client, newAgentVersion(builder, "Agent A"), "12.00");
        reviewAppService.accept(task.id(), client);

        Earnings e = earningsService.earningsFor(newUser("CLIENT"));

        assertThat(e.lifetimeEarned()).isEqualByComparingTo("0.00");
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("0.00");
        assertThat(e.perAgent()).isEmpty();
        assertThat(e.payouts()).isEmpty();
    }
}
