package com.hireai.task;

import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1+V2.
 * Verifies the Task submission slice end-to-end: persistence, the atomic escrow freeze
 * (Hard Invariant #1), rollback on insufficient balance, and the output-spec JSONB
 * round-trip. Each test creates its own client so the shared container carries no
 * cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class TaskSubmissionIntegrationTest {

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

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReadAppService taskReadAppService;
    @Autowired WalletWriteAppService walletWriteAppService;
    @Autowired WalletReadAppService walletReadAppService;
    @Autowired JdbcTemplate jdbc;

    /**
     * This slice asserts the post-submit state in isolation (status SUBMITTED + atomic escrow
     * freeze, Hard Invariant #1). Mock out auto-routing (which now fires on TaskSubmittedDomainEvent
     * after commit) so it does not flip the un-matched task to AWAITING_CAPACITY before the
     * assertion. Routing is covered by RoutingIntegrationTest.
     */
    @MockBean RoutingAppService routingAppService;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    private TaskSubmitInfo info(UUID clientId, String budget) {
        return new TaskSubmitInfo(clientId, "Summarise report", "Summarise the attached quarterly report",
                Money.of(budget), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"),
                "summarisation");
    }

    @Test
    void submitPersistsTaskAndFreezesEscrowAtomically() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");

        UUID taskId = taskWriteAppService.submit(info(client, "30.00"));

        TaskModel task = taskReadAppService.getForClient(taskId, client);
        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("70.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.of("30.00"));

        Integer frozen = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE related_task_id = ? AND entry_type = 'ESCROW_FREEZE'",
                Integer.class, taskId);
        assertThat(frozen).isEqualTo(1);
    }

    @Test
    void insufficientBalanceRollsBackTheTask() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("10.00"), "seed");

        assertThatThrownBy(() -> taskWriteAppService.submit(info(client, "50.00")))
                .isInstanceOf(DomainException.class);

        Integer tasks = jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE client_id = ?", Integer.class, client);
        assertThat(tasks).isZero();
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("10.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.ZERO);
    }

    @Test
    void outputSpecRoundTripsThroughJsonb() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");

        UUID taskId = taskWriteAppService.submit(info(client, "20.00"));

        OutputSpec spec = taskReadAppService.getForClient(taskId, client).outputSpec();
        assertThat(spec.format()).isEqualTo(OutputFormat.JSON);
        assertThat(spec.schema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.acceptanceCriteria()).isEqualTo("valid JSON summary");
    }
}
