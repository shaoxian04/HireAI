package com.hireai.task;

import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V4. Drives the
 * Task-side routing/execution flow: submit → assignAndQueue → markExecuting → token-verified
 * callback → RESULT_RECEIVED + persisted task_results; plus the bad-token rejection path.
 * The {@link DispatchTokenService} port is mocked (Track B owns the real HMAC impl).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class AgentCallbackIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;

    @MockBean DispatchTokenService dispatchTokenService;

    /**
     * This test drives the task lifecycle MANUALLY (submit → assignAndQueue → markExecuting) to set
     * up the EXECUTING precondition for the callback under test. Mock out auto-routing (which now
     * fires on TaskSubmittedDomainEvent after commit) so it does not pre-empt the manual flow by
     * marking the un-matched task AWAITING_CAPACITY. Routing itself is covered by RoutingIntegrationTest.
     */
    @MockBean RoutingAppService routingAppService;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    private UUID submitExecutingTask(UUID client, UUID agentVersionId) {
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");
        UUID taskId = taskWriteAppService.submit(new TaskSubmitInfo(
                client, "Summarise", "Summarise the attached report", Money.of("30.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"), "summarisation"));
        taskWriteAppService.assignAndQueue(taskId, agentVersionId);
        taskExecutionPort.markExecuting(taskId);
        return taskId;
    }

    @Test
    void validTokenCallbackRecordsResultAndPersistsTaskResults() {
        UUID client = newClient();
        UUID agentVersionId = UUID.randomUUID();
        UUID taskId = submitExecutingTask(client, agentVersionId);

        when(dispatchTokenService.verify(eq("good")))
                .thenReturn(new DispatchTokenClaims(taskId, agentVersionId, Instant.now().plusSeconds(120)));

        agentCallbackAppService.recordResult(taskId, "good",
                new AgentResultInfo("COMPLETED", "{\"summary\":\"ok\"}", "https://x/y", "done"));

        TaskModel task = taskReadAppService.getForClient(taskId, client);
        assertThat(task.status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(task.result()).isNotNull();
        assertThat(task.result().agentStatus()).isEqualTo("COMPLETED");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ? AND agent_status = 'COMPLETED'",
                Integer.class, taskId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void badTokenCallbackIsRejectedAndNoResultIsPersisted() {
        UUID client = newClient();
        UUID agentVersionId = UUID.randomUUID();
        UUID taskId = submitExecutingTask(client, agentVersionId);

        when(dispatchTokenService.verify(any())).thenThrow(new DispatchTokenInvalidException("bad"));

        assertThatThrownBy(() -> agentCallbackAppService.recordResult(taskId, "bad",
                new AgentResultInfo("COMPLETED", "{}", null, null)))
                .isInstanceOf(DispatchTokenInvalidException.class);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ?", Integer.class, taskId);
        assertThat(rows).isZero();
        assertThat(taskReadAppService.getForClient(taskId, client).status()).isEqualTo(TaskStatus.EXECUTING);
    }
}
