package com.hireai.task;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.exception.DomainException;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V5. Drives the read
 * path of {@link TaskReadAppService#getResult}: an owned RESULT_RECEIVED task returns its result;
 * a non-owner gets NOT_FOUND (existence not leaked); an owned task with no result row gets
 * NOT_FOUND (the UI's "pending" signal). Rows are seeded directly via JdbcTemplate — task_results
 * has no append-only trigger, so a plain INSERT is legitimate and keeps the test free of routing /
 * dispatch-token mocks. Each test uses fresh UUIDs so the shared container carries no cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class TaskResultEndpointIntegrationTest {

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

    @Autowired TaskReadAppService taskReadAppService;
    @Autowired JdbcTemplate jdbc;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    /** Seed a task in the given status for the given owner; returns the task id. */
    private UUID newTask(UUID clientId, String status) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, category)
                VALUES (?, ?, 'Summarise report', 'Summarise the attached quarterly report',
                        30.00, CAST(? AS jsonb), ?, 'summarisation')""",
                taskId, clientId,
                "{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"valid JSON\"}",
                status);
        return taskId;
    }

    /** Seed the single task_results row for a task. */
    private void newResult(UUID taskId, String agentStatus, String payloadJson, String resultUrl) {
        jdbc.update("""
                INSERT INTO task_results (id, task_id, result_payload, result_url, agent_status, received_at)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?)""",
                UUID.randomUUID(), taskId, payloadJson, resultUrl, agentStatus,
                Timestamp.from(Instant.now()));
    }

    @Test
    void returnsResultForOwningClient() {
        UUID client = newClient();
        UUID taskId = newTask(client, "RESULT_RECEIVED");
        newResult(taskId, "COMPLETED", "{\"summary\":\"all good\"}", "https://x/y");

        TaskResultModel result = taskReadAppService.getResult(taskId, client);

        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.agentStatus()).isEqualTo("COMPLETED");
        assertThat(result.resultPayloadJson()).contains("all good");
        assertThat(result.resultUrl()).isEqualTo("https://x/y");
    }

    @Test
    void hidesResultFromADifferentClient() {
        UUID owner = newClient();
        UUID other = newClient();
        UUID taskId = newTask(owner, "RESULT_RECEIVED");
        newResult(taskId, "COMPLETED", "{\"summary\":\"all good\"}", null);

        assertThatThrownBy(() -> taskReadAppService.getResult(taskId, other))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void notFoundWhenTaskHasNoResultYet() {
        UUID client = newClient();
        UUID taskId = newTask(client, "EXECUTING"); // no task_results row

        assertThatThrownBy(() -> taskReadAppService.getResult(taskId, client))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void notFoundWhenTaskDoesNotExist() {
        UUID client = newClient();

        assertThatThrownBy(() -> taskReadAppService.getResult(UUID.randomUUID(), client))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode().name())
                .isEqualTo("NOT_FOUND");
    }
}
