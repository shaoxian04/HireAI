package com.hireai.adjudication;

import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end validation gate: boots Spring against a real Postgres (Testcontainers; Flyway V1–V16).
 * Drives the callback flow to the gate and asserts the two terminal outcomes:
 *
 * <ul>
 *   <li>PASS scenario: a COMPLETED callback with a valid-JSON payload for a JSON-format spec moves
 *       the task from EXECUTING → RESULT_RECEIVED → PENDING_REVIEW and writes a validation_reports
 *       row with verdict PASS.</li>
 *   <li>FAIL scenario: a COMPLETED callback with a non-JSON payload moves the task to SPEC_VIOLATION,
 *       writes verdict FAIL, and auto-refunds the client's escrow.</li>
 * </ul>
 *
 * The {@link DispatchTokenService} port is mocked (HMAC impl is in Plan 2 infra; not needed here).
 * {@link RoutingAppService} is mocked to suppress the auto-routing listener that fires on
 * TaskSubmittedDomainEvent — without this the listener would pre-empt the manual assignAndQueue.
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ValidationGateIntegrationTest {

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
    @Autowired TaskExecutionPort taskExecutionPort;
    @Autowired AgentCallbackAppService agentCallbackAppService;
    @Autowired WalletWriteAppService walletWriteAppService;
    @Autowired WalletReadAppService walletReadAppService;
    @Autowired ValidationReportRepository validationReportRepository;
    @Autowired JdbcTemplate jdbc;

    /** Mocked: RoutingAppService suppresses the auto-routing event listener on task submit. */
    @MockBean RoutingAppService routingAppService;

    /** Mocked: real HMAC impl lives in infra; we just stub the verify call per-test. */
    @MockBean DispatchTokenService dispatchTokenService;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    /**
     * Submits a task, freezes the budget, and drives it to EXECUTING via the standard
     * domain transitions. Returns the task id. The OutputSpec uses JSON format with no schema
     * so the gate relies solely on JSON parseability (no JSON-Schema validation).
     */
    private UUID submitExecutingTask(UUID clientId, UUID agentVersionId, String budget) {
        walletWriteAppService.topUp(clientId, Money.of(budget), "seed-" + clientId);
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, null, "valid JSON output");
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(
                clientId, "Gate test task", "Drive the validation gate", Money.of(budget),
                spec, "summarisation"));
        taskWriteAppService.assignAndQueue(taskId, agentVersionId);
        taskExecutionPort.markExecuting(taskId);
        return taskId;
    }

    /**
     * PASS scenario: a COMPLETED callback with a valid-JSON payload passes the validation gate.
     * Expected: task status == PENDING_REVIEW; a validation_reports row exists with verdict PASS.
     * The spec is JSON-format with no schema, so FORMAT_JSON_PARSEABLE=true + SCHEMA_SKIPPED=true
     * is sufficient for a PASS verdict.
     */
    @Test
    void validJsonResultPassesGateAndMovesToPendingReview() {
        UUID client = newClient();
        UUID agentVersionId = UUID.randomUUID();
        UUID taskId = submitExecutingTask(client, agentVersionId, "30.00");

        when(dispatchTokenService.verify(eq("tok-pass")))
                .thenReturn(new DispatchTokenClaims(taskId, agentVersionId, Instant.now().plusSeconds(120)));

        agentCallbackAppService.recordResult(taskId, "tok-pass",
                new AgentResultInfo("COMPLETED", "{\"result\":\"gate passed\"}", null, "ok"));

        TaskModel task = taskReadAppService.getForClient(taskId, client);
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING_REVIEW);

        ValidationReportModel report = validationReportRepository
                .findByTaskIdAndAttemptNo(taskId, 1)
                .orElseThrow(() -> new AssertionError("Expected a validation_reports row for task " + taskId));
        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(report.taskId()).isEqualTo(taskId);
    }

    /**
     * FAIL scenario: a COMPLETED callback with a non-JSON payload fails the validation gate.
     * Expected: task status == SPEC_VIOLATION; validation_reports row with verdict FAIL;
     * the client's escrow is refunded (available restored to the budgeted amount, escrow zeroed).
     *
     * Wallet arithmetic: topUp(30) → submit/freeze(30) → available=0 / escrow=30
     * After auto-refund: available=30 / escrow=0.
     */
    @Test
    void invalidJsonResultFailsGateAndAutoRefundsClient() {
        UUID client = newClient();
        UUID agentVersionId = UUID.randomUUID();
        UUID taskId = submitExecutingTask(client, agentVersionId, "30.00");

        when(dispatchTokenService.verify(eq("tok-fail")))
                .thenReturn(new DispatchTokenClaims(taskId, agentVersionId, Instant.now().plusSeconds(120)));

        // "not-valid-json" is not parseable JSON → FORMAT_JSON_PARSEABLE=false → FAIL verdict
        agentCallbackAppService.recordResult(taskId, "tok-fail",
                new AgentResultInfo("COMPLETED", "not-valid-json", null, "done"));

        TaskModel task = taskReadAppService.getForClient(taskId, client);
        assertThat(task.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);

        ValidationReportModel report = validationReportRepository
                .findByTaskIdAndAttemptNo(taskId, 1)
                .orElseThrow(() -> new AssertionError("Expected a validation_reports row for task " + taskId));
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(report.taskId()).isEqualTo(taskId);

        // Escrow fully refunded: available == budget, escrow == 0
        WalletModel wallet = walletReadAppService.getByUserId(client);
        assertThat(wallet.available()).isEqualTo(Money.of("30.00"));
        assertThat(wallet.escrow()).isEqualTo(Money.ZERO);
    }
}
